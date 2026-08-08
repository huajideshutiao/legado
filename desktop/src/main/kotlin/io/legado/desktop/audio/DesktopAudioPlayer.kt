package io.legado.desktop.audio

import io.legado.app.help.http.OkHttpClientProviders
import javazoom.jl.decoder.Bitstream
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 桌面端音频播放器 (jlayer MP3 解码 + javax.sound.sampled 输出)。
 *
 * 对应 app 端 [io.legado.app.service.AudioPlayService] 的 ExoPlayer 部分:
 * - 解码: jlayer AdvancedPlayer (内部 Bitstream + Decoder + JavaSoundAudioDevice)
 * - 输出: JavaSoundAudioDevice 自动 open SourceDataLine (JDK 内置 javax.sound.sampled)
 * - 网络: OkHttpClientProviders 注入的 OkHttpClient (与 Android 端共用拦截器/SSL)
 *
 * # 与 app 端 ExoPlayer 行为对照
 * - play/pause/stop: 通过 new AdvancedPlayer + stop() 实现 pause/resume
 *   (AdvancedPlayer 是阻塞模型, stop 后不可复用, resume 需重新 new)
 * - seek: MP3 不支持随机 seek, 通过重新 OkHttp 请求 + skipFrames(targetFrame) 跳帧实现
 *   (开销大于 ExoPlayer SimpleCache, 但桌面端 seek 不频繁, 可接受)
 * - speed: [SonicAudioDevice] 在 jlayer PCM 输出层插入 Sonic 变速 (保音高),
 *   与 app 端 ExoPlayer.setPlaybackParameters 行为一致; speed 同时用于 currentPosition 估算
 * - duration: 从第一帧 MP3 Header + HTTP Content-Length 估算 (bitrate 反推)
 * - position: 用墙钟估算 (playbackStartMs + speed), 不依赖 jlayer 内部 frame 计数
 *
 * # 线程模型
 * - prepare/play 各启动 daemon 线程, 不阻塞 Compose 主线程
 * - listener 回调从工作线程触发, 调用方如需主线程访问需自行 invokeLater
 *
 * 参考 [io.legado.desktop.tts.DesktopHttpTtsPlayer] 的 javax.sound.sampled 使用模式。
 */
class DesktopAudioPlayer {

    /** 播放事件回调, 由 [DesktopAudioPlayProvider] 注册接收 */
    interface Listener {
        /** prepare 完成, 可以开始 play; [durationMs] 为估算总时长, -1 表示未知 */
        fun onReady(durationMs: Long) {}

        /** 流自然播放完毕 (对应 ExoPlayer STATE_ENDED) */
        fun onEndOfMedia() {}

        /** 播放出错 */
        fun onError(message: String?) {}
    }

    // ===== 状态字段 (volatile 供跨线程读) =====

    @Volatile private var url: String? = null
    @Volatile private var headers: Map<String, String> = emptyMap()
    @Volatile private var listenerField: Listener? = null

    /** 是否在播放中 (含 pause 状态); 真实播放 = playing && !paused */
    @Volatile private var playing: Boolean = false
    @Volatile private var paused: Boolean = false
    @Volatile private var released: Boolean = false

    @Volatile private var response: Response? = null
    /** InputStream 包装 OkHttp body stream, 支持 mark/reset (seek 时回退到开头跳帧) */
    @Volatile private var rawStream: InputStream? = null
    @Volatile private var advancedPlayer: AdvancedPlayer? = null
    @Volatile private var audioDevice: SonicAudioDevice? = null

    @Volatile private var playbackThread: Thread? = null

    /** prepare 协程 (超时/取消可控; 取代原不可中断的阻塞线程) */
    private val prepareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var prepareJob: Job? = null

    /** prepare 代次: 新 prepare 使旧 job 的迟到 onError 失效 (避免误杀新章节的加载态) */
    private val prepareGeneration = AtomicInteger(0)

    /** HTTP Content-Length, 用于估算 duration; -1 未知 */
    @Volatile private var contentLength: Long = -1L

    /** 总时长 (毫秒), 从第一帧 Header + ContentLength 估算; -1 未知 */
    @Volatile private var durationMs: Long = -1L

