@file:OptIn(ExperimentalForeignApi::class)

package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.header
import io.legado.app.help.media.AvPlayerItemStatusObserver
import io.legado.app.utils.File
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
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
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [HttpTtsPlayer] 的 iOS actual 实现: AVPlayer + AVURLAsset。
 *
 * - [setRequest]: 与 Android 共用 AnalyzeUrl 音频请求，下载后通过本地 URL 播放
 * - [setUrl]: 保留普通媒体 URL 的直接播放入口
 * - [prepare]: KVO 观察 AVPlayerItem.status，到 ReadyToPlay 才 onReady (对标 ExoPlayer STATE_READY)
 * - 播放结束经 NSNotificationCenter [AVPlayerItemDidPlayToEndTimeNotification] 转 onEndOfMedia
 */
class IosHttpTtsPlayer : HttpTtsPlayer {

    private data class AudioRequest(val config: HttpTTS, val text: String, val speechRate: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingRequest: AudioRequest? = null
    private var downloadJob: Job? = null
    private var prepareJob: Job? = null
    private var prepareGeneration = 0L
    private var requestGeneration = 0L
    private var audioFile: File? = null

    private fun onMain(action: () -> Unit) {
        if (NSThread.isMainThread) action()
        else dispatch_async(dispatch_get_main_queue()) { action() }
    }

    override fun setRequest(config: HttpTTS, text: String, speechRate: Int) = onMain {
        releaseCurrent()
        pendingRequest = AudioRequest(config.copy(), text, speechRate)
    }

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

    /** status KVO 观察器，stop/release/换源时释放。 */
    @Volatile
    private var statusObserver: AvPlayerItemStatusObserver? = null

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
            onMain { listenerField = value }
        }

    // ===== HttpTtsPlayer contract 方法 =====

    override fun setUrl(url: String, headers: Map<String, String>) = onMain {
        // 释放旧 player/item/observer
        releaseCurrent()

        loadUrl(url, headers)
    }

    private fun loadUrl(url: String, headers: Map<String, String>) {

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
        // 先创建空播放器，在 prepareItem 注册观察器后再关联媒体项。
        player = AVPlayer()

        // 注册播放结束监听
        registerEndObserver(newItem)
    }

