@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.help.tts

import kotlin.concurrent.Volatile

import io.legado.app.constant.AppLog
import io.legado.app.help.book.NativeBookStorage
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import io.legado.app.utils.File

/**
 * [HttpTtsPlayer] 的鸿蒙 (OHOS) 真实实现 (KP8+)。
 *
 * # 实现方式: napi 桥接 AVPlayer (tsfn 命令 + @CName 事件回调)
 *
 * 鸿蒙 `@ohos.multimedia.media` 仅提供 ArkTS API, Kotlin/Native 无法直接调用,
 * 通过 [OhosNativeBridge] napi 桥接实现真实 HTTP TTS 播放:
 *
 * ## 命令链 (Kotlin → ArkTS, fire-and-forget via tsfn)
 * 所有命令带固定 `playerId = "httpTts"`, ArkTS 侧按此 id 独占一个 AVPlayer 实例,
 * 与音频书 (`"audioBook"`) 互不抢占。
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
 * # 降级策略 (桥接未就绪时, 与占位行为一致)
 * [OhosNativeBridge.isMediaBridgeReady] 返回 false (tsfn 未注入) 时, 降级为
 * "网络下载已就绪, 播放占位" 模式 (play/pause/stop 仅维护标志, 不出声),
 * 让 [ReadAloudController] 状态机能正常推进 (onReady/onEndOfMedia 触发段落推进)。
 *
 * # 缓存: `{NativeBookStorage.defaultRootPath}/httpTTS/{md5(url)}.mp3`
 * url 已含 speakText+语速 (AnalyzeUrl 求值后), 去重粒度对齐原版 md5(url-|-rate-|-content);
 * 命中直接复用 (跨会话缓存), 连续下载失败经 [HttpTtsRequest.DownloadErrorBreaker] 熔断 (>5 次放弃),
 * release 时按 10 分钟老化清理 (近似原版 removeCacheFile, 无章节名分组靠新鲜度保护当前章节)
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

    /** 当前段音频缓存文件 (prepare 下载/命中缓存后赋值)。 */
    @Volatile private var cachedFile: File? = null

    /** 连续下载失败熔断计数 (对标原版: 成功清零, 超过 5 次放弃)。 */
    private val downloadErrorBreaker = HttpTtsRequest.DownloadErrorBreaker()

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
        // 释放旧下载任务 + 旧 AVPlayer (缓存文件保留, 供跨会话/重听复用)
        cancelDownload()
        cachedFile = null
        releaseArkTsPlayer()

        // 重置状态
        this.url = url
        this.headers = headers
        this.playing = false
        this.cachedDuration = -1L
        this.cachedPosition = 0L

        // 注册 media 事件监听器 (桥接就绪时, 接收 ArkTS AVPlayer 事件)
        // 按固定 playerId 注册, 与音频书 (PLAYER_ID_AUDIO_BOOK) 各持一个 AVPlayer 实例, 互不抢占
        if (OhosNativeBridge.isMediaBridgeReady() && !listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_HTTP_TTS, this)
            listenerRegistered = true
        }

        AppLog.putDebug(
            "setUrl: url=$url headers.size=${headers.size} bridge=${OhosNativeBridge.isMediaBridgeReady()}",
            tag = TAG,
        )
    }

    /**
     * prepare: 命中缓存直接就绪, 否则后台下载到缓存文件, 完成后:
     * - 桥接就绪: 发 "setSource" 命令把文件路径推给 ArkTS, ArkTS 创建 AVPlayer 并 prepare,
     *   就绪后回推 "onReady" 事件 (真实播放)
     * - 桥接未就绪: 直接触发 [HttpTtsPlayerListener.onReady] (降级占位, 让状态机推进)
     */
    override fun prepare() {
        val currentUrl = url ?: run {
            AppLog.putDebug("prepare: url 未设置, 跳过", tag = TAG)
            return
        }

        val newScope = CoroutineScope(SupervisorJob() + IoDispatcher)
        scope = newScope
        downloadJob = newScope.launch {
            try {
                val file = obtainCacheFile(currentUrl, headers)
                cachedFile = file
                AppLog.putDebug("prepare: 就绪, cached=${file.path} size=${file.length()}", tag = TAG)

                if (OhosNativeBridge.isMediaBridgeReady()) {
                    // 桥接就绪: 发 "setSource" 命令, ArkTS 创建 AVPlayer + prepare
                    // onReady 由 ArkTS AVPlayer 'stateChange' 事件 (state='prepared') 回推
                    sendMedia("setSource", path = file.path)
                } else {
                    // 降级: 直接 onReady (占位播放, 让调用方状态机推进)
                    listener?.onReady()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("prepare: 下载失败", e, tag = TAG)
                listener?.onError(e.message ?: "download failed")
            }
        }
    }

    override fun play() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            // 桥接就绪: 发 "play" 命令, ArkTS 调 AVPlayer.play()
            // playing 标志由 ArkTS "onPlaying" 事件更新
            sendMedia("play")
        } else {
            // 降级: 维护 playing 标志
            playing = true
            val file = cachedFile
            if (file != null) {
                AppLog.putDebug("play (占位): cached=${file.path} size=${file.length()}", tag = TAG)
            } else {
                AppLog.putDebug("play (占位): 缓存未就绪, url=$url", tag = TAG)
            }
        }
    }

    override fun pause() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            sendMedia("pause")
        } else {
            playing = false
            AppLog.putDebug("pause (占位)", tag = TAG)
        }
    }

    override fun stop() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            sendMedia("stop")
        } else {
            playing = false
            AppLog.putDebug("stop (占位)", tag = TAG)
        }
        cancelDownload()
    }

    override fun release() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            // 发 "release" 命令释放 ArkTS 侧 AVPlayer
            sendMedia("release")
        }
        // 注销 media 事件监听器
        if (listenerRegistered) {
            OhosNativeBridge.setMediaEventListener(OhosNativeBridge.PLAYER_ID_HTTP_TTS, null)
            listenerRegistered = false
        }
        playing = false
        cachedDuration = -1L
        cachedPosition = 0L
        cancelDownload()
        cachedFile = null
        cleanupAgedCache()
        releaseArkTsPlayer()
        url = null
        headers = emptyMap()
    }

    override fun seekTo(position: Long) {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            sendMedia("seekTo", position = position)
        } else {
            AppLog.putDebug("seekTo (占位): position=$position", tag = TAG)
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
     * 取缓存或下载: 文件名 md5(url) (url 经 AnalyzeUrl 求值已含 speakText+语速,
     * 去重粒度对齐原版 md5(url-|-rate-|-content)); 命中且非空直接复用 (跨会话缓存);
     * 未命中循环下载, 每次失败记入熔断器, 超过 5 次抛出放弃 (对标原版 getSpeakStream)。
     */
    private suspend fun obtainCacheFile(url: String, headers: Map<String, String>): File {
        val cacheDir = File("${NativeBookStorage.defaultRootPath()}/$TTS_CACHE_DIR")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val target = File(cacheDir, MD5Utils.md5Encode16(url) + ".mp3")
        if (target.exists() && target.length() > 0L) {
            downloadErrorBreaker.reset()
            return target
        }
        while (true) {
            try {
                val bytes = downloadBytes(url, headers)
                target.writeBytes(bytes)
                downloadErrorBreaker.reset()
                return target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("下载出错", e, tag = TAG)
                if (downloadErrorBreaker.record()) {
                    throw IllegalStateException("TTS 下载连续失败超过 5 次: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 通过鸿蒙原生 HTTP provider 下载。底层由 KmpHttpTypes.ohos.kt 经 napi 调用
     * `@ohos.net.http`，避免依赖没有 ohosArm64 变体的 Ktor。
     */
    private suspend fun downloadBytes(url: String, headers: Map<String, String>): ByteArray {
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
            return response.body.bytes()
        } finally {
            response.close()
        }
    }

    /** 取消后台下载协程 (与 NativeKmpCall.cancel 语义对齐: cancel job + scope)。 */
    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        scope?.cancel()
        scope = null
    }

    /**
     * 老化清理: 删除超过 10 分钟未更新的缓存文件 (近似原版 removeCacheFile 的 600000ms 规则;
     * 无章节名分组, 当前会话文件靠新鲜度豁免)。
     */
    private fun cleanupAgedCache() {
        runCatching {
            val cacheDir = File("${NativeBookStorage.defaultRootPath()}/$TTS_CACHE_DIR")
            if (!cacheDir.exists()) return
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > 600000L) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    /** 释放 ArkTS 侧 AVPlayer (发 "release" 命令, 桥接未就绪时 no-op)。 */
    private fun releaseArkTsPlayer() {
        if (OhosNativeBridge.isMediaBridgeReady()) {
            runCatching { sendMedia("release") }
        }
    }

    /** 发 media 命令, 统一带上本播放器的固定 playerId。 */
    private fun sendMedia(action: String, path: String? = null, position: Long? = null) {
        val cmd = MediaCommand(
            playerId = OhosNativeBridge.PLAYER_ID_HTTP_TTS,
            action = action,
            path = path,
            position = position,
        )
        OhosNativeBridge.sendMediaCommand(KS_JSON.encodeToString(MediaCommand.serializer(), cmd))
    }

    // ===== 桥接 JSON 数据类 (与 ArkTS 侧协议对齐) =====

    /**
     * media 命令 (Kotlin → ArkTS, fire-and-forget via tsfn)。
     * playerId 无 Kotlin 默认值: KS_JSON 的 encodeDefaults=false 会把默认值字段整个省掉。
     */
    @Serializable
    private data class MediaCommand(
        val playerId: String,
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
        private const val TAG = "ohos-httts"

        /** TTS 缓存子目录名 (位于 [NativeBookStorage.defaultRootPath] 下, 与原版 httpTTS 目录名对齐)。 */
        private const val TTS_CACHE_DIR = "httpTTS"
    }
}
