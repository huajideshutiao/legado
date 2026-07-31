@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.help.media.AvPlayerItemStatusObserver
import kotlinx.cinterop.ExperimentalForeignApi
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

// iOS 视频播放平台能力: AVPlayer 播控 + AVPlayerViewController 渲染
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
        val url = screenModel.shared.videoUrl.value?.url
        // AVPlayerViewController: 系统视频播放器 (含播放/暂停/进度/全屏控制)
        val avpvc = remember { AVPlayerViewController() }

        LaunchedEffect(url) {
            if (url != null) iosController.loadUrl(url)
        }

        UIKitView(
            factory = {
                avpvc.apply {
                    showsPlaybackControls = true
                }.view
            },
            update = {
                avpvc.player = iosController.player
            },
            modifier = modifier,
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
