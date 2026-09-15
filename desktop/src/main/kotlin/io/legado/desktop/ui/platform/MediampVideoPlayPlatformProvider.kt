package io.legado.desktop.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.PlaybackSnapshot
import io.legado.app.ui.book.video.VideoGestureController
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayUiEvent
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.desktop.audio.DesktopScreenBrightness
import io.legado.desktop.audio.DesktopSystemVolume
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.media.DesktopMediaRuntime
import io.legado.desktop.media.bufferedEndPositionMsOrZero
import io.legado.desktop.ui.DesktopWindowChrome
import io.legado.desktop.ui.DesktopWindowHandle
import io.legado.desktop.ui.applyWindowCornerPreference
import io.legado.desktop.ui.shouldRoundWindowCorner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePlayWhenReady

/**
 * desktop 端 [VideoPlayPlatformProvider] 实现: open-ani/mediamp (mediamp-mpv 后端)。
 *
 * 平台端仅提供 [RenderSurface] (纯 Compose [MediampPlayerSurface] 渲染层)、播放态快照
 * (由控制器 [MediampVideoPlayerController.playback] 单一来源供给共享层) 以及亮度/音量
 * 手势接入点。全部播控/状态/错误遮罩统一由共享层编排。
 */
class MediampVideoPlayPlatformProvider(
    private val windowHandle: DesktopWindowHandle = DesktopWindowHandle(),
) : VideoPlayPlatformProvider {

    override fun isPlaceholderController(controller: VideoPlayerController?): Boolean =
        controller === EmptyDesktopVideoPlayerController

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController {
        // 媒体播放组件 (mpv + FFmpeg native, 实测 21MB) 不再随包发布: 未就绪时不建真控制器,
        // ensureReady() 已把状态推到全局弹框 (确认 → 进度 → 失败重试), 这里只负责降级为占位。
        // 不拼到下面的 catch: 那里报的是"引擎初始化失败", 把"还没下载"当成故障报会误导用户。
        if (!DesktopMediaRuntime.ensureReady()) {
            AppLog.put("视频播放: 媒体播放组件未就绪, 已转按需下载", tag = "媒体组件")
            screenModel.dispatch(VideoPlayUiEvent.ShowError(jvmGetString("media_runtime_not_installed")))
            return EmptyDesktopVideoPlayerController
        }
        return try {
            MediampVideoPlayerController(screenModel, onPlaybackEnded)
        } catch (e: Throwable) {
            AppLog.put("mediamp 初始化失败: ${e.message}", e)
            screenModel.dispatch(VideoPlayUiEvent.ShowError("mediamp 初始化失败: ${e.message}"))
            EmptyDesktopVideoPlayerController
        }
    }

    // 系统级全屏 (Windows 真全屏独占覆盖任务栏)。
    // 一律走 PlatformServices.window 这个单点入口: 它负责"成功才翻转 DesktopWindowChrome.fullscreen",
    // 与 ESC / 标题栏 / 原生控制条三条入口共用同一状态源; 页面渲染读 rememberSystemFullScreen(),
    // 因此非 Windows 平台或 Win32 调用失败时窗口没全屏, 页面也不会按全屏渲染。
    override fun applySystemFullScreen(enabled: Boolean) {
        PlatformServiceProviders.get().window.setFullscreen(enabled)
    }

    // 窗口内全屏 (右上角三点菜单项)
    override fun applyFullscreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
    }

    /** 桌面系统级全屏 = 窗口真全屏, 直接以窗口实际态回显 (不用页面自存副本) */
    @Composable
    override fun rememberSystemFullScreen(): Boolean = DesktopWindowChrome.fullscreen

    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        when (controller) {
            is MediampVideoPlayerController -> MediampSurfaceRender(
                controller,
                screenModel,
                modifier
            )
            else -> Unit
        }
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? {
        val mediampController = controller as? MediampVideoPlayerController ?: return null
        return remember(mediampController) {
            VideoGestureController(
                isPlaying = { mediampController.playback.value.isPlaying },
                positionMs = { mediampController.positionMs },
                durationMs = { mediampController.durationMs },
                speed = { mediampController.playback.value.speed },
                setSpeed = { mediampController.setSpeed(it) },
                onPlayPause = { mediampController.playPause() },
                seekTo = { mediampController.seekTo(it) },
                // 亮度/音量读写一律不得在 UI 线程同步等子进程 (原实现每帧 .get(2~3s) → 左半
                // 竖滑整窗冻结、macOS/Linux 拖动全程掉帧)。读走缓存, 写走后台串行队列。
                readBrightness = { mediampController.peekBrightness() },
                writeBrightness = { mediampController.postBrightness(it) },
                readVolume = { mediampController.peekVolume() },
                writeVolume = { mediampController.postVolume(it) },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
            )
        }
    }
}

