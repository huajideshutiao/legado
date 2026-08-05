package io.legado.desktop.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudChapterNavigator
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.desktop.audio.DesktopSmtc
import io.legado.desktop.audio.SmtcState
import io.legado.desktop.help.tts.DesktopReadAloudHost.controller
import io.legado.desktop.help.tts.DesktopReadAloudHost.pause
import io.legado.desktop.help.tts.DesktopReadAloudHost.pendingStartPos
import io.legado.desktop.help.tts.DesktopReadAloudHost.play
import io.legado.desktop.help.tts.DesktopReadAloudHost.resume
import io.legado.desktop.help.tts.DesktopReadAloudHost.stop
import io.legado.desktop.help.tts.DesktopReadAloudHost.syncReadPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面端朗读宿主: 把 [ReadAloudControllerShared] 接到阅读编排上, 替代 app 端的
 * `BaseReadAloudService` + `ReadAloud` 门面 (桌面无前台 Service)。
 *
 * # 与原版链路的对照
 *
 * | 原版 (app)                                   | 桌面端 (本对象)                              |
 * |---------------------------------------------|---------------------------------------------|
 * | `ReadAloud.play/pause/resume/stop`          | [play] / [pause] / [resume] / [stop]        |
 * | `BaseReadAloudService.newReadAloud` 建队列  | [Navigator.loadChapterParagraphs] + startPos 裁剪 |
 * | `TTSUtteranceListener` 段推进                | [ReadAloudControllerShared] 的 onDone 推进   |
 * | `BaseReadAloudService.nextChapter`          | 控制器末段 → [Navigator.moveToNextChapter]  |
 * | `EventBus.TTS_PROGRESS` 观察者同步阅读位置   | [syncReadPosition]                          |
 * | `EventBus.ALOUD_STATE`                      | [ReadBookEvents.postAloudState]             |
 *
 * # 阅读位置口径
 *
 * 段落表来自已排版章节的页文本拼接, 故段落章内起始位置与 `durChapterPos` / 页索引同口径
 * (章节未排版时退回本地缓存正文, 位置可能与排版结果有偏差)。段起始位置的累加方式
 * (段长 + 1 个换行) 与 [ReadAloudQueue.readAloudNumber] 一致。
 */
object DesktopReadAloudHost {

    /** 原版 AppConfig.defaultSpeechRate。 */
    private const val DEFAULT_SPEECH_RATE = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前朗读章节的完整段落表 (未按 startPos 裁剪)。 */
    private var paragraphs: List<String> = emptyList()

    /** 各段在章内的起始字符位置。 */
    private var paragraphOffsets: IntArray = IntArray(0)

    /** 控制器段下标 0 对应的真实段下标 (startPos 裁剪产生的偏移)。 */
    private var paragraphBase: Int = 0

    /** 首段被 startPos 裁掉的段内字符数。 */
    private var firstParagraphSkip: Int = 0

    /** 下一次 [ReadAloudControllerShared.start] 的章内起始字符位置。 */
    private var pendingStartPos: Int = 0

    /** 本宿主最近一次写入的 durChapterPos, 用于把自身写入与用户翻页区分开。 */
    @Volatile
    private var lastSyncedPos: Int = -1

    /** 暂停期间发生过翻页, resume 时按新位置重开 (对照原版 newReadAloud(play=false))。 */
    @Volatile
    private var restartOnResume: Boolean = false

    private var positionWatchJob: Job? = null

    /** [controller] 的裸引用: 供 lazy 初始化期间启动的协程读取, 避免回环触发 lazy。 */
    @Volatile
    private var controllerRef: ReadAloudControllerShared? = null

    /** 朗读控制器 (进程内单例, 首次访问时建)。 */
    val controller: ReadAloudControllerShared by lazy { createController() }

    /** 对应 app 端 `BaseReadAloudService.isRun`。 */
    val isRun: Boolean
        get() = controllerRef?.state?.value
            .let { it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED }

