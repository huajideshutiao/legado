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
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
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
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        // 必须以 State 订阅: 直读 StateFlow.value 不会随链接就绪重组, 会一直停在等待态 (对照 desktop 64711ebf22)
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

    /** ArkTS 侧 `registerComposeInteropBuilder` 的注册 key (需与 ets 端字面量一致)。 */
    const val ARKUI_BUILDER_VIDEO_SURFACE: String = "legadoVideoSurface"

    override fun applyFullscreen(enabled: Boolean) {
        OhosNativeBridge.setWindowFullScreenLayout(enabled)
        OhosNativeBridge.setWindowSystemBarEnable(!enabled)
    }

    override fun toggleOrientation() {
        // 0 = 竖屏, 1 = 横屏 (对照 @ohos.window Orientation enum)
        OhosNativeBridge.setWindowPreferredOrientation(1)
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
    private var cachedDuration = 0L
    @Volatile
    private var cachedPosition = 0L
    @Volatile
    private var speed = 1f
    @Volatile
    private var loadedUrl: String? = null
    @Volatile
    private var listenerRegistered = false

    // 加载 URL: 经 tsfn 发 setSourceUrl 命令, ArkTS 创建 AVPlayer 设源 prepare
    fun loadUrl(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        ensureListener()
        sendCommand(MediaCommand(action = "setSourceUrl", url = url))
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

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        sendCommand(MediaCommand(action = "release"))
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_VIDEO_BOOK, null)
            listenerRegistered = false
        }
        playing = false
        cachedDuration = 0L
        cachedPosition = 0L
        loadedUrl = null
    }

    // ArkTS AVPlayer 事件回调 (同 OhosAvAudioPlayController.onMediaEvent 模式)
    override fun onMediaEvent(eventJson: String) {
        val event = runCatching {
            KS_JSON.decodeFromString(MediaEvent.serializer(), eventJson)
        }.getOrNull() ?: return
        when (event.event) {
            "onReady" -> {}
            "onEndOfMedia" -> {
                playing = false; onPlaybackEnded()
            }

            "onError" -> {
                playing = false
            }

            "onDuration" -> event.duration?.let { cachedDuration = it }
            "onPosition" -> event.position?.let { cachedPosition = it }
            "onPlaying" -> {
                playing = true
            }

            "onPaused" -> {
                playing = false
            }
        }
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
    )

    @Serializable
    private data class MediaEvent(
        val event: String,
        val message: String? = null,
        val duration: Long? = null,
        val position: Long? = null,
    )
}
