package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.SingleJobSlot
import io.legado.app.help.media.PhoneCallPauseHandler
import io.legado.app.help.media.PhoneCallState
import io.legado.app.help.media.ReadAloudRemoteHost
import io.legado.app.help.media.SleepTimer
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.service.ReadAloudChapterDataPort
import io.legado.app.service.ReadAloudChapterPlan
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.service.ReadAloudPlanBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 无前台 Service 的平台 (桌面 / iOS / 鸿蒙) 共用的朗读宿主: 把 [ReadAloudControllerShared]
 * 接到阅读编排上, 替代 app 端的 `BaseReadAloudService` + `ReadAloud` 门面。
 *
 * 段落表取已排版章节的页文本拼接, 故段落章内起始位置与 `durChapterPos` / 页索引同口径
 * (章节未排版时退回本地缓存正文); 起始位置的累加方式 (段长 + 1 个换行) 与
 * [ReadAloudQueue.Snapshot.readAloudNumber] 一致。
 */
abstract class ReadAloudHostShared(
    private val ports: ReadAloudHostPorts = ActiveReadAloudHostPorts,
) : ReadAloudRemoteHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 段落计划构建器 (与 Android 朗读服务共用同一算法, 状态由它持有)。 */
    private val planBuilder = ReadAloudPlanBuilder()

    /** 暂停期间发生过翻页；记录与章节绑定的新位置，resume 按它重建。 */
    @Volatile
    private var pausedPosition: ReadAloudPosition? = null

    /**
     * 翻页监听槽: 翻页/暂停/开始朗读都会重建它, 重建与 [stop] 分属不同线程。
     */
    private val positionWatch = SingleJobSlot(scope)

    /** 章节装载就绪监听槽: 与 [stop] 分属不同线程。 */
    private val chapterWatch = SingleJobSlot(scope)

    /** [controller] 的裸引用: 供 lazy 初始化期间启动的协程读取, 避免回环触发 lazy。 */
    @Volatile
    private var controllerRef: ReadAloudControllerShared? = null

    /** 朗读控制器 (进程内单例, 首次访问时建)。 */
    val controller: ReadAloudControllerShared by lazy { createController() }

    /**
     * 最近一次已知的书名快照。
     *
     * 朗读不依赖阅读页存活: 阅读页销毁后 [ActiveReadBookRegistry] 已置空, 此时
     * 从托盘/播控中心 pause→resume 会把会话切到空书名 pending 且无人补名
     * (听书时长从此不再计入)。故在每次拿到非空书名时留快照, 供无阅读页时回退。
     */
    @Volatile
    private var lastBookName: String = ""

    /** 当前朗读书名: 活动阅读实例优先, 无阅读页时回退到上次非空快照。 */
    private fun currentBookName(): String {
        ActiveReadBookRegistry.current?.bookValue?.name?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return lastBookName
    }

    /** 对应 app 端 `BaseReadAloudService.isRun`。 */
    override val isRun: Boolean
        get() = controllerRef?.state?.value
            .let { it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED || it == ReadAloudState.WAITING }

    /** 对应 app 端 `BaseReadAloudService.pause`。 */
    override val isPause: Boolean get() = controllerRef?.state?.value != ReadAloudState.PLAYING

    /**
     * 挂起等待朗读停止: 运行中(播放/暂停/等待装载)即挂起, 到空闲或终态即返回。
     * controller.state 是 StateFlow, 重放当前值, 等待开始前发生的停止不会错过。
     */
    suspend fun awaitStopped() {
        controller.state.first {
            it != ReadAloudState.PLAYING && it != ReadAloudState.PAUSED && it != ReadAloudState.WAITING
        }
    }

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
        chapterWatch.cancel()
        positionWatch.cancel()
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

    // ===== 来电暂停 (对照 app 端 `BaseReadAloudService` 的 ReadAloudPhoneStateListener) =====

    /**
     * 来电三态状态机 (平台事件源翻译成 [PhoneCallState] 后调 [onPhoneCallState])。
     *
     * `onPause` 不传 abandonFocus: 本类平台无音频焦点对象, 暂停只需停 TTS/播放器。
     */
    private val phoneCallHandler = PhoneCallPauseHandler(
        isPaused = { isPause },
        onPause = { pause() },
        onResume = { resume() },
    )

    /**
     * 派发一次电话状态变更 (iOS `CXCallObserver` / 鸿蒙 `callStateChange` 事件源调用)。
     *
     * 消费门槛与 app 端逐字一致 (两个开关都为真); 开关于事件到达时现读 —— 无 Service 的
     * 平台上没有 onDestroy 时机, 而平台观察者注册一次后全程在, 故用读数代替注销。
     */
    fun onPhoneCallState(state: PhoneCallState) {
        val prefs = PreferenceProviders.get()
        val needHandle = prefs.getBoolean(PreferKey.ignoreAudioFocus, false) &&
            prefs.getBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        if (!needHandle) return
        phoneCallHandler.onCallStateChanged(state)
    }

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
        controller.setSpeechRate(ttsSpeechRateScale(rate.coerceIn(0, 45)))
        // 原版 upTtsSpeechRate 后会 pause+resume 让新语速立刻作用到当前段
        if (!isPause) {
            controller.pause()
            controller.resume()
        }
    }

    /** 按当前语速配置重新生效 (对照 `ReadAloud.upTtsSpeechRate`): 不写配置, 只重放。 */
    fun upSpeechRate() {
        setSpeechRate(configuredTtsSpeechRate())
    }

    // region 内部实现

    /** 定时关闭: 到点终止朗读, 与原版 BaseReadAloudService 的 SleepTimer 同语义。 */
    private val sleepTimer by lazy {
        SleepTimer(
            scope = scope,
            postMinute = { ports.positionPublisher.publishTimer(it) },
            isPaused = { isPause },
            onTimeout = { stop() },
        )
    }

    private fun createController(): ReadAloudControllerShared {
        val instance = ReadAloudControllerShared(
            chapterData = chapterData,
            chapterNavigation = ports.chapterNavigation,
        )
        controllerRef = instance
        scope.launch {
            instance.paragraphIndex.collect {
                chapterCoordinator.syncParagraphStart(it, planBuilder.snapshot)
            }
        }
        scope.launch {
            // 字级进度: 长段跨页时按念到的字推进阅读页, 段级进度要等整段念完才动
            instance.speakPosition.collect { chapterCoordinator.syncSpeakPosition(it) }
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
        pausedPosition = null
        startChapterWatch()
        applySpeechRate()
        controller.start(chapterIndex, pos)
        startPositionWatch()
    }

    /**
     * 监听章节装载就绪 (对照原版 BaseReadAloudService.armChapterWatch):
     * 跨章未缓存时控制器置 WAITING, 待章节加载排版完成后自动续播。
     */
    /**
     * 监听章节进展: 排版产物就绪 (阅读页装载完成) 与装载结束答复合并看。
     *
     * 跨章未缓存时控制器置 WAITING, 待章节可读后自动续播; 装不出来则由装载答复上报停止。
     */
    private fun startChapterWatch() {
        // 已在跑就不重建: 重建会丢掉订阅间隙里的事件, 置 WAITING 的章节可能再也不续播
        chapterWatch.restartIfInactive {
            chapterCoordinator.startWatch()
        }
    }

    /**
     * 等章链路交给共享协调器: 平台侧只提供正文是否到手、装载请求与答复、有无阅读页这些事实。
     */
    private val chapterCoordinator by lazy {
        ReadAloudChapterCoordinator(ports) { controller }
    }

    /** AppConfig.ttsSpeechRate (0..45) 折算为控制器倍率 (与一次性朗读共用 [ttsSpeechRateScale])。 */
    private fun applySpeechRate() {
        controller.setSpeechRate(ttsSpeechRateScale(configuredTtsSpeechRate()))
    }

    /**
     * 监听用户手动翻页: 阅读位置被挪出当前朗读段时从新位置重开朗读。
     *
     * 对应原版 `ReadBook.curPageChanged` → `readAloud(!pause)` 那条回环 —— Compose 阅读页走
     * ViewModel 翻页, 不经 `ReadBookShared.curPageChanged`, 故改为直接盯 durChapterPos。
     *
     * 重建可能由 UI 线程 (play/resume) 或监听协程自身 (翻页重开) 发起, 与 [stop] 并发时由
     * [SingleJobSlot] 保证旧监听一定被取消、不会留下第二个活监听。
     */
    private fun startPositionWatch() {
        positionWatch.restart {
            ports.positionUpdates.collect { position ->
                chapterCoordinator.onPosition(
                    position = position,
                    paragraphRange = ::currentParagraphRange,
                    onFollow = { follow ->
                        AppLog.putDebug(
                            "朗读跟随阅读位置: 第 ${controller.chapterIndex.value + 1} 章 -> " +
                                "第 ${follow.chapterIndex + 1} 章"
                        )
                        pausedPosition = null
                        controller.seekTo(follow.chapterPosition, follow.chapterIndex)
                    },
                    onPauseMove = { pausedPosition = it },
                )
            }
        }
    }

    /** 当前朗读段在章内占据的字符区间。 */
    private fun currentParagraphRange(): IntRange =
        chapterCoordinator.paragraphRange(
            paragraphIndex = controllerRef?.paragraphIndex?.value ?: -1,
            geometry = planBuilder.snapshot,
        )

    /** 朗读态广播; 暂停/停止时的清高亮由阅读页的 ALOUD_STATE 订阅负责。 */
    private fun onStateChanged(state: ReadAloudState) {
        when (state) {
            ReadAloudState.PLAYING -> {
                // 本类平台无 BaseReadAloudService, 朗读计时由状态广播承担,
                // 语义对齐 app 端: PLAYING=start / PAUSED=end / 终态=endImmediately
                val bookName = currentBookName()
                lastBookName = bookName
                ReadTimeRecorder.start(ReadTimeRecorder.Source.READ_ALOUD, bookName)
                ports.positionPublisher.publishState(Status.PLAY)
                ports.mediaControl.sync(isPlaying = true)
            }

            ReadAloudState.PAUSED -> {
                ReadTimeRecorder.end(ReadTimeRecorder.Source.READ_ALOUD)
                ports.positionPublisher.publishState(Status.PAUSE)
                ports.mediaControl.sync(isPlaying = false)
            }

            ReadAloudState.STOPPED, ReadAloudState.COMPLETED, ReadAloudState.ERROR -> {
                ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.READ_ALOUD)
                ports.positionPublisher.publishState(Status.STOP)
                ports.mediaControl.release()
            }

            ReadAloudState.IDLE -> Unit

            ReadAloudState.WAITING -> chapterCoordinator.onWaiting()
        }
    }

    /**
     * 取章节朗读文本: 已排版章节用页文本拼接 (位置与 durChapterPos 同口径), 否则退回本地
     * 缓存正文; 都没有时触发一次装载 (对照原版 newReadAloud 等章节排版完成再建队列)。
     */
    private fun chapterText(chapterIndex: Int): String? =
        ports.chapterData.chapterText(chapterIndex)

    /** 将宿主章节数据端口适配为控制器的播放计划 (段落算法在 [planBuilder])。 */
    private val chapterData = object : ReadAloudChapterDataPort {
        override val chapterCount: Int get() = ports.chapterData.chapterCount

        override fun loadChapterPlan(
            chapterIndex: Int,
            chapterPosition: Int,
            fromLastSpeakable: Boolean,
        ): ReadAloudChapterPlan? {
            val text = chapterText(chapterIndex) ?: return null
            return planBuilder.buildPlan(text, chapterPosition, fromLastSpeakable)
        }

        override fun isChapterPaged(chapterIndex: Int): Boolean =
            ports.isChapterTextReady(chapterIndex)
    }
    // endregion
}

