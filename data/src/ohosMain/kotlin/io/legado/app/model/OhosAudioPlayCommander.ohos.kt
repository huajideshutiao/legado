package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.SingleJobSlot
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.media.NowPlayingLyricSink
import io.legado.app.help.media.SystemMediaControl
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.writeBytesAtomically
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import io.legado.app.model.audio.AudioEnergyMeter
import io.legado.app.model.audio.AudioPlaySession
import io.legado.app.model.audio.LyricPublisher
import io.legado.app.model.audio.NowPlayingSessionHost
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.File
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.canonicalizeUri
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 流播 prepare 看门狗槽: 桥侧不认识 setSourceUrl 时不会有任何事件, 靠超时回退。
     */
    private val streamWatchdog = SingleJobSlot(scope)

    /**
     * 流播回退预下载槽: 换章或销毁时必须显式取消, 避免完成时将旧音频塞给新会话或已结束会话。
     *
     * 取消与重建分属不同线程 (会话命令线程 / 桥事件线程), 两次写的先后颠倒会留下无人能取消的
     * 下载协程 —— 它照样把旧音频塞给新会话。
     */
    private val downloadJob = SingleJobSlot(scope)

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
        streamWatchdog.cancel()
    }

    // ===== 流播 / 预下载回退 =====

    /**
     * 流播: 直链 + 请求头交桥侧 MediaSource, 免整段预下载。
     *
     * 挂起直到装载落定 —— 与桌面端同语义: 装载的所有权在调用方 (会话的起播 job) 上,
     * 会话终结/换章取消 job 时 await 随之作废, 不会留下"没人认领但仍会出声"的装载。
     */
    private suspend fun startStreaming(mediaUrl: String, headers: Map<String, String>) {
        streaming = true
        avController.setStreamSource(mediaUrl, headers, startPosMs)
        // 旧版桥不认识 setSourceUrl 时既无 onReady 也无 onError, 靠超时回退
        streamWatchdog.restart {
            delay(STREAM_PREPARE_TIMEOUT_MS)
            if (streaming) fallbackToDownload("流播准备超时")
        }
        avController.prepare()
    }

    /** 清理流播相关状态 (换章/销毁时调用) */
    private fun clearStreamingState() {
        streaming = false
        streamingFellBack = false
        streamWatchdog.cancel()
        downloadJob.cancel()
        resolvedUrl = null
        resolvedHeaders = emptyMap()
    }

    /** 回退整段预下载后本地起播 (对应旧实现的唯一路径) */
    private fun fallbackToDownload(reason: String) {
        if (streamingFellBack) return
        val mediaUrl = resolvedUrl ?: return
        streamingFellBack = true
        streaming = false
        streamWatchdog.cancel()
        AppLog.put("音频流播回退预下载: $reason")
        downloadJob.restart {
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
                session.abortPlayback()
            }
        }
    }

    // ===== 会话收尾 =====

    override fun onSessionEnd() {
        downloadJob.cancel()
        avController.release()
        deleteCacheFile()
        clearStreamingState()
        SystemMediaControl.releaseAudio()
    }

    /** 起播失败: 释放这次起播占用的系统音频会话; 鸿蒙没有可停的宿主 (缓存等会话级资源不在此撤)。 */
    override fun onHostStopRequest() {
        SystemMediaControl.releaseAudio()
    }

    // ===== 下载缓存 (桥仅支持本地 fd 源, 整份落盘契约同 OhosHttpTtsPlayer) =====

    /**
     * 整段下载音频并返回**完整**的缓存文件。
     *
     * 落盘走 [writeBytesAtomically] 的整份写入契约: 先写目标同目录的随机名临时件, 整份写完再改名
     * 到 `<hash>.audio`, 之后才把路径交给播放器。半截文件字节数非空, 按"路径存在即可播"交给
     * AVPlayer 会直接播到一半就断, 故最终名上不允许出现写了一半的内容。
     *
     * 最终名用不声称格式的 `.audio`: 直链音频的容器格式不定 (mp3/m4a/flac 皆有), 而桥侧按 fd
     * 装源 (`MediaBridgeHandler.ets`: `player.url = 'fd://' + fd`), 不靠扩展名判格式。
     *
     * 内存口径: 这一端拿不到流 —— ArkTS 侧把 dataReceive 的分段拼成完整 ArrayBuffer 后才回送
     * Kotlin (`HttpBridgeHandler.ets` 的 dataEnd → concatChunks), 响应体到 Kotlin 时已整份驻留,
     * byteStream() 只是它的包装; 故落盘即整份写, 不为内存另加缓冲层。
     *
     * 写不进/改名失败抛异常, 由 [fallbackToDownload] 的既有失败路径收尾; 临时件由契约在写失败与
     * 改名失败两条退出路径上删掉, 进程被强杀留下的那份随 [AUDIO_CACHE_DIR] 整目录回收。
     */
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
                val cacheDir = File("${DataStorageProviders.get().chapterCacheDir}/$AUDIO_CACHE_DIR")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val cacheFile = File(cacheDir, "${url.hashCode() and 0x7FFFFFFF}$AUDIO_CACHE_SUFFIX")
                check(writeBytesAtomically(cacheFile.path, response.body.bytes())) {
                    "音频缓存写入失败: ${cacheFile.path}"
                }
                cacheFile
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
        /**
         * 音频缓存子目录 (位于 NativeBookStorage.defaultRootPath 下)。
         *
         * 目录名不在书架缓存目录集合里, 每日启动清理
         * ([io.legado.app.help.book.BookHelpShared.clearInvalidBookFolders]) 整目录删除, 强杀残留的
         * 整份写入临时件随之回收 —— 不需要另写按时间清理的清理器。
         */
        private const val AUDIO_CACHE_DIR = "audio_play_cache"

        /** 缓存文件后缀: 直链音频格式不定, 用不声称格式的后缀 (桥按 fd 装源, 不按扩展名判格式) */
        private const val AUDIO_CACHE_SUFFIX = ".audio"

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

    /**
     * 音频能量: AVPlayer 定时上报的整体最大电平 (鸿蒙没有分频, 两段取同一值),
     * 归一到 0~1 驱动值后供歌词辉光呼吸。电平是比值运算, 上报值的整体缩放不影响结果。
     */
    private val energyMeter = AudioEnergyMeter()
    private var energyMark: TimeMark? = null

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

    /**
     * 本次装载的就绪信号: [prepare] await 它, 由 onReady/onError 事件唤醒。
     *
     * 调用方协程被取消时 await 随之取消 ([readySignal] 置空), 引擎由调用方
     * ([AudioPlaySession] 会话终结/换章) 显式 stop —— 与桌面端同语义。
     */
    @Volatile private var readySignal: CompletableDeferred<Unit>? = null

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

    /**
     * 装载媒体并挂起至就绪; 完成后由 onPlaybackStateChanged 通知。
     *
     * 挂起至装载落定 (onReady/onError), 随调用方协程取消 —— 装载的所有权归会话的起播 job,
     * 会话终结/换章时在途装载一并作废 (与桌面端 DesktopAudioPlayer.prepare 同语义)。
     * 就绪信号带超时兑底: 桥既不回 onReady 也不回 onError 时不能永久挂住会话 job。
     */
    override suspend fun prepare() {
        // ets 侧 setSource/setSourceUrl 内部完成 设源 + 创建 AVPlayer + prepare, 就绪回推 onReady
        val url = sourceUrl
        val path = sourcePath
        if (url == null && path == null) return
        state = AudioPlayController.STATE_BUFFERING
        val signal = CompletableDeferred<Unit>()
        readySignal = signal
        try {
            if (url != null) {
                sendCommand(
                    MediaCommand(
                        action = "setSourceUrl",
                        url = url.canonicalizeUri(),
                        headers = sourceHeaders.takeIf { it.isNotEmpty() },
                    )
                )
            } else {
                sendCommand(MediaCommand(action = "setSource", path = path!!))
            }
            // 超时后照旧返回: 就绪与否由事件驱动 (与未挂起前一致), 只是不再永久占住会话 job
            withTimeoutOrNull(PREPARE_TIMEOUT_MS) { signal.await() }
        } finally {
            // 流播超时回退预下载时会并发发起下一轮 prepare, 不得清掉它新设的信号
            if (readySignal === signal) readySignal = null
        }
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
        energyMark = null
        energyMeter.reset()
        AudioPlayShared.publishLevelOnly(0f)
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
                readySignal?.complete(Unit)
                listener?.onPlaybackStateChanged(AudioPlayController.STATE_READY)
            }

            "onEndOfMedia" -> {
                playing = false
                state = AudioPlayController.STATE_ENDED
                listener?.onPlaybackStateChanged(AudioPlayController.STATE_ENDED)
            }

            "onError" -> {
                playing = false
                // 唤醒装载 await (正常完成: 错误照旧经 listener 上报, 不走异常路径以免改变重试/回退语义)
                readySignal?.complete(Unit)
                listener?.onPlayerError(RuntimeException(event.message ?: "AVPlayer error"))
            }

            "onDuration" -> event.duration?.let { cachedDuration = it }

            "onPosition" -> event.position?.let { cachedPosition = it }

            "onBufferingUpdate" -> event.percent?.let { bufferedPercent = it }

            "onCachedDuration" -> event.cachedDuration?.let { cachedBufferedMs = it }

            "onAmplitude" -> event.amplitude?.let { onAmplitude(it) }

            "onPlaying" -> {
                playing = true
            }

            "onPaused" -> {
                playing = false
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
        /** 仅 onAmplitude: AVPlayer 上报的各声道最大电平值 */
        val amplitude: List<Double>? = null,
    )

    /**
     * 推送一次能量: 取最大声道电平推进自归一化驱动。
     * 上报间隔由系统决定, 时间差按上报时刻实际间隔算 (与派发节奏解耦)。
     */
    private fun onAmplitude(values: List<Double>) {
        val level = values.maxOrNull() ?: return
        val dtMs = energyMark?.elapsedNow()?.inWholeMilliseconds?.toDouble()
            ?: AMPLITUDE_DEFAULT_DT_MS
        energyMark = TimeSource.Monotonic.markNow()
        // 鸿蒙只给整体电平（线性幅度, 量纲待实机确认）: 换算成分贝后进统一响度模型
        val linear = level.coerceAtLeast(0.0)
        val energy = energyMeter.pushLevel(
            dtMs.coerceIn(1.0, AMPLITUDE_MAX_DT_MS),
            AudioEnergyMeter.linearToDb(linear),
        )
        AudioPlayShared.publishLevelOnly(energy)
    }

    private companion object {
        /** 装载 await 兑底超时: 桥既不回 onReady 也不回 onError 时不能永久占住会话的起播 job */
        private const val PREPARE_TIMEOUT_MS = 30_000L

        /** 首个能量上报的时间间隔 (ms): 没有上一次上报时刻时按它估 */
        private const val AMPLITUDE_DEFAULT_DT_MS = 100.0

        /** 能量上报的最大时间间隔 (ms): 上报稀疏时不让平滑系数算出异常值 */
        private const val AMPLITUDE_MAX_DT_MS = 500.0
    }
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
