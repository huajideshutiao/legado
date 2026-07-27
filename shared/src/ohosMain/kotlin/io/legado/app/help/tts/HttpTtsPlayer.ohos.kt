package io.legado.app.help.tts

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.legado.app.help.book.NativeBookStorage
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.io.File

/**
 * [HttpTtsPlayer] 的鸿蒙 (OHOS) 真实实现 (KP8+)。
 *
 * # 实现方式: napi 桥接 AVPlayer (tsfn 命令 + @CName 事件回调)
 *
 * 鸿蒙 `@ohos.multimedia.media` 仅提供 ArkTS API, Kotlin/Native 无法直接调用,
 * 通过 [OhosNativeBridge] napi 桥接实现真实 HTTP TTS 播放:
 *
 * ## 命令链 (Kotlin → ArkTS, fire-and-forget via tsfn)
 * - [setUrl]: 存储 url/headers, 不立即下载 (与 iOS AVPlayer.playerWithURL 对齐)
 * - [prepare]: 后台下载音频到临时文件 (Ktor CIO, 处理 headers), 下载完成后通过 tsfn
 *   发 "setSource" 命令把文件路径推给 ArkTS; ArkTS 创建 AVPlayer + 设源 + prepare,
 *   AVPlayer 就绪后通过 @CName legado_media_event 回推 "onReady" 事件
 * - [play]/[pause]/[stop]/[seekTo]: 通过 tsfn 发命令, ArkTS 转发 AVPlayer 同名方法
 * - [release]: 通过 tsfn 发 "release" 命令释放 ArkTS 侧 AVPlayer + 取消下载 + 删临时文件
 *
 * ## 事件链 (ArkTS → Kotlin, via @CName legado_media_event)
 * - onReady: AVPlayer prepared → [HttpTtsPlayerListener.onReady]
 * - onEndOfMedia: 播放结束 → [HttpTtsPlayerListener.onEndOfMedia]
 * - onError: 播放错误 → [HttpTtsPlayerListener.onError]
 * - onBufferingUpdate: 缓冲进度 → [HttpTtsPlayerListener.onBufferingUpdate]
 * - onDuration/onPosition/onPlaying/onPaused: Kotlin 缓存 getter 返回值
 *
 * ## 状态缓存
 * [isPlaying]/[duration]/[currentPosition] 由 ArkTS 事件更新, Kotlin 侧缓存返回
 * (AVPlayer 状态在 ArkTS 侧, Kotlin 无法同步查询, 用事件推送 + 缓存模式)。
 *
 * # 降级策略 (桥接未就绪时, 与 KP2-H 占位行为一致)
 * [OhosNativeBridge.isMediaBridgeReady] 返回 false (tsfn 未注入) 时, 降级为
 * "网络下载已就绪, 播放占位" 模式 (play/pause/stop 仅维护标志 + println, 不出声),
 * 让 [ReadAloudController] 状态机能正常推进 (onReady/onEndOfMedia 触发段落推进)。
 *
 * # 临时文件路径: `{NativeBookStorage.defaultRootPath}/tts_cache/{url.hashCode}.tmp`
 * (复用鸿蒙可写目录派生逻辑; release 时删除)
 *
 * # 参考
 * - iOS 端 [IosHttpTtsPlayer] (AVPlayer + NSNotificationCenter, Kotlin/Native 直接调 ObjC)
 * - 鸿蒙 Ktor 用法参考 [io.legado.app.help.file.OhosFileDownloader]
 * - napi 桥接模式参考 [OhosNativeBridge] Toast/Notification tsfn + FileDir @CName 回调
 */
class OhosHttpTtsPlayer : HttpTtsPlayer, OhosNativeBridge.MediaEventListener {

    /** 当前 URL (记录用于日志 + 临时文件名派生)。 */
    @Volatile private var url: String? = null

    /** 当前请求头 (下载时注入 Ktor request)。 */
    @Volatile private var headers: Map<String, String> = emptyMap()

    /** 回调 listener。 */
    @Volatile private var listenerField: HttpTtsPlayerListener? = null

    /** 内部播放状态标志 (降级模式下由 play/pause/stop 维护; 桥接模式下由 onPlaying/onPaused 事件更新)。 */
    @Volatile private var playing: Boolean = false

    /** 缓存的总时长 (毫秒, ArkTS onDuration 事件更新; 降级模式 -1)。 */
    @Volatile private var cachedDuration: Long = -1L

    /** 缓存的播放位置 (毫秒, ArkTS onPosition 事件更新; 降级模式 0)。 */
    @Volatile private var cachedPosition: Long = 0L

    /** 下载完成的临时文件 (prepare 后台下载完成后赋值)。 */
    @Volatile private var cachedFile: File? = null