    /** MP3 每帧毫秒数 (MPEG1 Layer3 ≈ 26.122ms), 从第一帧 Header 取 */
    @Volatile private var msPerFrameMs: Float = 26.122f

    /** 播放速率 (1f = 原速); 持久化以便 resume 时重新 apply */
    @Volatile private var speed: Float = 1f

    /** 当前播放起始帧 (用于 currentPosition 估算) */
    @Volatile private var playbackStartFrame: Int = 0

    /** 当前播放起始墙钟 (用于 currentPosition 估算) */
    @Volatile private var playbackStartMs: Long = 0L

    // ===== 公开属性 =====

    val isPlaying: Boolean
        get() = playing && !paused

    val duration: Long
        get() = durationMs

    /**
     * 当前播放位置 (毫秒)。用墙钟估算:
     * - 未播放: 返回 playbackStartFrame 对应位置 (pause/stop 后保留)
     * - 播放中: playbackStartFrame + (now - playbackStartMs) * speed
     */
    val currentPosition: Long
        get() {
            val msPerFrame = msPerFrameMs
            if (!playing || paused) {
                return (playbackStartFrame.toLong() * msPerFrame).toLong()
            }
            val elapsed = (System.currentTimeMillis() - playbackStartMs) * speed
            return ((playbackStartFrame * msPerFrame) + elapsed).toLong()
        }

    var listener: Listener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== 控制方法 =====

    /**
     * 设置播放源 (URL + headers)。切换源前会清空上一次状态。
     * 不立即拉流, 等待 [prepare] 触发。
     */
    fun setUrl(url: String, headers: Map<String, String>) {
        if (released) return
        stopInternal(clearState = true)
        this.url = url
        this.headers = headers
        this.playbackStartFrame = 0
        this.playbackStartMs = System.currentTimeMillis()
        this.durationMs = -1L
        this.contentLength = -1L
    }

    /**
     * 后台启动 HTTP 拉流 + 解析首帧估算 duration, 完成后回调 [Listener.onReady]。
     * 协程化: withTimeout + call.cancel() 保证直链挂起时不残留 LOADING。
     */
    fun prepare() {
        if (released) return
        val theUrl = url ?: run {
            listener?.onError("no url set")
            return
        }
        prepareJob?.cancel()
        val gen = prepareGeneration.incrementAndGet()
        prepareJob = prepareScope.launch {
            try {
                withTimeout(PREPARE_TIMEOUT_MS) { prepareInternal(theUrl) }
            } catch (e: TimeoutCancellationException) {
                // call.cancel() 已中断连接; 释放响应, 收掉加载态避免转圈残留
                runCatching { response?.close() }
                if (!released && gen == prepareGeneration.get()) {
                    listener?.onError("prepare timeout")
                }
            } catch (e: CancellationException) {
                throw e // 主动取消 (换章/停止): 由新流程接管, 不报错
            } catch (e: Exception) {
                if (!released && gen == prepareGeneration.get()) {
                    listener?.onError("prepare failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun prepareInternal(theUrl: String) {
        // 关闭上次响应 (若有)
        try { response?.close() } catch (_: Exception) {}
        response = null
        rawStream = null

        val client = OkHttpClientProviders.get().okHttpClient
        val builder = Request.Builder().url(theUrl)
        headers.forEach { (k, v) -> builder.header(k, v) }
        // 异步 enqueue + suspendCancellableCoroutine: withTimeout 超时/换章取消时
        // invokeOnCancellation → call.cancel() 真正中断连接 (同步 execute 无法取消)
        val call = client.newCall(builder.build())
        val resp = try {
            suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response)
                    }
                })
            }
        } catch (e: IOException) {
            listener?.onError("HTTP connect failed: ${e.message}")
            return
        }
        if (!resp.isSuccessful) {
            listener?.onError("HTTP ${resp.code}: ${resp.message}")
            resp.close()
            return
        }
        val body = resp.body
        // NonClosingInputStream 包装: 防止 jlayer Bitstream/AdvancedPlayer close 误关底层 OkHttp 流
        // (release() 里 response.close() 才是真正的关闭点)
        val stream = NonClosingInputStream(BufferedInputStream(body.byteStream(), BUFFER_SIZE))
        synchronized(this) {
            response = resp
            rawStream = stream
            contentLength = body.contentLength()
        }

