package io.legado.app.model.audio

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayCommander
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * 音频播放会话的平台接入面 (四端各一份)。
 *
 * 只放非平台不可的三类东西: 引擎接入 ([controller] / [startPlayback])、宿主生命周期
 * ([onSessionStart] / [onSessionEnd])、平台外观 ([onSessionSync] / [onCoverUrl] / [toast])。
 * 会话状态机本身在 [AudioPlaySession], 四端共用一份。
 */
interface AudioPlaySessionHost {

    /** 底层播放器 (app ExoPlayer / 桌面 mpv / iOS AVPlayer / 鸿蒙 napi AVPlayer)。 */
    val controller: AudioPlayController

    /** 会话作用域 (app 端 Service lifecycleScope, 其余端宿主自建)。 */
    val scope: CoroutineScope

    /** AnalyzeRule 工厂 (封面/歌词规则求值要平台 JS 扩展面)。 */
    val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory

    /** 会话未启动时暂存的定时分钟数 (Android 放 companion, 要跨 Service 寿命)。 */
    var pendingTimerMinute: Int

    /**
     * 解析直链并交给引擎起播; 调用前 [AudioPlaySession] 已置 LOADING 与 playWhenReady。
     *
     * 直链必须经 AnalyzeUrl (拆 `url,{options}` 后缀并取 UA/Referer/Cookie), 之后各端引擎
     * 接法不同: app setMediaItem, 桌面/iOS setSource, 鸿蒙优先流播。
     */
    suspend fun startPlayback(url: String, positionMs: Int)

    /** 起播前放行 (app 申请音频焦点; 其余端恒 true)。 */
    fun onBeforeStart(): Boolean = true

    /** 引擎错误先给平台过一遍, 返回 true 表示已消化 (鸿蒙流播失败回退预下载)。 */
    fun onPlayerErrorIntercept(error: Throwable): Boolean = false

    /** 报错文案 (app 补 PlaybackException 的 errorCode)。 */
    fun playerErrorMessage(error: Throwable): String = "音频播放出错\n${error.message}"

    /** 会话开始 (app 建 MediaSession/注册耳机拔出/载通知封面; 桌面激活 SMTC)。 */
    fun onSessionStart() {}

    /** 会话终结: 引擎与平台会话的释放。引擎位置已由调用方读完, 可以放心释放。 */
    fun onSessionEnd() {}

    /** 引擎就绪 (鸿蒙据此撤流播看门狗)。 */
    fun onPlaybackReady() {}

    /** 刷一次平台外观 (app 通知 + MediaSession 播放态; 其余端系统播控卡片)。 */
    fun onSessionSync(positionMs: Long? = null) {}

    /** 当前章节封面 URL 就绪 (app 载通知位图; 其余端刷卡片封面)。 */
    fun onCoverUrl(url: String?) {}

    /** 切章时丢弃封面去重缓存, 强制重载。 */
    fun onResetCoverCache() {}

    fun toast(message: String)
}

/**
 * 音频播放会话状态机 (四端共用一份)。
 *
 * 原先 app 端 `AudioPlayService` / 桌面 `DesktopAudioPlayProvider` /
 * `IosAudioPlayCommander` / `OhosAudioPlayCommander` 各写一份, 字段 (running/pause/
 * playSpeed/url/position/sleepTimer/hasRefreshedOnPlayError) 与命令实现
 * (play/pause/resume/stopPlay/adjustSpeed/adjustProgress/triggerPlay/引擎状态回调)
 * 四份平行, 平台差异只在引擎接法与外观刷新 —— 那两件事收进 [AudioPlaySessionHost]。
 *
 * # 会话寿命
 * 非 Android 端由首个命令惰性开启 ([ensureRunning]), stop 命令终结 ([endSession])。
 * Android 的寿命归 OS: Service.onCreate 调 [ensureRunning], onDestroy 调 [endSession],
 * stop 命令走 `AudioPlayProvidersImpl` → IntentAction.stop → stopSelf, 不经本类。
 *
 * # 章节资源
 * 加载播放直链/封面/歌词与进度上报在 [manager], 本类只负责"拿到直链之后怎么放"。
 */
