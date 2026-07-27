package io.legado.app.model.audio

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.LrcParser
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 音频播放纯逻辑管理器 (KMP commonMain)。
 *
 * 从 app 端 `AudioPlayService` 下沉的纯逻辑部分:
 * - 进度上报循环 ([upPlayProgress])
 * - 歌词 (LRC) 高亮推进算法 ([upPlayProgressForLrc])
 * - 章节资源加载流程 ([loadPlayUrl] / [loadCoverUrl] / [loadLrcData] / [contentLoadFinish] / [refreshChapter])
 * - 章节并发加载守卫 ([addLoading] / [removeLoading])
 * - 进度协程取消 ([cancelProgressJobs])
 *
 * # 平台差异处理
 * - 底层播放器状态查询 (currentPosition / bufferedPosition / playbackState) 经
 *   [AudioPlayController] 抽象注入, app 端用 ExoPlayer 实现
 * - AnalyzeRule 构造 (app 端 `AnalyzeRule` 子类带 JsExtensions) 经
 *   [AudioPlayAnalyzeRuleFactory] 抽象注入, 返回 commonMain 的 [AnalyzeRuleCore]
 * - 平台副作用 (Glide 加载封面 / toast / triggerPlay 调 service.play()) 经
 *   [AudioPlayManagerListener] 回调注入
 * - CoroutineScope 由 Service 生命周期注入 (app 端 lifecycleScope)
 *
 * # 保留不动的逻辑 (与 app 端完全一致)
 * - LRC 推进的两层节能策略 (事件驱动 delay + subscriptionCount 门控)
 * - 章节加载的并发守卫 (loadingChapters synchronized)
 * - contentLoadFinish 的 isPlayToEnd 判断
 *
 * 模式参考 [io.legado.app.help.tts.HttpTtsDownloadScheduler]。
 *
 * @param controller 底层播放器抽象 (app 端 ExoPlayer 包装)
 * @param scope 协程作用域 (app 端 lifecycleScope)
 * @param analyzeRuleFactory AnalyzeRule 工厂 (app 端创建带 JsExtensions 的子类)
 * @param listener 平台副作用回调 (cover/toast/triggerPlay)
 */