/**
 * mediamp 播放控制器: 包装 [MediampPlayer], 桥接 [VideoPlayerController] 接口。
 *
 * - 播放态: 常驻收集 [MediampPlayer.state] + [PlaybackSpeed]/[AudioLevelController] 折叠成
 *   [PlaybackSnapshot] 单一快照流供共享层回显 (共享层不再缓存播放态, 平台也不再"记得回灌")
 * - playPause → togglePlayWhenReady; seekTo/skip 直调; setSpeed → features[PlaybackSpeed]
 * - 装载守卫 [startedUrl] 在错误/停止/release 时清除 (对齐 app/iOS/鸿蒙, 修复"重新加载"死锁)
 * - 播完 (MediaStatus.Ended) → onPlaybackEnded; 播放错误 → 先自动重试一次再报错
 * - 亮度/音量手势: 非阻塞缓存 + 后台串行落盘 (见 [gestureIo])
 */
class MediampVideoPlayerController(
    private val screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 独立于 [scope] 的关闭协程 (release 后 scope 已取消, close 需要自己的调度器) */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * mediamp player (SPI 工厂经 classpath 上的 mediamp-mpv 创建; 构造抛异常由调用方兜底)。
     *
     * parentCoroutineContext 必须无 Job: AbstractMediampPlayer 在父 Job 完成时自动
     * close() (mainScope Job invokeOnCompletion), 若挂到 [scope] 下, release() 的
     * scope.cancel() 会瞬间关闭 MPVHandle, 而渲染面的 onDispose
     * (setRenderUpdateListener(null) → handle.ptr) 尚未执行 → IllegalStateException
     * 崩溃。close 完全由本类按渲染槽生命周期门控 (见 [release]/[onSurfaceExited])。
     */
    val player: MediampPlayer = MediampPlayer(Unit, EmptyCoroutineContext)

    /**
     * 已起播的 url: 渲染槽重建时 LaunchedEffect(url) 会重跑, 守卫避免重复 setMediaData。
     *
     * 只在"同一 url 确实已经在播"时才有拦截意义: 装载失败 / 报错 / 停止 / 释放都必须清掉它,
     * 否则同一章重试永远走不通 (app/iOS/鸿蒙 都清, 上一版桌面漏清 → 错误遮罩的"重新加载"
     * 是死按钮, 只能退出页面重进)。
     */
    @Volatile
    private var startedUrl: String? = null

    /** release 已执行标记 (close 只调度一次) */
    @Volatile
    private var released = false

    /** 渲染面是否在组合中: close 必须等它离开组合 (其 onDispose 仍要访问 MPVHandle.ptr) */
    @Volatile
    private var surfaceInComposition = false

    private val _playback = MutableStateFlow(PlaybackSnapshot())

    /** 播放态快照 (共享层回显唯一数据源) */
    override val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    // ---- 手势用非阻塞音量/亮度通道 ----

    /** 手势 IO 串行队列: 亮度/音量子进程调用全部离开 UI 线程在此执行 */
    private val gestureIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-video-gesture-io").apply { isDaemon = true }
    }

    /** 最近一次读到的系统亮度 (0..1); null = 尚未读到 */
    @Volatile
    private var cachedBrightness: Float? = null

    /** 亮度是否真实可读 (台式机无 WMI Brightness 时永远读不到): 不可读就不写,
     *  否则会拿猜出来的基线把屏幕推到未知位置 */
    @Volatile
    private var brightnessReadable = false

    /** 已给过一次“亮度不可用”反馈 */
    @Volatile
    private var brightnessHinted = false

    /** 最近一次读到的系统音量 (0..1); null = 从未读到 */
    @Volatile
    private var cachedVolume: Float? = null

    /** 已投递但尚未落盘的亮度目标值 (0..100, -1 = 无待处理), latest-wins 不排队堆积 */
    private val pendingBrightness = AtomicInteger(-1)
    private val brightnessScheduled = AtomicBoolean(false)

    /** 已投递但尚未落盘的音量目标值 (0..1 定点到 0..1000, -1 = 无待处理) */
    private val pendingVolume = AtomicInteger(-1)
    private val volumeScheduled = AtomicBoolean(false)

    /** 应用内音量兜底 (系统音量不可用时): mediamp 的 AudioLevelController 特性 */
    private val appVolume: AudioLevelController?
        get() = runCatching { player.features[AudioLevelController.Key] }.getOrNull()

    init {
        scope.launch {
            player.state.collect { state ->
                publishPlayback(state.mediaStatus, state.playWhenReady, state.isBuffering)
                when (state.mediaStatus) {
                    MediaStatus.Ended -> {
                        startedUrl = null
                        onPlaybackEnded()
                    }

                    is MediaStatus.Error -> {
                        // 先清守卫: refreshChapter 会重新 emit 同一条 url, 不清的话重试必被吃掉
                        startedUrl = null
                        val retried =
                            screenModel.shared.retryOnPlayError(positionMs)
                        if (!retried) {
                            screenModel.dispatch(VideoPlayUiEvent.ShowError("播放失败"))
                        }
                    }

                    MediaStatus.Ready -> {
                        screenModel.shared.resetRetryOnPlayError()
                    }

                    else -> Unit
                }
            }
        }
        // 亮度/音量基线在控制器建立时先后台读一轮: 等用户真的滑动时大概率已有真值,
        // 既不需要在 UI 线程现场等子进程, 也不拿 0.5f 兑一个假基线
        scheduleBrightness { readBrightnessIntoCache() }
        scheduleVolume { readVolumeIntoCache() }
        // 倍速是独立特性流, 单独订阅回显 (含手势/键盘改速)
        scope.launch {
            runCatching {
                player.features[PlaybackSpeed.Key]?.valueFlow?.collect { speed ->
                    _playback.update { it.copy(speed = speed) }
                }
            }
        }
    }

    private fun publishPlayback(
        status: MediaStatus,
        playWhenReady: Boolean,
        buffering: Boolean,
    ) {
        val idle = status != MediaStatus.Ready && status !is MediaStatus.Ended
        val ended = status == MediaStatus.Ended
        // "媒体在装载但还没出帧"(Opening) 也算等数据: 共享层不再拿 playbackState 反推,
        // 上一版桌面缓冲判定要求 mediaStatus==Ready, 于是起播前那段既无转圈也无进度 = 纯黑
        val isBuffering = buffering || (status == MediaStatus.Opening) ||
            (idle && playWhenReady && !ended)
        _playback.update {
            it.copy(
                playWhenReady = playWhenReady,
                isPlaying = status == MediaStatus.Ready && playWhenReady && !buffering,
                isBuffering = isBuffering,
                ended = ended,
                idle = idle,
            )
        }
    }

    /** 装载并起播 (切章/切分辨率/重试统一入口) */
    fun startPlayback(url: String, headers: Map<String, String>, startMs: Long) {
        if (startedUrl == url) return
        val hadMedia = startedUrl != null
        // 换源前记下用户意图: 暂停态切清晰度不该被强制恢复播放 (对照原版
        // `if (oldPlayer.isPlaying) newPlayer.play()`)
        val keepPlaying = !hadMedia || player.state.value.playWhenReady
        startedUrl = url
        scope.launch {
            runCatching {
                player.setMediaData(UriMediaData(url, headers))
                if (keepPlaying) player.play()
                if (startMs > 0) player.seekTo(startMs)
            }.onFailure { e ->
                // 装载抛异常同样要清守卫, 否则该章在页面剩余生命周期内不可重试
                startedUrl = null
                AppLog.put("mediamp 加载失败: ${e.message}", e)
                val retried = screenModel.shared.retryOnPlayError(startMs)
                if (!retried) {
                    screenModel.dispatch(VideoPlayUiEvent.ShowError("加载失败: ${e.message}"))
                }
            }
        }
    }

    override val positionMs: Long get() = player.currentPositionMillis.value
    override val durationMs: Long get() = player.mediaProperties.value?.durationMillis ?: 0L

    /**
     * 已缓冲到的时间点 (进度条缓冲层用)。
     *
     * 读 mpv `demuxer-cache-time` = 解复用缓存里最后一帧的时间戳, 即"缓冲到哪儿了"。
     * 不用 mediamp 的 [org.openani.mediamp.features.Buffering.bufferedPercentage]:
     * 它取自 `cache-buffering-state` (初始缓冲进度, 缓冲完成后恒 100), 折算成时长就是一条
     * 永远铺满的假缓冲条。
     *
     * 经公开的 [MediampPlayer.impl] 取 MPVHandle (mediamp 的 `handle` 字段是 internal)。
     * 取不到属性时 mediamp 侧读回 0 (不抛), 本层再按 released 早退一次, 避免关闭后空转查询。
     */
    override val bufferedMs: Long
        get() {
            if (released) return 0L
            val mpv = player.impl as? MPVHandle ?: return 0L
            return mpv.bufferedEndPositionMsOrZero()
        }

    override fun playPause() = player.togglePlayWhenReady()

    /** 无条件暂停 (不 toggle): 对照原版点标题进详情前的 player?.pause() */
    override fun pause() = player.pause()

    /**
     * 停止并卸载当前媒体 (切章 / 刷新时由渲染层调用)。
     *
     * 共享层把 `videoUrl` 置 null 只表示"没有新源"、不是命令; 不显式停的话上一章画面与
     * 声音会一直播到新章解析完。同时清 [startedUrl], 让同链接重试可用。
     */
    override fun stop() {
        startedUrl = null
        if (released) return
        scope.launch {
            runCatching { player.stopPlayback() }
                .onFailure { AppLog.put("mediamp 停止播放失败: ${it.message}", it) }
        }
    }

    /**
     * 按当前地址与请求头重装一次 (外部直投错误遮罩的「重新加载」, 见
     * [VideoPlayerController.reload])。
     *
     * 不能靠重发 StateFlow: 渲染层是 `LaunchedEffect(url)`, 同址不重跑, StateFlow 对相等值
     * 也不发射; 而 [startPlayback] 开头的 `startedUrl == url` 守卫会把同址重载直接吃成空操作
     * (就是之前那颗死按钮), 所以必须先把守卫清掉。
     *
     * 停与装排在**同一条**协程里顺序发出: 复用 [stop] 会另起一条 launch, 其 `stopPlayback()`
     * 挂起期间本方法后续的 `setMediaData` 能插到它前面, 同一条地址就变成两次并发装载。
     */
    override fun reload() {
        val source = screenModel.shared.videoUrl.value
        if (source == null) {
            // 没有地址可重装: 退化成卸载 (与切章同语义), 不装空气
            stop()
            return
        }
        val url = source.url
        val headers = source.headerMap.toMap()
        // 守卫先清 (startPlayback 自会重挂); released 后不得再碰播放器
        startedUrl = null
        if (released) return
        scope.launch {
            runCatching { player.stopPlayback() }
                .onFailure { AppLog.putDebug("mediamp 重载前停止播放失败: ${it.message}") }
            startPlayback(url, headers, screenModel.shared.startPositionMs.value)
        }
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.skip(deltaMs)
    override fun setSpeed(speed: Float) {
        runCatching { player.features[PlaybackSpeed.Key]?.set(speed) }
            .onFailure { AppLog.putDebug("mediamp 倍速设置失败: ${it.message}") }
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    // ---- 亮度/音量: 读缓存 + 写后台串行 (latest-wins), 绝不在 UI 线程等子进程 ----

    /** 立即返回已缓存的系统亮度; 无缓存 = 尚未读到 (顺手排一次后台读取), 不伪装成 50%。 */
    fun peekBrightness(): Float? {
        if (!brightnessReadable) scheduleBrightness { readBrightnessIntoCache() }
        return cachedBrightness
    }

    /**
     * 投递亮度目标值 (0..1): 覆盖上一个未落盘的值, 后台单线程写 WMI/PowerShell。
     *
     * 基线不可读时不写, 只给一次反馈: 上一版拿 `?: 0.5f` 当基线, 台式机 (无 WMI)
     * 上滑动一下会把系统亮度硬推到猜出来的位置, 且 HUD 照常显示百分比假装已生效。
     */
    fun postBrightness(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (!brightnessReadable) {
            if (!brightnessHinted) {
                brightnessHinted = true
                screenModel.onGestureText("亮度调节不可用")
            }
            return
        }
        cachedBrightness = clamped
        pendingBrightness.set((clamped * 100).toInt())
        scheduleBrightness { drainBrightness() }
    }

    /** 取走并写入当前待生效亮度 (排空到无待处理项: 写一次期间又攢下的新一笔一并跟上)。 */
    private fun drainBrightness() {
        while (true) {
            val target = pendingBrightness.getAndSet(-1)
            if (target < 0) return
            runCatching { DesktopScreenBrightness.set(target) }
                .onFailure { AppLog.putDebug("亮度设置失败: ${it.message}") }
        }
    }

    /** 把读/写排到手势专用单线程队列; 已在队列里时不重复入队 (拖动每帧只留最后一笔) */
    private fun scheduleBrightness(task: () -> Unit) {
        if (!brightnessScheduled.compareAndSet(false, true)) return
        gestureIo.execute {
            try {
                task()
            } finally {
                brightnessScheduled.set(false)
                // 刚刚解锁的瞬间发现还有没送出去的待处理值 = 那一笔是在 task 排空之后、
                // 解锁之前递进来的, 它的入队尝试已被 compareAndSet 拦下 → 不补排就永远没人取
                if (pendingBrightness.get() >= 0) scheduleBrightness { drainBrightness() }
            }
        }
    }

    private fun readBrightnessIntoCache() {
        runCatching {
            DesktopScreenBrightness.get()?.let {
                cachedBrightness = it / 100f
                brightnessReadable = true
            }
        }.onFailure { AppLog.putDebug("亮度读取失败: ${it.message}") }
    }

    /**
     * 立即返回已缓存的系统音量; 无缓存时回落到应用内音量 (mediamp [AudioLevelController]),
     * 仍拿不到才返回 null —— 不再 `?: 0.5f` 把"读不到"伪装成 50%, 那会让起手第一帧就把
     * 系统音量硬拉到一半。
     */
    fun peekVolume(): Float? {
        cachedVolume?.let { return it }
        appVolume?.volume?.value?.let { return it }
        scheduleVolume { readVolumeIntoCache() }
        return null
    }

    /** 投递音量目标值 (0..1): 后台写系统音量; 系统音量不可用则写应用内音量。 */
    fun postVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        cachedVolume = clamped
        pendingVolume.set((clamped * 1000).toInt())
        scheduleVolume { drainVolume() }
    }

    /** 取走并写入当前待生效音量 (同 [drainBrightness]: 排空到无待处理项)。 */
    private fun drainVolume() {
        while (true) {
            val target = pendingVolume.getAndSet(-1)
            if (target < 0) return
            applyVolume(target / 1000f)
        }
    }

    private fun scheduleVolume(task: () -> Unit) {
        if (!volumeScheduled.compareAndSet(false, true)) return
        gestureIo.execute {
            try {
                task()
            } finally {
                volumeScheduled.set(false)
                // 同 scheduleBrightness: 不补排则最后一笔手势值会被永久丢弃
                if (pendingVolume.get() >= 0) scheduleVolume { drainVolume() }
            }
        }
    }

    /** 系统音量优先 (WASAPI/osascript/wpctl); 失败回落播放器自身音量。 */
    private fun applyVolume(value: Float) {
        val ok = runCatching { DesktopSystemVolume.setVolume(value) }.getOrDefault(false)
        if (!ok) {
            runCatching { appVolume?.setVolume(value) }
                .onFailure { AppLog.putDebug("应用内音量回落失败: ${it.message}") }
        }
    }

    private fun readVolumeIntoCache() {
        runCatching { DesktopSystemVolume.getVolume()?.let { cachedVolume = it } }
            .onFailure { AppLog.putDebug("音量读取失败: ${it.message}") }
    }

    override fun release() {
        scope.cancel()
        gestureIo.shutdownNow()
        if (released) return
        released = true
        startedUrl = null
        closePlayer()
    }

    /** 渲染槽进入组合 */
    fun onSurfaceEntered() {
        surfaceInComposition = true
    }

    /** 渲染槽离开组合: 若已 release, 此时 (且仅此时) 才真正关闭播放器 */
    fun onSurfaceExited() {
        surfaceInComposition = false
        if (released) closePlayer()
    }

    private fun closePlayer() {
        if (surfaceInComposition) return
        closeScope.launch {
            runCatching { player.close() }
                .onFailure { AppLog.put("mediamp 播放器关闭失败", it) }
        }
    }
}

