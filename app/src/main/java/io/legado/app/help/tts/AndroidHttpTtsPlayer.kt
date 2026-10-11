package io.legado.app.help.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.utils.ensureParentDirectory
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * app 端一次性 HttpTTS 播放器 (朗读试听专用)。
 *
 * 对齐 iOS 播放器的取音频方式: [setRequest] 走 [HttpTtsRequest.audioResponse] 完整规则
 * (JS/登录头/loginCheckJs/Content-Type 校验, 与正式朗读同语义), 音频落临时文件后交给
 * ExoPlayer 播本地文件; [setUrl] 保留远程流直连入口 (带请求头)。
 * 正式朗读不经此实例 (HttpReadAloudService 下载调度 + 缓存播放)。
 *
 * ExoPlayer 只能主线程操作: 试听入口 (UI 菜单) 与回调 (ExoPlayer 主线程) 均在主线程,
 * 仍统一经 [onMain] 派发兜底。
 */
class AndroidHttpTtsPlayer : HttpTtsPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** 当前 URL, setUrl 注入, 远程流直连时使用。 */
    @Volatile
    private var url: String? = null

    /** 当前请求头, setUrl 注入。 */
    @Volatile
    private var headers: Map<String, String> = emptyMap()

    /** setRequest 存下的完整请求, prepare 时执行取音频。 */
    @Volatile
    private var pendingRequest: AudioRequest? = null

    /** ExoPlayer 实例, prepare 成功后创建。 */
    private var exoPlayer: ExoPlayer? = null

    /** setRequest 路径落地的临时音频文件, stop/release 时删除。 */
    private var audioFile: File? = null

    /** 取音频协程, stop/release 时取消。 */
    private var prepareJob: kotlinx.coroutines.Job? = null

    /** 代次: 防止上一次取音频/回调触碰新一次播放。 */
    @Volatile
    private var generation = 0L

    /** 回调 listener。 */
    @Volatile
    private var listenerField: HttpTtsPlayerListener? = null

    private class AudioRequest(val config: HttpTTS, val text: String, val speechRate: Int)

    override val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    override val duration: Long
        get() {
            val d = exoPlayer?.duration ?: return -1L
            return if (d == androidx.media3.common.C.TIME_UNSET) -1L else d
        }

    override val currentPosition: Long
        get() = exoPlayer?.currentPosition ?: 0L

    override var listener: HttpTtsPlayerListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    override fun setUrl(url: String, headers: Map<String, String>) {
        onMain {
            releaseCurrent()
            this.url = url
            this.headers = headers
            pendingRequest = null
        }
    }

    override fun setRequest(config: HttpTTS, text: String, speechRate: Int) {
        onMain {
            releaseCurrent()
            pendingRequest = AudioRequest(config.copy(), text, speechRate)
        }
    }

    override fun prepare() {
        onMain {
            generation++
            val currentGeneration = generation
            prepareJob?.cancel()
            prepareJob = null
            val request = pendingRequest
            if (request == null) {
                val remoteUrl = url
                if (remoteUrl.isNullOrBlank()) {
                    listenerField?.onError("未设置播放源")
                    return@onMain
                }
                // 远程流直连 (setUrl 路径): 请求头经 DefaultHttpDataSource 注入
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers)
                    .setAllowCrossProtocolRedirects(true)
                buildAndPreparePlayer(
                    MediaItem.fromUri(remoteUrl),
                    DefaultMediaSourceFactory(httpFactory),
                )
                return@onMain
            }
            // setRequest 路径: 完整规则取音频 (与正式朗读同语义) → 落临时文件 → 播本地文件
            prepareJob = scope.launch {
                val file = File(App.instance.cacheDir, "httpTTS/test-${Random.nextLong()}.mp3")
                var adopted = false
                try {
                    withContext(Dispatchers.IO) {
                        val response = HttpTtsRequest.audioResponse(
                            request.config, request.text, request.speechRate,
                            currentCoroutineContext(),
                        )
                        try {
                            check(file.ensureParentDirectory()) { "无法创建 TTS 缓存目录" }
                            val input = response.body.byteStream()
                            try {
                                file.outputStream().use { output ->
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
                    if (currentGeneration != generation) return@launch
                    audioFile = file
                    adopted = true
                    // ExoPlayer 只能主线程操作: 落盘完成后回主线程建播放器
                    withContext(Dispatchers.Main) {
                        buildAndPreparePlayer(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (currentGeneration == generation) {
                        val message = "HTTP TTS 音频请求失败：${error.message?.take(500)}"
                        AppLog.put(message, error)
                        listener?.onError(message)
                    }
                } finally {
                    if (!adopted) file.delete()
                }
            }
        }
    }

    /** 主线程调用: 建 ExoPlayer → 挂监听 → 装媒体 → prepare, STATE_READY 才 onReady。 */
    private fun buildAndPreparePlayer(
        mediaItem: MediaItem,
        mediaSourceFactory: DefaultMediaSourceFactory? = null,
    ) {
        releasePlayer()
        val context: Context = App.instance
        val builder = ExoPlayer.Builder(context)
        mediaSourceFactory?.let { builder.setMediaSourceFactory(it) }
        val player = builder.build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> listenerField?.onReady()
                    Player.STATE_ENDED -> listenerField?.onEndOfMedia()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listenerField?.onError(error.message ?: "播放出错")
            }
        })
        exoPlayer = player
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    override fun play() {
        onMain { exoPlayer?.play() }
    }

    override fun pause() {
        onMain { exoPlayer?.pause() }
    }

    override fun stop() {
        onMain {
            generation++
            prepareJob?.cancel()
            prepareJob = null
            releasePlayer()
        }
    }

    override fun release() {
        stop()
    }

    override fun seekTo(position: Long) {
        onMain { exoPlayer?.seekTo(position) }
    }

    /** 主线程调用: 取消取音频、释放播放器并删临时文件。 */
    private fun releaseCurrent() {
        prepareJob?.cancel()
        prepareJob = null
        releasePlayer()
    }

    private fun releasePlayer() {
        exoPlayer?.let { p ->
            exoPlayer = null
            runCatching {
                p.stop()
                p.release()
            }
        }
        audioFile?.let { f ->
            audioFile = null
            f.delete()
        }
    }
}
