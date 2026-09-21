@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.media.AvPlayerItemStatusObserver
import io.legado.app.help.media.NowPlayingLyricSink
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import io.legado.app.model.audio.AudioPlaySession
import io.legado.app.model.audio.LyricPublisher
import io.legado.app.model.audio.NowPlayingSessionHost
import io.legado.app.help.media.maxLoadedTimeRangeEndMs
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * iOS 端 AudioPlay 宿主 (对标 app 端 AudioPlayService 的平台部分)。
 *
 * 会话状态机在 commonMain [AudioPlaySession] (四端共用), 播控卡片同步在
 * [NowPlayingSessionHost] (三端共用), 本类只做 AVPlayer 接入。
 * MediaSession/Notification/AudioFocus/WakeLock 为 Android 专属, 不实现。
 */
class IosAudioPlayCommander : NowPlayingSessionHost() {

    // AVPlayer contract 要求主线程访问, 命令统一经 Main scope 串行派发 (等价 Service 主线程 onStartCommand)
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val avController = IosAvAudioPlayController()

    override val controller: AudioPlayController get() = avController

    override val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory
        get() = IosAudioPlayAnalyzeRuleFactory

    override var pendingTimerMinute: Int = 0

    /** 直链解析 → AVURLAsset (headers 经 options 注入) → prepare。 */
    override suspend fun startPlayback(url: String, positionMs: Int) {
        val (mediaUrl, headers) = AnalyzeUrlFactories.create(
            rawUrl = url,
            source = AudioPlayShared.bookSource,
            ruleData = AudioPlayShared.book,
            chapter = AudioPlayShared.durChapter,
            coroutineContext = currentCoroutineContext(),
        ).resolveMedia()
        avController.setSource(mediaUrl, headers, positionMs.toLong())
        avController.prepare()
    }

    override fun onSessionEnd() {
        avController.release()
        SystemMediaControl.releaseAudio()
    }
}

/**
 * [AudioPlayController] 的 iOS AVPlayer 实现 (对标 app 端 ExoPlayerAudioPlayController)。
 *
 * item.status 通过共享 [AvPlayerItemStatusObserver] KVO 观察，状态变化时立即回调。
 */
private class IosAvAudioPlayController : AudioPlayController {

    override var listener: AudioPlayControllerListener? = null

    private var player: AVPlayer? = null
    private var item: AVPlayerItem? = null
    private var endObserver: Any? = null
    private var failObserver: Any? = null
    private var statusObserver: AvPlayerItemStatusObserver? = null

    /** 当前倍速; AVPlayer.play() 会把 rate 复位为 1, 播放中变速/起播都要重设 rate */
    private var speed = 1f

    private var state = AudioPlayController.STATE_IDLE

    /** prepare 就绪后再应用的起播位置 (对应 app 端 setMediaItem 后的 seekTo(position)) */
    private var pendingSeekMs = 0L

    override var playWhenReady = false

    override val isPlaying: Boolean
        get() = (player?.rate() ?: 0f) > 0f

    override val duration: Long
        get() {
            val cur = item ?: return 0L
            val seconds = CMTimeGetSeconds(cur.duration)
            if (seconds.isNaN() || seconds.isInfinite()) return 0L
            return (seconds * 1000.0).toLong()
        }

    override val currentPosition: Long
        get() {
            val pl = player ?: return 0L
            val seconds = CMTimeGetSeconds(pl.currentTime())
            if (seconds.isNaN() || seconds.isInfinite()) return 0L
            return (seconds * 1000.0).toLong()
        }

    /**
     * 已缓冲到的时间点 (进度条缓冲层用)。
     *
     * `AVPlayerItem.loadedTimeRanges` 是 `NSValue`(装 `CMTimeRange`) 数组, 逐段取
     * `CMTimeRangeGetEnd` 的最大值; seek 后会出现多段不连续区间, 取最大 end 与
     * ExoPlayer `bufferedPosition` 口径一致 (与视频端 IosVideoPlayerController 同实现)。
     */
    override val bufferedPosition: Long
        get() = item?.maxLoadedTimeRangeEndMs() ?: 0L

    override val playbackState: Int get() = state