    /** 后台下载协程 scope (每次 prepare 创建, release/stop 时 cancel)。 */
    @Volatile private var scope: CoroutineScope? = null

    /** 当前下载 Job (release/stop 时 cancel)。 */
    @Volatile private var downloadJob: Job? = null

    /** 是否已注册为 media 事件监听器 (setUrl 时注册, release 时注销, 避免重复注册)。 */
    @Volatile private var listenerRegistered: Boolean = false

    // ===== HttpTtsPlayer contract =====

    override val isPlaying: Boolean
        get() = playing

    override val duration: Long
        get() = cachedDuration

    override val currentPosition: Long
        get() = cachedPosition

    override var listener: HttpTtsPlayerListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== HttpTtsPlayer contract 方法 =====

    override fun setUrl(url: String, headers: Map<String, String>) {
        // 释放旧下载任务与临时文件 + 释放旧 AVPlayer (如有)
        cancelDownload()
        deleteCachedFile()
        releaseArkTsPlayer()

        // 重置状态
        this.url = url
        this.headers = headers
        this.playing = false
        this.cachedDuration = -1L
        this.cachedPosition = 0L

        // 注册 media 事件监听器 (桥接就绪时, 接收 ArkTS AVPlayer 事件)
        // 注: OhosNativeBridge.mediaEventListener 是单例, 同一时间仅一个 player 可接收事件
        // (当前使用场景: ReadAloudController 同时仅一个 HttpTtsPlayer 活跃, 无冲突)
        if (OhosNativeBridge.isMediaBridgeReady() && !listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(this)
            listenerRegistered = true
        }

        println("[ohos-httts] setUrl: url=$url headers.size=${headers.size} bridge=${OhosNativeBridge.isMediaBridgeReady()}")
    }

    /**
     * prepare: 启动后台下载到临时文件, 下载完成后:
     * - 桥接就绪: 发 "setSource" 命令把文件路径推给 ArkTS, ArkTS 创建 AVPlayer 并 prepare,
     *   就绪后回推 "onReady" 事件 (真实播放)
     * - 桥接未就绪: 直接触发 [HttpTtsPlayerListener.onReady] (降级占位, 让状态机推进)
     */
    override fun prepare() {
        val currentUrl = url ?: run {
            println("[ohos-httts] prepare: url not set, skip")
            return
        }
        println("[ohos-httts] prepare: start background download url=$currentUrl")

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        downloadJob = newScope.launch {
            try {
                val file = downloadToTempFile(currentUrl, headers)
                cachedFile = file
                println("[ohos-httts] prepare: download ok, cached=${file.path} size=${file.length()}")

                if (OhosNativeBridge.isMediaBridgeReady()) {
                    // 桥接就绪: 发 "setSource" 命令, ArkTS 创建 AVPlayer + prepare
                    // onReady 由 ArkTS AVPlayer 'stateChange' 事件 (state='prepared') 回推
                    val cmd = KS_JSON.encodeToString(
                        MediaCommand(action = "setSource", path = file.path)
                    )
                    OhosNativeBridge.sendMediaCommand(cmd)
                } else {
                    // 降级: 直接 onReady (占位播放, 让调用方状态机推进)
                    listener?.onReady()
                }
            } catch (e: Exception) {
                println("[ohos-httts] prepare: download failed: ${e.message}")
                listener?.onError(e.message ?: "download failed")
            }
        }
    }