    /** 对应 app 端 `BaseReadAloudService.pause`。 */
    val isPause: Boolean get() = controllerRef?.state?.value != ReadAloudState.PLAYING

    /** 书名 / 章节名 (托盘 tooltip 用)。 */
    val bookName: String? get() = ActiveReadBookRegistry.current?.bookValue?.name

    val chapterTitle: String?
        get() = ActiveReadBookRegistry.current?.let { readBook ->
            readBook.chapterListValue?.getOrNull(readBook.durChapterIndexValue)?.title
        }

    /**
     * 开始 / 准备朗读 (对照 `ReadAloud.play(context, play, pageIndex, startPos)`)。
     *
     * @param play false 表示只记录新位置不出声 (原版翻页时朗读处于暂停态的分支)
     * @param startPos 相对当前 `durChapterPos` 的附加偏移
     */
    fun play(play: Boolean = true, startPos: Int = 0) {
        val readBook = ActiveReadBookRegistry.current ?: return
        val pos = (readBook.durChapterPosValue + startPos).coerceAtLeast(0)
        if (!play) {
            restartOnResume = true
            return
        }
        startAt(readBook.durChapterIndexValue, pos)
    }

    /** 暂停朗读 (对照 `ReadAloud.pause`)。 */
    fun pause() {
        controllerRef?.pause()
    }

    /** 继续朗读; 暂停期间翻过页时按新位置重开 (对照 `ReadAloud.resume`)。 */
    fun resume() {
        val readBook = ActiveReadBookRegistry.current
        if (restartOnResume && readBook != null) {
            startAt(readBook.durChapterIndexValue, readBook.durChapterPosValue)
        } else {
            controllerRef?.resume()
        }
    }

    /** 停止朗读并清理 (对照 `ReadAloud.stop`)。 */
    fun stop() {
        positionWatchJob?.cancel()
        positionWatchJob = null
        restartOnResume = false
        sleepTimer.cancel()
        controllerRef?.stop()
    }

    /**
     * 朗读按钮短按 (对照 app 端 `AndroidReaderPlatformProvider.clickReadAloud`):
     * 未运行 → 开始, 暂停中 → 继续, 否则 → 暂停。
     */
    fun toggle() {
        when {
            !isRun -> play()
            isPause -> resume()
            else -> pause()
        }
    }

    /** 上一句 / 下一句 (对照 `ReadAloud.prevParagraph/nextParagraph`)。 */
    fun prevParagraph() {
        controllerRef?.prevParagraph()
    }

    fun nextParagraph() {
        controllerRef?.nextParagraph()
    }

    /** 定时关闭剩余分钟 (对照 `BaseReadAloudService.timeMinute`)。 */
    val timeMinute: Int get() = sleepTimer.minutes

    /** 设定定时关闭 (对照 `ReadAloud.setTimer` → `BaseReadAloudService` 的 SleepTimer)。 */
    fun setTimer(minute: Int) {
        sleepTimer.set(minute)
    }

    /**
     * 设定语速并实时生效 (对照 `AppConfig.ttsSpeechRate = v` + `ReadAloud.upTtsSpeechRate`)。
     *
     * @param rate 原版 ttsSpeechRate 口径 (0..45), 折算倍率 (rate + 5) / 10f
     */
    fun setSpeechRate(rate: Int) {
        controller.setSpeechRate((rate.coerceIn(0, 45) + 5) / 10f)
        // 原版 upTtsSpeechRate 后会 pause+resume 让新语速立刻作用到当前段
        if (!isPause) {
            controller.pause()
            controller.resume()
        }
    }

    // region 内部实现

    /** 定时关闭: 到点暂停朗读, 与原版 BaseReadAloudService 的 SleepTimer 同语义。 */
    private val sleepTimer by lazy {
        SleepTimer(
            scope = scope,
            postMinute = { ReadBookEvents.postReadAloudDs(it) },
            isPaused = { isPause },
            onTimeout = { pause() },
        )
    }