class AudioPlaySession(private val host: AudioPlaySessionHost) :
    AudioPlayCommander, AudioPlayControllerListener {

    /** 章节资源加载 + 进度上报 (与本会话同寿命)。 */
    private val manager =
        AudioPlayManager(host.controller, host.scope, host.analyzeRuleFactory, this)

    /** 会话是否存活 (对应 app 端 `AudioPlayService.isRun`)。 */
    @Volatile
    var isRunning = false
        private set

    /** 是否暂停 (对应 app 端 `AudioPlayService.pause`)。 */
    @Volatile
    var isPaused = true
        private set

    /**
     * 播放倍速 (对应 app 端 `AudioPlayService.playSpeed`)。
     *
     * 真源在 [AudioPlayShared.playSpeed]: 它跨会话存活 (Android 的会话随 Service 重建),
     * 且歌词发布器要按倍速把"到下一行还有多少歌曲时间"换算成墙上时间。
     */
    val playSpeed: Float get() = AudioPlayShared.playSpeed

    /** 当前交给引擎的直链 (对应 app 端 `AudioPlayService.url`)。 */
    @Volatile
    private var url = ""

    /** 起播/暂停位置, 毫秒 (对应 app 端 `AudioPlayService.position`)。 */
    @Volatile
    private var position = 0

    /** 当前起播任务 (新请求启动时取消前一个任务, 防止并发错乱)。 */
    private var playJob: Job? = null

    private var sleepTimer: SleepTimer? = null

    /** 剩余定时分钟 (通知标题要显示)。 */
    val timerMinute: Int get() = sleepTimer?.minutes ?: 0

    /** 播放出错后先静默重试一次再报错; 就绪即复位, 即"每播成功一次给一次机会"。 */
    private var hasRefreshedOnPlayError = false

    init {
        host.controller.listener = this
    }

    // region AudioPlayCommander (命令面, 非 Android 端本类即注册的 commander)

    override val isServiceRunning: Boolean get() = isRunning

    override var pendingTimerMinute: Int
        get() = host.pendingTimerMinute
        set(value) {
            host.pendingTimerMinute = value
        }

    override val positionMs: Int get() = manager.positionMs

    override fun play() = start(playNew = false)

    override fun playNew() = start(playNew = true)

    override fun stop() = endSession()

    override fun stopPlay() {
        if (!isRunning) return
        playJob?.cancel()
        playJob = null
        host.controller.stop()
        manager.cancelProgressJob()
        manager.cancelChapterLoad()
        manager.clearPendingSeek()
        AudioPlayShared.status = Status.STOP
        AudioPlayShared.book?.save()
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        // 会话还活着 (切章也走这里), 只更新播放态不撤会话。
        // 刻意不动 isPaused 与阅读计时: ReadTimeRecorder 按 refCount 配对, 而切章后的起播不经
        // resume, 这里 end 掉就再无对应的 start; isPaused 置真则通知会在拉链接那几秒显示"已暂停"
        host.onSessionSync()
    }

    override fun pause() {
        if (!isRunning) return
        // 引擎调用抛了就吞掉: 状态已落, 会话照旧 (对照 app 原版 pause 的 catch + printOnDebug)
        runCatching {
            isPaused = true
            ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
            manager.cancelProgressJob()
            position = manager.positionMs
            AudioPlayShared.durChapterPos = position
            // 缓冲中按的暂停: 光调 pause() 不落 playWhenReady, 引擎缓冲完会自己起播
            host.controller.playWhenReady = false
            if (host.controller.isPlaying) host.controller.pause()
            AudioPlayShared.status = Status.PAUSE
            postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
            host.onSessionSync()
        }
    }

    override fun resume() {
        if (!isRunning) return
        // 起播失败即终结会话 (对照 app 原版 resume 的 catch → stopSelf)
        runCatching {
            // 直链还没解析过 (会话刚起或播完停在末章): 先补齐资源再起播
            if (url.isEmpty()) {
                AudioPlayShared.loadOrUpPlayUrl()
                return
            }
            isPaused = false
            ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
            host.controller.playWhenReady = true
            if (!host.controller.isPlaying) host.controller.play()
            manager.upPlayProgress()
            AudioPlayShared.status = Status.PLAY
            postEvent(EventBus.AUDIO_STATE, Status.PLAY)
            host.onSessionSync()
        }.onFailure { endSession() }
    }

    override fun adjustSpeed(adjust: Float) {
        if (!isRunning) return
        runCatching {
            AudioPlayShared.playSpeed = adjust
            host.controller.setPlaybackSpeed(adjust)
            postEvent(EventBus.AUDIO_SPEED, adjust)
            host.onSessionSync()
        }
    }

    override fun adjustProgress(position: Int) {
        if (!isRunning) return
        this.position = position
        host.controller.seekTo(position.toLong())
        postEvent(EventBus.AUDIO_PROGRESS, position)
        // 引擎 seek 是异步的, 告知位置真源目标值: 确认前歌词与进度都按新位置算
        manager.onSeekTo(position)
        host.onSessionSync(positionMs = position.toLong())
    }

    override fun setTimer(minute: Int) {
        if (!isRunning) return
        sleepTimer?.set(minute)
    }

    override fun addTimer() {
        ensureRunning()
        sleepTimer?.add()
    }

    override fun loadPlayUrl() {
        ensureRunning()
        manager.loadPlayUrl()
    }

    // endregion

    // region 会话寿命

    /**
     * 会话开始 (对应 app 端 `AudioPlayService.onCreate`)。幂等。
     *
     * Android 由 Service.onCreate 显式调用, 其余端由首个命令惰性触发。
     */
    fun ensureRunning() {
        if (isRunning) return
        isRunning = true
        isPaused = true
        hasRefreshedOnPlayError = false
        // 对外歌词发布 (车载/状态栏) 与会话同寿命。绑在这里而不是 manager 构造里: 非 Android 端
        // manager 只造一次, 停播 detach 后就再也不会重绑
        LyricPublisher.attach(host.scope) { manager.positionMs }
        sleepTimer = SleepTimer(
            scope = host.scope,
            postMinute = { postEvent(EventBus.AUDIO_DS, it) },
            isPaused = { isPaused },
            onTimeout = { AudioPlayShared.stop() },
            onTick = { host.onSessionSync() },
        )
        ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
        host.onSessionStart()
        val pending = host.pendingTimerMinute
        if (pending > 0) {
            host.pendingTimerMinute = 0
            sleepTimer?.set(pending)
        } else {
            postEvent(EventBus.AUDIO_DS, 0)
            host.onSessionSync()
        }
    }

    /**
     * 会话终结 (对应 app 端 `AudioPlayService.onDestroy`)。幂等。
     *
     * [isRunning] 先落: 会话寿命的消费方 (桌面托盘显隐 / thumbbar / 播控卡片归属) 都在
     * AUDIO_STATE 事件里回读它, 迟置会有窗口读成"还活着"。引擎位置也必须在
     * [AudioPlaySessionHost.onSessionEnd] 释放引擎之前读完, 否则落库进度不准。
     */
    fun endSession() {
        if (!isRunning) return
        isRunning = false
        playJob?.cancel()
        playJob = null
        sleepTimer?.cancel()
        sleepTimer = null
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.durChapterPos = manager.positionMs
        AudioPlayShared.saveRead()
        manager.cancelProgressJob()
        manager.cancelPreload()
        manager.cancelChapterLoad()
        manager.clearPendingSeek()
        LyricPublisher.detach()
        url = ""
        isPaused = true
        host.onSessionEnd()
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
    }

    // endregion

    // region 起播

    private fun start(playNew: Boolean) {
        ensureRunning()
        playJob?.cancel()
        playJob = host.scope.launch { runTriggerPlay(playNew) }
    }

    /**
     * [triggerPlay] 的异常收尾。
     *
     * 直链解析要走书源 `<js>` 求值与网络, 必抛; 对照 app 原版 `play().onError → stopSelf()`,
     * 起播链路失败即终结会话 (通知/播控卡片一并撤掉), 下次 play 重建。
     */
    private suspend fun runTriggerPlay(playNew: Boolean) {
        runCatching { triggerPlay(playNew) }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("播放出错\n${it.message}", it)
            host.toast("$url ${it.message}")
            endSession()
        }
    }

    /**
     * 起播当前 [AudioPlayShared.durPlayUrl]。
     *
     * 同一直链且引擎没闲着就跳过, 免得重复命令打断正在播的音频 ([playNew] 强制重播)。
     */
    private suspend fun triggerPlay(playNew: Boolean) {
        val playUrl = AudioPlayShared.durPlayUrl
        if (url == playUrl && !playNew &&
            host.controller.playbackState != AudioPlayController.STATE_IDLE
        ) return
        host.controller.stop()
        manager.cancelProgressJob()
        isPaused = false
        position = if (playNew) 0 else AudioPlayShared.book?.durChapterPos ?: 0
        url = playUrl
        host.onCoverUrl(AudioPlayShared.durCoverUrl)
        // 拉链接+缓冲窗口置 LOADING 而不是 STOP, 让 UI 能区分"没在播"和"正在启动"
        AudioPlayShared.status = Status.LOADING
        postEvent(EventBus.AUDIO_STATE, Status.LOADING)
        postEvent(EventBus.AUDIO_LOADING, true)
        host.controller.playWhenReady = true
        if (!host.onBeforeStart()) {
            // 焦点拒绝发生在完整 prepare 前；保留 URL/位置，resume 必须重新走 triggerPlay。
            host.controller.playWhenReady = false
            host.controller.stop()
            url = ""
            isPaused = true
            AudioPlayShared.status = Status.PAUSE
            postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
            postEvent(EventBus.AUDIO_LOADING, false)
            host.onSessionSync(positionMs = position.toLong())
            return
        }
        // 起播位置也是一次 seek: 非 Android 端引擎要等 prepare 就绪才应用它, 先告知位置真源目标值,
        // 并传给外观同步, 避免首次播控同步读到旧进度
        manager.onSeekTo(position)
        host.onSessionSync(positionMs = position.toLong())
        host.startPlayback(playUrl, position)
    }

    // endregion

    // region AudioPlayControllerListener (引擎状态回调)

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            AudioPlayController.STATE_IDLE,
            AudioPlayController.STATE_BUFFERING -> Unit

            AudioPlayController.STATE_READY -> {
                hasRefreshedOnPlayError = false
                host.onPlaybackReady()
                postEvent(EventBus.AUDIO_LOADING, false)
                // 倍速重设: AVPlayer 的 play() 会把 rate 打回 1, mpv 换引擎实例后也回 1x
                host.controller.setPlaybackSpeed(playSpeed)
                postEvent(EventBus.AUDIO_SPEED, playSpeed)
                AudioPlayShared.status =
                    if (host.controller.playWhenReady) Status.PLAY else Status.PAUSE
                postEvent(EventBus.AUDIO_STATE, AudioPlayShared.status)
                val duration = host.controller.duration
                // 流式资源就绪时 duration 可能还未知 (0 / Media3 的 TIME_UNSET), 写下去会把
                // chapter.end 冲坏; 播放中变已知后由 upPlayProgress 心跳补发
                if (duration > 0) {
                    postEvent(EventBus.AUDIO_SIZE, duration.toInt())
                    AudioPlayShared.saveDurChapter(duration)
                }
                manager.upPlayProgress()
                host.onSessionSync()
            }

            AudioPlayController.STATE_ENDED -> {
                manager.cancelProgressJob()
                manager.clearPendingSeek()
                AudioPlayShared.playPositionChanged(host.controller.duration.toInt())
                if (!AudioPlayShared.next()) {
                    isPaused = true
                    ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
                    stopPlay()
                }
            }
        }
    }

    override fun onPlayerError(error: Throwable) {
        if (host.onPlayerErrorIntercept(error)) return
        if (!hasRefreshedOnPlayError) {
            // 首错静默: 直链多半过期了, 清掉 resourceUrl 重新解析一次
            hasRefreshedOnPlayError = true
            manager.refreshChapter()
            return
        }
        manager.cancelProgressJob()
        isPaused = true
        // 对照 app 原版 onPlayerError: 只落 STOP + 提示, 不终结会话 —— 通知与播控卡片留着,
        // 用户可以再按播放重试
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        host.onSessionSync()
        val message = host.playerErrorMessage(error)
        AppLog.put(message, error)
        host.toast(message)
    }

    // endregion

    // region AudioPlayManager 回调 (章节资源加载的会话侧动作)

    /** 章节直链就绪, 起播; [playNew] 为真表示上一轮已播到末章末尾, 从头放。 */
    internal fun onTriggerPlay(playNew: Boolean) = start(playNew)

    internal fun onLoadCover(url: String?) = host.onCoverUrl(url)

    internal fun onResetCoverCache() = host.onResetCoverCache()

    internal fun onToast(message: String) = host.toast(message)

    // endregion
}

/**
 * 走系统播控卡片的宿主基类 (iOS / 鸿蒙 / 桌面共用)。
 *
 * 这三端都只有一张卡片, "刷平台外观"就是刷卡片, 三份实现原本逐字相同。Android 不用本类:
 * 它有独立的 MediaSession + 前台通知, 外观刷新语义不同。
 *
 * [session] 惰性创建: 宿主的引擎/作用域字段要先初始化完, 会话构造时才读得到。
 */
abstract class NowPlayingSessionHost : AudioPlaySessionHost {

    /** 注册进 [io.legado.app.model.AudioPlayCommanders] 的命令面。 */
    val session by lazy { AudioPlaySession(this) }

    override fun onSessionSync(positionMs: Long?) =
        SystemMediaControl.syncAudio(positionMs, session.playSpeed)

    /** 书源 musicCover 规则算出的当前章节封面, 刷进播控卡片。 */
    override fun onCoverUrl(url: String?) =
        SystemMediaControl.syncAudio(playbackRate = session.playSpeed, coverUrl = url)

    override fun toast(message: String) {
        runCatching { Toasters.get().toast(message) }
    }
}
