@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.help.media.AvPlayerItemStatusObserver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

// iOS 视频播放平台能力: AVPlayer 播控, AVPlayerViewController 仅渲染 (纯视频流)
object IosVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = IosVideoPlayerController(onPlaybackEnded)

    @Composable
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val iosController = controller as? IosVideoPlayerController ?: return
        // 必须以 State 订阅: 直读 StateFlow.value 不会随链接就绪重组, 会一直停在等待态 (对照 desktop 64711ebf22)
        val videoUrl by screenModel.shared.videoUrl.collectAsState()
        val uiState by screenModel.state.collectAsState()
        val url = videoUrl?.url
        // AVPlayerViewController: 只出画面 (showsPlaybackControls=false), 系统控制条不显示
        val avpvc = remember { AVPlayerViewController() }
        // 控制层回显: AVPlayer 属性非 State, 轮询写入驱动重组 (对照 desktop 控制层进度轮询)
        var controlsPositionMs by remember { mutableLongStateOf(0L) }
        var controlsDurationMs by remember { mutableLongStateOf(0L) }
        var isPlaying by remember { mutableStateOf(false) }

        LaunchedEffect(url) {
            if (url != null) iosController.loadUrl(url)
        }

        LaunchedEffect(uiState.controlsVisible) {
            while (uiState.controlsVisible) {
                controlsPositionMs = iosController.positionMs
                controlsDurationMs = iosController.durationMs
                isPlaying = iosController.player?.rate()?.let { it > 0f } == true
                delay(500)
            }
        }

        Box(modifier) {
            UIKitView(
                factory = {
                    avpvc.apply {
                        // 纯播放: 隐藏系统控制条, 控制交给 Compose 统一处理
                        showsPlaybackControls = false
                    }.view
                },
                update = {
                    avpvc.player = iosController.player
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 透明触摸层: UIKit 视图不参与 Compose 触摸测试, 盖一层透明 clickable
            // 让"点击视频区切换控制层"落到 Compose (控制层可见时它在其下, 不抢按钮事件)
            Box(
                Modifier
                    .matchParentSize()
                    .clickable { screenModel.onToggleControls() }
            )
            // 控制层: iOS 无 airspace, Compose 可直接叠在 UIKit 视图之上 (对照桌面窗口级 Popup)
            if (uiState.controlsVisible) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable { screenModel.onToggleControls() }
                ) {
                    VideoControlsOverlay(
                        visible = true,
                        isPlaying = isPlaying,
                        positionMs = controlsPositionMs,
                        durationMs = controlsDurationMs,
                        playbackSpeed = uiState.playbackSpeed,
                        hasMultiResolution = uiState.hasMultiResolution,
                        resolutions = uiState.resolutions,
                        currentResolutionIndex = uiState.currentResolutionIndex,
                        onPlayPause = screenModel::onPlayPause,
                        onSeek = screenModel::onSeekTo,
                        onSpeedChange = screenModel::onSpeedChange,
                        onSwitchResolution = screenModel::onSwitchResolution,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// AVPlayer 视频控制器: 播放/暂停/进度/倍速 (参考 IosHttpTtsPlayer 的 AVPlayer 用法)
class IosVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    var player: AVPlayer? = null
        private set
    private var item: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var statusObserver: AvPlayerItemStatusObserver? = null
    private var loadedUrl: String? = null

    // 加载 URL: 创建 AVPlayerItem + AVPlayer, 注册播放结束监听
    fun loadUrl(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        releasePlayer()
        val nsUrl = NSURL.URLWithString(url) ?: return
        val newItem = AVPlayerItem(asset = AVURLAsset(nsUrl, null))
        item = newItem
        player = AVPlayer(playerItem = newItem)
        registerEndObserver(newItem)
    }

    override val positionMs: Long
        get() = player?.let {
            val sec = CMTimeGetSeconds(it.currentTime())
            if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000.0).toLong()
        } ?: 0L

    override val durationMs: Long
        get() = item?.let {
            val sec = CMTimeGetSeconds(it.duration)
            if (sec.isNaN() || sec.isInfinite()) 0L else (sec * 1000.0).toLong()
        } ?: 0L

    override val bufferedMs: Long get() = durationMs

    override fun playPause() {
        val pl = player ?: return
        if (pl.rate() > 0f) pl.pause() else pl.play()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekToTime(CMTimeMake(positionMs, 1000))
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(positionMs + deltaMs)
    }

    override fun setSpeed(speed: Float) {
        player?.setRate(speed)
    }

    override fun seekBack() = seekBy(-10000)

    override fun seekForward() = seekBy(10000)

    override fun release() {
        releasePlayer()
        loadedUrl = null
    }

    // 注册播放结束监听 (AVPlayerItemDidPlayToEndTimeNotification)
    private fun registerEndObserver(target: AVPlayerItem) {
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onPlaybackEnded() }
    }

    private fun releasePlayer() {
        statusObserver?.dispose()
        statusObserver = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        item = null
    }
}