data class ReadAloudPosition(
    val chapterIndex: Int,
    val chapterPosition: Int,
    /** 这次位置变化是谁写的: 朗读自己推进的不该被当成用户翻页。 */
    val origin: io.legado.app.model.PositionOrigin = io.legado.app.model.PositionOrigin.User,
)

/** 等章方需要重新评估的原因。 */
sealed interface ReadAloudChapterSignal {
    /** 章号 [chapterIndex] 的装载任务已结束 (无论成或不成)。 */
    data class Settled(val chapterIndex: Int) : ReadAloudChapterSignal

    /** 阅读实例更换: 之前发起的装载答复已不再有意义, 按新实例重新评估。 */
    object SourceSwitched : ReadAloudChapterSignal
}
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

    /** 朗读位置推进联动: 返回是否发生了翻页; 无活动阅读页时 no-op 返回 false。 */
    fun advanceReadAloudPage(chapterIndex: Int, readingPos: Int): Boolean = false

    /** [chapterIndex] 的正文是否已排版就绪 (三章滑窗内命中该章, 且不是占位消息页)。 */
    fun isChapterTextReady(chapterIndex: Int): Boolean = false

    /**
     * 当前是否有能提供正文的阅读页。
     *
     * 朗读等章时先查这个状态: 没有阅读页就不会有任何答复, 不能静默等。
     */
    val chapterSourceAvailable: Boolean get() = false

    /**
     * 等章方需要重新评估的信号: 装载任务结束 (无论从哪条分支) 与阅读实例更换。
     *
     * 等章方据此重新评估, 不轮询状态; 能否续读以控制器能否建出计划为准。
     */
    val chapterSignals: Flow<ReadAloudChapterSignal> get() = emptyFlow()

    /**
     * 请求装载 [chapterIndex] 的正文 (不改变当前阅读位置、不动页面流)。
     *
     * 进入一章时就把下一章备好, 章末换章才不会退化成"等一次网络往返"; 进入 WAITING
     * 时也用它请求装载。装载结束的答复经 [chapterSignals] 回来。
     */
    fun prefetchChapter(chapterIndex: Int) = Unit
}
