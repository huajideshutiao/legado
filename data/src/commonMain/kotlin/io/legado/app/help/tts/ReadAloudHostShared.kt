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
import kotlinx.coroutines.delay
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

    /** 本宿主最近一次写入的 durChapterPos, 用于把自身写入与用户翻页区分开。 */
    @Volatile
    private var lastSyncedPos: Int = -1

    /** 暂停期间发生过翻页；记录与章节绑定的新位置，resume 按它重建。 */
    @Volatile
    private var pausedPosition: ReadAloudPosition? = null

    /**
     * 翻页监听槽: 翻页/暂停/开始朗读都会重建它, 重建与 [stop] 分属不同线程。
     */
    private val positionWatch = SingleJobSlot(scope)

    /** 章节装载就绪监听槽: 与 [stop] 分属不同线程。 */
    private val chapterWatch = SingleJobSlot(scope)

    /** 章末等章看门狗: 装载无声夭折时补发/上报, 与 [stop] 分属不同线程。 */
    private val waitingWatch = SingleJobSlot(scope)

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
        waitingWatch.cancel()
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
            instance.paragraphIndex.collect { syncReadPosition(it) }
        }
        scope.launch {
            instance.state.collect { onStateChanged(it) }
        }
        scope.launch {
            instance.lastError.collect { AppLog.put(it) }
        }
        scope.launch {
            // 进入一章就先备好下一章的正文: 章末换章不该再等一次网络往返
            // (正文章节已在磁盘缓存时, 章末 chapterText 直接命中, 不会落到 WAITING)
            instance.chapterIndex.collect { index ->
                if (index >= 0) ports.prefetchChapter(index + 1)
            }
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
        retryWaitingIfReadyNow()
        startPositionWatch()
    }

    /**
     * 控制器刚落到 [ReadAloudState.WAITING] 时立刻补一次就绪检查。
     *
     * `curTextChapter` 的就绪事件可能早于 WAITING 建立 (装载任务与 [startAt] 并发), 那样
     * [startChapterWatch] 会把该事件按"状态不是 WAITING"丢弃, 之后再没有事件可等 ——
     * 这正是"章末停住再也不恢复"的一条路径。这里在 [ReadAloudControllerShared.start] 返回后
     * 按当前状态对齐一次, 不依赖那次事件是否送达。
     */
    private fun retryWaitingIfReadyNow() {
        val chapterIndex = controller.chapterIndex.value
        if (chapterIndex < 0) return
        if (controller.state.value != ReadAloudState.WAITING) return
        if (!ports.isChapterTextReady(chapterIndex)) return
        controller.retryWaiting(chapterIndex)
    }

    /**
     * 章末等章看门狗: 覆盖 [startChapterWatch] 覆盖不到的两类停摆。
     *
     * 换章时控制器置 [ReadAloudState.WAITING], 正常由 `curTextChapter` 的就绪事件唤起续读。但
     * 装载任务可能**无声结束**: `loadContent` 在目录未就绪 / 章节表越界 / 窗口外等分支直接
     * return, 不产生任何 TextChapter, 也就既没有更新事件、也没有错误 —— 朗读就此永久停在
     * WAITING, 听感是"章末停住, 有时几分钟后自己接上, 有时再也不恢复"。
     *
     * 本看门狗补三件事:
     * - 就绪即续读 (不依赖事件送达);
     * - 装载迟迟不完成 (联网正文卡住) 时隔一段重发一次, 卡死的那次不再拖着整场朗读;
     * - 等太久仍不成就上报错误, 把"永久静音"变成可见的停止。
     */
    private fun startWaitingWatch() {
        waitingWatch.restart {
            val chapterIndex = controller.chapterIndex.value
            if (chapterIndex < 0) return@restart
            AppLog.put("朗读等待第 ${chapterIndex + 1} 章正文")
            var deadRetries = 0
            var forces = 0
            var nextForceAt = WAITING_FORCE_INTERVAL_MS
            var waited = 0L
            while (true) {
                delay(WAITING_POLL_MS)
                waited += WAITING_POLL_MS
                if (controller.state.value != ReadAloudState.WAITING) return@restart
                if (ports.isChapterTextReady(chapterIndex)) {
                    AppLog.put("朗读续读第 ${chapterIndex + 1} 章 (等待 ${waited}ms)")
                    if (controller.retryWaiting(chapterIndex)) return@restart
                }
                if (!ports.isChapterLoading(chapterIndex)) {
                    // 装载已无声结束 (目录缺失 / 越界 / 窗口外等分支直接 return, 不产生 TextChapter):
                    // 没有谁会再来通知, 只能补发一次
                    deadRetries++
                    if (deadRetries > WAITING_MAX_RETRIES) {
                        AppLog.put("朗读第 ${chapterIndex + 1} 章装载反复失败, 已停止")
                        controller.reportError("第 ${chapterIndex + 1} 章加载失败, 朗读已停止")
                        return@restart
                    }
                    AppLog.put("朗读重发第 ${chapterIndex + 1} 章装载 ($deadRetries)")
                    ports.prefetchChapter(chapterIndex)
                } else if (waited >= nextForceAt && forces < WAITING_MAX_FORCES) {
                    // 在途装载久不完成 (卡在联网正文上): 没人会取消它, 换掉重来
                    forces++
                    nextForceAt = waited + WAITING_FORCE_INTERVAL_MS
                    AppLog.put("朗读等章过久, 强制重发第 ${chapterIndex + 1} 章装载 ($forces)")
                    ports.prefetchChapter(chapterIndex, force = true)
                }
                if (waited >= WAITING_TIMEOUT_MS) {
                    AppLog.put("朗读等待第 ${chapterIndex + 1} 章超时, 已停止")
                    controller.reportError("等待第 ${chapterIndex + 1} 章超时, 朗读已停止")
                    return@restart
                }
            }
        }
    }

    /**
     * 监听章节装载就绪 (对照原版 BaseReadAloudService.armChapterWatch):
     * 跨章未缓存时控制器置 WAITING, 待章节加载排版完成后自动续播。
     */
    private fun startChapterWatch() {
        // 已在跑就不重建: 重建会丢掉订阅间隙里的事件, 置 WAITING 的章节可能再也不续播
        chapterWatch.restartIfInactive {
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
                // 回上一章且该章未预载时, 位置先是 Int.MAX_VALUE 哨兵 (排版完成后才归一为末页页首);
                // 拿哨兵去建计划没有意义, 等归一后的那次更新再跟
                if (position.chapterPosition == Int.MAX_VALUE) return@collect
                if (position.chapterPosition == lastSyncedPos) return@collect
                if (position.chapterIndex != controller.chapterIndex.value) {
                    // 用户翻到了别的章: 朗读跟过去 (从那一章的阅读位置重开)。
                    // 朗读自己换章时, 控制器在调 moveToChapter 之前就把 chapterIndex 换好了,
                    // 阅读页随后写回的位置一定与它同章, 不会走到这里 —— 所以这条分支只会被
                    // 用户跨章翻页触发。此前这里直接丢弃, 表现就是"翻到下一章, 朗读留在上一章"。
                    AppLog.put(
                        "朗读跟随跨章翻页: 第 ${controller.chapterIndex.value + 1} 章 -> " +
                            "第 ${position.chapterIndex + 1} 章 (pos=${position.chapterPosition})"
                    )
                    // 跨章也走软切换: 换一份章节计划而已, 不必把整场会话拆掉
                    lastSyncedPos = position.chapterPosition
                    pausedPosition = null
                    controller.seekTo(position.chapterPosition, position.chapterIndex)
                    return@collect
                }
                // 换章时阅读页会把阅读位置落成章首 0 (loadChapterInternal 的跳章落位), 这不是
                // 用户翻页; 朗读此刻也在章首 (段 0 / 尚未起段), 放行就会立刻 startAt 把刚起的
                // 这一章再拆一次, 正是章首那声停顿的来源。
                if (position.chapterPosition == 0 && controller.paragraphIndex.value <= 0) {
                    return@collect
                }
                // 朗读自己翻的页: [advanceReadAloudPage] 把阅读位置写成新页页首 (与用户手动翻页
                // 落在同一位置空间), 这里按"当前朗读段所在页的页首"识别并忽略。
                // 漏掉这一步就会每次翻页都 [startAt] → 停引擎 + 整章重排 + 从页首重读,
                // 听感正是"每读一页停一下"; 对照 app 端 TTS_PROGRESS 只写 durChapterPos,
                // 不触发 curPageChanged 的朗读分支。
                if (lastSyncedPos >= 0 &&
                    position.chapterPosition == ports.pageStartOf(position.chapterIndex, lastSyncedPos)
                ) {
                    return@collect
                }
                if (position.chapterPosition in currentParagraphRange()) return@collect
                // 同章翻页: 软定位即可 —— 重建整场会话 (startAt) 会让翻页后卡一段甚至把
                // 引擎停住; 这里只换队列, 引擎路由与订阅令牌原样保留。
                lastSyncedPos = position.chapterPosition
                pausedPosition = null
                controller.seekTo(position.chapterPosition, position.chapterIndex)
            }
        }
    }

    /** 当前朗读段在章内占据的字符区间。 */
    private fun currentParagraphRange(): IntRange {
        val index = controllerRef?.paragraphIndex?.value ?: return IntRange.EMPTY
        // 几何一次取值: 段起点与裁剪偏移必须同源 (逐字段读会取到跨版本混合值)
        val geometry = planBuilder.snapshot
        val real = geometry.paragraphBase + index.coerceAtLeast(0)
        val start = geometry.paragraphStart(real) ?: return IntRange.EMPTY
        val length = geometry.paragraphs.getOrNull(real)?.length ?: return IntRange.EMPTY
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
        // 几何一次取值: 段起点与裁剪偏移必须同源 (逐字段读会取到跨版本混合值)
        val geometry = planBuilder.snapshot
        val real = geometry.paragraphBase + index
        var pos = geometry.paragraphStart(real) ?: return
        if (index == 0) pos += geometry.firstParagraphSkip
        lastSyncedPos = pos
        ports.advanceReadAloudPage(controller.chapterIndex.value, pos)
        ports.positionPublisher.publishPosition(pos)
    }

    /** 朗读态广播; 暂停/停止时的清高亮由阅读页的 ALOUD_STATE 订阅负责。 */
    private fun onStateChanged(state: ReadAloudState) {
        // 只有 WAITING 需要看门狗; 落在其它状态说明等章已结束, 看门狗无事可做
        if (state != ReadAloudState.WAITING) waitingWatch.cancel()
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

            ReadAloudState.WAITING -> startWaitingWatch()

            ReadAloudState.IDLE -> Unit
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
    }
    // endregion

    private companion object {
        /** WAITING 看门狗的轮询间隔 (就绪检查与超时计时共用)。 */
        const val WAITING_POLL_MS = 3_000L

        /** 装载任务已无声结束时, 最多补发几次装载。 */
        const val WAITING_MAX_RETRIES = 3

        /** 在途装载久不完成时, 隔多久强制换掉重发一次。 */
        const val WAITING_FORCE_INTERVAL_MS = 180_000L

        /** 最多强制重发几次 (再久就是源站不可用, 留给超时兜底)。 */
        const val WAITING_MAX_FORCES = 2

        /** WAITING 最长等待: 超过即上报错误, 不让朗读无声地无限等下去。 */
        const val WAITING_TIMEOUT_MS = 10 * 60 * 1000L
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

    /** 登记一次新朗读会话的起点; 无活动阅读页时 no-op。 */
    fun resetReadAloudPageCursor(chapterPosition: Int) = Unit

    /** 朗读位置推进联动: 返回是否发生了翻页; 无活动阅读页时 no-op 返回 false。 */
    fun advanceReadAloudPage(chapterIndex: Int, readingPos: Int): Boolean = false

    /**
     * [chapterPosition] 所在页的页首字符位置 (章内偏移); 无页概念或该章未排版时返回 null。
     *
     * 朗读自己驱动的翻页把阅读位置写成新页页首, 与用户手动翻页在位置空间上无差别, 宿主靠本
     * 方法把两者区分开 (见 [ReadAloudHostShared] 的翻页监听)。
     */
    fun pageStartOf(chapterIndex: Int, chapterPosition: Int): Int? = null

    /**
     * [chapterIndex] 的正文是否已排版就绪 (当前章命中该章, 且不是占位消息页)。
     *
     * 供等章看门狗判定"可以续读了": 不依赖 `curTextChapter` 的那一次更新事件是否送达。
     */
    fun isChapterTextReady(chapterIndex: Int): Boolean = false

    /**
     * [chapterIndex] 是否有在途装载任务。
     *
     * false 且正文未就绪 = 装载已无声结束 (目录缺失/越界等分支直接 return), 再等也不会有人来通知,
     * 需要调用方补发一次装载。
     */
    fun isChapterLoading(chapterIndex: Int): Boolean = false

    /**
     * 预取 [chapterIndex] 的正文 (不改变当前阅读位置、不动页面流)。
     *
     * 朗读进入一章时就把下一章备好, 章末换章才不会退化成"等一次网络往返"。
     *
     * @param force true 时连在途任务一起替换 —— 卡在联网正文上的装载没有谁会去取消它,
     *   等章看门狗靠它把卡死的那一次重发掉
     */
    fun prefetchChapter(chapterIndex: Int, force: Boolean = false) = Unit
}
