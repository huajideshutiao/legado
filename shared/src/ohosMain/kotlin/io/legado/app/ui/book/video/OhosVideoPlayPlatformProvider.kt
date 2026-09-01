package io.legado.app.ui.book.video

import kotlin.concurrent.Volatile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.ArkUIView2
import androidx.compose.ui.napi.js
import androidx.compose.runtime.remember
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [VideoPlayPlatformProvider]: 控制器经 napi Media 桥操作 AVPlayer (playerId "videoBook"),
 * 画面经 CPF [ArkUIView2] interop 混排 ArkTS XComponent surface。
 *
 * 控制器复用 [OhosNativeBridge] 的 media tsfn + @CName legado_media_event 事件回调,
 * 与 OhosAudioPlayCommander ("audioBook") / OhosHttpTtsPlayer ("httpTts") 各持独立 AVPlayer 实例。
 */
object OhosVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = OhosVideoPlayerController(onPlaybackEnded)

    /**
     * 画面渲染: 经 CPF interop 把 ArkTS 的 XComponent(type:'surface') 混排进 Compose 层级。
     *
     * ArkTS 侧需以 [ARKUI_BUILDER_VIDEO_SURFACE] 为 key 调 `registerComposeInteropBuilder`
     * 注册一个 @Builder, 内部 `XComponent({type:'surface'}).onLoad{}` 取到 surfaceId 后
     * 交给 MediaBridgeHandler 上对应 playerId 的 AVPlayer (`player.surfaceId = id`)。
     * 播控命令仍走既有 media tsfn 通道, 与本视图无耦合。
     *
     * interactive=false: 触摸留给 Compose 控件层 (进度条/手势), ArkUI 侧不参与触摸测试。
     */
    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val videoUrl by screenModel.shared.videoUrl.collectAsState()
        val url = videoUrl?.url

        LaunchedEffect(url) {
            if (url != null) (controller as? OhosVideoPlayerController)?.loadUrl(url)
        }

        ArkUIView2(
            name = ARKUI_BUILDER_VIDEO_SURFACE,
            modifier = modifier.fillMaxSize(),
            parameter = js { "playerId"(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK) },
            background = Color.Black,
            interactive = false,
        )
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? {
        val ohosController = controller as? OhosVideoPlayerController ?: return null
        return remember(ohosController) {
            VideoGestureController(
                isPlaying = { ohosController.isPlaying },
                positionMs = { ohosController.positionMs },
                durationMs = { ohosController.durationMs },
                speed = { ohosController.speed },
                setSpeed = { ohosController.setSpeed(it) },
                onPlayPause = { ohosController.playPause() },
                seekTo = { ohosController.seekTo(it) },
                readBrightness = { if (ohosController.brightness >= 0f) ohosController.brightness else 0.5f },
                writeBrightness = { ohosController.setBrightness(it) },
                readVolume = { ohosController.volume },
                writeVolume = { ohosController.setVolume(it) },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
            )
        }
    }

    @Composable
    override fun isBuffering(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): Boolean? {
        val ohosController = controller as? OhosVideoPlayerController ?: return null
        val isBuffering by ohosController.isBufferingFlow.collectAsState()
        return isBuffering
    }

    /** ArkTS 侧 `registerComposeInteropBuilder` 的注册 key (需与 ets 端字面量一致)。 */
    const val ARKUI_BUILDER_VIDEO_SURFACE: String = "legadoVideoSurface"

    override fun applyFullscreen(enabled: Boolean) {
        OhosNativeBridge.setWindowFullScreenLayout(enabled)
        OhosNativeBridge.setWindowSystemBarEnable(!enabled)
    }

    // 横屏全屏: 2=LANDSCAPE / 0=UNSPECIFIED (枚举映射见 OhosPlatformServices.setOrientation)
    override fun applySystemFullScreen(enabled: Boolean) {
        OhosNativeBridge.setWindowPreferredOrientation(if (enabled) 2 else 0)
    }
}

/**
 * 视频播放控制器: 命令经 napi Media 桥发 ArkTS AVPlayer, 事件回推缓存状态。
 * playerId 固定 "videoBook", 与音频书/HttpTTS 互不抢占 (同 OhosAvAudioPlayController 模式)。
 */
class OhosVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController, OhosNativeBridge.MediaEventListener {

    @Volatile
    private var playing = false

    @Volatile
    var speed = 1f
        private set

    @Volatile
    var volume = 1f
        private set

