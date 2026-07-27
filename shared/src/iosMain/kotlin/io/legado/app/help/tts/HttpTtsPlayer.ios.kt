@file:OptIn(ExperimentalForeignApi::class)

package io.legado.app.help.tts

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * [HttpTtsPlayer] 的 iOS actual 实现: AVPlayer + AVURLAsset。
 *
 * - [setUrl]: AVURLAsset(URL:options:) 经 AVURLAssetHTTPHeaderFieldsKey 注入书源 headers
 *   (cookie/UA/Referer), 与 app 端 ExoPlayer 请求头语义对齐
 * - [prepare]: 轮询 AVPlayerItem.status 到 ReadyToPlay 才 onReady (对标 ExoPlayer STATE_READY)
 * - 播放结束经 NSNotificationCenter [AVPlayerItemDidPlayToEndTimeNotification] 转 onEndOfMedia
 * - AVPlayer API 应在主线程调用 (轮询跑 Dispatchers.Main), 与 AVPlayer contract 一致
 */
class IosHttpTtsPlayer : HttpTtsPlayer {

    /** 当前 URL, setUrl 注入。 */
    @Volatile private var url: String? = null

    /** 当前请求头, setUrl 时注入 AVURLAsset options。 */
    @Volatile private var headers: Map<String, String> = emptyMap()

    /** 回调 listener。 */
    @Volatile private var listenerField: HttpTtsPlayerListener? = null

    /** AVPlayer 实例, setUrl 时创建。 */
    @Volatile private var player: AVPlayer? = null

    /** 当前 AVPlayerItem, setUrl 时创建。 */
    @Volatile private var item: AVPlayerItem? = null

    /** 播放结束通知 observer token, release 时用于 removeObserver。 */
    @Volatile private var endObserver: Any? = null

    /** 主线程协程域, prepare 的 status 轮询用。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** status 轮询 Job, stop/release/换源时取消。 */
    @Volatile private var statusPollJob: Job? = null

    // ===== HttpTtsPlayer contract =====

    /**
     * 是否在播放中: `player.rate() > 0f` (rate=1 播放, rate=0 暂停/停止)。
     * 简化为 rate 检查, "缓冲中"也算 playing=true。
     */
    override val isPlaying: Boolean
        get() = (player?.rate() ?: 0f) > 0f

    /**
     * 总时长 (毫秒), CMTime 换算; NaN/Infinite (流式未就绪) 返回 -1。
     */
    override val duration: Long
        get() {
            val it = item ?: return -1L
            val seconds = CMTimeGetSeconds(it.duration)
            if (seconds.isNaN() || seconds.isInfinite()) return -1L
            return (seconds * 1000.0).toLong()
        }

    /**
     * 当前播放位置 (毫秒), 未就绪返回 0。
     */
    override val currentPosition: Long
        get() {
            val pl = player ?: return 0L
            val seconds = CMTimeGetSeconds(pl.currentTime())
            if (seconds.isNaN() || seconds.isInfinite()) return 0L
            return (seconds * 1000.0).toLong()
        }

    override var listener: HttpTtsPlayerListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== HttpTtsPlayer contract 方法 =====

    override fun setUrl(url: String, headers: Map<String, String>) {
        // 释放旧 player/item/observer
        releaseCurrent()

        this.url = url
        this.headers = headers

        val nsUrl = NSURL.URLWithString(url) ?: run {
            listener?.onError("非法 URL: $url")
            return
        }

        // 书源 headers 经 AVURLAssetHTTPHeaderFieldsKey 注入
        // (该 key 常量未随 cinterop 平台库暴露, 用同名字面量 key)
        val options: Map<Any?, Any?>? = if (headers.isEmpty()) null else {
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers)
        }
        val newItem = AVPlayerItem(asset = AVURLAsset(nsUrl, options))
        item = newItem
        player = AVPlayer(playerItem = newItem)

        // 注册播放结束监听
        registerEndObserver(newItem)
    }

    /**
     * prepare: 轮询 AVPlayerItem.status, ReadyToPlay 才 onReady / Failed 则 onError。
     * Kotlin/Native 无法 override NSObject category 的 observeValueForKeyPath (映射为扩展函数),
     * 经典 KVO 不可实现, 取轻量主线程轮询 (50ms) 代替。
     */
    override fun prepare() {
        val target = item ?: run {
            listener?.onError("未设置播放源")
            return
        }
        statusPollJob?.cancel()
        statusPollJob = scope.launch {
            while (isActive) {
                when (target.status) {
                    AVPlayerItemStatusReadyToPlay -> {
                        listener?.onReady()
                        return@launch
                    }

                    AVPlayerItemStatusFailed -> {
                        listener?.onError(
                            target.error?.localizedDescription ?: "AVPlayerItem 加载失败"
                        )
                        return@launch
                    }
                }
                delay(50)
            }
        }
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        statusPollJob?.cancel()
        statusPollJob = null
        val pl = player ?: return
        pl.pause()
        // 回到开头
        pl.seekToTime(CMTimeMake(0, 1000))
    }

    override fun release() {
        releaseCurrent()
        url = null
        headers = emptyMap()
    }

    /**
     * seekTo: CMTimeMake(value, timescale), timescale=1000 让 value 直接为毫秒。
     */
    override fun seekTo(position: Long) {
        val time = CMTimeMake(position, 1000)
        player?.seekToTime(time)
    }

    // ===== 内部实现 =====

    /**
     * 注册 NSNotificationCenter 监听 [AVPlayerItemDidPlayToEndTimeNotification],
     * 主线程回调转发为 [HttpTtsPlayerListener.onEndOfMedia]。
     */
    private fun registerEndObserver(target: AVPlayerItem) {
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            // 读 listener 字段而非注册时快照: setUrl 之后再赋值的 listener 也能收到章节衔接回调
            listener?.onEndOfMedia()
        }
    }

    /**
     * 释放当前 player/item/observer/轮询, 不清空 url/headers (供 setUrl 切换源时复用)。
     */
    private fun releaseCurrent() {
        statusPollJob?.cancel()
        statusPollJob = null
        endObserver?.let { token ->
            NSNotificationCenter.defaultCenter.removeObserver(token)
            endObserver = null
        }
        player?.let { pl ->
            pl.pause()
            pl.replaceCurrentItemWithPlayerItem(null)
        }
        player = null
        item = null
    }
}
