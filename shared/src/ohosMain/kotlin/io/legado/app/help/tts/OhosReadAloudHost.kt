package io.legado.app.help.tts

import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.service.ReadAloudChapterNavigator
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.book.read.ReadBookEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 鸿蒙端朗读宿主: 把 [ReadAloudControllerShared] 接到阅读编排上 (替代 app 端 BaseReadAloudService)。
 *
 * # 对照桌面端 DesktopReadAloudHost
 * - [play]/[pause]/[resume]/[stop]/[toggle] 对应用户朗读按钮操作
 * - [ReadAloudControllerShared] 段级推进 (系统 TTS onDone → onParagraphDone)
 * - 章节切章经 [Navigator] 桥接 ActiveReadBookRegistry 的当前 ViewModel
 * - 朗读位置回写: 段推进时经 [ReadBookEvents.postTtsProgress] 触发 ReaderScreenModel 的
 *   ttsProgress collector → viewModel.onTtsProgress (翻页 + 高亮)
 *
 * # 鸿蒙 textToSpeech 无 pause/resume API
 * [OhosSystemTtsEngine] 维护 paused 标志, pause 命令仅打日志; 恢复朗读 (resume) 由控制器
 * 重播当前段落 (ReadAloudControllerShared.resume → 引擎 isPaused!=true → playCurrent)。
 *
 * # 进程内单例
 * 首读创建 [ReadAloudControllerShared] (Navigator 动态查询当前阅读 ViewModel, 换书/退出阅读
 * 后旧 controller 保留但所有调用经 currentViewModel 空判短路, 行为安全)。
 */
object OhosReadAloudHost {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /** 朗读控制器 (进程内单例, 首次访问时建)。 */
    private val controller: ReadAloudControllerShared by lazy { createController() }

    /** [controller] 的裸引用: 供 lazy 初始化期间读取, 避免回环触发 lazy。 */
    @Volatile
    private var controllerRef: ReadAloudControllerShared? = null

    /** 下一次 [ReadAloudControllerShared.start] 的章内起始字符位置。 */
    @Volatile
    private var pendingStartPos: Int = 0

    /** 本宿主最近一次写入的阅读位置 (与用户翻页区分开)。 */
    @Volatile
    private var lastSyncedPos: Int = -1

    /** 暂停期间发生过翻页, resume 时按新位置重开 (对照原版 newReadAloud(play=false))。 */
    @Volatile
    private var restartOnResume: Boolean = false

    /** 对应 app 端 `BaseReadAloudService.isRun`。 */
    val isRun: Boolean
        get() = controllerRef?.state?.value
            .let { it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED }

    /** 对应 app 端 `BaseReadAloudService.pause`。 */
    val isPause: Boolean get() = controllerRef?.state?.value != ReadAloudState.PLAYING

    /** 当前朗读章节段落表 (供位置回写计算段落起始偏移)。 */
    private var paragraphs: List<String> = emptyList()

    /** 各段在章内的起始字符位置。 */
    private var paragraphOffsets: IntArray = IntArray(0)

    /** 控制器段下标 0 对应的真实段下标 (startPos 裁剪产生的偏移)。 */
    private var paragraphBase: Int = 0

    /** 首段被 startPos 裁掉的段内字符数。 */
    private var firstParagraphSkip: Int = 0

    /**
     * 开始 / 准备朗读 (对照 `ReadAloud.play(context, play, pageIndex, startPos)`)。
     *
     * @param play false 表示只记录新位置不出声 (翻页时朗读处于暂停态的分支)
     * @param startPos 相对当前 `durChapterPos` 的附加偏移
     */
    fun play(play: Boolean = true, startPos: Int = 0) {
        val readBook = ActiveReadBookRegistry.current ?: return
        val pos = (readBook.durChapterPosValue + startPos).coerceAtLeast(0)
        if (!play) {
            restartOnResume = true
            return
        }
        pendingStartPos = pos
        lastSyncedPos = pos
        restartOnResume = false
        applySpeechRate()
        controller.start(readBook.durChapterIndexValue)
    }