@Suppress("unused")
class AudioPlayManager(
    val controller: AudioPlayController,
    private val scope: CoroutineScope,
    private val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory,
    private val listener: AudioPlayManagerListener,
) {

    /** 播放速率 (对应 app 端 `AudioPlayService.playSpeed`), LRC 推进 delay 时长按此缩放。 */
    @Volatile
    var playSpeed: Float = 1f

    /** 上次发出的歌词 position; 切章 / seek 时由调用方置回 -1。 */
    private var lastLrcPosition = -1

    /** 正在加载的章节下标, 防止并发加载。 */
    private val loadingChapters = mutableListOf<Int>()
    private val loadingLock = SynchronizedObject()

    /** 进度上报协程。 */
    private var upPlayProgressJob: Job? = null

    /** LRC 推进协程。 */
    private var upPlayProgressForLrcJob: Job? = null

    /** 歌词同步补偿偏移量 (毫秒)。 */
    private val lrcOffsetMs = 60L

    /** seek 后重置歌词位置, 使下次推进从真实位置重算 (对标 app 端 adjustProgress 的 lastLrcPosition = -1)。 */
    fun resetLrcPosition() {
        lastLrcPosition = -1
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
                AudioPlayShared.durChapterPos = controller.currentPosition.toInt()
                postEvent(EventBus.AUDIO_BUFFER_PROGRESS, controller.bufferedPosition.toInt())
                postEvent(EventBus.AUDIO_PROGRESS, AudioPlayShared.durChapterPos)
                delay(1000)
            }
        }
    }

    /**
     * 推进当前章节歌词高亮位置。
     *
     * 节能策略两层:
     * 1. 事件驱动: 按下一句歌词时间戳精确 delay, 而非高频轮询;
     *    一首歌只唤醒 N 行次, 而非 时长(s)*20 次。
     * 2. 订阅门控: 通过 FlowBus 的 subscriptionCount 感知是否有 Activity 在收事件,
     *    没有时直接挂起整个循环, 后台/锁屏场景零空转。
     *
     * 调用方在以下场景触发重启: STATE_READY / adjustProgress / 调整播放速度。
     *
     * 对标 app 端 `AudioPlayService.upPlayProgressForLrc`。
     */
    fun upPlayProgressForLrc() {
        upPlayProgressForLrcJob?.cancel()
        val lrc = AudioPlayShared.durLrcData ?: return
        if (lrc.isEmpty() || lrc.last().first == -1) return
        // 切歌中(stop 后未 prepare 新 mediaItem)player 处于 IDLE,
        // currentPosition 仍是上一首的残留位置, 据此推进会把新章节歌词跳到旧位置;
        // 新媒体 prepare 完成后 STATE_READY 会再次触发本方法, 这里直接退出。
        if (controller.playbackState == AudioPlayController.STATE_IDLE) return
        val subCount = FlowBus.withSticky(EventBus.AUDIO_LRCPROGRESS).subscriptionCount

        // 注意: 此协程绑定主线程(scope 默认 Main), 因为 ExoPlayer 默认绑定主 looper,
        // 从其它线程访问 currentPosition 会抛 IllegalStateException。
        upPlayProgressForLrcJob = scope.launch {
            while (isActive) {
                subCount.first { it > 0 }
                val curLrc = AudioPlayShared.durLrcData ?: break
                val curMs = controller.currentPosition + lrcOffsetMs
                // 续推: 上次位置仍在范围且没被新 lrc 失效就直接接上, 否则从 0 起重新单向扫
                var position = lastLrcPosition.takeIf {
                    it in 0 until curLrc.size && curLrc[it].first <= curMs
                } ?: 0
                while (position + 1 < curLrc.size && curLrc[position + 1].first <= curMs) {
                    position++
                }
                if (position != lastLrcPosition) {
                    lastLrcPosition = position
                    postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                }
                // 已停在末行: 直接结束协程。线性播放不会再前进, 需要重启的事件
                // (seek/换章/换速/新 lrc 到货) 都已在对应入口显式调用 upPlayProgressForLrc。
                if (position >= curLrc.size - 1) return@launch

                while (isActive && position < curLrc.size - 1) {
                    val remain = ((curLrc[position + 1].first
                        - controller.currentPosition - lrcOffsetMs) / playSpeed).toLong()
                    if (remain > 0) {
                        val dropped = withTimeoutOrNull(remain) {
                            subCount.first { it == 0 }
                            true
                        } == true
                        if (dropped) break
                        position++
                        lastLrcPosition = position
                        postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                        continue
                    }
                    // 时间戳已过期: 可能是 seek 跳跃, 也可能是 currentPosition 停滞。
                    // 单向向前扫到真实位置; 若仍未前进, 直接退出协程, 由 upPlayProgress 的 1s 心跳
                    // 在 player 真正推进后重启, 避免无挂起点的忙等。
                    val before = position
                    val nowMs = controller.currentPosition + lrcOffsetMs
                    while (position + 1 < curLrc.size && curLrc[position + 1].first <= nowMs) {
                        position++
                    }
                    if (position == before) return@launch
                    lastLrcPosition = position
                    postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                }
            }
        }
    }

    /** 取消进度上报 + LRC 推进协程。 */
    fun cancelProgressJobs() {
        upPlayProgressJob?.cancel()
        upPlayProgressForLrcJob?.cancel()
    }

    /** 释放资源 (Service onDestroy 调用)。 */
    fun onDestroy() {
        cancelProgressJobs()
    }

    // endregion

    // region 章节数据加载

    /** 同一章节不允许并发加载, 失败时也要 remove。 */
    private fun addLoading(index: Int): Boolean = synchronized(loadingLock) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    private fun removeLoading(index: Int) = synchronized(loadingLock) {
        loadingChapters.remove(index)
    }

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
     * 加载当前章节的播放 URL, 并联动拉取封面 + 歌词。
     *
     * 流程:
     * 1. 取 chapter.resourceUrl, 没有则 fetch 章节内容
     * 2. 内容回来后写回章节并触发 [AudioPlayManagerListener.onTriggerPlay]
     * 3. 同时并行启动 [loadCoverUrl] 与 [loadLrcData]
     *
     * 对标 app 端 `AudioPlayService.loadPlayUrl`。
     */
    fun loadPlayUrl() {
        val index = AudioPlayShared.durChapterIndex
        if (!addLoading(index)) return
        val book = AudioPlayShared.book
        val bookSource = AudioPlayShared.bookSource
        if (book == null || bookSource == null) {
            removeLoading(index)
            listener.onToast("book or source is null")
            return
        }
        scope.launch {
            AudioPlayShared.upDurChapter()
            val chapter = AudioPlayShared.durChapter
            if (chapter == null) {
                removeLoading(index)
                return@launch
            }
            if (chapter.isVolume) {
                AudioPlayShared.skipTo(index + 1)
                removeLoading(index)
                return@launch
            }
            postEvent(EventBus.AUDIO_LOADING, true)
            // 拉链接窗口置 LOADING, 让 UI 能区分"没在播"和"正在启动"(否则退出界面会被误判为停播)
            AudioPlayShared.status = Status.LOADING
            postEvent(EventBus.AUDIO_STATE, Status.LOADING)
            AudioPlayShared.durCoverUrl = null
            AudioPlayShared.durLrcData = null
            lastLrcPosition = -1
            listener.onResetCoverCache()
            loadCoverUrl(bookSource, book, chapter)
            loadLrcData(bookSource, book, chapter)
            Coroutine.async(scope = scope) {
                chapter.resourceUrl
                    ?: getContentAwait(bookSource, book, chapter, needSave = false)
            }.onSuccess { content ->
                if (content.isEmpty()) {
                    // 拿不到链接也要收掉 loading 并回落 STOP, 否则转圈一直挂着
                    listener.onToast("未获取到资源链接")
                    postEvent(EventBus.AUDIO_LOADING, false)
                    AudioPlayShared.status = Status.STOP
                    postEvent(EventBus.AUDIO_STATE, Status.STOP)
                } else {
                    if (chapter.resourceUrl != content) {
                        chapter.resourceUrl = content
                        if (AudioPlayShared.inBookshelf) {
                            AppDbProviders.get().bookChapterDao.update(chapter)
                        }
                    }
                    contentLoadFinish(chapter, content)
                }
            }.onError {
                AppLog.put("获取资源链接出错\n$it", it, true)
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status = Status.STOP
                postEvent(EventBus.AUDIO_STATE, Status.STOP)
            }.onCancel {
                removeLoading(index)
            }.onFinally {
                removeLoading(index)
            }
        }
    }

    /**
     * 用书源的 musicCover 规则计算封面 URL, 空规则就用书的默认 cover。
     *
     * 对标 app 端 `AudioPlayService.loadCoverUrl`。
     */
    private fun loadCoverUrl(bookSource: BookSource, book: Book, chapter: BookChapter) {
        Coroutine.async(scope = scope) {
            val musicCover = bookSource.contentRule.musicCover
            AudioPlayShared.durCoverUrl = if (!musicCover.isNullOrBlank()) {
                val rule = analyzeRuleFactory.create(
                    book, bookSource, chapter, currentCoroutineContext()
                )
                rule.evalJS(musicCover).toString()
            } else book.getDisplayCover()
        }.onSuccess {
            val coverUrl = AudioPlayShared.durCoverUrl ?: return@onSuccess
            postEvent(EventBus.AUDIO_COVER, coverUrl)
            listener.onLoadCover(coverUrl)
        }
    }

    /**
     * 用书源的 subContent 规则计算歌词数据。
     *
     * 对标 app 端 `AudioPlayService.loadLrcData`。
     */
    private fun loadLrcData(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ): Coroutine<List<Pair<Int, String>>> {
        return Coroutine.async(scope = scope) {
            val subContent = bookSource.contentRule.subContent
            if (subContent.isNullOrBlank()) return@async emptyList()
            val rule = analyzeRuleFactory.create(
                book, bookSource, chapter, currentCoroutineContext()
            )
            val raw = rule.evalJS(subContent) as? List<*> ?: return@async emptyList()
            LrcParser.parse(raw)
        }.onSuccess {
            if (it.isEmpty()) return@onSuccess
            AudioPlayShared.durLrcData = it
            lastLrcPosition = -1
            postEvent(EventBus.AUDIO_LRC, it)
            postEvent(EventBus.AUDIO_LRCPROGRESS, 0)
            // 歌词到货后显式启动推进协程 (STATE_READY 可能早于歌词加载完成)
            upPlayProgressForLrc()
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
        if (chapter.index != AudioPlayShared.book?.durChapterIndex) return
        AudioPlayShared.durPlayUrl = content
        val isPlayToEnd =
            AudioPlayShared.durChapterIndex + 1 == AudioPlayShared.simulatedChapterSize &&
                AudioPlayShared.durChapterPos == AudioPlayShared.durAudioSize
        listener.onTriggerPlay(isPlayToEnd)
    }

    // endregion
}

/**
 * 平台副作用回调 (app 端 Service 实现)。
 *
 * 把 manager 不能直接做的平台操作抽出来:
 * - `onTriggerPlay`: 调 service.triggerPlay (acquire lock + request focus + ExoPlayer setMediaItem/play)
 * - `onLoadCover`: 调 service.loadCover (Glide 加载封面 Bitmap)
 * - `onResetCoverCache`: 重置 service.lastCoverUrl (切章时强制重新加载封面)
 * - `onToast`: 调 service.toastOnUi (Android Toast)
 */
interface AudioPlayManagerListener {

    /** 章节资源加载完成, 调用方应触发播放 (service.triggerPlay(playNew))。 */
    fun onTriggerPlay(playNew: Boolean)

    /** 封面 URL 已计算完成, 调用方加载封面 Bitmap (service.loadCover(url))。 */
    fun onLoadCover(url: String?)

    /** 切章时重置封面缓存 (service.lastCoverUrl = null), 强制下次 loadCover 重新加载。 */
    fun onResetCoverCache()

    /** 显示 Toast (service.toastOnUi(message))。 */
    fun onToast(message: String)
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
