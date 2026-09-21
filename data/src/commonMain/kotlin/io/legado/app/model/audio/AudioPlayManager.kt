package io.legado.app.model.audio

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.Lrc
import io.legado.app.model.LrcParser
import io.legado.app.model.ResourceUrlPreloader
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.audio.AudioPlayManager.Companion.NO_SEEK
import io.legado.app.model.chapter.ChapterLoadingGuard
import io.legado.app.model.chapter.ChapterTocUpdater
import io.legado.app.model.chapter.updateResourceUrl
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 音频播放纯逻辑管理器 (KMP commonMain)。
 *
 * 从 app 端 `AudioPlayService` 下沉的纯逻辑部分:
 * - 进度上报循环 ([upPlayProgress])
 * - 播放位置真源 ([positionMs] / [onSeekTo], 消化引擎 seek 异步)
 * - 章节资源加载流程 ([loadPlayUrl] / [loadCoverUrl] / [loadLrcData] / [contentLoadFinish] / [refreshChapter])
 * - 前后章直链预解析 ([ResourceUrlPreloader])
 * - 章节并发加载守卫 ([ChapterLoadingGuard], 四模式共用)
 * - 进度协程取消 ([cancelProgressJob])
 *
 * # 平台差异处理
 * - 底层播放器状态查询 (currentPosition / bufferedPosition / playbackState) 经
 *   [AudioPlayController] 抽象注入, app 端用 ExoPlayer 实现
 * - AnalyzeRule 构造 (app 端 `AnalyzeRule` 子类带 JsExtensions) 经
 *   [AudioPlayAnalyzeRuleFactory] 抽象注入, 返回 commonMain 的 [AnalyzeRuleCore]
 * - 平台副作用 (封面加载 / toast / 起播) 经 [AudioPlaySession] 转给宿主
 * - CoroutineScope 由 Service 生命周期注入 (app 端 lifecycleScope)
 *
 * # 保留不动的逻辑 (与 app 端完全一致)
 * - contentLoadFinish 的 isPlayToEnd 判断
 *
 * # 不在这里
 * 歌词高亮行不由本类推送: 它是 (歌词, 播放位置) 的纯函数, 见 [io.legado.app.model.Lrc.indexAt]。
 * 界面按帧求值, 对外发布见 [LyricPublisher]。
 *
 * 模式参考 [io.legado.app.help.tts.HttpTtsDownloadScheduler]。
 *
 * @param controller 底层播放器抽象 (app 端 ExoPlayer 包装)
 * @param scope 协程作用域 (app 端 lifecycleScope)
 * @param analyzeRuleFactory AnalyzeRule 工厂 (app 端创建带 JsExtensions 的子类)
 * @param session 会话状态机 (起播 / 封面 / toast 的落点)
 */