/** 占位控制器 (初始化失败时不崩溃) */
object EmptyDesktopVideoPlayerController : VideoPlayerController {
    override val playback: StateFlow<PlaybackSnapshot> =
        MutableStateFlow(PlaybackSnapshot()).asStateFlow()
    override val positionMs: Long get() = 0L
    override val durationMs: Long get() = 0L
    override val bufferedMs: Long get() = 0L
    override fun playPause() = Unit
    override fun pause() = Unit
    override fun stop() = Unit

    /**
     * 占位控制器无引擎可重装 (mediamp 初始化就失败了), 只能空实现。
     *
     * 它是全仓唯一一颗“按了不会动”的重新加载钮: 那里连播放器都没有, 错误文案也不走
     * 直投那套重试提示 ([io.legado.app.ui.book.video.VideoPlayViewModelShared.reportPlayError]
     * 在建控制器阶段还没进直投态), 需重拉只能退页重进。
     */
    override fun reload() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun release() = Unit
}

/**
 * mediamp 纯视频渲染面: [MediampPlayerSurface] 渲染视频画面。
 * 控制栏、手势、加载转圈、缓冲圈等全部覆盖层由共享层 [VideoPlayerHostContainer] 编排。
 */
@OptIn(ExperimentalMediampApi::class)
@Composable
private fun MediampSurfaceRender(
    controller: MediampVideoPlayerController,
    screenModel: VideoPlayScreenModel,
    modifier: Modifier,
) {
    val videoUrl by screenModel.shared.videoUrl.collectAsState()
    val url = videoUrl?.url
    val headers = videoUrl?.headerMap ?: emptyMap()

    DisposableEffect(controller) {
        controller.onSurfaceEntered()
        onDispose {
            controller.onSurfaceExited()
        }
    }

    // 起播位置必须在 effect 内现取: 上一版在组合期读 curBook.durChapterPos, 拿到的是
    // 上一次重组的快照, 与切章位置重置赛跑 → 下一章从上一章的位置起播
    LaunchedEffect(url) {
        if (url != null) {
            controller.startPlayback(
                url,
                headers,
                screenModel.shared.startPositionMs.value,
            )
        } else {
            // 切章/刷新清空了源: 显式停, 否则上一章画面与声音继续播
            controller.stop()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        MediampPlayerSurface(
            mediampPlayer = controller.player,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
