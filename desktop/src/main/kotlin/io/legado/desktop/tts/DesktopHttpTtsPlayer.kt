package io.legado.desktop.tts

import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.tts.HttpTtsPlayer
import io.legado.app.help.tts.HttpTtsPlayerListener
import io.legado.app.ui.compose.platform.jvmGetString
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.UnsupportedAudioFileException

/**
 * [HttpTtsPlayer] 的桌面端 (JVM) actual 实现 (KP2-H)。
 *
 * **平台 API**:
 * - 网络拉流: [OkHttpClientProviders] 注入的 OkHttpClient (与 Android 端共用同套拦截器/SSL/超时配置)
 * - 音频播放: `javax.sound.sampled.SourceDataLine` 流式播放 (JDK 内置, 无需额外依赖)
 * - 格式识别: `AudioSystem.getAudioInputStream(InputStream)` 自动解析 WAV/PCM/AU/AIFF
 *   (MP3 需要 JDK 自带 MP3 SPI, 默认未启用; MP3 场景下报错由调用方降级)
 *
 * **实现策略**:
 * - [prepare] 启动后台线程建立 HTTP 连接 + 解析音频格式 + 打开 SourceDataLine, 完成后回调 [HttpTtsPlayerListener.onReady]
 * - [play] 启动拉流写入循环: 边从 HTTP InputStream 读字节边写入 SourceDataLine
 * - [pause] 调用 `SourceDataLine.stop()` 暂停音频输出 (流位置保留)
 * - [stop] 停止 + flush + 重置位置
 * - [seekTo] 流式 HTTP 场景下不支持精确 seek, 留空 no-op (与 app 端 Media3 cache seek 不同)
 * - 播放完毕 (InputStream.read 返回 -1) 自动回调 [HttpTtsPlayerListener.onEndOfMedia]
 *
 * **线程模型**:
 * - prepare/play 各启动 daemon 线程, 不阻塞 Compose 主线程
 * - listener 回调从工作线程触发, 调用方如需主线程访问需自行 invokeLater
 *
 * **简化**:
 * - 不缓存到本地文件 (与 app 端 `HttpReadAloudService.downloadAndPlayAudiosStream` 区别)
 *   仅内存流式播放; 后续若要支持 mp3 / 离线播放, 可在 setUrl 后落盘再交给 AudioSystem
 * - duration 仅 WAV/PCM 可基于 contentLength 与 frameSize 计算; 流式 mp3 等返回 -1
 *
 * **参考**:
 * - app 端: `app/.../service/HttpReadAloudService.kt` 用 ExoPlayer + Media3 + SimpleCache
 * - 模式参考: `desktop/http/DesktopHttpProvider.kt` OkHttpClientProvider 注入风格
 */
class DesktopHttpTtsPlayer : HttpTtsPlayer {

    /** 当前 URL, setUrl 注入, prepare 时使用。 */
    @Volatile private var url: String? = null

    /** 当前请求头, setUrl 注入。 */
    @Volatile private var headers: Map<String, String> = emptyMap()

    /** 回调 listener, 由 HttpTtsPlayer contract 暴露。 */
    @Volatile private var listenerField: HttpTtsPlayerListener? = null

    /** 是否在播放中 (含 pause 状态); 真实播放 = playing && !paused。 */
    @Volatile private var playing: Boolean = false

    /** 是否暂停中。 */
    @Volatile private var paused: Boolean = false

    /** 是否已 release, release 后所有操作 no-op。 */
    @Volatile private var released: Boolean = false

    /** OkHttp Response, 持有以在 stop/release 时关闭。 */
    @Volatile private var response: Response? = null

    /** HTTP body InputStream, 由 response.body.byteStream() 取得。 */
    @Volatile private var rawInputStream: InputStream? = null

    /** AudioInputStream 包装, getAudioInputStream 包装 rawInputStream 后返回。 */
    @Volatile private var audioInputStream: AudioInputStream? = null

    /** 音频格式, 由 AudioInputStream.format 取得。 */
    @Volatile private var audioFormat: AudioFormat? = null

    /** SourceDataLine 输出线, 由 AudioSystem.getLine(DataLine.Info) 取得。 */
    @Volatile private var sourceDataLine: SourceDataLine? = null

    /** 后台拉流播放线程, release/stop 时 interrupt。 */
    @Volatile private var playbackThread: Thread? = null

    /** 后台 prepare 线程, release 时 interrupt。 */
    @Volatile private var prepareThread: Thread? = null

    /** HTTP Content-Length, 用于估算 duration; -1 表示未知。 */
    @Volatile private var contentLength: Long = -1L

    /** 已播放字节数, 用于估算 currentPosition。 */
    @Volatile private var bytesRead: Long = 0L

    /** 总时长 (毫秒), 由 audioFormat + contentLength 计算; -1 表示未知。 */
    @Volatile private var durationMs: Long = -1L