    private fun createController(): ReadAloudControllerShared {
        val instance = ReadAloudControllerShared(
            navigator = Navigator,
            // Book.ttsEngine 优先, 否则 AppConfig.ttsEngine (与原版 ReadAloud.ttsEngine 一致)
            ttsEngineConfigProvider = {
                ActiveReadBookRegistry.current?.bookValue?.config?.ttsEngine
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { AppConfigProviders.get().ttsEngine }
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
        scope.launch {
            instance.lastError.collect { AppLog.put(it) }
        }
        return instance
    }

    private fun startAt(chapterIndex: Int, pos: Int) {
        pendingStartPos = pos
        lastSyncedPos = pos
        restartOnResume = false
        applySpeechRate()
        controller.start(chapterIndex)
        startPositionWatch()
    }

    /** AppConfig.ttsSpeechRate (0..45) 折算为控制器倍率 (原版 (rate + 5) / 10f)。 */
    private fun applySpeechRate() {
        // 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)
        val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return
        val rate = if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
            DEFAULT_SPEECH_RATE
        } else {
            prefs.getInt(PreferKey.ttsSpeechRate, DEFAULT_SPEECH_RATE)
        }
        controller.setSpeechRate((rate + 5) / 10f)
    }

    /**
     * 监听用户手动翻页: 阅读位置被挪出当前朗读段时从新位置重开朗读。
     *
     * 对应原版 `ReadBook.curPageChanged` → `readAloud(!pause)` 那条回环 —— 桌面阅读页走
     * ViewModel 翻页, 不经 `ReadBookShared.curPageChanged`, 故改为直接盯 durChapterPos。
     */
    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        val readBook = ActiveReadBookRegistry.current ?: return
        val controller = controllerRef ?: return
        positionWatchJob = scope.launch {
            readBook.durChapterPos.collect { pos ->
                if (controller.state.value != ReadAloudState.PLAYING) return@collect
                if (pos == lastSyncedPos) return@collect
                if (readBook.durChapterIndexValue != controller.chapterIndex.value) return@collect
                if (pos in currentParagraphRange()) return@collect
                startAt(readBook.durChapterIndexValue, pos)
            }
        }
    }

    /** 当前朗读段在章内占据的字符区间。 */
    private fun currentParagraphRange(): IntRange {
        val index = controllerRef?.paragraphIndex?.value ?: return IntRange.EMPTY
        val real = paragraphBase + index.coerceAtLeast(0)
        val start = paragraphOffsets.getOrNull(real) ?: return IntRange.EMPTY
        val length = paragraphs.getOrNull(real)?.length ?: return IntRange.EMPTY
        return start until (start + length + 1)
    }

    /**
     * 朗读推进到某段时把阅读位置拉过去并高亮该段。
     *
     * 对照 app 端 ReadBookActivity 的 TTS_PROGRESS 观察者。
     */
    private fun syncReadPosition(index: Int) {
        if (index < 0) return
        val controller = controllerRef ?: return
        val readBook = ActiveReadBookRegistry.current ?: return
        val viewModel = ActiveReadBookRegistry.currentViewModel ?: return
        if (readBook.durChapterIndexValue != controller.chapterIndex.value) return
        val real = paragraphBase + index
        var pos = paragraphOffsets.getOrNull(real) ?: return
        if (index == 0) pos += firstParagraphSkip
        lastSyncedPos = pos
        runCatching {
            viewModel.updateReadPosition(pos)
            val chapter = readBook.curChapter ?: return
            val pageIndex = readBook.durPageIndexValue
            chapter.getPage(pageIndex)?.upPageAloudSpan(pos - chapter.getReadLength(pageIndex))
            // 高亮是页对象原地修改，StateFlow 去重不重发；自增版本驱动 Compose Canvas 重绘
            viewModel.bumpPageContentVersion()
        }.onFailure { AppLog.put("同步朗读位置出错", it) }
    }

