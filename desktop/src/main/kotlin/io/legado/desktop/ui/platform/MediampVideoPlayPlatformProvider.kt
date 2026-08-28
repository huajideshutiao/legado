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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.VideoGestureController
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayUiEvent
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.desktop.audio.DesktopScreenBrightness
import io.legado.desktop.audio.DesktopSystemVolume
import io.legado.desktop.ui.DesktopFullscreenController
import io.legado.desktop.ui.DesktopWindowChrome
import io.legado.desktop.ui.DesktopWindowHandle
import io.legado.desktop.ui.applyWindowCornerPreference
import io.legado.desktop.ui.shouldRoundWindowCorner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePlayWhenReady
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext

/**
 * desktop 端 [VideoPlayPlatformProvider] 实现: open-ani/mediamp (mediamp-mpv 后端)。
 *
 * 平台端仅提供 [RenderSurface] (纯 Compose [MediampPlayerSurface] 渲染层)
 * 以及亮度/音量手势接入点。全部播控/手势/状态/错误遮罩统一由共享层托管。
 */
class MediampVideoPlayPlatformProvider(
    private val windowHandle: DesktopWindowHandle = DesktopWindowHandle(),
) : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController {
        return try {
            MediampVideoPlayerController(screenModel, onPlaybackEnded).also {
                AppLog.put("视频播放: mediamp-mpv 后端")
            }
        } catch (e: Throwable) {
            AppLog.put("mediamp 初始化失败: ${e.message}", e)
            screenModel.dispatch(VideoPlayUiEvent.ShowError("mediamp 初始化失败: ${e.message}"))
            EmptyDesktopVideoPlayerController
        }
    }

    // 系统级全屏 (Windows 真全屏独占覆盖任务栏)
    override fun applySystemFullScreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        val ok = DesktopFullscreenController.setFullscreen(window, enabled)
        if (ok) DesktopWindowChrome.fullscreen = enabled
    }

    // 窗口内全屏 (右上角三点菜单项)
    override fun applyFullscreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
    }

    override fun setOverlayVisible(visible: Boolean) = Unit

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
                isPlaying = { mediampController.isPlaying },
                positionMs = { mediampController.positionMs },
                durationMs = { mediampController.durationMs },
                speed = {
                    runCatching {
                        mediampController.player.features[PlaybackSpeed.Key]?.value ?: 1f
                    }.getOrDefault(1f)
                },
                setSpeed = { mediampController.setSpeed(it) },
                onPlayPause = { mediampController.playPause() },
                seekTo = { mediampController.seekTo(it) },
                readBrightness = {
                    DesktopScreenBrightness.get()?.let { it / 100f } ?: 0.5f
                },
                writeBrightness = { DesktopScreenBrightness.set((it * 100).toInt()) },
                readVolume = {
                    DesktopSystemVolume.getVolume() ?: 0.5f
                },
                writeVolume = { level ->
                    DesktopSystemVolume.setVolume(level)
                },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
            )
        }
    }

    @OptIn(ExperimentalMediampApi::class)
    @Composable
    override fun isBuffering(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): Boolean? {
        val mediampController = controller as? MediampVideoPlayerController ?: return null
        val playerState by mediampController.player.state.collectAsState()
        val bufferingFeature = mediampController.player.features[Buffering.Key]
        val bufferedPercent by bufferingFeature
            ?.bufferedPercentage
            ?.collectAsState(initial = 100) ?: remember { mutableIntStateOf(100) }
        return playerState.mediaStatus == MediaStatus.Ready &&
            (bufferedPercent < 100 || playerState.isBuffering)
    }
}

/**
 * mediamp 播放控制器: 包装 [MediampPlayer], 桥接 [VideoPlayerController] 接口。
 *
 * - playPause → togglePause; seekTo/skip 直调; setSpeed → features[PlaybackSpeed]
 * - positionMs/durationMs 读 mediamp StateFlow 最新值
 * - 播完 (MediaStatus.Ended) → onPlaybackEnded; 播放错误 (MediaStatus.Error/异常) → onError (UI 占位)
 * - startPlayback: setMediaData(UriMediaData(url, headers)) 后 resume + seek 恢复进度
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

    /** 已起播的 url: 渲染槽重建时 LaunchedEffect(url) 会重跑, 守卫避免重复 setMediaData */
    @Volatile
    private var startedUrl: String? = null

    /** release 已执行标记 (close 只调度一次) */
    @Volatile
    private var released = false

    /** 渲染面是否在组合中: close 必须等它离开组合 (其 onDispose 仍要访问 MPVHandle.ptr) */
    @Volatile
    private var surfaceInComposition = false

    val isBuffering: Boolean
        get() = player.state.value.isBuffering

    val isPlaying: Boolean
        get() = player.state.value.isPlaying

    init {
        scope.launch {
            player.state.collect { state ->
                when (state.mediaStatus) {
                    MediaStatus.Ended -> onPlaybackEnded()
                    is MediaStatus.Error -> {
                        val retried = screenModel.shared.retryOnPlayError()
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
    }

    /** 加载播放 (切章/切分辨率统一入口); setMediaData 完成后自动播放 + 恢复进度 */
    fun startPlayback(url: String, headers: Map<String, String>, startMs: Long) {
        if (startedUrl == url) return
        startedUrl = url
        scope.launch {
            runCatching {
                player.setMediaData(UriMediaData(url, headers))
                player.play()
                if (startMs > 0) player.seekTo(startMs)
            }.onFailure { e ->
                AppLog.put("mediamp 加载失败: ${e.message}", e)
                val retried = screenModel.shared.retryOnPlayError()
                if (!retried) {
                    screenModel.dispatch(VideoPlayUiEvent.ShowError("加载失败: ${e.message}"))
                }
            }
        }
    }

    override val positionMs: Long get() = player.currentPositionMillis.value
    override val durationMs: Long get() = player.mediaProperties.value?.durationMillis ?: 0L
    override val bufferedMs: Long get() = durationMs

    override fun playPause() = player.togglePlayWhenReady()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.skip(deltaMs)
    override fun setSpeed(speed: Float) {
        runCatching { player.features[PlaybackSpeed.Key]?.set(speed) }
            .onFailure { AppLog.putDebug("mediamp 倍速设置失败: ${it.message}") }
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        scope.cancel()
        if (released) return
        released = true
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
    override val positionMs: Long get() = 0L
    override val durationMs: Long get() = 0L
    override val bufferedMs: Long get() = 0L
    override fun playPause() = Unit
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
    val startMs = screenModel.shared.curBook?.durChapterPos?.toLong() ?: 0L

    DisposableEffect(controller) {
        controller.onSurfaceEntered()
        onDispose {
            controller.onSurfaceExited()
        }
    }

    LaunchedEffect(url) {
        if (url != null) {
            controller.startPlayback(url, headers, startMs)
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
