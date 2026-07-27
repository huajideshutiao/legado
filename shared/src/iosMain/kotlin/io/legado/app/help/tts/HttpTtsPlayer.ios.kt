package io.legado.app.help.tts

import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * [HttpTtsPlayer] 的 iOS actual 实现 (KP2-H)。
 *
 * **平台 API**:
 * - `AVPlayer` + `AVPlayerItem` (AVFoundation): iOS 原生媒体播放器, 自动处理网络拉流/缓冲/解码
 * - `NSNotificationCenter`: 监听 `AVPlayerItemDidPlayToEndTimeNotification` 触发 [HttpTtsPlayerListener.onEndOfMedia]
 * - `CMTime` / `CMTimeMake`: 时间表示与转换
 *
 * **实现策略**:
 * - [setUrl] 创建 `AVPlayer.playerWithURL(nsUrl)` (headers 简化忽略; 调用方需通过 URL query 传递)
 * - [prepare] 直接回调 [HttpTtsPlayerListener.onReady] (AVPlayer 在 setUrl 后已自动开始缓冲,
 *   真实就绪状态由 KVO item.status 监听; 此处简化省略 KVO)
 * - [play]/[pause]/[stop]/[seekTo] 直接转发 AVPlayer 同名方法
 * - [release] 释放 player/item + 移除 NSNotification observer
 *
 * **线程模型**:
 * - 所有 AVPlayer API 调用应在主线程; iOS Kotlin/Native 中 `Dispatchers.Main` 即主线程
 * - listener 回调由 NSNotificationCenter queue=mainQueue 触发, 与主线程一致
 * - 调用方负责保证 prepare/play 等方法在主线程调用 (与 AVPlayer contract 一致)
 *
 * **简化**:
 * - 未监听 `AVPlayerItem.status` (KVO); prepare 后直接 onReady, 实际缓冲未完成时 play 会自动等待
 * - 未监听 `AVPlayer.timeControlStatus` (KVO); isPlaying 通过 `player.rate() > 0` 判断
 * - duration/currentPosition 用 CMTimeGetSeconds 换算; 失败返回 -1/0
 * - headers 简化忽略; AVURLAssetHTTPHeaderFieldsKey 桥接 API 跨版本兼容性不稳, 此处不依赖
 *
 * **参考**:
 * - app 端: `app/.../service/HttpReadAloudService.kt` 用 ExoPlayer
 * - iOS 平台 API 调用风格: `iosMain/lib/webdav/WebDav.kt` 用 platform.Foundation.*
 */
class IosHttpTtsPlayer : HttpTtsPlayer {

    /** 当前 URL, setUrl 注入。 */
    @Volatile private var url: String? = null

    /** 当前请求头 (简化忽略, 仅保留用于日志)。 */
    @Volatile private var headers: Map<String, String> = emptyMap()

    /** 回调 listener。 */
    @Volatile private var listenerField: HttpTtsPlayerListener? = null

    /** AVPlayer 实例, setUrl 时创建。 */
    @Volatile private var player: AVPlayer? = null

    /** 当前 AVPlayerItem, setUrl 时通过 player.currentItem 获取。 */
    @Volatile private var item: AVPlayerItem? = null

    /** 播放结束通知 observer token, release 时用于 removeObserver。 */
    @Volatile private var endObserver: Any? = null

    // ===== HttpTtsPlayer contract =====

    /**
     * 是否在播放中。
     *
     * 用 `player.rate() > 0f` 判断: rate=1 正常播放, rate=0 暂停/停止。
     * 与 AVPlayer.timeControlStatus 不同 (KVO 监听 playing/paused/buffering),
     * 简化为 rate 检查; 调用方需知"缓冲中"也算 playing=true。
     */
    override val isPlaying: Boolean
        get() = (player?.rate() ?: 0f) > 0f

    /**
     * 总时长 (毫秒)。
     *
     * 用 `AVPlayerItem.duration` 取 CMTime, 换算为秒。
     * duration 为 NaN/Infinite (流式未就绪) 时返回 -1。
     */
    override val duration: Long
        get() {
            val it = item ?: return -1L
            val seconds = CMTimeGetSeconds(it.duration)
            if (seconds.isNaN() || seconds.isInfinite()) return -1L
            return (seconds * 1000.0).toLong()
        }

    /**
     * 当前播放位置 (毫秒)。
     *
     * 用 `AVPlayer.currentTime()` 取 CMTime, 换算为秒。
     * 返回 0 表示尚未播放或未就绪。
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

        // 创建 NSURL
        val nsUrl = NSURL.URLWithString(url) ?: run {
            listener?.onError("非法 URL: $url")
            return
        }

        // 创建 AVPlayer (用 playerWithURL, 简化忽略 headers)
        val newPlayer = AVPlayer.playerWithURL(nsUrl)
        player = newPlayer
        item = newPlayer.currentItem

        // 注册播放结束监听
        registerEndObserver(item)
    }

    /**
     * prepare: AVPlayer 在 setUrl 后已自动开始缓冲, 此处直接触发 onReady。
     *
     * 严格实现需要 KVO 监听 AVPlayerItem.status == AVPlayerItemStatusReadyToPlay,
     * 此处简化省略; 调用方在 onReady 后调 play() 时, AVPlayer 会自动等待缓冲完成。
     */
    override fun prepare() {
        listener?.onReady()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
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
     * seekTo: 用 AVPlayer.seekToTime 跳转。
     *
     * CMTimeMake(value, timescale): value/timescale = 秒。
     * 用 timescale=1000 让 value 直接为毫秒, 与 [currentPosition] 一致。
     */
    override fun seekTo(position: Long) {
        val time = CMTimeMake(position, 1000)
        player?.seekToTime(time)
    }

    // ===== 内部实现 =====

    /**
     * 注册 NSNotificationCenter 监听 [AVPlayerItemDidPlayToEndTimeNotification]。
     *
     * 播放完毕时由 NSNotificationCenter 在主线程触发回调, 转发为 [HttpTtsPlayerListener.onEndOfMedia]。
     *
     * lambda 捕获 listener (var) 跨线程共享; Kotlin/Native 新内存模型默认 relaxed mode 允许该捕获。
     */
    private fun registerEndObserver(target: AVPlayerItem?) {
        if (target == null) return
        // 捕获当前 listener 到 val, 避免闭包持有可变 var 引发跨线程访问问题
        val captured = listener
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            captured?.onEndOfMedia()
        }
    }

    /**
     * 释放当前 player/item/observer, 不清空 url/headers (供 setUrl 切换源时复用)。
     */
    private fun releaseCurrent() {
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