    private fun onStateChanged(state: ReadAloudState) {
        when (state) {
            ReadAloudState.PLAYING -> {
                ReadBookEvents.postAloudState(Status.PLAY)
                syncSmtc(playing = true)
            }

            ReadAloudState.PAUSED -> {
                ReadBookEvents.postAloudState(Status.PAUSE)
                removeAloudSpan()
                syncSmtc(paused = true)
            }

            ReadAloudState.STOPPED, ReadAloudState.COMPLETED, ReadAloudState.ERROR -> {
                ReadBookEvents.postAloudState(Status.STOP)
                removeAloudSpan()
                syncSmtc(stopped = true)
            }

            ReadAloudState.IDLE -> Unit
        }
    }

    /**
     * 同步 SMTC 媒体卡 (Title=章节 / Artist=书名 / AlbumArtist=作者)。
     * 音频活跃时 SMTC 卡归音频 (与托盘/任务栏优先级一致), 朗读侧不覆盖。
     */
    private fun syncSmtc(
        playing: Boolean = false,
        paused: Boolean = false,
        stopped: Boolean = false
    ) {
        if (AudioPlayShared.status != Status.STOP) return
        if (!com.sun.jna.Platform.isWindows()) return
        val readBook = ActiveReadBookRegistry.current
        DesktopSmtc.update(
            SmtcState(
                title = chapterTitle ?: "",
                artist = bookName ?: "",
                albumArtist = readBook?.bookValue?.author ?: "",
                isPlaying = playing,
                isPaused = paused,
                prevNextEnabled = true,
                playbackRate = 0f, // 朗读无倍速概念, 不写 PlaybackRate
            )
        )
        if (stopped) {
            DesktopSmtc.release()
        }
    }

    private fun removeAloudSpan() {
        val readBook = ActiveReadBookRegistry.current ?: return
        runCatching {
            readBook.curChapter?.getPage(readBook.durPageIndexValue)?.removePageAloudSpan()
            // 原地修改需自增版本驱动 Compose Canvas 重绘 (与 syncReadPosition 同口径)
            ActiveReadBookRegistry.currentViewModel?.bumpPageContentVersion()
        }
    }

    /**
     * 取章节朗读文本: 已排版章节用页文本拼接 (位置与 durChapterPos 同口径),
     * 否则退回本地缓存正文。
     */
    private fun chapterText(chapterIndex: Int): String? {
        val readBook = ActiveReadBookRegistry.current ?: return null
        if (chapterIndex == readBook.durChapterIndexValue) {
            val laidOut = readBook.curTextChapter.value
            if (laidOut != null && laidOut.isCompleted && laidOut.pages.isNotEmpty()) {
                return laidOut.pages.joinToString("") { it.text }
            }
        }
        val book = readBook.bookValue ?: return null
        val chapter = readBook.chapterListValue?.getOrNull(chapterIndex) ?: return null
        return runCatching { BookStorageProviders.get().getContent(book, chapter) }.getOrNull()
    }

    /**
     * 建段落表并按 [pendingStartPos] 裁掉已读部分。
     *
     * 对照原版 `newReadAloud` 的 `getParagraphNum` + `paragraphStartPos`: 从包含起始位置的
     * 那一段开始朗读, 该段内已读的字符直接截掉。
     */
    private fun buildParagraphs(chapterIndex: Int): List<String> {
        val text = chapterText(chapterIndex) ?: return emptyList()
        val all = ReadAloudQueue.splitParagraphs(text)
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

    /** 章节导航: 桥接到当前阅读 ViewModel (对照原版 BaseReadAloudService 直接调 ReadBook)。 */
    private object Navigator : ReadAloudChapterNavigator {

        override val chapterCount: Int
            get() = ActiveReadBookRegistry.current?.simulatedChapterSize ?: 0

        override fun loadChapterParagraphs(chapterIndex: Int): List<String> =
            DesktopReadAloudHost.buildParagraphs(chapterIndex)

        override fun moveToChapter(chapterIndex: Int) {
            val viewModel = ActiveReadBookRegistry.currentViewModel ?: return
            // 控制器切章后会再调一次本方法, 已在目标章时不重复触发装载
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
}