    // ===== HttpTtsPlayer contract =====

    override val isPlaying: Boolean
        get() = playing && !paused

    override val duration: Long
        get() = durationMs

    /**
     * 当前播放位置 (毫秒)。
     *
     * 优先用 SourceDataLine.microsecondPosition (硬件级精确, 反映已写入到音频设备的字节数);
     * 若 line 不可用 (尚未 prepare) 则用 bytesRead + audioFormat 估算。
     */
    override val currentPosition: Long
        get() {
            val line = sourceDataLine ?: return 0L
            val fmt = audioFormat ?: return 0L
            // line.microsecondPosition 返回已播放的微秒数 (从 line.open 后开始累计)
            // 注意: pause 后 microsecondPosition 不再增长, 与"当前播放位置"语义一致
            val micros = line.microsecondPosition
            if (micros > 0) return micros / 1000L
            // 兜底: 用 bytesRead + frameSize + sampleRate 估算
            val frameSize = fmt.frameSize.toLong().coerceAtLeast(1L)
            val sampleRate = fmt.sampleRate.toLong().coerceAtLeast(1L)
            val frames = bytesRead / frameSize
            return frames * 1000L / sampleRate
        }

    override var listener: HttpTtsPlayerListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== HttpTtsPlayer contract 方法 =====

    override fun setUrl(url: String, headers: Map<String, String>) {
        if (released) return
        // 切换源前先清空上一次的状态
        stopInternal(clearState = true)
        this.url = url
        this.headers = headers
        this.bytesRead = 0L
        this.durationMs = -1L
        this.contentLength = -1L
    }

    override fun prepare() {
        if (released) return
        val theUrl = url ?: run {
            listener?.onError(jvmGetString("tts_error_prepare_no_url"))
            return
        }
        // 启动后台线程建立 HTTP 连接 + 解析音频格式 + 打开 SourceDataLine
        prepareThread = Thread({
            try {
                prepareInternal(theUrl)
            } catch (e: Exception) {
                listener?.onError(jvmGetString("tts_error_prepare_exception", e.message))
            }
        }, "DesktopHttpTtsPlayer-prepare").apply {
            isDaemon = true
            start()
        }
    }

    override fun play() {
        if (released) return
        if (paused) {
            // 从暂停恢复
            paused = false
            playing = true
            try {
                sourceDataLine?.start()
            } catch (e: Exception) {
                listener?.onError(jvmGetString("tts_error_resume_failed", e.message))
            }
            return
        }
        if (playing) return
        playing = true
        // 启动拉流播放线程
        playbackThread = Thread({
            try {
                playbackLoop()
            } catch (e: Exception) {
                if (!released) {
                    listener?.onError(jvmGetString("tts_error_play_exception", e.message))
                }
            }
        }, "DesktopHttpTtsPlayer-playback").apply {
            isDaemon = true
            start()
        }
    }

    override fun pause() {
        if (released) return
        paused = true
        try {
            sourceDataLine?.stop()
        } catch (_: Exception) {
            // SourceDataLine.stop 在某些状态下抛异常, 忽略
        }
    }

    override fun stop() {
        stopInternal(clearState = false)
    }

    override fun release() {
        released = true
        stopInternal(clearState = true)
        try {
            sourceDataLine?.close()
        } catch (_: Exception) {
        }
        try {
            audioInputStream?.close()
        } catch (_: Exception) {
        }
        try {
            rawInputStream?.close()
        } catch (_: Exception) {
        }
        try {
            response?.close()
        } catch (_: Exception) {
        }
        sourceDataLine = null
        audioInputStream = null
        rawInputStream = null
        response = null
        audioFormat = null
        url = null
        headers = emptyMap()
        durationMs = -1L
        contentLength = -1L
        bytesRead = 0L
    }

    /**
     * 流式 HTTP 场景下不支持精确 seek, no-op。
     *
     * 若需支持 seek, 可改为: 关闭当前流 -> 用 Range 请求头重新拉流 -> 重新 prepare。
     * 与 app 端 Media3 SimpleCache 精确 seek 不同, 此处简化。
     */
    override fun seekTo(position: Long) {
        // no-op: 流式播放不支持 seek
    }

    // ===== 内部实现 =====

    /**
     * prepare 阶段:
     * 1. 用 OkHttpClient 同步 execute 拉流
     * 2. 用 AudioSystem.getAudioInputStream 包装 raw stream (自动解析格式)
     * 3. 用 AudioSystem.getLine 打开 SourceDataLine
     * 4. 估算 duration (基于 contentLength + frameSize)
     * 5. 触发 listener.onReady
     */
    private fun prepareInternal(theUrl: String) {
        val client = OkHttpClientProviders.get().okHttpClient
        val requestBuilder = Request.Builder().url(theUrl)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val request = requestBuilder.build()

        val resp = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            listener?.onError(jvmGetString("tts_error_http_connect_failed", e.message))
            return
        }
        if (!resp.isSuccessful) {
            listener?.onError("HTTP ${resp.code}: ${resp.message}")
            resp.close()
            return
        }
        synchronized(this) {
            response = resp
            rawInputStream = resp.body.byteStream()
            contentLength = resp.body.contentLength()
        }

