package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.media.ReadAloudRemoteHost
import io.legado.app.help.media.SleepTimer
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.service.ReadAloudChapterDataPort
import io.legado.app.service.ReadAloudChapterPlan
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 无前台 Service 的平台 (桌面 / iOS / 鸿蒙) 共用的朗读宿主: 把 [ReadAloudControllerShared]
 * 接到阅读编排上, 替代 app 端的 `BaseReadAloudService` + `ReadAloud` 门面。
 *
 * 段落表取已排版章节的页文本拼接, 故段落章内起始位置与 `durChapterPos` / 页索引同口径
 * (章节未排版时退回本地缓存正文); 起始位置的累加方式 (段长 + 1 个换行) 与
 * [ReadAloudQueue.readAloudNumber] 一致。
 */
abstract class ReadAloudHostShared(
    private val ports: ReadAloudHostPorts = ActiveReadAloudHostPorts,
) : ReadAloudRemoteHost {

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
    @Volatile
    private var pendingStartPos: Int = 0

    /** 本宿主最近一次写入的 durChapterPos, 用于把自身写入与用户翻页区分开。 */
    @Volatile
    private var lastSyncedPos: Int = -1

    /** 暂停期间发生过翻页；记录与章节绑定的新位置，resume 按它重建。 */
    @Volatile
    private var pausedPosition: ReadAloudPosition? = null

    private var positionWatchJob: Job? = null

    /** [controller] 的裸引用: 供 lazy 初始化期间启动的协程读取, 避免回环触发 lazy。 */
    @Volatile
    private var controllerRef: ReadAloudControllerShared? = null

    /** 朗读控制器 (进程内单例, 首次访问时建)。 */
    val controller: ReadAloudControllerShared by lazy { createController() }

    /** 对应 app 端 `BaseReadAloudService.isRun`。 */
    override val isRun: Boolean
        get() = controllerRef?.state?.value
            .let { it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED || it == ReadAloudState.WAITING }

    /** 对应 app 端 `BaseReadAloudService.pause`。 */
    override val isPause: Boolean get() = controllerRef?.state?.value != ReadAloudState.PLAYING

    /**
     * 开始朗读 (对照 `ReadAloud.play(context, play, pageIndex, startPos)` → `newReadAloud`):
     * 无条件按新位置重建队列, 暂停中续读走 [resume] 而非本方法。
     *
     * @param play false 表示只记录新位置不出声 (原版翻页时朗读处于暂停态的分支)
     * @param startPos 相对当前 `durChapterPos` 的附加偏移
     */
    fun play(play: Boolean = true, startPos: Int = 0) {
        val position = ports.currentPosition ?: return
        if (!play) {
            pausedPosition = ReadAloudPosition(
                position.chapterIndex,
                (position.chapterPosition + startPos).coerceAtLeast(0),
            )
            startPositionWatch()
            return
        }
        val pos = (position.chapterPosition + startPos).coerceAtLeast(0)
        startAt(position.chapterIndex, pos)
    }

    /** 暂停朗读 (对照 `ReadAloud.pause`)。 */
    override fun pause() {
        controllerRef?.pause()
    }

    /** 继续朗读; 暂停期间翻过页时按新位置重开 (对照 `ReadAloud.resume`)。 */
    override fun resume() {
        val position = pausedPosition ?: ports.currentPosition
        if (pausedPosition != null && position != null) {
            startAt(position.chapterIndex, position.chapterPosition)
        } else {
            controllerRef?.resume()
        }
    }

    /** 停止朗读并清理 (对照 `ReadAloud.stop`)。 */
    override fun stop() {
        chapterWatchJob?.cancel()
        chapterWatchJob = null
        positionWatchJob?.cancel()
        positionWatchJob = null
        pausedPosition = null
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
    override fun prevParagraph() {
        controllerRef?.prevParagraph()
    }

    override fun nextParagraph() {
        controllerRef?.nextParagraph()
    }

    /** 上一章 / 下一章 (对照 `ReadAloud.prevChapter/nextChapter`, 媒体键开了"按章切换"时走这里)。 */
    override fun prevChapter() {
        controllerRef?.prevChapter()
    }

    override fun nextChapter() {
        controllerRef?.nextChapter()
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

    /** 按当前语速配置重新生效 (对照 `ReadAloud.upTtsSpeechRate`): 不写配置, 只重放。 */
    fun upSpeechRate() {
        setSpeechRate(configuredSpeechRate())
    }

    // region 内部实现

    /** 定时关闭: 到点暂停朗读, 与原版 BaseReadAloudService 的 SleepTimer 同语义。 */
    private val sleepTimer by lazy {
        SleepTimer(
            scope = scope,
            postMinute = { ports.positionPublisher.publishTimer(it) },
            isPaused = { isPause },
            onTimeout = { pause() },
        )
    }

    private fun createController(): ReadAloudControllerShared {
        val instance = ReadAloudControllerShared(
            chapterData = chapterData,
            chapterNavigation = ports.chapterNavigation,
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
        startChapterWatch()
        return instance
    }

    private fun startAt(chapterIndex: Int, pos: Int) {
        lastSyncedPos = pos
        pausedPosition = null
        startChapterWatch()
        applySpeechRate()
        controller.start(chapterIndex, pos)
        startPositionWatch()
    }

    private var chapterWatchJob: Job? = null

    /**
     * 监听章节装载就绪 (对照原版 BaseReadAloudService.armChapterWatch):
     * 跨章未缓存时控制器置 WAITING, 待章节加载排版完成后自动续播。
     */
    private fun startChapterWatch() {
        if (chapterWatchJob?.isActive == true) return
        chapterWatchJob = scope.launch {
            ports.chapterUpdates.collect { update ->
                if (controller.state.value == ReadAloudState.WAITING &&
                    update.chapterIndex == controller.chapterIndex.value
                ) {
                    if (update.error != null) controller.reportError(update.error)
                    else if (update.ready) controller.retryWaiting(update.chapterIndex)
                }
            }
        }
    }

    /** AppConfig.ttsSpeechRate (0..45) 折算为控制器倍率 (原版 (rate + 5) / 10f)。 */
    private fun applySpeechRate() {
        controller.setSpeechRate((configuredSpeechRate() + 5) / 10f)
    }

    /** 当前生效语速 0..45; 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)。 */
    private fun configuredSpeechRate(): Int {
        val prefs = PreferenceProviders.get()
        return if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
            DEFAULT_SPEECH_RATE
        } else {
            prefs.getInt(PreferKey.ttsSpeechRate, DEFAULT_SPEECH_RATE)
        }
    }

    /**
     * 监听用户手动翻页: 阅读位置被挪出当前朗读段时从新位置重开朗读。
     *
     * 对应原版 `ReadBook.curPageChanged` → `readAloud(!pause)` 那条回环 —— Compose 阅读页走
     * ViewModel 翻页, 不经 `ReadBookShared.curPageChanged`, 故改为直接盯 durChapterPos。
     */
    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        positionWatchJob = scope.launch {
            ports.positionUpdates.collect { position ->
                val state = controller.state.value
                if (state == ReadAloudState.PAUSED) {
                    if (position.chapterIndex != controller.chapterIndex.value ||
                        position.chapterPosition !in currentParagraphRange()
                    ) {
                        pausedPosition = position
                    }
                    return@collect
                }
                if (state != ReadAloudState.PLAYING) return@collect
                if (position.chapterPosition == lastSyncedPos) return@collect
                if (position.chapterIndex != controller.chapterIndex.value) return@collect
                if (position.chapterPosition in currentParagraphRange()) return@collect
                startAt(position.chapterIndex, position.chapterPosition)
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
     * 朗读推进到某段时把进度发给阅读页 (对照原版 `BaseReadAloudService.upTtsProgress`):
     * 阅读页的 TTS_PROGRESS 订阅负责写 durChapterPos 与朗读高亮。
     */
    private fun syncReadPosition(index: Int) {
        if (index < 0) return
        val controller = controllerRef ?: return
        val position = ports.currentPosition ?: return
        if (position.chapterIndex != controller.chapterIndex.value) return
        val real = paragraphBase + index
        var pos = paragraphOffsets.getOrNull(real) ?: return
        if (index == 0) pos += firstParagraphSkip
        lastSyncedPos = pos
        ports.positionPublisher.publishPosition(pos)
    }

    /** 朗读态广播; 暂停/停止时的清高亮由阅读页的 ALOUD_STATE 订阅负责。 */
    private fun onStateChanged(state: ReadAloudState) {
        when (state) {
            ReadAloudState.PLAYING -> {
                ports.positionPublisher.publishState(Status.PLAY)
                ports.mediaControl.sync(isPlaying = true)
            }

            ReadAloudState.PAUSED -> {
                ports.positionPublisher.publishState(Status.PAUSE)
                ports.mediaControl.sync(isPlaying = false)
            }

            ReadAloudState.STOPPED, ReadAloudState.COMPLETED, ReadAloudState.ERROR -> {
                ports.positionPublisher.publishState(Status.STOP)
                ports.mediaControl.release()
            }

            ReadAloudState.IDLE, ReadAloudState.WAITING -> Unit
        }
    }

    /**
     * 取章节朗读文本: 已排版章节用页文本拼接 (位置与 durChapterPos 同口径), 否则退回本地
     * 缓存正文; 都没有时触发一次装载 (对照原版 newReadAloud 等章节排版完成再建队列)。
     */
    private fun chapterText(chapterIndex: Int): String? =
        ports.chapterData.chapterText(chapterIndex)

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

    /** 将宿主章节数据端口适配为控制器的播放计划。 */
    private val chapterData = object : ReadAloudChapterDataPort {
        override val chapterCount: Int get() = ports.chapterData.chapterCount

        override fun loadChapterPlan(
            chapterIndex: Int,
            chapterPosition: Int,
            fromLastSpeakable: Boolean,
        ): ReadAloudChapterPlan? {
            pendingStartPos = chapterPosition
            val plan = buildParagraphs(chapterIndex)
                .takeIf { it.isNotEmpty() }
                ?.let { ReadAloudChapterPlan(it, chapterPosition = chapterPosition) }
                ?: return null
            if (!fromLastSpeakable) return plan
            val index = plan.paragraphs.indexOfLast {
                !it.matches(io.legado.app.constant.AppPattern.notReadAloudRegex)
            }
            if (index < 0) return null
            val chapterPos = paragraphOffsets.getOrNull(paragraphBase + index)
                ?: plan.paragraphs.take(index).sumOf { it.length + 1 }
            return plan.copy(
                paragraphIndex = index,
                paragraphOffset = 0,
                chapterPosition = chapterPos,
            )
        }
    }
    // endregion

    private companion object {
        /** 原版 AppConfig.defaultSpeechRate。 */
        const val DEFAULT_SPEECH_RATE = 5
    }
}

data class ReadAloudPosition(val chapterIndex: Int, val chapterPosition: Int)
data class ReadAloudChapterUpdate(
    val chapterIndex: Int,
    val ready: Boolean,
    val error: String? = null
)

interface ReadAloudChapterDataHostPort {
    val chapterCount: Int
    fun chapterText(chapterIndex: Int): String?
}

interface ReadAloudPositionPublisher {
    fun publishPosition(chapterPosition: Int)
    fun publishState(state: Int)
    fun publishTimer(minute: Int)
}

interface ReadAloudMediaControlPort {
    fun sync(isPlaying: Boolean)
    fun release()
}

/** 页面宿主能力集合；业务宿主只依赖这些端口，不反查活动 ViewModel/UI 总线。 */
interface ReadAloudHostPorts {
    val chapterData: ReadAloudChapterDataHostPort
    val chapterNavigation: io.legado.app.service.ReadAloudChapterNavigationPort
    val positionPublisher: ReadAloudPositionPublisher
    val mediaControl: ReadAloudMediaControlPort
    val currentPosition: ReadAloudPosition?
    val positionUpdates: Flow<ReadAloudPosition> get() = emptyFlow()
    val chapterUpdates: Flow<ReadAloudChapterUpdate> get() = emptyFlow()
}