    override fun play() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            // 桥接就绪: 发 "play" 命令, ArkTS 调 AVPlayer.play()
            // playing 标志由 ArkTS "onPlaying" 事件更新
            val cmd = KS_JSON.encodeToString(MediaCommand(action = "play"))
            OhosNativeBridge.sendMediaCommand(cmd)
        } else {
            // 降级: 维护 playing 标志
            playing = true
            val file = cachedFile
            if (file != null) {
                println("[ohos-httts] play (placeholder): cached=${file.path} size=${file.length()}")
            } else {
                println("[ohos-httts] play (placeholder): cached not ready yet, url=$url")
            }
        }
    }

    override fun pause() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            val cmd = KS_JSON.encodeToString(MediaCommand(action = "pause"))
            OhosNativeBridge.sendMediaCommand(cmd)
        } else {
            playing = false
            println("[ohos-httts] pause (placeholder)")
        }
    }

    override fun stop() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            val cmd = KS_JSON.encodeToString(MediaCommand(action = "stop"))
            OhosNativeBridge.sendMediaCommand(cmd)
        } else {
            playing = false
            println("[ohos-httts] stop (placeholder)")
        }
        cancelDownload()
    }

    override fun release() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            // 发 "release" 命令释放 ArkTS 侧 AVPlayer
            val cmd = KS_JSON.encodeToString(MediaCommand(action = "release"))
            OhosNativeBridge.sendMediaCommand(cmd)
        }
        // 注销 media 事件监听器
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(null)
            listenerRegistered = false
        }
        playing = false
        cachedDuration = -1L
        cachedPosition = 0L
        cancelDownload()
        deleteCachedFile()
        releaseArkTsPlayer()
        url = null
        headers = emptyMap()
        println("[ohos-httts] release")
    }

    override fun seekTo(position: Long) {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            val cmd = KS_JSON.encodeToString(MediaCommand(action = "seekTo", position = position))
            OhosNativeBridge.sendMediaCommand(cmd)
        } else {
            println("[ohos-httts] seekTo (placeholder): position=$position")
        }
    }

    // ===== OhosNativeBridge.MediaEventListener =====

    /**
     * 接收 ArkTS AVPlayer 事件 (通过 @CName legado_media_event → OhosNativeBridge.onMediaEvent 推送)。
     * 解析事件 JSON, 更新缓存状态 + 转发给 [HttpTtsPlayerListener]。
     */
    override fun onMediaEvent(eventJson: String) {
        val event = runCatching {
            KS_JSON.decodeFromString(MediaEvent.serializer(), eventJson)
        }.getOrNull() ?: return

        when (event.event) {
            "onReady" -> {
                playing = false
                listener?.onReady()
            }
            "onEndOfMedia" -> {
                playing = false
                listener?.onEndOfMedia()
            }
            "onError" -> {
                playing = false
                listener?.onError(event.message ?: "AVPlayer error")
            }
            "onBufferingUpdate" -> {
                event.percent?.let { listener?.onBufferingUpdate(it) }
            }
            "onDuration" -> {
                event.duration?.let { cachedDuration = it }
            }
            "onPosition" -> {
                event.position?.let { cachedPosition = it }
            }
            "onPlaying" -> {
                playing = true
            }
            "onPaused" -> {
                playing = false
            }
        }
    }

    // ===== 内部实现 =====

    /**
     * 用 Ktor HttpClient (CIO engine) 下载 url 到临时文件。
     * - headers 逐个注入 (与 WebDav.ohos.kt 行为一致)
     * - 整文件 bytes 一次性写入 (与 OhosFileDownloader 模式一致)
     * 参考 [io.legado.app.help.file.OhosFileDownloader.download]。
     */
    private suspend fun downloadToTempFile(url: String, headers: Map<String, String>): File {
        val client = HttpClient(CIO)
        try {
            val response = client.get(url) {
                headers.forEach { (k, v) -> header(k, v) }
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: $url")
            }
            val bytes = response.bodyAsBytes()

            // 临时文件: {book_cache}/tts_cache/{url.hashCode}.tmp
            val cacheDir = File("${NativeBookStorage.defaultRootPath()}/$TTS_CACHE_DIR")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val tmpFile = File(cacheDir, "${url.hashCode() and 0x7FFFFFFF}.tmp")
            tmpFile.writeBytes(bytes)
            return tmpFile
        } finally {
            client.close()
        }
    }

    /** 取消后台下载协程 (与 NativeKmpCall.cancel 语义对齐: cancel job + scope)。 */
    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        scope?.cancel()
        scope = null
    }

    /** 删除临时文件 (release/setUrl 时清理, 避免磁盘残留)。 */
    private fun deleteCachedFile() {
        cachedFile?.let { file ->
            runCatching { if (file.exists()) file.delete() }
            cachedFile = null
        }
    }

    /** 释放 ArkTS 侧 AVPlayer (发 "release" 命令, 桥接未就绪时 no-op)。 */
    private fun releaseArkTsPlayer() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            runCatching {
                val cmd = KS_JSON.encodeToString(MediaCommand(action = "release"))
                OhosNativeBridge.sendMediaCommand(cmd)
            }
        }
    }

    // ===== 桥接 JSON 数据类 (与 ArkTS 侧协议对齐) =====

    /** media 命令 (Kotlin → ArkTS, fire-and-forget via tsfn)。 */
    @Serializable
    private data class MediaCommand(
        val action: String,
        val path: String? = null,
        val position: Long? = null,
    )

    /** media 事件 (ArkTS → Kotlin, via @CName legado_media_event)。 */
    @Serializable
    private data class MediaEvent(
        val event: String,
        val message: String? = null,
        val percent: Int? = null,
        val duration: Long? = null,
        val position: Long? = null,
    )

    companion object {
        /** TTS 临时文件子目录名 (位于 [NativeBookStorage.defaultRootPath] 下)。 */
        private const val TTS_CACHE_DIR = "tts_cache"
    }
}