        // 用 AudioSystem 解析音频格式 (WAV/PCM/AU/AIFF 自动识别; MP3 需 JDK 自带 SPI, 默认不支持)
        val stream = rawInputStream ?: run {
            listener?.onError(jvmGetString("tts_error_input_stream_null"))
            return
        }
        val audioStream: AudioInputStream = try {
            AudioSystem.getAudioInputStream(stream)
        } catch (e: UnsupportedAudioFileException) {
            listener?.onError(
                jvmGetString("tts_error_unsupported_audio_format", e.message)
            )
            return
        } catch (e: IOException) {
            listener?.onError(jvmGetString("tts_error_read_stream_failed", e.message))
            return
        }
        val fmt = audioStream.format
        synchronized(this) {
            audioInputStream = audioStream
            audioFormat = fmt
        }

        // 估算 duration: contentLength / (frameSize * sampleRate) * 1000
        if (contentLength > 0 && fmt.frameSize > 0 && fmt.sampleRate > 0f) {
            val frames = contentLength / fmt.frameSize.toLong()
            durationMs = frames * 1000L / fmt.sampleRate.toLong()
        }

        // 打开 SourceDataLine
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        val line: SourceDataLine = try {
            AudioSystem.getLine(info) as SourceDataLine
        } catch (e: LineUnavailableException) {
            listener?.onError(jvmGetString("tts_error_audio_line_unavailable", e.message))
            return
        }
        try {
            line.open(fmt)
            // 不立即 start; 等 play() 调用再 start
        } catch (e: LineUnavailableException) {
            listener?.onError(jvmGetString("tts_error_open_line_failed", e.message))
            return
        }
        synchronized(this) {
            sourceDataLine = line
        }

        // 准备完成
        listener?.onReady()
    }

    /**
     * 播放循环: 从 audioInputStream 读字节写入 sourceDataLine, 直到流结束或被 stop。
     *
     * 写入循环按 buffer 块大小 4KB, 平衡 CPU 与延迟。
     */
    private fun playbackLoop() {
        val line = sourceDataLine ?: run {
            listener?.onError(jvmGetString("tts_error_source_line_not_ready"))
            return
        }
        val audio = audioInputStream ?: run {
            listener?.onError(jvmGetString("tts_error_audio_stream_not_ready"))
            return
        }
        // 启动 SourceDataLine (从 pause 恢复时也会调用 play, line 已 open)
        try {
            line.start()
        } catch (e: Exception) {
            listener?.onError(jvmGetString("tts_error_start_failed", e.message))
            return
        }
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (playing && !released) {
                // 暂停时让出 CPU
                if (paused) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                val n = try {
                    audio.read(buffer)
                } catch (e: IOException) {
                    listener?.onError(jvmGetString("tts_error_read_stream_interrupted", e.message))
                    break
                }
                if (n < 0) {
                    // 流结束 -> 触发 onEndOfMedia
                    playing = false
                    paused = false
                    try {
                        line.drain()
                        line.stop()
                    } catch (_: Exception) {
                    }
                    listener?.onEndOfMedia()
                    break
                }
                if (n > 0) {
                    try {
                        line.write(buffer, 0, n)
                    } catch (e: Exception) {
                        listener?.onError(jvmGetString("tts_error_write_line_failed", e.message))
                        break
                    }
                    bytesRead += n.toLong()
                    // 估算 buffering 进度 (基于 bytesRead / contentLength)
                    if (contentLength > 0) {
                        val percent = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                        listener?.onBufferingUpdate(percent)
                    }
                }
            }
        } finally {
            try {
                if (!playing) line.stop()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 内部停止: 停止播放线程 + 停 SourceDataLine。
     *
     * @param clearState true 表示完全清空状态 (stop/release/setUrl 切换源时调用);
     *                    false 表示仅停止播放 (stop() 调用)
     */
    private fun stopInternal(clearState: Boolean) {
        playing = false
        paused = false
        // 中断 prepare 线程 (若正在 prepare)
        prepareThread?.interrupt()
        prepareThread = null
        // 中断播放线程
        playbackThread?.interrupt()
        playbackThread = null
        try {
            sourceDataLine?.let { line ->
                line.stop()
                if (clearState) {
                    line.flush()
                }
            }
        } catch (_: Exception) {
        }
        if (clearState) {
            bytesRead = 0L
        }
    }

    private companion object {
        /** 播放循环缓冲区大小 (字节); 4KB 平衡延迟与 CPU。 */
        private const val BUFFER_SIZE = 4096
    }
}
