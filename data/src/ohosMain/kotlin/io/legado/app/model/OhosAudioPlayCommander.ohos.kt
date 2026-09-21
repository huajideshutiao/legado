package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.NativeBookStorage
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
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
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.File
import io.legado.app.utils.KS_JSON
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 AudioPlay 宿主 (对标 app 端 AudioPlayService 的平台部分)。
 *
 * 会话状态机在 commonMain [AudioPlaySession] (四端共用), 播控卡片同步在
 * [NowPlayingSessionHost] (三端共用), 本类只做 napi Media 桥接入与流播/预下载取舍。
 * 播放原语经 MediaBridgeHandler.ets 的 AVPlayer 执行, 固定 playerId "audioBook" 独占一个实例,
 * 与网络朗读 (OhosHttpTtsPlayer, "httpTts") 可同时播放。
 * 网络直链默认走桥侧 setSourceUrl 流播 (MediaSource 可带请求头); 桥侧不支持该 action 时
 * 自动退回 Ktor 整段下载到缓存再播。桥未就绪时抛错让会话落 STOP, 不做静默假播放。
 */
class OhosAudioPlayCommander : NowPlayingSessionHost() {

    // 命令统一经 Main scope 串行派发 (等价 Service 主线程 onStartCommand)
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val avController = OhosAvAudioPlayController()

    override val controller: AudioPlayController get() = avController

    override val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory
        get() = OhosAudioPlayAnalyzeRuleFactory

    override var pendingTimerMinute: Int = 0

    /** 本轮起播位置 (流播失败回退预下载时复用) */
    @Volatile private var startPosMs = 0L

    /** 当前章节的下载缓存文件, 换章/销毁时删除 */
    @Volatile private var cacheFile: File? = null

    /** 本次播放已解析出的直链与请求头 (流播失败回退预下载时复用, 免重复解析) */
    @Volatile private var resolvedUrl: String? = null

    @Volatile private var resolvedHeaders: Map<String, String> = emptyMap()

    /** 当前是否处于流播尝试中 (onReady 前出错或超时则回退预下载) */
    @Volatile private var streaming = false

    /** 本章节是否已回退过预下载 (只退一次, 避免死循环) */
    @Volatile private var streamingFellBack = false

    /** 流播 prepare 看门狗: 桥侧不认识 setSourceUrl 时不会有任何事件, 靠超时回退 */
    private var streamWatchdog: Job? = null

    /** 流播回退预下载 Job: 换章或销毁时必须显式取消, 避免完成时将旧音频塞给新会话或已结束会话 */
    private var downloadJob: Job? = null

    /** 直链解析 → 优先流播, 桥侧无响应/出错时回退整段预下载 (见 [fallbackToDownload])。 */
    override suspend fun startPlayback(url: String, positionMs: Int) {
        clearStreamingState()
        startPosMs = positionMs.toLong()
        // 桥未就绪就抛, 交会话的起播异常收尾统一处理 (落 STOP + 提示), 不假播放
        if (!OhosNativeBridge.isMediaBridgeReady()) {
            throw IllegalStateException("napi media 桥未就绪, 无法播放音频")
        }
        val (mediaUrl, headers) = AnalyzeUrlFactories.create(
            rawUrl = url,
            source = AudioPlayShared.bookSource,
            ruleData = AudioPlayShared.book,
            chapter = AudioPlayShared.durChapter,
            coroutineContext = currentCoroutineContext(),
        ).resolveMedia()
        resolvedUrl = mediaUrl
        resolvedHeaders = headers
        startStreaming(mediaUrl, headers)
    }

    /** 流播阶段出错先退回预下载, 不惊动会话的重试/报错链路。 */
    override fun onPlayerErrorIntercept(error: Throwable): Boolean {
        if (!streaming) return false
        fallbackToDownload(error.message ?: "流播失败")
        return true
    }

    /** 流播已就绪, 撤看门狗。 */
    override fun onPlaybackReady() {
        streaming = false
        streamWatchdog?.cancel()
        streamWatchdog = null
    }

    // ===== 流播 / 预下载回退 =====

    /** 流播: 直链 + 请求头交桥侧 MediaSource, 免整段预下载 */
    private fun startStreaming(mediaUrl: String, headers: Map<String, String>) {
        streaming = true
        avController.setStreamSource(mediaUrl, headers, startPosMs)
        avController.prepare()
        // 旧版桥不认识 setSourceUrl 时既无 onReady 也无 onError, 靠超时回退
        streamWatchdog?.cancel()
        streamWatchdog = scope.launch {
            delay(STREAM_PREPARE_TIMEOUT_MS)
            if (streaming) fallbackToDownload("流播准备超时")
        }
    }