        // peek 第一帧 Header 估算 duration (不消耗流, 用 mark/reset 回退)
        // 使用 jlayer 确定 public 的 Header API (frequency/version/framesize),
        // 避免 master fork 才有的 bitrate/ms_per_frame() (1.0.1 不一定有)
        // 超时/取消后不再上报 ready (流已被 call.cancel() 中断);
        // peek 前再检查一次取消, 避免取消已发生仍进入阻塞读
        coroutineContext.ensureActive()
        try {
            stream.mark(BUFFER_SIZE)
            val bitstream = Bitstream(stream)
            val header = bitstream.readFrame()
            if (header != null) {
                val freq = header.frequency() // Hz (如 44100)
                if (freq > 0) {
                    // MPEG1(version==1) Layer3: 1152 samples/frame;
                    // MPEG2/2.5(version==0/2) Layer3: 576 samples/frame
                    val samplesPerFrame = if (header.version() == 1) 1152 else 576
                    msPerFrameMs = samplesPerFrame.toFloat() / freq * 1000f
                    // bitrate 反推: framesize ≈ 144 * bitrate / freq (Layer3 CBR 近似)
                    // → bitrate = framesize * freq / 144
                    val frameSizeBytes = header.framesize // public 字段
                    if (frameSizeBytes > 0 && contentLength > 0) {
                        val bitrate = frameSizeBytes.toLong() * freq / 144 // bits per second
                        if (bitrate > 0) {
                            val seconds = contentLength * 8L / bitrate
                            durationMs = seconds * 1000L
                        }
                    }
                }
            }
            // 不调 bitstream.close(): 它会关闭底层 stream; NonClosingInputStream 已保护,
            // 但 close 仍可能破坏 Bitstream 引用的流位置; 直接 reset 到 mark 即可
            stream.reset()
        } catch (e: Exception) {
            // Header 解析失败不阻断播放, 仅 duration 未知; reset 流到 mark 位置
            try { stream.reset() } catch (_: Exception) {}
        }

