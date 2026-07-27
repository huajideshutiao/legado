package io.legado.app.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.NativeBookStorage
import io.legado.app.help.book.removeType
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.media.SleepTimer
import io.legado.app.help.toast.Toasters
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import io.legado.app.model.audio.AudioPlayManager
import io.legado.app.model.audio.AudioPlayManagerListener
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 AudioPlay 平台 provider (对标 app 端 AudioPlayService + AudioPlayProvidersImpl)。
 *
 * 编排层复用 commonMain [AudioPlayManager], 播放原语经 napi Media 桥
 * (MediaBridgeHandler.ets 的 AVPlayer) 执行。桥仅支持本地 fd 源, 网络直链先经
 * Ktor 整段下载到缓存再播 (与 OhosHttpTtsPlayer 同策略, 非流式为已知差异)。
 * 桥未就绪时置 STOP + toast 报错, 不做静默假播放。
 */
class OhosAudioPlayCommander : AudioPlayCommander, AudioPlayBookBridge,
    AudioPlayControllerListener, AudioPlayManagerListener {

    // 命令统一经 Main scope 串行派发 (等价 Service 主线程 onStartCommand)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller = OhosAvAudioPlayController()
    private val manager = AudioPlayManager(controller, scope, OhosAudioPlayAnalyzeRuleFactory, this)

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

    /** 当前章节的下载缓存文件, 换章/销毁时删除 */
    @Volatile private var cacheFile: File? = null

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
            this@OhosAudioPlayCommander.position = position
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
        deleteCacheFile()
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

    /** 对应 AudioPlayService.play(): 置 LOADING → 解析直链 headers → 下载缓存 → setSource+seek+prepare */
    private suspend fun playMedia() {
        try {
            AudioPlayShared.status = Status.LOADING
            postEvent(EventBus.AUDIO_STATE, Status.LOADING)
            manager.cancelProgressJobs()
            // 桥未就绪: 报错落 STOP, 不假播放 (catch 分支统一处理)
            if (!OhosNativeBridge.isMediaBridgeReady()) {
                throw IllegalStateException("napi media 桥未就绪, 无法播放音频")
            }
            val analyzeUrl = MediaAnalyzeUrl(
                url,
                AudioPlayShared.bookSource,
                AudioPlayShared.book,
                AudioPlayShared.durChapter,
                currentCoroutineContext(),
            )
            val (mediaUrl, headers) = analyzeUrl.resolveMedia()
            val file = downloadToCache(mediaUrl, headers)
            swapCacheFile(file)
            controller.playWhenReady = true
            controller.setSource(file.path, position.toLong())
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

    /** 对应 AudioPlayService.upSpeed() (桥侧倍速就近吸附到 0.75/1/1.25/1.75/2 档) */
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
                // durationUpdate 事件未到时跳过, 避免把 chapter.end 冲成 0
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
        // 鸿蒙无通知栏封面, AUDIO_COVER 事件已由 manager 推送 UI
    }

    override fun onResetCoverCache() {
        // 同上, 无平台封面缓存
    }

    override fun onToast(message: String) = toast(message)

    private fun toast(message: String) {
        runCatching { Toasters.get().toast(message) }
    }

    // ===== 下载缓存 (桥仅支持本地 fd 源, 参考 OhosHttpTtsPlayer.downloadToTempFile) =====

    private suspend fun downloadToCache(url: String, headers: Map<String, String>): File =
        withContext(Dispatchers.IO) {
            val client = HttpClient(CIO)
            try {
                val response = client.get(url) {
                    headers.forEach { (k, v) -> header(k, v) }
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value}: $url")
                }
                val bytes = response.bodyAsBytes()
                val cacheDir = File("${NativeBookStorage.defaultRootPath()}/$AUDIO_CACHE_DIR")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val tmpFile = File(cacheDir, "${url.hashCode() and 0x7FFFFFFF}.tmp")
                tmpFile.writeBytes(bytes)
                tmpFile
            } finally {
                client.close()
            }
        }

    /** 换章后删除上一章缓存, 避免磁盘累积 */
    private fun swapCacheFile(file: File) {
        val old = cacheFile
        if (old != null && old.path != file.path) {
            runCatching { if (old.exists()) old.delete() }
        }
        cacheFile = file
    }

    private fun deleteCacheFile() {
        cacheFile?.let { file ->
            runCatching { if (file.exists()) file.delete() }
            cacheFile = null
        }
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

    private companion object {
        /** 音频缓存子目录 (位于 NativeBookStorage.defaultRootPath 下) */
        private const val AUDIO_CACHE_DIR = "audio_play_cache"
    }
}

/**
 * [AudioPlayController] 的鸿蒙实现: 命令经 napi 桥 (tsfn) 发给 MediaBridgeHandler.ets 的
 * AVPlayer, 状态由 @CName legado_media_event 事件回推并缓存 (同 OhosHttpTtsPlayer 模式)。
 *
 * 注意: OhosNativeBridge.mediaEventListener 是单槽位, 与 OhosHttpTtsPlayer 互斥,
 * 音频书与网络朗读不可同时播放 (协议缺口, 见注册函数注释)。
 */
private class OhosAvAudioPlayController : AudioPlayController, OhosNativeBridge.MediaEventListener {

    override var listener: AudioPlayControllerListener? = null

    /** 由 onPlaying/onPaused 事件维护 */
    @Volatile private var playing = false

    @Volatile private var state = AudioPlayController.STATE_IDLE

    /** ArkTS onDuration 事件缓存 (未知 0, 与 controller contract 一致) */
    @Volatile private var cachedDuration = 0L

    /** ArkTS onPosition (timeUpdate) 事件缓存; seek 时乐观更新 */
    @Volatile private var cachedPosition = 0L

    /** ArkTS onBufferingUpdate 事件缓存的缓冲百分比 */
    @Volatile private var bufferedPercent = 0

    @Volatile private var speed = 1f

    /** prepare 就绪后再应用的起播位置 (对应 app 端 setMediaItem 后的 seekTo(position)) */
    @Volatile private var pendingSeekMs = 0L

    /** 待 prepare 的本地缓存文件路径 */
    @Volatile private var sourcePath: String? = null

    @Volatile private var listenerRegistered = false

    override var playWhenReady = false

    override val isPlaying: Boolean get() = playing

    override val duration: Long get() = cachedDuration

    override val currentPosition: Long get() = cachedPosition

    override val bufferedPosition: Long
        get() = if (cachedDuration > 0) cachedDuration * bufferedPercent / 100 else 0L

    override val playbackState: Int get() = state

    /** 设置本地缓存源, startPosMs 就绪后生效 */
    fun setSource(localPath: String, startPosMs: Long) {
        // 单槽位事件监听, setSource 时抢注 (与 OhosHttpTtsPlayer 互斥, 见类注释)
        if (!listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(this)
            listenerRegistered = true
        }
        sourcePath = localPath
        pendingSeekMs = startPosMs
        playing = false
        cachedDuration = 0L
        cachedPosition = 0L
        bufferedPercent = 0
        state = AudioPlayController.STATE_IDLE
    }

    override fun prepare() {
        val path = sourcePath ?: return
        state = AudioPlayController.STATE_BUFFERING
        // ets 侧 setSource 内部完成 打开 fd + 创建 AVPlayer + prepare, 就绪回推 onReady
        sendCommand(MediaCommand(action = "setSource", path = path))
    }

    override fun play() {
        sendCommand(MediaCommand(action = "play"))
        if (speed != 1f) sendCommand(MediaCommand(action = "setSpeed", speed = speed))
    }

    override fun pause() {
        sendCommand(MediaCommand(action = "pause"))
    }

    override fun stop() {
        sendCommand(MediaCommand(action = "stop"))
        playing = false
        state = AudioPlayController.STATE_IDLE
    }

    override fun seekTo(position: Long) {
        if (state == AudioPlayController.STATE_READY || state == AudioPlayController.STATE_ENDED) {
            sendCommand(MediaCommand(action = "seekTo", position = position))
            // 乐观更新, 否则 1s 进度循环在 timeUpdate 事件到达前读到旧值
            cachedPosition = position
        } else {
            // 未就绪时暂存, 就绪后统一应用
            pendingSeekMs = position
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        sendCommand(MediaCommand(action = "setSpeed", speed = speed))
    }

    override fun release() {
        sendCommand(MediaCommand(action = "release"))
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(null)
            listenerRegistered = false
        }
        sourcePath = null
        pendingSeekMs = 0L
        playing = false
        cachedDuration = 0L
        cachedPosition = 0L
        bufferedPercent = 0
        state = AudioPlayController.STATE_IDLE
    }

    // ===== OhosNativeBridge.MediaEventListener =====

    override fun onMediaEvent(eventJson: String) {
        val event = runCatching {
            KS_JSON.decodeFromString(MediaEvent.serializer(), eventJson)
        }.getOrNull() ?: return

        when (event.event) {
            "onReady" -> {
                if (pendingSeekMs > 0) {
                    sendCommand(MediaCommand(action = "seekTo", position = pendingSeekMs))
                    cachedPosition = pendingSeekMs
                    pendingSeekMs = 0L
                }
                state = AudioPlayController.STATE_READY
                if (playWhenReady) play()
                listener?.onPlaybackStateChanged(AudioPlayController.STATE_READY)
            }

            "onEndOfMedia" -> {
                playing = false
                state = AudioPlayController.STATE_ENDED
                listener?.onPlaybackStateChanged(AudioPlayController.STATE_ENDED)
            }

            "onError" -> {
                playing = false
                listener?.onPlayerError(RuntimeException(event.message ?: "AVPlayer error"))
            }

            "onDuration" -> event.duration?.let { cachedDuration = it }

            "onPosition" -> event.position?.let { cachedPosition = it }

            "onBufferingUpdate" -> event.percent?.let { bufferedPercent = it }

            "onPlaying" -> {
                playing = true
                listener?.onIsPlayingChanged(true)
            }

            "onPaused" -> {
                playing = false
                listener?.onIsPlayingChanged(false)
            }
        }
    }

    private fun sendCommand(cmd: MediaCommand) {
        OhosNativeBridge.sendMediaCommand(KS_JSON.encodeToString(MediaCommand.serializer(), cmd))
    }

    /** media 命令 (Kotlin → ArkTS, 与 MediaBridgeHandler.ets 协议对齐) */
    @Serializable
    private data class MediaCommand(
        val action: String,
        val path: String? = null,
        val position: Long? = null,
        val speed: Float? = null,
    )

    /** media 事件 (ArkTS → Kotlin, 与 MediaBridgeHandler.ets 协议对齐) */
    @Serializable
    private data class MediaEvent(
        val event: String,
        val message: String? = null,
        val percent: Int? = null,
        val duration: Long? = null,
        val position: Long? = null,
    )
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

/** [AudioPlayAnalyzeRuleFactory] 的鸿蒙实现: 直接用 commonMain AnalyzeRuleCore (同 desktop) */
private object OhosAudioPlayAnalyzeRuleFactory : AudioPlayAnalyzeRuleFactory {

    override fun create(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        coroutineContext: CoroutineContext,
    ): AnalyzeRuleCore {
        return AnalyzeRuleCore(book, bookSource).apply {
            this.coroutineContext = coroutineContext
            setBaseUrl(chapter.url)
            this.chapter = chapter
        }
    }
}

/**
 * 鸿蒙宿主启动早期注册 AudioPlay 平台 provider (对标 registerDesktopAudioPlayProviders)。
 * 须在 AppDbProviders / JsEngines / 网络 provider 注册之后调用。
 * 已知缺口: Media 桥单实例单监听, 与 OhosHttpTtsPlayer (网络朗读) 互斥。
 */
fun registerOhosAudioPlayCommanders() {
    val impl = OhosAudioPlayCommander()
    AudioPlayCommanders.register(impl)
    AudioPlayBookBridges.register(impl)
}