    /** 清理流播相关状态 (换章/销毁时调用) */
    private fun clearStreamingState() {
        streaming = false
        streamingFellBack = false
        streamWatchdog?.cancel()
        streamWatchdog = null
        downloadJob?.cancel()
        downloadJob = null
        resolvedUrl = null
        resolvedHeaders = emptyMap()
    }

    /** 回退整段预下载后本地起播 (对应旧实现的唯一路径) */
    private fun fallbackToDownload(reason: String) {
        if (streamingFellBack) return
        val mediaUrl = resolvedUrl ?: return
        streamingFellBack = true
        streaming = false
        streamWatchdog?.cancel()
        streamWatchdog = null
        AppLog.put("音频流播回退预下载: $reason")
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                avController.stop()
                val file = downloadToCache(mediaUrl, resolvedHeaders)
                swapCacheFile(file)
                // 缓冲期按过暂停则回退后不自动起播
                avController.playWhenReady = !session.isPaused
                avController.setSource(file.path, startPosMs)
                avController.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("播放出错\n${e.message}", e)
                toast("$mediaUrl ${e.message}")
                session.endSession()
            }
        }
    }

    // ===== 会话收尾 =====

    override fun onSessionEnd() {
        downloadJob?.cancel()
        downloadJob = null
        avController.release()
        deleteCacheFile()
        clearStreamingState()
        SystemMediaControl.releaseAudio()
    }

    // ===== 下载缓存 (桥仅支持本地 fd 源, 参考 OhosHttpTtsPlayer.downloadToTempFile) =====

    private suspend fun downloadToCache(url: String, headers: Map<String, String>): File =
        withContext(IoDispatcher) {
            val request = KmpRequestBuilder()
                .url(url)
                .get()
                .apply {
                    headers.forEach { (name, value) -> addHeader(name, value) }
                }
                .build()
            val response = OkHttpClientProviders.get().okHttpClient.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $url")
                }
                val cacheDir = File("${NativeBookStorage.defaultRootPath()}/$AUDIO_CACHE_DIR")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val tmpFile = File(cacheDir, "${url.hashCode() and 0x7FFFFFFF}.tmp")
                tmpFile.writeBytes(response.body.bytes())
                tmpFile
            } finally {
                response.close()
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

    private companion object {
        /** 音频缓存子目录 (位于 NativeBookStorage.defaultRootPath 下) */
        private const val AUDIO_CACHE_DIR = "audio_play_cache"

        /** 流播 prepare 看门狗超时: 超时未 onReady/onError 视为桥不支持 setSourceUrl, 回退预下载 */
        private const val STREAM_PREPARE_TIMEOUT_MS = 15_000L
    }
}

