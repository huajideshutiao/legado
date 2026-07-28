@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.media.AvPlayerItemStatusObserver
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.toast.Toasters
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import io.legado.app.model.audio.AudioPlayManager
import io.legado.app.model.audio.AudioPlayManagerListener
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * iOS 端 AudioPlay 平台 provider (对标 app 端 AudioPlayService + AudioPlayProvidersImpl)。
 *
 * 编排层 (进度/歌词/章节加载) 全部复用 commonMain [AudioPlayManager], 本类只做
 * AVPlayer 原语 + Service 生命周期等价 (running/pause/SleepTimer/ReadTimeRecorder)。
 * MediaSession/Notification/AudioFocus/WakeLock 为 Android 专属, iOS 端不实现。
 */
class IosAudioPlayCommander : AudioPlayCommander, AudioPlayBookBridge,
    AudioPlayControllerListener, AudioPlayManagerListener {

    // AVPlayer contract 要求主线程访问, 命令统一经 Main scope 串行派发 (等价 Service 主线程 onStartCommand)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller = IosAvAudioPlayController(scope)
    private val manager = AudioPlayManager(controller, scope, IosAudioPlayAnalyzeRuleFactory, this)

    /** 对应 AudioPlayService.isRun */
    @Volatile private var running = false

    /** 对应 AudioPlayService.pause */
    @Volatile private var pause = true

    /** 对应 AudioPlayService.playSpeed */
    @Volatile private var playSpeed = 1f

    /** 对应 AudioPlayService.url (当前播放直链) */
    @Volatile private var url = ""

    /** 对应 AudioPlayService.position (起播/暂停位置, 毫秒) */
    @Volatile private var position = 0

    private var sleepTimer: SleepTimer? = null

    /** 播放出错后先 refreshChapter 重试一次, 再报错 (对应 app 端 hasRefreshedOnPlayError) */
    private var hasRefreshedOnPlayError = false

    init {
        controller.listener = this
    }

    // ===== AudioPlayCommander =====

    override val isServiceRunning: Boolean get() = running

    override var pendingTimerMinute: Int = 0

    override fun play() {
        ensureRunning()
        scope.launch { triggerPlay(playNew = false) }
    }

    override fun playNew() {
        ensureRunning()
        scope.launch { triggerPlay(playNew = true) }
    }

    override fun stop() {
        if (!running) return
        scope.launch { destroySelf() }
    }

    override fun stopPlay() {
        if (!running) return
        scope.launch {
            controller.stop()
            manager.cancelProgressJobs()
            AudioPlayShared.status = Status.STOP
            AudioPlayShared.book?.let { save(it) }
            postEvent(EventBus.AUDIO_STATE, Status.STOP)
        }
    }

    override fun pause() {
        if (!running) return
        scope.launch { pauseImpl() }
    }

    override fun resume() {
        if (!running) return
        scope.launch { resumeImpl() }
    }

    override fun adjustSpeed(adjust: Float) {
        if (!running) return
        scope.launch { upSpeed(adjust) }
    }

    override fun adjustProgress(position: Int) {
        if (!running) return
        scope.launch {
            this@IosAudioPlayCommander.position = position
            controller.seekTo(position.toLong())
            // seek 后歌词位置失效, 重算 (对应 app 端 adjustProgress)
            manager.resetLrcPosition()
            manager.upPlayProgressForLrc()
        }
    }

    override fun setTimer(minute: Int) {
        if (!running) return
        scope.launch { sleepTimer?.set(minute) }
    }

    override fun addTimer() {
        ensureRunning()
        scope.launch { sleepTimer?.add() }
    }

    override fun loadPlayUrl() {
        ensureRunning()
        manager.loadPlayUrl()
    }

    // ===== Service 生命周期等价 =====

    /** 对应 AudioPlayService.onCreate */
    private fun ensureRunning() {
        if (running) return
        running = true
        pause = true
        hasRefreshedOnPlayError = false
        manager.playSpeed = playSpeed
        sleepTimer = SleepTimer(
            scope = scope,
            postMinute = { postEvent(EventBus.AUDIO_DS, it) },
            isPaused = { pause },
            onTimeout = { AudioPlayShared.stop() },
        )
        ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
        if (pendingTimerMinute > 0) {
            sleepTimer?.set(pendingTimerMinute)
            pendingTimerMinute = 0
        } else {
            postEvent(EventBus.AUDIO_DS, 0)
        }
    }

    /** 对应 AudioPlayService.onDestroy (stopSelf 等价) */
    private fun destroySelf() {
        running = false
        sleepTimer?.cancel()
        sleepTimer = null
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.AUDIO)
        AudioPlayShared.durChapterPos = controller.currentPosition.toInt()
        AudioPlayShared.saveRead()
        manager.onDestroy()
        controller.release()
        url = ""
        pause = true
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
    }

    /** 对应 AudioPlayService.triggerPlay */
    private suspend fun triggerPlay(playNew: Boolean) {
        if (url == AudioPlayShared.durPlayUrl && !playNew
            && controller.playbackState != AudioPlayController.STATE_IDLE
        ) return
        controller.stop()
        manager.cancelProgressJobs()
        pause = false
        position = if (playNew) 0 else AudioPlayShared.book?.durChapterPos ?: 0
        url = AudioPlayShared.durPlayUrl
        playMedia()
    }

    /** 对应 AudioPlayService.play(): 置 LOADING → 解析直链 headers → setSource+seek+prepare */
    private suspend fun playMedia() {
        try {
            AudioPlayShared.status = Status.LOADING
            postEvent(EventBus.AUDIO_STATE, Status.LOADING)
            manager.cancelProgressJobs()
            val analyzeUrl = MediaAnalyzeUrl(
                url,
                AudioPlayShared.bookSource,
                AudioPlayShared.book,
                AudioPlayShared.durChapter,
                currentCoroutineContext(),
            )
            val (mediaUrl, headers) = analyzeUrl.resolveMedia()
            controller.playWhenReady = true
            controller.setSource(mediaUrl, headers, position.toLong())
            controller.prepare()
        } catch (e: Exception) {
            AppLog.put("播放出错\n${e.message}", e)
            toast("$url ${e.message}")
            destroySelf()
        }
    }

    /** 对应 AudioPlayService.pause() */
    private fun pauseImpl() {
        runCatching {
            pause = true
            ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
            manager.cancelProgressJobs()
            position = controller.currentPosition.toInt()
            // 对齐 ExoPlayer.pause() 语义: 缓冲中暂停则就绪后不自动起播
            controller.playWhenReady = false
            if (controller.isPlaying) controller.pause()
            AudioPlayShared.status = Status.PAUSE
            postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
        }
    }

    /** 对应 AudioPlayService.resume() */
    private fun resumeImpl() {
        runCatching {
            pause = false
            ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlayShared.book?.name ?: "")
            if (url.isEmpty()) {
                AudioPlayShared.loadOrUpPlayUrl()
                return
            }
            controller.playWhenReady = true
            if (!controller.isPlaying) controller.play()
            manager.upPlayProgress()
            manager.upPlayProgressForLrc()
            AudioPlayShared.status = Status.PLAY
            postEvent(EventBus.AUDIO_STATE, Status.PLAY)
        }.onFailure {
            destroySelf()
        }
    }

    /** 对应 AudioPlayService.upSpeed() */
    private fun upSpeed(adjust: Float) {
        runCatching {
            playSpeed = adjust
            manager.playSpeed = adjust
            controller.setPlaybackSpeed(adjust)
            postEvent(EventBus.AUDIO_SPEED, adjust)
            // 变速后 lrc 推进 delay 需按新速率重算
            manager.upPlayProgressForLrc()
        }
    }

    // ===== AudioPlayControllerListener (对应 AudioPlayService.onPlaybackStateChanged) =====

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            AudioPlayController.STATE_IDLE,
            AudioPlayController.STATE_BUFFERING -> Unit

            AudioPlayController.STATE_READY -> {
                hasRefreshedOnPlayError = false
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlayShared.status =
                    if (controller.playWhenReady) Status.PLAY else Status.PAUSE
                postEvent(EventBus.AUDIO_STATE, AudioPlayShared.status)
                val duration = controller.duration
                // duration 未知 (流式未就绪) 时跳过, 避免把 chapter.end 冲成 0
                if (duration > 0) {
                    postEvent(EventBus.AUDIO_SIZE, duration.toInt())
                    AudioPlayShared.saveDurChapter(duration)
                }
                manager.upPlayProgress()
                manager.upPlayProgressForLrc()
            }

            AudioPlayController.STATE_ENDED -> {
                manager.cancelProgressJobs()
                AudioPlayShared.playPositionChanged(controller.duration.toInt())
                AudioPlayShared.next()
            }
        }
    }

    override fun onPlayerError(error: Throwable) {
        if (!hasRefreshedOnPlayError) {
            // 首次出错清 resourceUrl 重试 (对应 app 端 refreshChapter)
            hasRefreshedOnPlayError = true
            manager.refreshChapter()
            return
        }
        AudioPlayShared.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        val errorMsg = "音频播放出错\n${error.message}"
        AppLog.put(errorMsg, error)
        toast(errorMsg)
    }

    // ===== AudioPlayManagerListener =====

    override fun onTriggerPlay(playNew: Boolean) {
        scope.launch { triggerPlay(playNew) }
    }

    override fun onLoadCover(url: String?) {
        // iOS 无通知栏封面, AUDIO_COVER 事件已由 manager 推送 UI
    }

    override fun onResetCoverCache() {
        // 同上, 无平台封面缓存
    }

    override fun onToast(message: String) = toast(message)

    private fun toast(message: String) {
        runCatching { Toasters.get().toast(message) }
    }

    // ===== AudioPlayBookBridge (对应 app 端 BookExtensions, 直接走 DAO) =====

    override fun saveRead(book: Book) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                book.durChapterTime = systemCurrentTimeMillis()
                AppDbProviders.get().bookDao.updateProgress(
                    bookUrl = book.bookUrl,
                    durChapterIndex = book.durChapterIndex,
                    durChapterPos = book.durChapterPos,
                    durChapterTime = book.durChapterTime,
                    durChapterTitle = book.durChapterTitle,
                )
                ReadTimeRecorder.flushAll()
            }.onFailure { AppLog.put("保存阅读进度出错\n${it.message}", it) }
        }
    }

    override fun save(book: Book) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                book.removeType(BookType.notShelf)
                val dao = AppDbProviders.get().bookDao
                if (dao.has(book.bookUrl)) dao.update(book) else dao.insert(book)
            }.onFailure { AppLog.put("保存书籍出错\n${it.message}", it) }
        }
    }

    override fun getBookSource(book: Book): BookSource? = runBlocking {
        runCatching {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }.onFailure {
            AppLog.put("获取书源出错\n${it.message}", it)
        }.getOrNull()
    }
}