    /** 暂停朗读 (对照 `ReadAloud.pause`)。 */
    fun pause() {
        controllerRef?.pause()
    }

    /** 继续朗读; 暂停期间翻过页时按新位置重开 (对照 `ReadAloud.resume`)。 */
    fun resume() {
        val readBook = ActiveReadBookRegistry.current
        if (restartOnResume && readBook != null) {
            pendingStartPos = readBook.durChapterPosValue
            lastSyncedPos = pendingStartPos
            restartOnResume = false
            applySpeechRate()
            controller.start(readBook.durChapterIndexValue)
        } else {
            controllerRef?.resume()
        }
    }

    /** 停止朗读并清理 (对照 `ReadAloud.stop`)。 */
    fun stop() {
        restartOnResume = false
        controllerRef?.stop()
    }

    /** 朗读按钮短按 (对照 app 端 `clickReadAloud`): 未运行 → 开始, 暂停中 → 继续, 否则 → 暂停。 */
    fun toggle() {
        when {
            !isRun -> play()
            isPause -> resume()
            else -> pause()
        }
    }

    /**
     * 设定语速并实时生效 (对照 `AppConfig.ttsSpeechRate = v` + `ReadAloud.upTtsSpeechRate`)。
     *
     * @param rate 原版 ttsSpeechRate 口径 (0..45), 折算倍率 (rate + 5) / 10f
     */
    fun setSpeechRate(rate: Int) {
        controller.setSpeechRate((rate.coerceIn(0, 45) + 5) / 10f)
        // 原版 upTtsSpeechRate 后会 pause+resume 让新语速立刻作用到当前段;
        // 鸿蒙 textToSpeech 无 pause/resume API (ArkTS 侧仅日志, 见 TtsBridgeHandler),
        // 当前段无法重播, 新语速从下一段生效 (引擎 speechRate 已即时同步)
        if (!isPause) {
            controller.pause()
            controller.resume()
        }
    }

    // region 内部实现

    private fun createController(): ReadAloudControllerShared {
        val instance = ReadAloudControllerShared(
            navigator = Navigator,
            // Book.ttsEngine 优先, 否则 AppConfig.ttsEngine (数字串表示 HttpTTS id);
            // 鸿蒙 HttpTTS 播放器已注册 (OhosHttpTtsPlayer), 配置到 HttpTTS 时走该路径
            ttsEngineConfigProvider = {
                ActiveReadBookRegistry.current?.bookValue?.config?.ttsEngine
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { PreferenceProviders.get().getString(PreferKey.ttsEngine, "") }
                        .getOrNull()?.takeIf { it.isNotBlank() }
            },
        )
        controllerRef = instance
        scope.launch {
            instance.paragraphIndex.collect { syncReadPosition(it) }
        }
        scope.launch {
            instance.state.collect { onStateChanged(it) }
        }
        return instance
    }