    /** prepare: KVO 观察 AVPlayerItem.status，立即处理初始状态及后续变化。 */
    override fun prepare() = onMain {
        val request = pendingRequest
        if (request == null) {
            prepareItem()
            return@onMain
        }
        downloadJob?.cancel()
        prepareGeneration++
        prepareJob?.cancel()
        prepareJob = null
        statusObserver?.dispose()
        statusObserver = null
        val generation = ++requestGeneration
        downloadJob = scope.launch {
            val file = File(AppFilesDirs.get().cacheDir, "httpTTS/ios-${Random.nextLong()}.mp3")
            var adopted = false
            try {
                withContext(Dispatchers.IO) {
                    val response = HttpTtsRequest.audioResponse(
                        request.config, request.text, request.speechRate, currentCoroutineContext(),
                    )
                    try {
                        check(file.parentFile?.mkdirs() == true) { "无法创建 TTS 缓存目录" }
                        val input = response.body.byteStream()
                        try {
                            FileSystem.SYSTEM.sink(file.path.toPath()).buffer().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) output.write(buffer, 0, count)
                                }
                            }
                        } finally {
                            input.close()
                        }
                        check(file.length() > 0) { "TTS 服务返回空音频" }
                    } finally {
                        response.close()
                    }
                }
                ensureActive()
                if (generation != requestGeneration) return@launch
                audioFile = file
                adopted = true
                loadUrl(NSURL.fileURLWithPath(file.path).absoluteString!!, emptyMap())
                prepareItem()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == requestGeneration) {
                    val message = "HTTP TTS 音频请求失败：${error.message?.take(500)}"
                    AppLog.put(message, error)
                    listener?.onError(message)
                }
            } finally {
                if (!adopted) file.delete()
            }
        }
    }

    private fun prepareItem() {
        val target = item ?: run {
            listener?.onError("未设置播放源")
            return
        }
        statusObserver?.dispose()
        prepareJob?.cancel()
        prepareJob = null
        val preparation = ++prepareGeneration
        val generation = requestGeneration
        val callback = listener
        var completed = false
        fun complete(message: String?) {
            if (completed || preparation != prepareGeneration ||
                generation != requestGeneration || item !== target
            ) return
            completed = true
            prepareJob?.cancel()
            prepareJob = null
            statusObserver?.dispose()
            statusObserver = null
            if (message == null) {
                callback?.onReady()
            } else {
                player?.pause()
                AppLog.put("iOS HTTP TTS：音频加载失败，$message")
                callback?.onError(message)
            }
        }
        val observer = AvPlayerItemStatusObserver(
            item = target,
            onReady = {
                onMain { complete(null) }
            },
            onFailed = { message ->
                onMain { complete(message) }
            },
        )
        statusObserver = observer
        observer.start()
        if (completed) return
        player?.replaceCurrentItemWithPlayerItem(target)
        if (completed || preparation != prepareGeneration || generation != requestGeneration) return
        // KVO 桥接未送达时仍按真实 status 起播/报错；只在准备阶段检查。
        prepareJob = scope.launch {
            repeat(PREPARE_TIMEOUT_MS / PREPARE_CHECK_INTERVAL_MS) {
                when (target.status) {
                    AVPlayerItemStatusReadyToPlay -> {
                        complete(null)
                        return@launch
                    }
                    AVPlayerItemStatusFailed -> {
                        complete(target.error?.localizedDescription ?: "AVPlayerItem 加载失败")
                        return@launch
                    }
                }
                delay(PREPARE_CHECK_INTERVAL_MS.toLong())
            }
            // 最后再读一次，避免在超时边界把刚就绪的媒体项当作失败。
            if (target.status == AVPlayerItemStatusReadyToPlay) complete(null)
            else complete(target.error?.localizedDescription ?: "音频准备超时（15 秒），status=${target.status}")
        }
    }

    override fun play() = onMain {
        player?.play()
    }

    override fun pause() = onMain {
        player?.pause()
    }

    override fun stop() = onMain {
        requestGeneration++
        downloadJob?.cancel()
        prepareGeneration++
        prepareJob?.cancel()
        prepareJob = null
        statusObserver?.dispose()
        statusObserver = null
        val pl = player ?: return@onMain
        pl.pause()
        // 回到开头
        pl.seekToTime(CMTimeMake(0, 1000))
    }

    override fun release() = onMain {
        releaseCurrent()
        url = null
        headers = emptyMap()
    }

    /**
     * seekTo: CMTimeMake(value, timescale), timescale=1000 让 value 直接为毫秒。
     */
    override fun seekTo(position: Long) = onMain {
        val time = CMTimeMake(position, 1000)
        player?.seekToTime(time)
    }

    // ===== 内部实现 =====

    /**
     * 注册 NSNotificationCenter 监听 [AVPlayerItemDidPlayToEndTimeNotification],
     * 主线程回调转发为 [HttpTtsPlayerListener.onEndOfMedia]。
     */
    private fun registerEndObserver(target: AVPlayerItem) {
        val generation = requestGeneration
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            `object` = target,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (generation == requestGeneration && item === target) listener?.onEndOfMedia()
        }
    }

    /**
     * 释放当前 player/item/observer, 不清空 url/headers (供 setUrl 切换源时复用)。
     */
    private fun releaseCurrent() {
        requestGeneration++
        downloadJob?.cancel()
        downloadJob = null
        prepareGeneration++
        prepareJob?.cancel()
        prepareJob = null
        pendingRequest = null
        statusObserver?.dispose()
        statusObserver = null
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
        audioFile?.delete()
        audioFile = null
    }
}

private const val PREPARE_TIMEOUT_MS = 15_000
private const val PREPARE_CHECK_INTERVAL_MS = 100