/**
 * [AudioPlayController] 的 iOS AVPlayer 实现 (对标 app 端 ExoPlayerAudioPlayController)。
 *
 * item.status 通过共享 [AvPlayerItemStatusObserver] KVO 观察，状态变化时立即回调。
 */
private class IosAvAudioPlayController(
    private val scope: CoroutineScope,
) : AudioPlayController {

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

    // AVPlayerItem.loadedTimeRanges 的 NSValue→CMTimeRange 桥接符号无法本机验证, 缓冲条恒 0 (与 desktop 一致)
    override val bufferedPosition: Long get() = 0L

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

/** 解析播放直链: setCookie 后取 url + headers (对应 app 端 AnalyzeUrl.getMediaItem, 剔除 cookieJar 伪头) */
private class MediaAnalyzeUrl(
    rawUrl: String,
    source: BookSource?,
    ruleData: Book?,
    chapter: BookChapter?,
    coroutineContext: CoroutineContext,
) : AnalyzeUrlCore(
    rawUrl,
    source = source,
    ruleData = ruleData,
    chapter = chapter,
    coroutineContext = coroutineContext,
) {
    fun resolveMedia(): Pair<String, Map<String, String>> {
        setCookie()
        val headers = LinkedHashMap(headerMap)
        headers.remove(cookieJarHeader)
        return url to headers
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
    val impl = IosAudioPlayCommander()
    AudioPlayCommanders.register(impl)
    AudioPlayBookBridges.register(impl)
}