/**
 * [AudioPlayController] 的鸿蒙实现: 命令经 napi 桥 (tsfn) 发给 MediaBridgeHandler.ets 的
 * AVPlayer, 状态由 @CName legado_media_event 事件回推并缓存 (同 OhosHttpTtsPlayer 模式)。
 *
 * 命令/事件均带固定 playerId "audioBook", 桥侧按 id 独占 AVPlayer 实例,
 * 与 OhosHttpTtsPlayer ("httpTts") 可同时播放。
 * 源支持两种: [setStreamSource] 网络流播 (setSourceUrl) / [setSource] 本地缓存 (setSource)。
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

    /** ArkTS onBufferingUpdate 事件缓存的缓冲百分比 (起播缓冲进度, 只用于加载态判定) */
    @Volatile private var bufferedPercent = 0

    /** 已缓存时长 ms (ArkTS onCachedDuration 推送 = AVPlayer CACHED_DURATION 档) */
    @Volatile private var cachedBufferedMs = 0L

    @Volatile private var speed = 1f

    /** prepare 就绪后再应用的起播位置 (对应 app 端 setMediaItem 后的 seekTo(position)) */
    @Volatile private var pendingSeekMs = 0L

    /** 待 prepare 的本地缓存文件路径 (与 [sourceUrl] 互斥) */
    @Volatile private var sourcePath: String? = null

    /** 待 prepare 的网络流地址 (与 [sourcePath] 互斥) */
    @Volatile private var sourceUrl: String? = null

    /** 流播请求头 (setSourceUrl 时随命令下发) */
    @Volatile private var sourceHeaders: Map<String, String> = emptyMap()

    @Volatile private var listenerRegistered = false

    override var playWhenReady = false

    override val isPlaying: Boolean get() = playing

    override val duration: Long get() = cachedDuration

    override val currentPosition: Long get() = cachedPosition

    /**
     * 已缓冲到的时间点 (进度条缓冲层用)。
     *
     * AVPlayer 的 CACHED_DURATION 档给的是"已缓存时长", 从当前播放位置往后算, 换成绝对
     * 时间点才能画进度条; 不用 [bufferedPercent] —— 那是起播缓冲进度, 缓冲完成后恒 100,
     * 折算成时长就是一条永远铺满的假缓冲条 (与视频端 OhosVideoPlayerController 同实现)。
     */
    override val bufferedPosition: Long
        get() {
            if (cachedBufferedMs <= 0L) return 0L
            val end = cachedPosition + cachedBufferedMs
            return if (cachedDuration > 0L) end.coerceAtMost(cachedDuration) else end
        }

    override val playbackState: Int get() = state

    /** 设置本地缓存源, startPosMs 就绪后生效 */
    fun setSource(localPath: String, startPosMs: Long) {
        ensureListener()
        sourcePath = localPath
        sourceUrl = null
        sourceHeaders = emptyMap()
        resetPlaybackCache(startPosMs)
    }

    /** 设置网络流播源 (直链 + 请求头), startPosMs 就绪后生效 */
    fun setStreamSource(url: String, headers: Map<String, String>, startPosMs: Long) {
        ensureListener()
        sourceUrl = url
        sourceHeaders = headers
        sourcePath = null
        resetPlaybackCache(startPosMs)
    }

    private fun ensureListener() {
        if (!listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_AUDIO_BOOK, this)
            listenerRegistered = true
        }
    }

    private fun resetPlaybackCache(startPosMs: Long) {
        pendingSeekMs = startPosMs
        playing = false
        cachedDuration = 0L
        cachedPosition = 0L
        bufferedPercent = 0
        cachedBufferedMs = 0L
        state = AudioPlayController.STATE_IDLE
    }

    override fun prepare() {
        // ets 侧 setSource/setSourceUrl 内部完成 设源 + 创建 AVPlayer + prepare, 就绪回推 onReady
        val url = sourceUrl
        if (url != null) {
            state = AudioPlayController.STATE_BUFFERING
            sendCommand(
                MediaCommand(
                    action = "setSourceUrl",
                    url = url,
                    headers = sourceHeaders.takeIf { it.isNotEmpty() },
                )
            )
            return
        }
        val path = sourcePath ?: return
        state = AudioPlayController.STATE_BUFFERING
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
        cachedBufferedMs = 0L
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
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_AUDIO_BOOK, null)
            listenerRegistered = false
        }
        sourcePath = null
        sourceUrl = null
        sourceHeaders = emptyMap()
        pendingSeekMs = 0L
        playing = false
        cachedDuration = 0L
        cachedPosition = 0L
        bufferedPercent = 0
        cachedBufferedMs = 0L
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

            "onCachedDuration" -> event.cachedDuration?.let { cachedBufferedMs = it }

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

    /** 统一盖上固定 playerId 后经 tsfn 下发 (集中处理, 各命令构造处无需重复传) */
    private fun sendCommand(cmd: MediaCommand) {
        val stamped = cmd.copy(playerId = OhosNativeBridge.PLAYER_ID_AUDIO_BOOK)
        OhosNativeBridge.sendMediaCommand(KS_JSON.encodeToString(MediaCommand.serializer(), stamped))
    }

    /**
     * media 命令 (Kotlin → ArkTS, 与 MediaBridgeHandler.ets 协议对齐)。
     * playerId 默认空串仅为构造便利, [sendCommand] 必改写为 PLAYER_ID_AUDIO_BOOK
     * (非默认值, 不受 KS_JSON encodeDefaults=false 影响, 必然编码)。
     */
    @Serializable
    private data class MediaCommand(
        val action: String,
        val playerId: String = "",
        val path: String? = null,
        val url: String? = null,
        val headers: Map<String, String>? = null,
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
        val cachedDuration: Long? = null,
        val position: Long? = null,
    )
}

/** [AudioPlayAnalyzeRuleFactory] 的鸿蒙实现: 经 [AnalyzeRuleFactories] 创建 (同 desktop) */
private object OhosAudioPlayAnalyzeRuleFactory : AudioPlayAnalyzeRuleFactory {

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
 * 鸿蒙宿主启动早期注册 AudioPlay 平台 provider (对标 registerDesktopAudioPlayProviders)。
 * 须在 AppDbProviders / JsEngines / 网络 provider 注册之后调用。
 * Media 桥已按 playerId 多实例, 与 OhosHttpTtsPlayer (网络朗读) 可同时播放。
 */
fun registerOhosAudioPlayCommanders() {
    AudioPlayCommanders.register(OhosAudioPlayCommander().session)
    LyricPublisher.register(NowPlayingLyricSink)
}