    /** 设置播放源 (headers 经 AVURLAssetHTTPHeaderFieldsKey 注入), startPosMs 就绪后生效 */
    fun setSource(url: String, headers: Map<String, String>, startPosMs: Long) {
        releaseCurrent()
        pendingSeekMs = startPosMs
        val nsUrl = NSURL.URLWithString(url) ?: run {
            listener?.onPlayerError(IllegalArgumentException("非法 URL: $url"))
            return
        }
        // AVURLAssetHTTPHeaderFieldsKey 非公开头文件常量, platform 库无符号, 用字面量 key
        val options: Map<Any?, *>? = if (headers.isEmpty()) null else {
            mapOf<Any?, Any?>(AV_HTTP_HEADER_FIELDS_KEY to headers)
        }
        val asset = AVURLAsset.URLAssetWithURL(nsUrl, options)
        val newItem = AVPlayerItem.playerItemWithAsset(asset)
        item = newItem
        player = AVPlayer.playerWithPlayerItem(newItem)
        registerItemObservers(newItem)
    }

    override fun prepare() {
        val watchedItem = item ?: return
        state = AudioPlayController.STATE_BUFFERING
        statusObserver?.dispose()
        val observer = AvPlayerItemStatusObserver(
            item = watchedItem,
            onReady = {
                statusObserver = null
                onItemReady()
            },
            onFailed = { message ->
                statusObserver = null
                state = AudioPlayController.STATE_IDLE
                listener?.onPlayerError(RuntimeException(message))
            },
        )
        statusObserver = observer
        observer.start()
    }

    /** 就绪: 先 seek 起播位置, playWhenReady 则起播, 再回调 STATE_READY */
    private fun onItemReady() {
        if (pendingSeekMs > 0) {
            player?.seekToTime(CMTimeMake(pendingSeekMs, 1000))
            pendingSeekMs = 0
        }
        state = AudioPlayController.STATE_READY
        if (playWhenReady) play()
        listener?.onPlaybackStateChanged(AudioPlayController.STATE_READY)
    }

    override fun play() {
        player?.play()
        // play() 把 rate 复位为 1, 非常速时重设
        if (speed != 1f) player?.setRate(speed)
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        statusObserver?.dispose()
        statusObserver = null
        player?.pause()
        state = AudioPlayController.STATE_IDLE
    }

    override fun seekTo(position: Long) {
        if (state == AudioPlayController.STATE_READY || state == AudioPlayController.STATE_ENDED) {
            player?.seekToTime(CMTimeMake(position, 1000))
        } else {
            // 未就绪时暂存, 就绪后统一应用
            pendingSeekMs = position
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        // 暂停时只记录 (setRate>0 会触发起播), 播放中立即生效
        if (isPlaying) player?.setRate(speed)
    }

    override fun release() {
        releaseCurrent()
        state = AudioPlayController.STATE_IDLE
    }

    /** 监听播放结束/播放失败通知 (对应 ExoPlayer STATE_ENDED / onPlayerError) */
    private fun registerItemObservers(target: AVPlayerItem) {
        val center = NSNotificationCenter.defaultCenter
        endObserver = center.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            state = AudioPlayController.STATE_ENDED
            listener?.onPlaybackStateChanged(AudioPlayController.STATE_ENDED)
        }
        failObserver = center.addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            listener?.onPlayerError(
                RuntimeException(target.error?.localizedDescription ?: "AVPlayerItem play failed")
            )
        }
    }

    private fun releaseCurrent() {
        statusObserver?.dispose()
        statusObserver = null
        val center = NSNotificationCenter.defaultCenter
        endObserver?.let { center.removeObserver(it) }
        endObserver = null
        failObserver?.let { center.removeObserver(it) }
        failObserver = null
        player?.let {
            it.pause()
            it.replaceCurrentItemWithPlayerItem(null)
        }
        player = null
        item = null
        pendingSeekMs = 0
    }

    private companion object {
        /** AVURLAsset options 的 HTTP headers key (非公开常量, 见 setSource 注释) */
        private const val AV_HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"
    }
}

/** [AudioPlayAnalyzeRuleFactory] 的 iOS 实现: 经 [AnalyzeRuleFactories] 创建 (同 desktop) */
private object IosAudioPlayAnalyzeRuleFactory : AudioPlayAnalyzeRuleFactory {

    override fun create(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        coroutineContext: CoroutineContext,
    ): AnalyzeRuleCore {
        return AnalyzeRuleFactories.create(book, bookSource).apply {
            this.coroutineContext = coroutineContext
            setBaseUrl(chapter.url)
            this.chapter = chapter
        }
    }
}

/**
 * iOS 宿主启动早期注册 AudioPlay 平台 provider (对标 registerDesktopAudioPlayProviders)。
 * 须在 AppDbProviders / JsEngines / 网络 provider 注册之后调用。
 */
fun registerIosAudioPlayCommanders() {
    AudioPlayCommanders.register(IosAudioPlayCommander().session)
    LyricPublisher.register(NowPlayingLyricSink)
}