        // 超时/取消后不再上报 ready (流已被 call.cancel() 中断)
        coroutineContext.ensureActive()
        listener?.onReady(durationMs)
    }

    /**
     * 开始播放。若处于 paused 状态则恢复; 否则从头/或 seekTo 设置的位置开始。
     * 对应 app 端 ExoPlayer.play() / resume()。
     */
    fun play() {
        if (released) return
        if (paused) {
            // 从暂停恢复: stream 位置保留, new AdvancedPlayer 从当前位置继续
            paused = false
            playing = true
            startPlaybackLoop(fromFrame = playbackStartFrame, isResume = true)
            return
        }
        if (playing) return
        playing = true
        startPlaybackLoop(fromFrame = playbackStartFrame, isResume = false)
    }

    /**
     * 启动后台播放线程。
     *
     * @param fromFrame 起始帧 (用于估算 currentPosition; jlayer play 内部自己 skip)
     * @param isResume true 表示从暂停恢复 (stream 位置已对, 不需重新请求);
     *                 false 表示首次播放或 seek 后 (stream 已 prepare/重新请求过)
     */
    private fun startPlaybackLoop(fromFrame: Int, isResume: Boolean) {
        playbackStartFrame = fromFrame
        playbackStartMs = System.currentTimeMillis()
        playbackThread = Thread({
            try {
                val stream = rawStream ?: run {
                    listener?.onError("stream not ready")
                    return@Thread
                }
                if (!isResume) {
                    // 首次播放或 seek 后: stream 已在 prepare 时 reset 到开头
                    // 若 fromFrame > 0 (seek), 需要 reset 回开头再跳帧
                    try { stream.reset() } catch (_: Exception) {}
                }
                // resume 时 stream 位置已对, 不能 reset (会回到开头跳过已播放部分)
                // SonicAudioDevice 在 PCM 输出层做保音高变速; 建线程时就带上当前 speed, 避免与 setSpeed 竞争
                val device = SonicAudioDevice().apply { speed = this@DesktopAudioPlayer.speed }
                val player = AdvancedPlayer(stream, device)
                player.setPlayBackListener(object : PlaybackListener() {
                    override fun playbackStarted(evt: PlaybackEvent?) {
                        // jlayer 在 play 开始时回调, evt.frame 通常是 fromFrame
                    }

                    override fun playbackFinished(evt: PlaybackEvent?) {
                        // playbackFinished 在 stop() 或自然结束时触发
                        // evt.frame 是本次 play 调用期间已播放的帧数 (相对值)
                        // 仅在自然结束时更新 playbackStartFrame (stop 不更新, 保留 pause 时的位置)
                    }
                })
                synchronized(this) {
                    advancedPlayer = player
                    audioDevice = device
                }
                // play(fromFrame, end): 内部会 skipFrames(fromFrame) 再播放
                // resume 时 fromFrame 传 0 (stream 位置已对, 不再 skip)
                val startFrame = if (isResume) 0 else fromFrame
                val playedToEnd = player.play(startFrame, Int.MAX_VALUE)
                // play 返回表示播放结束 (自然结束或被 stop)
                if (playedToEnd && !released && !paused && playing) {
                    // 自然结束
                    playing = false
                    paused = false
                    playbackStartFrame = 0
                    listener?.onEndOfMedia()
                }
            } catch (e: Exception) {
                if (!released) {
                    listener?.onError("play error: ${e.message}")
                }
            }
        }, "DesktopAudioPlayer-playback").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 暂停播放。对应 app 端 ExoPlayer.pause()。
     * 调用 [AdvancedPlayer.stop] 触发 playbackFinished, stream 位置保留。
     */
    fun pause() {
        if (released) return
        if (!playing) return
        // 先估算当前位置再置 paused: estimateCurrentFrame 依赖 !paused 才能累加已播帧,
        // 顺序反了会把 playbackStartFrame 重置回旧值 → resume 后进度从 0 起算
        playbackStartFrame = estimateCurrentFrame()
        paused = true
        try {
            advancedPlayer?.stop()
        } catch (_: Exception) {
        }
    }

    /** 停止播放并清空内部 player (保留 stream/url, 可重新 play)。对应 app 端 ExoPlayer.stop()。 */
    fun stop() {
        stopInternal(clearState = false)
    }

    /**
     * 跳转到指定位置 (毫秒)。MP3 不支持随机 seek, 通过重新 OkHttp 请求 + skipFrames 实现。
     * 对应 app 端 ExoPlayer.seekTo()。
     */
    fun seekTo(positionMs: Long) {
        val msPerFrame = msPerFrameMs.coerceAtLeast(0.001f)
        val targetFrame = (positionMs / msPerFrame).toInt().coerceAtLeast(0)
        val wasPlaying = playing && !paused
        // 停止当前 player (会触发 playbackFinished, 但 paused 标志已置保护, 不会误触 onEndOfMedia)
        paused = true
        try { advancedPlayer?.stop() } catch (_: Exception) {}
        try { advancedPlayer?.close() } catch (_: Exception) {}
        advancedPlayer = null
        // 丢弃 Sonic 内残留样本, 避免 seek 后串音
        try { audioDevice?.resetSonic() } catch (_: Exception) {}
        try { audioDevice?.close() } catch (_: Exception) {}
        audioDevice = null

        playbackStartFrame = targetFrame
        // 重新拉流 (OkHttp Range 不一定支持, 走全量请求 + skipFrames 跳帧)
        val theUrl = url ?: return
        relaunchStream(theUrl)
        if (wasPlaying) {
            paused = false
            playing = true
            startPlaybackLoop(fromFrame = targetFrame, isResume = false)
        } else {
            // 停止状态下 seek, 仅更新位置, 不启动播放
            playing = false
            paused = false
        }
    }

    /**
     * 设置播放速率。对应 app 端 ExoPlayer.setPlaybackParameters(speed)。
     *
     * 由 [SonicAudioDevice] 在 PCM 输出层用 Sonic 变速 (保音高), 立即作用于当前播放;
     * speed 同时用于 [currentPosition] 的墙钟估算。
     */
    fun setSpeed(rate: Float) {
        // 先按旧 speed 结算已播放帧, 再切换, 避免 currentPosition 跳变
        playbackStartFrame = estimateCurrentFrame()
        playbackStartMs = System.currentTimeMillis()
        // 与 SonicAudioDevice 用同一钳制, 保证 currentPosition 估算与实际播放速率一致
        speed = SonicAudioDevice.coerceSpeed(rate)
        audioDevice?.speed = speed
    }

    /** 释放所有资源 (OkHttp Response / InputStream / AdvancedPlayer / AudioDevice)。 */
    fun release() {
        released = true
        stopInternal(clearState = true)
        try { advancedPlayer?.close() } catch (_: Exception) {}
        try { audioDevice?.close() } catch (_: Exception) {}
        try { rawStream?.close() } catch (_: Exception) {}
        try { response?.close() } catch (_: Exception) {}
        advancedPlayer = null
        audioDevice = null
        rawStream = null
        response = null
        url = null
        headers = emptyMap()
    }

    // ===== 内部辅助 =====

    /** 估算当前已播放帧数 (基于墙钟 + speed) */
    private fun estimateCurrentFrame(): Int {
        if (!playing || paused) return playbackStartFrame
        val elapsed = (System.currentTimeMillis() - playbackStartMs) * speed
        val framesPlayed = (elapsed / msPerFrameMs.coerceAtLeast(0.001f)).toInt()
        return (playbackStartFrame + framesPlayed).coerceAtLeast(0)
    }

    /** 重新发起 OkHttp 请求获取新流 (用于 seek) */
    private fun relaunchStream(theUrl: String) {
        try { response?.close() } catch (_: Exception) {}
        response = null
        rawStream = null
        val client = OkHttpClientProviders.get().okHttpClient
        val builder = Request.Builder().url(theUrl)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            listener?.onError("seek: HTTP reconnect failed: ${e.message}")
            return
        }
        if (!resp.isSuccessful) {
            resp.close()
            listener?.onError("seek: HTTP ${resp.code}")
            return
        }
        val body = resp.body
        val stream = NonClosingInputStream(BufferedInputStream(body.byteStream(), BUFFER_SIZE))
        synchronized(this) {
            response = resp
            rawStream = stream
            contentLength = body.contentLength()
        }
    }

    /**
     * 内部停止: 中断 prepare/play 线程 + 停止 AdvancedPlayer。
     *
     * @param clearState true 表示完全清空状态 (stop/release/setUrl 切换源时);
     *                   false 表示仅停止播放 (stop() 调用, 保留 url/duration 供 resume)
     */
    private fun stopInternal(clearState: Boolean) {
        playing = false
        paused = false
        // 中断 prepare 协程 (超时/换章/停止时 call.cancel() 断开连接)
        prepareJob?.cancel()
        prepareJob = null
        playbackThread?.interrupt()
        playbackThread = null
        try { advancedPlayer?.stop() } catch (_: Exception) {}
        try { advancedPlayer?.close() } catch (_: Exception) {}
        advancedPlayer = null
        // 换章/停止后丢弃 Sonic 残留样本
        try { audioDevice?.resetSonic() } catch (_: Exception) {}
        try { audioDevice?.close() } catch (_: Exception) {}
        audioDevice = null
        if (clearState) {
            playbackStartFrame = 0
            playbackStartMs = System.currentTimeMillis()
        }
    }

    private companion object {
        /** BufferedInputStream 缓冲区大小 (字节); 8KB 平衡 mark/reset 内存与 seek 精度 */
        private const val BUFFER_SIZE = 8192

        /** prepare 超时 (毫秒): 响应头/首帧拿不到即 onError, 防止直链挂起转圈永久残留 */
        private const val PREPARE_TIMEOUT_MS = 30_000L
    }

    /**
     * 包装 InputStream, 重写 close 为 no-op, 防止 jlayer Bitstream/AdvancedPlayer
     * 调用 close() 误关底层 OkHttp body stream。真正的关闭由 [release] 里 response.close() 控制。
     */
    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() {
            // no-op: 不关闭底层流, 由 DesktopAudioPlayer.release 统一管理
        }
    }
}
