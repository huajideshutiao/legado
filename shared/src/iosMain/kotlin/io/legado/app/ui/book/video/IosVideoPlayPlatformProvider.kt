@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.help.media.AvPlayerBufferingObserver
import io.legado.app.help.media.AvPlayerItemStatusObserver
import io.legado.app.ui.IosStatusBarHiddenKey
import io.legado.app.ui.IosStatusBarHiddenNotification
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import platform.AVFoundation.setVolume
import platform.AVFoundation.volume
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIScreen

// iOS 视频播放平台能力: AVPlayer 播控, AVPlayerViewController 仅渲染 (纯视频流)
object IosVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = IosVideoPlayerController(onPlaybackEnded)

    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val iosController = controller as? IosVideoPlayerController ?: return
        val videoUrl by screenModel.shared.videoUrl.collectAsState()
        val url = videoUrl?.url
        // AVPlayerViewController: 只出画面 (showsPlaybackControls=false), 系统控制条不显示
        val avpvc = remember { AVPlayerViewController() }

        LaunchedEffect(url) {
            if (url != null) iosController.loadUrl(url)
        }

        UIKitView(
            factory = {
                avpvc.apply {
                    showsPlaybackControls = false
                }.view
            },
            update = {
                avpvc.player = iosController.player
            },
            modifier = modifier.fillMaxSize(),
        )
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController? {
        val iosController = controller as? IosVideoPlayerController ?: return null
        return remember(iosController) {
            VideoGestureController(
                isPlaying = { iosController.player?.rate()?.let { it > 0f } == true },
                positionMs = { iosController.positionMs },
                durationMs = { iosController.durationMs },
                speed = { iosController.player?.rate() ?: 1f },
                setSpeed = { iosController.setSpeed(it) },
                onPlayPause = { iosController.playPause() },
                seekTo = { iosController.seekTo(it) },
                readBrightness = { UIScreen.mainScreen.brightness.toFloat() },
                writeBrightness = { UIScreen.mainScreen.brightness = it.toDouble() },
                readVolume = { iosController.player?.volume ?: 1f },
                writeVolume = { iosController.player?.setVolume(it) },
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
        val iosController = controller as? IosVideoPlayerController ?: return null
        val isBuffering by iosController.isBufferingFlow.collectAsState()
        return isBuffering
    }

    override fun applyFullscreen(enabled: Boolean) {
        // 对照原版 setFullScreen 的系统栏部分 (iOS 无窗口内全屏布局概念, 全屏观感=状态栏显隐):
        // 经 SwiftUI 根视图 .statusBarHidden 桥, 与 WindowPolicy.setSystemBars 同通道。
        // iOS 不支持编程强制方向 (见 IosWindowController.setOrientation), 故无 applySystemFullScreen
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = IosStatusBarHiddenNotification,
            `object` = null,
            userInfo = mapOf(IosStatusBarHiddenKey to enabled),
        )
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
    private var bufferingObserver: AvPlayerBufferingObserver? = null
    private var loadedUrl: String? = null
    private val _isBuffering = MutableStateFlow(false)
    /** 缓冲状态 (事件驱动: KVO 观察 item.status / player.timeControlStatus, 无轮询)。 */
    val isBufferingFlow: StateFlow<Boolean> = _isBuffering.asStateFlow()

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
        registerBufferingObserver(newItem)
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

    // 缓冲状态 KVO 观察 (事件驱动: item.status 加载中 / player.timeControlStatus 等待起播)
    private fun registerBufferingObserver(target: AVPlayerItem) {
        val pl = player ?: return
        val observer = AvPlayerBufferingObserver(
            player = pl,
            item = target,
            onBufferingChange = { _isBuffering.value = it },
        )
        bufferingObserver = observer
        observer.start()
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
        bufferingObserver?.dispose()
        bufferingObserver = null
        _isBuffering.value = false
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        item = null
    }
}