    /** AppConfig.ttsSpeechRate (0..45) 折算为控制器倍率 (原版 (rate + 5) / 10f)。 */
    private fun applySpeechRate() {
        val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return
        val rate = if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
            DEFAULT_SPEECH_RATE
        } else {
            prefs.getInt(PreferKey.ttsSpeechRate, DEFAULT_SPEECH_RATE)
        }
        controller.setSpeechRate((rate + 5) / 10f)
    }

    /**
     * 朗读推进到某段时把阅读位置拉过去并高亮该段。
     *
     * 对照 app 端 ReadBookActivity 的 TTS_PROGRESS 观察者: 经 [ReadBookEvents.postTtsProgress]
     * 触发 ReaderScreenModel 的 ttsProgress collector → viewModel.onTtsProgress (翻页 + 高亮 span)。
     */
    private fun syncReadPosition(index: Int) {
        if (index < 0) return
        val controller = controllerRef ?: return
        val readBook = ActiveReadBookRegistry.current ?: return
        if (readBook.durChapterIndexValue != controller.chapterIndex.value) return
        val real = paragraphBase + index
        var pos = paragraphOffsets.getOrNull(real) ?: return
        if (index == 0) pos += firstParagraphSkip
        lastSyncedPos = pos
        ReadBookEvents.postTtsProgress(pos)
    }

    private fun onStateChanged(state: ReadAloudState) {
        when (state) {
            ReadAloudState.PLAYING -> ReadBookEvents.postAloudState(Status.PLAY)
            ReadAloudState.PAUSED -> ReadBookEvents.postAloudState(Status.PAUSE)
            ReadAloudState.STOPPED, ReadAloudState.COMPLETED, ReadAloudState.ERROR ->
                ReadBookEvents.postAloudState(Status.STOP)

            ReadAloudState.IDLE -> Unit
        }
    }

    /**
     * 取章节朗读文本并建段落表 (按 [pendingStartPos] 裁掉已读部分)。
     *
     * 段落表来自本地缓存正文 (ReadBookViewModelShared 当前已排版章节的正文也走
     * BookStorageProviders 缓存), 与 DefaultReadAloudNavigator 同策略: 缓存未命中时
     * 返回空列表 + 触发 ViewModel 联网拉取 (state 置 ERROR, 用户重试时缓存已就绪)。
     */
    private fun buildParagraphs(chapterIndex: Int): List<String> {
        val viewModel = ActiveReadBookRegistry.currentViewModel ?: return emptyList()
        val book = viewModel.book.value ?: return emptyList()
        val chapter = viewModel.chapterList.value.getOrNull(chapterIndex) ?: run {
            viewModel.loadChapter(chapterIndex)
            return emptyList()
        }
        val content = runCatching {
            BookStorageProviders.get().getContent(book, chapter)
        }.getOrNull()
        if (content.isNullOrBlank()) {
            viewModel.loadChapter(chapterIndex)
            return emptyList()
        }
        val all = ReadAloudQueue.splitParagraphs(content)
        if (all.isEmpty()) return emptyList()
        val offsets = IntArray(all.size)
        var acc = 0
        for (i in all.indices) {
            offsets[i] = acc
            acc += all[i].length + 1
        }
        paragraphs = all
        paragraphOffsets = offsets

        val startPos = pendingStartPos.coerceAtLeast(0)
        pendingStartPos = 0
        var base = 0
        while (base + 1 < all.size && offsets[base + 1] <= startPos) base++
        var skip = (startPos - offsets[base]).coerceIn(0, all[base].length)
        if (skip >= all[base].length && base + 1 < all.size) {
            base++
            skip = 0
        }
        paragraphBase = base
        firstParagraphSkip = skip
        val sub = all.subList(base, all.size).toMutableList()
        if (skip > 0) sub[0] = sub[0].substring(skip)
        return sub
    }

    /** 章节导航: 桥接到当前阅读 ViewModel (对照 desktop DesktopReadAloudHost.Navigator)。 */
    private object Navigator : ReadAloudChapterNavigator {

        override val chapterCount: Int
            get() = ActiveReadBookRegistry.currentViewModel?.chapterSize ?: 0

        override fun loadChapterParagraphs(chapterIndex: Int): List<String> =
            OhosReadAloudHost.buildParagraphs(chapterIndex)

        override fun moveToChapter(chapterIndex: Int) {
            val viewModel = ActiveReadBookRegistry.currentViewModel ?: return
            if (viewModel.durChapterIndex.value == chapterIndex) return
            viewModel.loadChapter(chapterIndex)
        }

        override fun moveToNextChapter() {
            ActiveReadBookRegistry.currentViewModel?.moveToNextChapter()
        }

        override fun moveToPrevChapter() {
            ActiveReadBookRegistry.currentViewModel?.moveToPrevChapter()
        }
    }
    // endregion

    /** 原版 AppConfig.defaultSpeechRate。 */
    private const val DEFAULT_SPEECH_RATE = 5
}