@Suppress("unused")
class AudioPlayManager(
    val controller: AudioPlayController,
    private val scope: CoroutineScope,
    private val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory,
    private val session: AudioPlaySession,
) {

    /** 章节装载守卫 (四模式共用, 见 [ChapterLoadingGuard]): 同一章不并发加载。 */
    private val loadGuard = ChapterLoadingGuard(scope)

    /** 进度上报协程。 */
    private var upPlayProgressJob: Job? = null

    /** 前后各一章的直链预解析器 (窗口写死 ±1, 不读 preDownloadNum)。 */
    private val preloader = ResourceUrlPreloader(scope)

    /**
     * 目录自动更新 (四模式共用 [ChapterTocUpdater])。
     *
     * 原版音频侧没有这一步: 追更书播到内存目录最后一章就停, 不会去看有没有新章
     * (文字/漫画都有 upToc), 属缺陷, 此处补齐。
     */
    private val tocUpdater = ChapterTocUpdater(
        scope = scope,
        onUpdated = { _, chapters -> AudioPlayShared.updateChapterList(chapters) },
    )

    /**
     * seek 目标位置 ([NO_SEEK] = 无进行中的 seek)。
     *
     * 引擎 seekTo 是异步的 (mpv / AVPlayer / 鸿蒙 AVPlayer 尤其明显), 完成前引擎自身报告的
     * 仍是旧位置, 据此判定歌词行会先把高亮拉回旧行、等旧行播完才跳回。Media3 内部替调用方
     * 消化了这件事, 别的引擎没有 —— 于是在此统一消化一次, 四端语义一致。
     */
    @Volatile
    private var pendingSeekMs: Int = NO_SEEK

    /** 触发 seek 时的引擎位置, 用于区分近距离 seek。 */
    @Volatile
    private var seekOriginMs: Int = NO_SEEK

    /** 触发 seek 时的单调时钟标记。 */
    @Volatile
    private var seekStartedAt: TimeMark? = null

    /** 当前章节装载协程。 */
    private var loadJob: Job? = null
    private var contentJob: Coroutine<*>? = null
    private var coverJob: Coroutine<*>? = null
    private var lrcJob: Coroutine<*>? = null

    /**
     * 播放位置真源 (毫秒): 歌词判定与进度上报都读它。
     *
     * 引擎空转时它报的还是上一章的位置 (换章要等新直链解析完才 setMediaItem), 此时本章的位置真源
     * 是章节进度 —— next/prev/skipTo 已把起始位置同步写好。
     *
     * seek 后引擎确认前返回目标位置; 引擎报告落到目标附近即认为 seek 生效, 恢复实时值。
     */
    val positionMs: Int
        get() {
            var target = pendingSeekMs
            val state = controller.playbackState

            // 1. 播放结束时直接清理 pending，以媒体末尾或引擎实时值为准
            if (state == AudioPlayController.STATE_ENDED) {
                clearPendingSeek()
                return controller.currentPosition.toInt()
            }

            // 2. 无 pending seek 时，IDLE 状态回退到 durChapterPos，非 IDLE 返回引擎实时值
            if (target == NO_SEEK) {
                return if (state == AudioPlayController.STATE_IDLE) {
                    AudioPlayShared.durChapterPos
                } else {
                    controller.currentPosition.toInt()
                }
            }

            // 3. 有 pending seek: 控制器 IDLE 期间优先返回该目标 (起播登记的 seek 目标不被旧进度冲掉)
            if (state == AudioPlayController.STATE_IDLE) {
                return target
            }

            // 4. 控制器已有明确时长时，规范化越界目标 (防越界 seek 长期盖住真实位置)
            val duration = controller.duration
            if (duration > 0 && target > duration) {
                target = duration.toInt()
                pendingSeekMs = target
            }

            val engine = controller.currentPosition.toInt()
            val origin = seekOriginMs
            val elapsed = seekStartedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L

            // 5. 判定 seek 是否已生效:
            // - 超时兜底: 超过 SEEK_TIMEOUT_MS (3000ms) 则不再等待，放弃 pending
            // - 远距离 seek (|origin - target| >= SEEK_ACK_MS 或无有效 origin): 引擎进入 target 邻域即可确认
            // - 近距离 seek (|origin - target| < SEEK_ACK_MS): 须等待引擎实际移动到位或等待超过近距离保持期 (SEEK_HOLD_MS)
            val isNearSeek = origin != NO_SEEK && abs(origin - target) < SEEK_ACK_MS
            val isAck = when {
                elapsed >= SEEK_TIMEOUT_MS -> true
                isNearSeek -> {
                    abs(engine - target) <= SEEK_NEAR_ACK_MS ||
                        (elapsed >= SEEK_HOLD_MS && abs(engine - target) < SEEK_ACK_MS)
                }

                else -> abs(engine - target) < SEEK_ACK_MS
            }

            if (isAck) {
                clearPendingSeek()
                return engine
            }
            return target
        }

    /**
     * 引擎将要就位到 [position] 时告知 (拖动 seek 与起播, 对标原版 adjustProgress 里的歌词位置重置时机)。
     */
    fun onSeekTo(position: Int) {
        val duration = controller.duration
        val target = if (duration > 0) {
            position.coerceIn(0, duration.toInt())
        } else {
            position.coerceAtLeast(0)
        }
        pendingSeekMs = target
        seekOriginMs = if (controller.playbackState != AudioPlayController.STATE_IDLE) {
            controller.currentPosition.toInt()
        } else {
            NO_SEEK
        }
        seekStartedAt = TimeSource.Monotonic.markNow()
        // 发布循环可能正睡在"旧的下一行"时间点上, 叫醒它重算 (界面走帧驱动, 下一帧自会正确)
        LyricPublisher.invalidate()
    }

    /** 清理未完成的 seek 目标 (切章 / 停播 / 播放结束时调用)。 */
    fun clearPendingSeek() {
        pendingSeekMs = NO_SEEK
        seekOriginMs = NO_SEEK
        seekStartedAt = null
    }

    // region 进度上报

    /**
     * 每隔 1 秒发送播放进度。
     *
     * 对标 app 端 `AudioPlayService.upPlayProgress`。
     */
    fun upPlayProgress() {
        upPlayProgressJob?.cancel()
        upPlayProgressJob = scope.launch {
            while (isActive) {
                AudioPlayShared.durChapterPos = positionMs
                postEvent(EventBus.AUDIO_BUFFER_PROGRESS, controller.bufferedPosition.toInt())
                postEvent(EventBus.AUDIO_PROGRESS, AudioPlayShared.durChapterPos)
                // 时长兜底: 流式资源 READY 时 duration 可能未知 (0/-1), 播放中变已知后
                // 心跳补发 AUDIO_SIZE, 否则 UI 时长恒 0/旧值, 进度会“超过时长”
                val duration = controller.duration
                if (duration > 0 && duration.toInt() != AudioPlayShared.durAudioSize) {
                    AudioPlayShared.durAudioSize = duration.toInt()
                    postEvent(EventBus.AUDIO_SIZE, AudioPlayShared.durAudioSize)
                    AudioPlayShared.saveDurChapter(duration)
                }
                delay(1000)
            }
        }
    }

    /** 取消进度上报协程。 */
    fun cancelProgressJob() {
        upPlayProgressJob?.cancel()
    }

    // endregion

    // region 章节数据加载

    /**
     * 清掉当前章节 URL 并重新加载, 用于播放器报错后的自动重试。
     *
     * 对标 app 端 `AudioPlayService.refreshChapter`。
     */
    fun refreshChapter() {
        val chapter = AudioPlayShared.durChapter ?: return
        chapter.resourceUrl = null
        AudioPlayShared.durPlayUrl = ""
        loadPlayUrl()
    }

    /**
     * 停播 / 会话终结时作废预解析 (由 [AudioPlaySession.endSession] 调)。
     *
     * 不跟着 [cancelProgressJob] 走: 进度上报每次换章都会重启, 预解析只在会话结束时才该停。
     */
    fun cancelPreload() {
        preloader.cancel()
    }

    /**
     * 取消当前正在执行的章节装载任务 (封面、歌词、直链获取及守卫标记)。
     */
    fun cancelChapterLoad() {
        loadJob?.cancel()
        loadJob = null
        contentJob?.cancel()
        contentJob = null
        coverJob?.cancel()
        coverJob = null
        lrcJob?.cancel()
        lrcJob = null
        loadGuard.clear()
    }

    /**
     * 加载当前章节的播放 URL, 并联动拉取封面 + 歌词。
     *
     * 流程:
     * 1. 取 chapter.resourceUrl, 没有则 fetch 章节内容
     * 2. 内容回来后写回章节并触发 [AudioPlaySession.onTriggerPlay]
     * 3. 同时并行启动 [loadCoverUrl] 与 [loadLrcData]
     *
     * 对标 app 端 `AudioPlayService.loadPlayUrl`。
     */
    fun loadPlayUrl() {
        val index = AudioPlayShared.durChapterIndex
        if (!loadGuard.tryAdd(index)) return
        val book = AudioPlayShared.book
        val bookSource = AudioPlayShared.bookSource
        if (book == null || bookSource == null) {
            loadGuard.releaseNow(index)
            session.onToast("book or source is null")
            return
        }
        // 切源重新装载时作废并清理先前的章节任务
        contentJob?.cancel()
        coverJob?.cancel()
        lrcJob?.cancel()
        loadJob?.cancel()
        loadJob = scope.launch {
            // 装载权交接给下面的 Coroutine.async 之前 (取目录 / 起封面歌词) 若抛错或被取消,
            // 没人再释放标记, 该章将永久拉不起来 —— 未交接的路径统一由 finally 释放
            var handedOff = false
            try {
                AudioPlayShared.upDurChapter()
                val chapter = AudioPlayShared.durChapter ?: return@launch
                if (chapter.isVolume) {
                    AudioPlayShared.skipTo(index + 1)
                    return@launch
                }
                postEvent(EventBus.AUDIO_LOADING, true)
                // 拉链接窗口置 LOADING, 让 UI 能区分"没在播"和"正在启动"(否则退出界面会被误判为停播)
                AudioPlayShared.status = Status.LOADING
                postEvent(EventBus.AUDIO_STATE, Status.LOADING)
                // 开解当前章前先作废上一轮预解析: 两者共用书源, 并行跑会拖慢用户等的这一章
                preloader.cancel()
                AudioPlayShared.durCoverUrl = null
                AudioPlayShared.durLrc.value = null
                session.onResetCoverCache()
                coverJob = loadCoverUrl(bookSource, book, chapter)
                lrcJob = loadLrcData(bookSource, book, chapter)
                contentJob = Coroutine.async(scope = scope) {
                    chapter.resourceUrl
                        ?: getContentAwait(bookSource, book, chapter, needSave = false)
                }.onSuccess { content ->
                    if (content.isEmpty()) {
                        // 拿不到链接也要收掉 loading 并回落 STOP, 否则转圈一直挂着
                        session.onToast("未获取到资源链接")
                        postEvent(EventBus.AUDIO_LOADING, false)
                        AudioPlayShared.status = Status.STOP
                        postEvent(EventBus.AUDIO_STATE, Status.STOP)
                    } else {
                        // 直链回写 + 在架书 PATCH 落库 (实现已收敛至 updateResourceUrl, 音视频共用)
                        chapter.updateResourceUrl(content, AudioPlayShared.inBookshelf)
                        contentLoadFinish(chapter, content)
                        // 当前章就绪后再预解析前后各一章 (对标小说 contentLoadFinish → preDownload 的时机)
                        preloadNeighbors(bookSource, book, chapter)
                        // 播到目录尾部时检查新章 (对标小说 preDownload 里的 upToc)
                        upToc()
                    }
                }.onError {
                    AppLog.put("获取资源链接出错\n$it", it, true)
                    postEvent(EventBus.AUDIO_LOADING, false)
                    AudioPlayShared.status = Status.STOP
                    postEvent(EventBus.AUDIO_STATE, Status.STOP)
                }.onCancel {
                    loadGuard.releaseNow(index)
                }.onFinally {
                    loadGuard.releaseNow(index)
                }
                // 装载权已交给上面的 Coroutine (它的 onCancel/onFinally 负责释放)
                handedOff = true
            } finally {
                if (!handedOff) loadGuard.releaseNow(index)
            }
        }
    }

    /**
     * 播到目录尾部时检查目录有无新章 (四模式共用 [ChapterTocUpdater])。
     *
     * @param force 跳过 canUpdate / 剩余章数 / 节流三重守卫
     */
    fun upToc(force: Boolean = false) {
        val book = AudioPlayShared.book ?: return
        tocUpdater.upToc(
            book = book,
            bookSource = AudioPlayShared.bookSource,
            chapterSize = AudioPlayShared.chapterSize,
            durChapterIndex = AudioPlayShared.durChapterIndex,
            force = force,
        )
    }

    /**
     * 预解析前后各一章的播放直链。
     *
     * 窗口写死 ±1 而不读 `preDownloadNum`: 直链大多带时效签名, 预取多了到播的时候已失效。
     * 不传 nextChapterUrl: 音频正文就是一条直链, 不存在正文翻页 (与 [loadPlayUrl] 一致)。
     *
     * @param chapter 刚就绪的章节; 已不是当前章就不预解析 —— 同 [contentLoadFinish] 的作废判定,
     *   否则连点切章时旧一轮的尾巴会跟新一轮抢书源
     */
    private fun preloadNeighbors(bookSource: BookSource, book: Book, chapter: BookChapter) {
        if (chapter.index != AudioPlayShared.durChapterIndex) return
        preloader.preload(
            book = book,
            chapters = AudioPlayShared.chapterList,
            centerIndex = AudioPlayShared.durChapterIndex,
            inBookshelf = AudioPlayShared.inBookshelf,
        ) { target ->
            getContentAwait(bookSource, book, target, needSave = false)
        }
    }

    /**
     * 用书源的 musicCover 规则计算封面 URL, 空规则就用书的默认 cover。
     *
     * 对标 app 端 `AudioPlayService.loadCoverUrl`; 多级回落对齐 desktop 版
     * (2026-08 修): 规则求值失败/结果 null/空白时兜底书籍默认封面, 不再把
     * evalJS 的 null 经 toString() 变成字符串 "null" 当 URL 推送 —— 否则 UI
     * 加载 "null" 失败回落默认封面, 真实书封面被顶掉。
     */
    private fun loadCoverUrl(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ): Coroutine<String?> {
        return Coroutine.async(scope = scope) {
            val musicCover = bookSource.contentRule.musicCover
            if (!musicCover.isNullOrBlank()) {
                runCatching {
                    val rule = analyzeRuleFactory.create(
                        book, bookSource, chapter, currentCoroutineContext()
                    )
                    rule.evalJS(musicCover)?.toString()?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: book.getDisplayCover()
            } else {
                book.getDisplayCover()
            }
        }.onSuccess { coverUrl ->
            // 防旧章节封面异步晚到污染当前章节
            if (chapter.index != AudioPlayShared.durChapterIndex) return@onSuccess
            AudioPlayShared.durCoverUrl = coverUrl
            if (!coverUrl.isNullOrBlank()) {
                postEvent(EventBus.AUDIO_COVER, coverUrl)
                session.onLoadCover(coverUrl)
            }
        }
    }

    /**
     * 用书源的 subContent 规则计算歌词数据, 就绪后写入 [AudioPlayShared.durLrc]。
     *
     * 对标 app 端 `AudioPlayService.loadLrcData`。
     */
    private fun loadLrcData(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ): Coroutine<Lrc?> {
        return Coroutine.async(scope = scope) {
            val subContent = bookSource.contentRule.subContent
            if (subContent.isNullOrBlank()) return@async null
            val rule = analyzeRuleFactory.create(
                book, bookSource, chapter, currentCoroutineContext()
            )
            val raw = rule.evalJS(subContent) as? List<*> ?: return@async null
            LrcParser.parse(raw)
        }.onSuccess {
            // 防旧章节歌词异步晚到污染当前章节
            if (chapter.index != AudioPlayShared.durChapterIndex) return@onSuccess
            if (it == null || it.lines.isEmpty()) return@onSuccess
            // 只发布数据; 当前行由消费方按播放位置求值 (界面按帧, 对外发布按行唤醒)
            AudioPlayShared.durLrc.value = it
        }.onError {
            AppLog.put("获取歌词出错\n$it", it, true)
        }
    }

    /**
     * 章节内容加载完成, 写回 durPlayUrl 并触发播放。
     *
     * 对标 app 端 `AudioPlayService.contentLoadFinish`。
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index != AudioPlayShared.durChapterIndex) return
        AudioPlayShared.durPlayUrl = content
        val isPlayToEnd =
            AudioPlayShared.durChapterIndex + 1 == AudioPlayShared.simulatedChapterSize &&
                AudioPlayShared.durChapterPos == AudioPlayShared.durAudioSize
        session.onTriggerPlay(isPlayToEnd)
    }

    // endregion

    private companion object {
        /** [pendingSeekMs] 的空值 (真实位置不会是负数)。 */
        const val NO_SEEK = -1

        /** 引擎报告与 seek 目标的差值小于此值即认为远距离 seek 已生效 (毫秒)。 */
        const val SEEK_ACK_MS = 1000

        /** 近距离 seek 紧邻域阈值 (毫秒)。 */
        const val SEEK_NEAR_ACK_MS = 100

        /** 近距离 seek 防早确认保持时间 (毫秒)。 */
        const val SEEK_HOLD_MS = 600L

        /** seek 确认超时兜底时长 (毫秒)。 */
        const val SEEK_TIMEOUT_MS = 3000L
    }
}

/**
 * AnalyzeRule 工厂 (KMP 版)。
 *
 * app 端创建带 JsExtensions 的 [io.legado.app.model.analyzeRule.AnalyzeRule] (app 子类),
 * commonMain 只依赖 [AnalyzeRuleCore]。工厂返回 AnalyzeRuleCore, 实际实例由 app 端决定,
 * 保证 JS bindings 中 `java` 变量可见 JsExtensions 方法。
 *
 * 模式参考 [io.legado.app.help.tts.HttpTtsAnalyzeUrlFactory]。
 */
fun interface AudioPlayAnalyzeRuleFactory {

    fun create(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        coroutineContext: CoroutineContext,
    ): AnalyzeRuleCore
}