    @Volatile
    var brightness = -1f
        private set
    @Volatile
    private var cachedDuration = 0L
    @Volatile
    private var cachedPosition = 0L
    @Volatile
    private var loadedUrl: String? = null
    @Volatile
    private var listenerRegistered = false
    /** AVPlayer prepare 完成 (onReady) 前视为加载中 */
    @Volatile
    private var ready = false
    /** 缓冲百分比 (ArkTS onBufferingUpdate 推送, 0-100) */
    @Volatile
    private var bufferingPercent = 100

    /** 缓冲中: 链接就绪后 AVPlayer 未 prepare 完成 (onReady 前), 或缓冲百分比 < 100 (起播/卡顿/seek)。 */
    val isBuffering: Boolean
        get() = !ready || bufferingPercent < 100

    val isPlaying: Boolean
        get() = playing

    private val _isBuffering = MutableStateFlow(false)
    /** 缓冲状态 (事件驱动: ArkTS 事件回推 StateFlow, 无轮询)。 */
    val isBufferingFlow: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private fun syncBuffering() {
        _isBuffering.value = isBuffering
    }

    // 加载 URL: 经 tsfn 发 setSourceUrl 命令, ArkTS 创建 AVPlayer 设源 prepare
    fun loadUrl(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        ready = false
        bufferingPercent = 0
        ensureListener()
        sendCommand(MediaCommand(action = "setSourceUrl", url = url))
        syncBuffering()
    }

    private fun ensureListener() {
        if (!listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK, this)
            listenerRegistered = true
        }
    }

    override val positionMs: Long get() = cachedPosition
    override val durationMs: Long get() = cachedDuration
    override val bufferedMs: Long get() = cachedDuration

    override fun playPause() {
        if (playing) {
            sendCommand(MediaCommand(action = "pause"))
        } else {
            sendCommand(MediaCommand(action = "play"))
            if (speed != 1f) sendCommand(MediaCommand(action = "setSpeed", speed = speed))
        }
    }

    override fun seekTo(positionMs: Long) {
        sendCommand(MediaCommand(action = "seekTo", position = positionMs))
        cachedPosition = positionMs
    }

    override fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    override fun setSpeed(speed: Float) {
        this.speed = speed
        sendCommand(MediaCommand(action = "setSpeed", speed = speed))
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        sendCommand(MediaCommand(action = "setVolume", volume = volume.toDouble()))
    }

    fun setBrightness(b: Float) {
        brightness = b.coerceIn(0f, 1f)
        OhosNativeBridge.setWindowBrightness(brightness)
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        if (brightness >= 0f) {
            OhosNativeBridge.setWindowBrightness(-1f)
            brightness = -1f
        }
        sendCommand(MediaCommand(action = "release"))
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK, null)
            listenerRegistered = false
        }
        playing = false
        ready = false
        bufferingPercent = 100
        cachedDuration = 0L
        cachedPosition = 0L
        loadedUrl = null
        syncBuffering()
    }

    // ArkTS AVPlayer 事件回调 (同 OhosAvAudioPlayController.onMediaEvent 模式)
    override fun onMediaEvent(eventJson: String) {
        val event = runCatching {
            KS_JSON.decodeFromString(MediaEvent.serializer(), eventJson)
        }.getOrNull() ?: return
        when (event.event) {
            "onReady" -> {
                ready = true
                bufferingPercent = 100
            }

            "onBufferingUpdate" -> event.percent?.let { bufferingPercent = it.toInt() }

            "onEndOfMedia" -> {
                playing = false
                bufferingPercent = 100
                onPlaybackEnded()
            }

            "onError" -> {
                playing = false
                bufferingPercent = 100
            }

            "onDuration" -> event.duration?.let { cachedDuration = it }
            "onPosition" -> event.position?.let { cachedPosition = it }
            "onPlaying" -> {
                playing = true
                bufferingPercent = 100
            }

            "onPaused" -> {
                playing = false
                bufferingPercent = 100
            }
        }
        syncBuffering()
    }

    private fun sendCommand(cmd: MediaCommand) {
        val stamped = cmd.copy(playerId = OhosNativeBridge.PLAYER_ID_VIDEO_BOOK)
        OhosNativeBridge.sendMediaCommand(
            KS_JSON.encodeToString(
                MediaCommand.serializer(),
                stamped
            )
        )
    }

    @Serializable
    private data class MediaCommand(
        val action: String,
        val playerId: String = "",
        val url: String? = null,
        val position: Long? = null,
        val speed: Float? = null,
        val volume: Double? = null,
    )

    @Serializable
    private data class MediaEvent(
        val event: String,
        val message: String? = null,
        val percent: Long? = null,
        val duration: Long? = null,
        val position: Long? = null,
    )
}
