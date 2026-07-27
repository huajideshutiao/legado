@file:Suppress("unused")

package io.legado.app.napi

import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙 napi 桥接基础设施: Kotlin → ArkTS 反向调用 (KP7+)。
 *
 * # 设计目的
 * 与 [LegadoNativeExports] (ArkTS → Kotlin, 用 @CName 导出) 反向。
 * 鸿蒙 `@ohos.promptAction.showToast` / `@ohos.notificationManager.publish` 仅提供 ArkTS API,
 * 无 NDK C 接口, Kotlin/Native 无法直接调用。本桥接通过 threadsafe_function 把 Kotlin 业务线程
 * 的调用调度到 ArkTS 主线程执行。
 *
 * # tsfn 注入模型
 * 当前阶段 cinterop (node_api.h) 未接入, tsfn 以 Kotlin lambda `(String) -> Unit` 抽象表示,
 * 接收 JSON 字符串并 dispatch 到 ArkTS。后续 legado_napi.cpp 实现 registerToastCallback /
 * registerNotificationCallback 后, 由 C++ 侧创建真实 napi_threadsafe_function 并通过 @CName
 * 注入口注入到此 object。
 *
 * # 降级策略
 * 未注册 tsfn 时降级为 println (兼容当前未接入 napi 阶段, stdout 由鸿蒙 runtime 重定向到 hilog)。
 *
 * # 跨语言传递格式
 * JSON 字符串 (与 LegadoNativeExports.kt 一致), 见 [ToastPayload] / [NotificationPayload]。
 *
 * # 线程安全
 * tsfn 引用用 [lock] + synchronized 块保护 (KMP 业务代码可能在 worker 线程调用)。
 *
 * 模式参考 [LegadoNativeExports] 与 nativeMain 下 NativeLocalBookLocator 的 synchronized 用法。
 */
object OhosNativeBridge {

    /** tsfn 注入回调类型: 接收 JSON 字符串, 内部 dispatch 到 ArkTS 主线程。 */
    private typealias TsfnCallback = (String) -> Unit

    /** tsfn 引用保护锁 (与 NativeLocalBookLocator.pathCache 同模式)。 */
    private val lock = Any()

    /** toast threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var toastTsfn: TsfnCallback? = null

    /** notification threadsafe_function 引用。 */
    @Volatile
    private var notificationTsfn: TsfnCallback? = null

    /** 注入 toast tsfn (由 legado_napi.cpp registerToastCallback 调用)。 */
    fun registerToastFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            toastTsfn = tsfn
        }
    }

    /** 注入 notification tsfn。 */
    fun registerNotificationFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            notificationTsfn = tsfn
        }
    }

    /**
     * 显示 toast。未注册 tsfn 时降级 println。
     *
     * @param message toast 文本
     * @param durationMs 显示时长 (对齐 Android Toast: 2000ms 短 / 3500ms 长)
     */
    fun showToast(message: String, durationMs: Int) {
        val json = KS_JSON.encodeToString(ToastPayload(message = message, durationMs = durationMs))
        val tsfn = synchronized(lock) { toastTsfn }
        if (tsfn != null) {
            runCatching { tsfn(json) }.onFailure {
                // tsfn 调用失败 (module 卸载 / 线程异常) 时降级 println
                println("[ohos-toast] tsfn call failed, fallback: $message")
            }
        } else {
            // 降级: 未注册 tsfn
            println("[ohos-toast] $message")
        }
    }

    /**
     * 显示/更新进度通知。未注册 tsfn 时降级 println。
     *
     * @param id 通知 id (由调用方稳定生成, 如 title.hashCode())
     * @param title 通知标题
     * @param content 通知正文 (已含进度文本, 如 "下载中 (50/100)")
     * @param progress 当前进度
     * @param max 最大进度 (<=0 视为不确定进度)
     */
    fun showNotification(id: Int, title: String, content: String, progress: Int, max: Int) {
        val json = KS_JSON.encodeToString(
            NotificationPayload(
                action = NotificationAction.SHOW,
                id = id,
                title = title,
                content = content,
                progress = progress,
                max = max,
            )
        )
        val tsfn = synchronized(lock) { notificationTsfn }
        if (tsfn != null) {
            runCatching { tsfn(json) }.onFailure {
                println("[ohos-notification] tsfn call failed, fallback: $title | $content")
            }
        } else {
            // 降级: 未注册 tsfn
            val progressText = if (max > 0) "$content ($progress/$max)" else content
            println("[ohos-notification] $title | $progressText")
        }
    }

    /**
     * 取消通知。未注册 tsfn 时降级 println。
     *
     * @param id 通知 id (与 showNotification 传入的 id 一致)
     */
    fun cancelNotification(id: Int) {
        val json = KS_JSON.encodeToString(
            NotificationPayload(action = NotificationAction.CANCEL, id = id)
        )
        val tsfn = synchronized(lock) { notificationTsfn }
        if (tsfn != null) {
            runCatching { tsfn(json) }.onFailure {
                println("[ohos-notification] tsfn call failed, fallback: cancel id=$id")
            }
        } else {
            // 降级: 未注册 tsfn
            println("[ohos-notification] cancel id=$id")
        }
    }

    // ===== FileDir / CacheDir 路径注入 (ArkTS → Kotlin 同步推送) =====

    /**
     * filesDir 沙盒路径 (ArkTS EntryAbility.onCreate 调 [registerFileDirFn] 注入)。
     *
     * # 与 toast/notification 的 tsfn 模型差异
     * toast/notification 是运行期事件 (Kotlin → ArkTS fire-and-forget), 用 tsfn 跨线程 dispatch,
     * 无返回值; 而目录路径是静态配置 (启动时已知, 不变), 消费方 (AppFilesDir/DatabaseDriver) 需
     * 同步读取, tsfn 无法回传值。故采用 ArkTS → Kotlin 同步推送: ArkTS 取 `context.filesDir` /
     * `context.cacheDir` 后, 经 @CName (legado_register_file_dir) 直接把路径字符串注入本字段。
     *
     * # 降级策略
     * 未注入 (null) 时, AppFilesDir 回退 POSIX `user.dir` 派生路径 (兼容 .so 未加载 / napi 未接入阶段,
     * 与历史行为一致; 对齐 toast 未注册 tsfn 时降级 println 的思路: 保证未接入阶段可运行)。
     *
     * # 线程安全
     * 与 tsfn 同用 [lock] + synchronized (AppFilesDir 可能在 worker 线程访问)。
     */
    @Volatile
    private var filesDirPath: String? = null

    /** cacheDir 沙盒路径 (同 [filesDirPath], ArkTS 注入)。 */
    @Volatile
    private var cacheDirPath: String? = null

    /**
     * 注入鸿蒙应用沙盒 filesDir 路径 (由 @CName legado_register_file_dir 调用)。
     *
     * 时机: 须在 [io.legado.app.help.config.registerOhosProviders] 之前注入, 使
     * OhosDatabaseDriver.defaultDbPath / BookStorage 等读到真实沙盒路径; 即便迟到
     * (LegadoNativeExports init 块提前触发 registerOhosProviders), AppFilesDirs 用计算 getter,
     * 显式 registerOhosProviders 重注册时 OhosDatabaseDriver 重建即读到真实路径 (自愈)。
     */
    fun registerFileDirFn(path: String) {
        synchronized(lock) {
            filesDirPath = path
        }
    }

    /** 注入鸿蒙应用沙盒 cacheDir 路径 (由 @CName legado_register_cache_dir 调用)。 */
    fun registerCacheDirFn(path: String) {
        synchronized(lock) {
            cacheDirPath = path
        }
    }

    /** 读取已注入的 filesDir 路径, 未注入返回 null (调用方回退 POSIX user.dir)。 */
    fun getFilesDir(): String? = synchronized(lock) { filesDirPath }

    /** 读取已注入的 cacheDir 路径, 未注入返回 null。 */
    fun getCacheDir(): String? = synchronized(lock) { cacheDirPath }

    // ===== Image 同步桥 (tsfn + callback, KP8+) =====
    // 图片操作 (decode/encode/size/crop/split/stitch) 是同步接口, 但 ArkTS @ohos.multimedia.image
    // 只能通过 napi 桥接异步调用。采用 "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式:
    //
    // 调用链 (以 decode 为例):
    // KMP OhosImageOps.decode(bytes)
    //   → invokeImageSync("decode", json)
    //   → 生成 requestId, 存入 imagePendingRequests (CompletableDeferred)
    //   → imageTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞 JS 引擎线程等待结果)
    //   → [ArkTS 主线程] image callback 收到 requestJson
    //   → image.createImageSource(bytes).createPixelMap() → 存入 pixelMaps Map<id, PixelMap>
    //   → legado.imageCallback(requestId, resultJson)  (napi → @CName legado_image_callback)
    //   → onImageResult(requestId, resultJson) → deferred.complete(resultJson)
    //   → runBlocking 返回, OhosImageOps.decode 返回 ImageRef(pixelMapId)
    //
    // 线程安全: JS 引擎线程阻塞等待, ArkTS 主线程回调 complete, 不同线程无死锁。

    /** image threadsafe_function 引用 (Kotlin → ArkTS 发送图片操作请求)。 */
    @Volatile
    private var imageTsfn: TsfnCallback? = null

    /** 待响应的图片同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val imagePendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** 图片请求自增 ID (原子性由 [lock] 保护)。 */
    private var imageRequestCounter = 0L

    /** 注入 image tsfn (由 legado_napi.cpp registerImageCallback 调用)。 */
    fun registerImageFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            imageTsfn = tsfn
        }
    }

    /**
     * 图片操作结果回调 (由 ArkTS 侧调 @CName legado_image_callback 触发)。
     * ArkTS 完成 decode/encode/size/crop/split/stitch 后, 把结果 JSON 通过 napi 回调推送给 Kotlin,
     * 唤醒 [invokeImageSync] 中阻塞的 CompletableDeferred。
     *
     * @param requestId 对应 [invokeImageSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (含 ok/result 或 ok/error 字段)
     */
    fun onImageResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { imagePendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用图片操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "decode" / "encode" / "size" / "crop" / "split" / "stitch" / "release"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 如 `{"bytes":"<base64>"}`)
     * @param timeoutMs 超时毫秒 (默认 15s, 防止 ArkTS 无响应永久阻塞)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方降级处理)
     */
    fun invokeImageSync(action: String, payloadJson: String, timeoutMs: Long = 15000L): String? {
        val requestId = synchronized(lock) { ++imageRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { imagePendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            ImageBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { imageTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (napi 未接入阶段), 移除 pending 请求返回 null
            synchronized(lock) { imagePendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { imagePendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (JS 引擎线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { imagePendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 image 桥是否已就绪 (tsfn 已注入)。
     * OhosImageOps 据此判断走真实 PixelMap 实现还是降级占位。
     */
    fun isImageBridgeReady(): Boolean = synchronized(lock) { imageTsfn != null }

    // ===== Media 事件桥 (tsfn fire-and-forget + @CName event callback, KP8+) =====
    // HttpTtsPlayer / OhosAudioPlayCommander 的 play/pause/stop/seekTo 是 fire-and-forget 命令
    // (无返回值), 用 tsfn dispatch 到 ArkTS 主线程操作 AVPlayer;
    // AVPlayer 事件 (onReady/onEndOfMedia/onError/onBufferingUpdate/duration/position)
    // 由 ArkTS 通过 @CName legado_media_event 回调推送, Kotlin 侧 [MediaEventListener] 接收。
    //
    // 多实例: 音频书与 HttpTTS 朗读需各持一个 AVPlayer, 命令/事件均带 playerId,
    // 监听器按 playerId 注册到 [mediaEventListeners]; 无 playerId 的旧消息按
    // [DEFAULT_PLAYER_ID] 处理。

    /** 缺省 playerId (无 playerId 字段的旧协议消息落到这里)。 */
    const val DEFAULT_PLAYER_ID: String = "default"

    /** 音频书播放实例 id (OhosAudioPlayCommander 使用)。 */
    const val PLAYER_ID_AUDIO_BOOK: String = "audioBook"

    /** HttpTTS 朗读播放实例 id (OhosHttpTtsPlayer 使用)。 */
    const val PLAYER_ID_HTTP_TTS: String = "httpTts"

    /** media threadsafe_function 引用 (Kotlin → ArkTS 发送播放器命令)。 */
    @Volatile
    private var mediaTsfn: TsfnCallback? = null

    /** media 事件监听器 Map<playerId, listener> (由各播放器按固定 id 注册)。 */
    private val mediaEventListeners = mutableMapOf<String, MediaEventListener>()

    /**
     * media 事件监听器接口 (ArkTS → Kotlin 推送 AVPlayer 事件)。
     * OhosHttpTtsPlayer / OhosAudioPlayCommander 实现此接口, 把事件转换为各自的回调。
     */
    fun interface MediaEventListener {
        fun onMediaEvent(eventJson: String)
    }

    /** 注入 media tsfn (由 legado_napi.cpp registerMediaCallback 调用)。 */
    fun registerMediaFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            mediaTsfn = tsfn
        }
    }

    /**
     * 按 playerId 设置 media 事件监听器 (由各播放器设置/清除)。
     *
     * @param playerId 播放实例 id, 如 [PLAYER_ID_AUDIO_BOOK] / [PLAYER_ID_HTTP_TTS]
     * @param listener null 表示注销
     */
    fun setMediaEventListener(playerId: String, listener: MediaEventListener?) {
        synchronized(lock) {
            if (listener == null) {
                mediaEventListeners.remove(playerId)
            } else {
                mediaEventListeners[playerId] = listener
            }
        }
    }

    /** 兼容旧签名: 未指定 playerId 时注册到 [DEFAULT_PLAYER_ID]。 */
    fun setMediaEventListener(listener: MediaEventListener?) {
        setMediaEventListener(DEFAULT_PLAYER_ID, listener)
    }

    /**
     * 发送 media 命令到 ArkTS (fire-and-forget, 无返回值)。
     * 命令类型: "setSource" / "setSourceUrl" / "play" / "pause" / "stop" / "seekTo" /
     * "setSpeed" / "release"。
     *
     * @param commandJson 命令 JSON (含 playerId + action + data, 由调用方序列化)
     */
    fun sendMediaCommand(commandJson: String) {
        val tsfn = synchronized(lock) { mediaTsfn }
        if (tsfn != null) {
            runCatching { tsfn(commandJson) }.onFailure {
                println("[ohos-media] tsfn call failed: $commandJson")
            }
        } else {
            // 降级: tsfn 未注册
            println("[ohos-media] tsfn not registered: $commandJson")
        }
    }

    /**
     * media 事件回调 (由 ArkTS 侧调 @CName legado_media_event 触发)。
     * ArkTS AVPlayer 状态变化 / 播放结束 / 错误 / 缓冲进度等事件通过 napi 回调推送给 Kotlin,
     * 按 playerId 转发给对应的 [MediaEventListener]。
     *
     * @param eventJson 事件 JSON (含 playerId + event, 如 `{"playerId":"httpTts","event":"onReady"}`)
     */
    fun onMediaEvent(eventJson: String) {
        val playerId = parsePlayerId(eventJson)
        val listener = synchronized(lock) { mediaEventListeners[playerId] }
        listener?.onMediaEvent(eventJson)
    }

    /**
     * 从事件 JSON 提取 playerId (缺失时返回 [DEFAULT_PLAYER_ID])。
     * 事件由 ArkTS 侧 JSON.stringify 生成, 字段名固定, 用轻量字符串扫描避开反序列化开销
     * (timeUpdate 事件高频推送, 每次全量反序列化不划算)。
     */
    private fun parsePlayerId(eventJson: String): String {
        val key = "\"playerId\""
        val keyIndex = eventJson.indexOf(key)
        if (keyIndex < 0) return DEFAULT_PLAYER_ID
        val colon = eventJson.indexOf(':', keyIndex + key.length)
        if (colon < 0) return DEFAULT_PLAYER_ID
        val start = eventJson.indexOf('"', colon + 1)
        if (start < 0) return DEFAULT_PLAYER_ID
        val end = eventJson.indexOf('"', start + 1)
        if (end < 0) return DEFAULT_PLAYER_ID
        val id = eventJson.substring(start + 1, end)
        return id.ifEmpty { DEFAULT_PLAYER_ID }
    }

    /**
     * 检查 media 桥是否已就绪 (tsfn 已注入)。
     * OhosHttpTtsPlayer / OhosAudioPlayCommander 据此判断走真实 AVPlayer 实现还是降级占位。
     */
    fun isMediaBridgeReady(): Boolean = synchronized(lock) { mediaTsfn != null }

    // ===== TTS 事件桥 (tsfn fire-and-forget + @CName event callback) =====
    // OhosSystemTtsEngine 的 speak/pause/resume/stop/shutdown 是 fire-and-forget 命令,
    // 用 tsfn dispatch 到 ArkTS 主线程操作 @ohos.textToSpeech;
    // TTS 事件 (onStart/onComplete/onStop/onError) 由 ArkTS 通过 @CName legado_tts_event 回调推送。

    /** tts threadsafe_function 引用 (Kotlin → ArkTS 发送 TTS 命令)。 */
    @Volatile
    private var ttsTsfn: TsfnCallback? = null

    /** tts 事件监听器 (由 [OhosSystemTtsEngine] 设置, 接收 ArkTS 推送的 TTS 事件)。 */
    @Volatile
    private var ttsEventListener: TtsEventListener? = null

    /**
     * TTS 事件监听器接口 (ArkTS → Kotlin 推送 TTS 事件)。
     * OhosSystemTtsEngine 实现此接口, 把事件转换为 [TtsProgressListener] 回调。
     */
    fun interface TtsEventListener {
        fun onTtsEvent(eventJson: String)
    }

    /** 注入 tts tsfn (由 legado_napi.cpp registerTtsCallback 调用)。 */
    fun registerTtsFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            ttsTsfn = tsfn
        }
    }

    /** 设置 tts 事件监听器 (由 OhosSystemTtsEngine 设置)。 */
    fun setTtsEventListener(listener: TtsEventListener) {
        synchronized(lock) {
            ttsEventListener = listener
        }
    }

    /** 当前监听器仍是 [listener] 时才清除并发送 shutdown，避免旧引擎释放新引擎。 */
    fun shutdownTtsIfListener(listener: TtsEventListener): Boolean {
        val commandJson = KS_JSON.encodeToString(TtsCommand(action = "shutdown"))
        return synchronized(lock) {
            if (ttsEventListener !== listener) return@synchronized false
            ttsEventListener = null
            val tsfn = ttsTsfn
            if (tsfn != null) {
                runCatching { tsfn(commandJson) }.onFailure {
                    println("[ohos-tts] tsfn call failed: $commandJson")
                }
            } else {
                println("[ohos-tts] tsfn not registered: $commandJson")
            }
            true
        }
    }

    /**
     * 发送 tts 命令到 ArkTS (fire-and-forget, 无返回值)。
     * 命令类型: "createEngine" / "speak" / "pause" / "resume" / "stop" / "shutdown"。
     *
     * @param commandJson 命令 JSON (含 action + text/utteranceId/rate/lang, 由调用方序列化)
     */
    fun sendTtsCommand(commandJson: String) {
        val tsfn = synchronized(lock) { ttsTsfn }
        if (tsfn != null) {
            runCatching { tsfn(commandJson) }.onFailure {
                println("[ohos-tts] tsfn call failed: $commandJson")
            }
        } else {
            // 降级: tsfn 未注册
            println("[ohos-tts] tsfn not registered: $commandJson")
        }
    }

    /**
     * 便捷方法: 构造 [TtsCommand] 并发送。
     * 内部用 [TtsCommand] 序列化为 JSON, 再调 [sendTtsCommand]。
     */
    fun sendTtsCommand(
        action: String,
        text: String? = null,
        utteranceId: String? = null,
        rate: Float? = null,
        lang: String? = null,
    ) {
        val json = KS_JSON.encodeToString(TtsCommand(action, text, utteranceId, rate, lang))
        sendTtsCommand(json)
    }

    /**
     * tts 事件回调 (由 ArkTS 侧调 @CName legado_tts_event 触发)。
     * ArkTS @ohos.textToSpeech 的 onStart/onComplete/onStop/onError 事件通过 napi 回调推送给 Kotlin,
     * 转发给 [ttsEventListener] (即 OhosSystemTtsEngine)。
     *
     * @param eventJson 事件 JSON (含 event + utteranceId, 如 `{"event":"onStart"}`)
     */
    fun onTtsEvent(eventJson: String) {
        val listener = synchronized(lock) { ttsEventListener }
        listener?.onTtsEvent(eventJson)
    }

    /**
     * 检查 tts 桥是否已就绪 (tsfn 已注入)。
     * OhosSystemTtsEngine 据此判断走真实 @ohos.textToSpeech 实现还是降级占位。
     */
    fun isTtsBridgeReady(): Boolean = synchronized(lock) { ttsTsfn != null }

    // ===== Crypto 同步桥 (tsfn + callback, KP8+) =====
    // 非对称加解密 + 签名/验签 (encrypt/decrypt/sign/verify) 是同步接口, 但 ArkTS
    // @ohos.security.cryptoFramework 只能通过 napi 桥接异步调用。采用与 Image 完全一致的
    // "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式:
    //
    // 调用链 (以 encrypt 为例):
    // KMP NativeAsymmetricCryptoOps.encrypt
    //   → invokeCryptoSync("encrypt", json)
    //   → 生成 requestId, 存入 cryptoPendingRequests (CompletableDeferred)
    //   → cryptoTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞 JS 引擎线程等待结果)
    //   → [ArkTS 主线程] crypto callback 收到 requestJson
    //   → CryptoBridgeHandler.handleCryptoRequest → cryptoFramework.createCipher/createSign/createVerify/createMd/createMac
    //   → legado.cryptoCallback(requestId, resultJson)  (napi → @CName legado_crypto_callback)
    //   → onCryptoResult(requestId, resultJson) → deferred.complete(resultJson)
    //   → runBlocking 返回, NativeAsymmetricCryptoOps.encrypt 解析 base64 返回 ByteArray

    /** crypto threadsafe_function 引用 (Kotlin → ArkTS 发送 crypto 操作请求)。 */
    @Volatile
    private var cryptoTsfn: TsfnCallback? = null

    /** 待响应的 crypto 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val cryptoPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** crypto 请求自增 ID (原子性由 [lock] 保护)。 */
    private var cryptoRequestCounter = 0L

    /** 注入 crypto tsfn (由 legado_napi.cpp registerCryptoCallback 调用)。 */
    fun registerCryptoFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            cryptoTsfn = tsfn
        }
    }

    /**
     * crypto 操作结果回调 (由 ArkTS 侧调 @CName legado_crypto_callback 触发)。
     * ArkTS 完成 encrypt/decrypt/sign/verify 后, 把结果 JSON 通过 napi 回调推送给 Kotlin,
     * 唤醒 [invokeCryptoSync] 中阻塞的 CompletableDeferred。
     *
     * @param requestId 对应 [invokeCryptoSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (含 ok/data 或 ok/result 或 ok/error 字段)
     */
    fun onCryptoResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { cryptoPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用 crypto 操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "encrypt" / "decrypt" / "sign" / "verify" / "digest" / "hmac" / "aesEncrypt" / "aesDecrypt"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 含 algorithm/key/data 等)
     * @param timeoutMs 超时毫秒 (默认 30s, crypto 比 image 慢, 给更长超时)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方抛异常)
     */
    fun invokeCryptoSync(action: String, payloadJson: String, timeoutMs: Long = 30000L): String? {
        val requestId = synchronized(lock) { ++cryptoRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { cryptoPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            CryptoBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { cryptoTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (napi 未接入阶段), 移除 pending 请求返回 null
            synchronized(lock) { cryptoPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { cryptoPendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (JS 引擎线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { cryptoPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 crypto 桥是否已就绪 (tsfn 已注入)。
     * NativeAsymmetricCryptoOps / NativeSignOps 据此判断走真实 cryptoFramework 实现还是抛异常。
     */
    fun isCryptoBridgeReady(): Boolean = synchronized(lock) { cryptoTsfn != null }

    // ===== Http 同步桥 (tsfn + callback, KP8+) =====
    // KmpHttpClient 的 newCall/execute 是同步接口, 但 ArkTS @ohos.net.http 只能通过 napi 桥接异步调用。
    // 采用与 Image/Crypto 完全一致的 "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式:
    //
    // 调用链 (以 execute 为例):
    // KMP OhosKmpCall.execute
    //   → invokeHttpSync("execute", json)
    //   → 生成 requestId, 存入 httpPendingRequests (CompletableDeferred)
    //   → httpTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] HttpBridgeHandler.handleHttpRequest
    //   → http.createHttp().request(url, options, callback)
    //   → legado.httpCallback(requestId, resultJson)  (napi → @CName legado_http_callback)
    //   → onHttpResult(requestId, resultJson) → deferred.complete(resultJson)
    //   → runBlocking 返回, OhosKmpCall 解析响应

    /** http threadsafe_function 引用 (Kotlin → ArkTS 发送 HTTP 请求)。 */
    @Volatile
    private var httpTsfn: TsfnCallback? = null

    /** 待响应的 http 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val httpPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** http 请求自增 ID (原子性由 [lock] 保护)。 */
    private var httpRequestCounter = 0L

    /** 注入 http tsfn (由 legado_napi.cpp registerHttpCallback 调用)。 */
    fun registerHttpFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            httpTsfn = tsfn
        }
    }

    /**
     * http 请求结果回调 (由 ArkTS 侧调 @CName legado_http_callback 触发)。
     * ArkTS 完成 HTTP 请求后, 把结果 JSON 通过 napi 回调推送给 Kotlin,
     * 唤醒 [invokeHttpSync] 中阻塞的 CompletableDeferred。
     */
    fun onHttpResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { httpPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用 http 操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "execute" / "cancel"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 含 url/method/headers/body 等)
     * @param timeoutMs 超时毫秒 (默认 60s, HTTP 请求可能较慢)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方抛异常)
     */
    fun invokeHttpSync(action: String, payloadJson: String, timeoutMs: Long = 60000L): String? {
        val requestId = synchronized(lock) { ++httpRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { httpPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            HttpBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { httpTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (napi 未接入阶段), 移除 pending 请求返回 null
            synchronized(lock) { httpPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { httpPendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (JS 引擎线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { httpPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 http 桥是否已就绪 (tsfn 已注入)。
     * KmpHttpClientBuilder.build 据此判断走真实 @ohos.net.http 实现还是抛异常。
     */
    fun isHttpBridgeReady(): Boolean = synchronized(lock) { httpTsfn != null }

    // ===== OpenUrl tsfn (KMP → ArkTS, fire-and-forget, 同 Toast 模式) =====
    // OhosOpenUrlProvider.openUrl 经 tsfn dispatch 到 ArkTS, 由 SystemBridgeHandler.handleOpenUrl
    // 调 context.startAbility(Want.uri=url) 打开 URL (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接)。

    /** openUrl threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var openUrlTsfn: TsfnCallback? = null

    /** 注入 openUrl tsfn (由 legado_napi.cpp RegisterOpenUrlCallback 调用)。 */
    fun registerOpenUrlFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            openUrlTsfn = tsfn
        }
    }

    /**
     * 打开 URL (跨线程 dispatch 到 ArkTS context.startAbility)。未注册 tsfn 时降级 println。
     *
     * @param url 要打开的 URL (http/https/file/intent 等, 由 ArkTS 侧 system 选择合适应用打开)
     * @param mimeType MIME 类型 (可为空, 作为 Want.type 传给 startAbility)
     * @param sourceKey 调用源 key (调试用, 透传给 ArkTS 便于日志追踪)
     * @param sourceTag 调用源 tag (调试用, 透传给 ArkTS 便于日志追踪)
     * @param sourceType 调用源类型 (调试用, 透传给 ArkTS 便于日志追踪)
     */
    fun openUrl(url: String, mimeType: String?, sourceKey: String?, sourceTag: String?, sourceType: Int) {
        val json = KS_JSON.encodeToString(
            OpenUrlPayload(
                url = url,
                mimeType = mimeType,
                sourceKey = sourceKey,
                sourceTag = sourceTag,
                sourceType = sourceType,
            )
        )
        val tsfn = synchronized(lock) { openUrlTsfn }
        if (tsfn != null) {
            runCatching { tsfn(json) }.onFailure {
                // tsfn 调用失败 (module 卸载 / 线程异常) 时降级 println
                println("[ohos-open-url] tsfn call failed, fallback: $url")
            }
        } else {
            // 降级: 未注册 tsfn
            println("[ohos-open-url] $url")
        }
    }

    /**
     * 检查 openUrl 桥是否已就绪 (tsfn 已注入)。
     * OhosOpenUrlProviderImpl 据此判断走真实 startAbility 还是降级 println (兼容 napi 未接入阶段)。
     */
    fun isOpenUrlBridgeReady(): Boolean = synchronized(lock) { openUrlTsfn != null }

    // ===== FilePicker 同步桥 (tsfn + callback, KP8+, 同 Image/Crypto/Http 模式) =====
    // 文件选择 (pickDocuments/pickDocumentContent) 是同步接口, 但 ArkTS @ohos.file.picker.DocumentViewPicker
    // 与 @ohos.file.fs 只能通过 napi 桥接异步调用。采用与 Image 完全一致的
    // "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式:
    //
    // 调用链 (以 pickDocuments 为例):
    // KMP OhosFilePicker.pickDocuments
    //   → invokeFilePickerSync("pickDocuments", json)
    //   → 生成 requestId, 存入 filePickerPendingRequests (CompletableDeferred)
    //   → filePickerTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] FilePickerBridgeHandler.handleFilePickerRequest
    //   → @ohos.file.picker.DocumentViewPicker.select / @ohos.file.fs.openSync+readSync
    //   → legado.filePickerCallback(requestId, resultJson)  (napi → @CName legado_file_picker_callback)
    //   → onFilePickerResult(requestId, resultJson) → deferred.complete(resultJson)
    //   → runBlocking 返回, OhosFilePicker 解析 uris / base64 data

    /** filePicker threadsafe_function 引用 (Kotlin → ArkTS 发送文件选择请求)。 */
    @Volatile
    private var filePickerTsfn: TsfnCallback? = null

    /** 待响应的 filePicker 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val filePickerPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** filePicker 请求自增 ID (原子性由 [lock] 保护)。 */
    private var filePickerRequestCounter = 0L

    /** 注入 filePicker tsfn (由 legado_napi.cpp RegisterFilePickerCallback 调用)。 */
    fun registerFilePickerFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            filePickerTsfn = tsfn
        }
    }

    /**
     * filePicker 操作结果回调 (由 ArkTS 侧调 @CName legado_file_picker_callback 触发)。
     * ArkTS 完成 pickDocuments/pickDocumentContent 后, 把结果 JSON 通过 napi 回调推送给 Kotlin,
     * 唤醒 [invokeFilePickerSync] 中阻塞的 CompletableDeferred。
     *
     * @param requestId 对应 [invokeFilePickerSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (含 ok/uris 或 ok/data 或 ok/error 字段)
     */
    fun onFilePickerResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { filePickerPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用 filePicker 操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "pickDocuments" / "pickDocumentContent"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 含 contentTypes/allowsMultiple 或 uri)
     * @param timeoutMs 超时毫秒 (默认 60s, 用户选文件可能耗时较长)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方降级处理)
     */
    fun invokeFilePickerSync(action: String, payloadJson: String, timeoutMs: Long = 60000L): String? {
        val requestId = synchronized(lock) { ++filePickerRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { filePickerPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            FilePickerBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { filePickerTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (napi 未接入阶段), 移除 pending 请求返回 null
            synchronized(lock) { filePickerPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { filePickerPendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (JS 引擎线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { filePickerPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 filePicker 桥是否已就绪 (tsfn 已注入)。
     * OhosFilePicker 据此判断走真实 DocumentViewPicker 实现还是降级返回 null。
     */
    fun isFilePickerBridgeReady(): Boolean = synchronized(lock) { filePickerTsfn != null }

    // ===== Pasteboard 同步桥 (tsfn + callback, 同 Image/Crypto/Http/FilePicker 模式) =====
    // 剪贴板读写走 ArkTS @ohos.pasteboard.getSystemPasteboard(), 无 NDK C 接口。
    // readFromClipboard 需返回值, 故读写统一走同步请求/响应模式 (写也回 ok 以便调用方感知失败)。
    //
    // 调用链 (以 read 为例):
    // KMP OhosPlatformOps.readFromClipboard()
    //   → invokePasteboardSync("read", "{}")
    //   → 生成 requestId, 存入 pasteboardPendingRequests (CompletableDeferred)
    //   → pasteboardTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] PasteboardBridgeHandler.handlePasteboardRequest
    //   → pasteboard.getSystemPasteboard().getData() → getPrimaryText()
    //   → legado.pasteboardCallback(requestId, resultJson)  (napi → @CName legado_pasteboard_callback)
    //   → onPasteboardResult(requestId, resultJson) → deferred.complete(resultJson)

    /** pasteboard threadsafe_function 引用 (Kotlin → ArkTS 发送剪贴板请求)。 */
    @Volatile
    private var pasteboardTsfn: TsfnCallback? = null

    /** 待响应的 pasteboard 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val pasteboardPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** pasteboard 请求自增 ID (原子性由 [lock] 保护)。 */
    private var pasteboardRequestCounter = 0L

    /** 注入 pasteboard tsfn (由 legado_napi.cpp RegisterPasteboardCallback 调用)。 */
    fun registerPasteboardFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            pasteboardTsfn = tsfn
        }
    }

    /**
     * pasteboard 操作结果回调 (由 ArkTS 侧调 @CName legado_pasteboard_callback 触发)。
     *
     * @param requestId 对应 [invokePasteboardSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (含 ok/text 或 ok/error 字段)
     */
    fun onPasteboardResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { pasteboardPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用剪贴板操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "read" / "write"
     * @param payloadJson 操作参数 JSON (write 时为 `{"text":"..."}`, read 为 `{}`)
     * @param timeoutMs 超时毫秒 (默认 5s, 剪贴板操作应当极快)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方降级处理)
     */
    fun invokePasteboardSync(action: String, payloadJson: String, timeoutMs: Long = 5000L): String? {
        val requestId = synchronized(lock) { ++pasteboardRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { pasteboardPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            PasteboardBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { pasteboardTsfn }
        if (tsfn == null) {
            synchronized(lock) { pasteboardPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            synchronized(lock) { pasteboardPendingRequests.remove(requestId) }
            return null
        }

        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { pasteboardPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 pasteboard 桥是否已就绪 (tsfn 已注入)。
     * OhosPlatformOps 据此判断走真实 SystemPasteboard 还是降级 println/null。
     */
    fun isPasteboardBridgeReady(): Boolean = synchronized(lock) { pasteboardTsfn != null }

    // ===== TextCodec 同步桥 (tsfn + callback, 同 Crypto/Pasteboard 模式) =====
    // TXT 解析的 GB18030/Big5 编解码走 ArkTS @ohos.util.TextDecoder/TextEncoder
    // (支持 gbk/gb18030/big5), 无 NDK C 接口, 需 tsfn 桥接。
    //
    // 调用链 (以 decode 为例):
    // KMP platformDecodeCjk (TextCharsetCodec.ohos.kt)
    //   → invokeTextCodecSync("decode", {charset, data(Base64)})
    //   → 生成 requestId, 存入 textCodecPendingRequests (CompletableDeferred)
    //   → textCodecTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] TextCodecBridgeHandler.handleTextCodecRequest
    //   → new util.TextDecoder(charset).decodeToString(bytes) / new util.TextEncoder(charset).encodeInto(text)
    //   → legado.textCodecCallback(requestId, resultJson)  (napi → @CName legado_text_codec_callback)
    //   → onTextCodecResult(requestId, resultJson) → deferred.complete(resultJson)

    /** textCodec threadsafe_function 引用 (Kotlin → ArkTS 发送编解码请求)。 */
    @Volatile
    private var textCodecTsfn: TsfnCallback? = null

    /** 待响应的 textCodec 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val textCodecPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** textCodec 请求自增 ID (原子性由 [lock] 保护)。 */
    private var textCodecRequestCounter = 0L

    /** 注入 textCodec tsfn (由 legado_napi.cpp RegisterTextCodecCallback 调用)。 */
    fun registerTextCodecFn(tsfn: TsfnCallback) {
        synchronized(lock) {
            textCodecTsfn = tsfn
        }
    }

    /**
     * textCodec 操作结果回调 (由 ArkTS 侧调 @CName legado_text_codec_callback 触发)。
     *
     * @param requestId 对应 [invokeTextCodecSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (含 ok/text 或 ok/data 或 ok/error 字段)
     */
    fun onTextCodecResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { textCodecPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用文本编解码 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "decode" (字节→字符串) / "encode" (字符串→字节)
     * @param payloadJson 操作参数 JSON (decode: `{charset, data(Base64)}`, encode: `{charset, text}`)
     * @param timeoutMs 超时毫秒 (默认 15s: TXT 分章单块最大 512KB, Base64+跨线程往返留裕量)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方按"平台不支持"降级)
     */
    fun invokeTextCodecSync(action: String, payloadJson: String, timeoutMs: Long = 15000L): String? {
        val requestId = synchronized(lock) { ++textCodecRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { textCodecPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            TextCodecBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { textCodecTsfn }
        if (tsfn == null) {
            synchronized(lock) { textCodecPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            synchronized(lock) { textCodecPendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (业务线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { textCodecPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 textCodec 桥是否已就绪 (tsfn 已注入)。
     * TextCharsetCodec.ohos.kt 据此判断走真实 TextDecoder/TextEncoder 还是保持"暂不支持请转码"。
     */
    fun isTextCodecBridgeReady(): Boolean = synchronized(lock) { textCodecTsfn != null }

    /** toast 跨语言传递 payload (序列化为 JSON 给 ArkTS)。 */
    @Serializable
    private data class ToastPayload(
        val message: String,
        val durationMs: Int,
    )

    /** notification 跨语言传递 payload。show 携带 title/content/progress/max, cancel 仅 id。 */
    @Serializable
    private data class NotificationPayload(
        val action: NotificationAction,
        val id: Int,
        val title: String? = null,
        val content: String? = null,
        val progress: Int? = null,
        val max: Int? = null,
    )

    /** notification 操作类型 (ArkTS 侧据 action 分支 show/cancel)。 */
    @Serializable
    private enum class NotificationAction { SHOW, CANCEL }

    /** image 桥请求 payload (Kotlin → ArkTS, 包含 requestId/action/payload)。 */
    @Serializable
    private data class ImageBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** crypto 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=encrypt/decrypt/sign/verify)。 */
    @Serializable
    private data class CryptoBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** http 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=execute/cancel)。 */
    @Serializable
    private data class HttpBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** filePicker 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=pickDocuments/pickDocumentContent)。 */
    @Serializable
    private data class FilePickerBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** pasteboard 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=read/write)。 */
    @Serializable
    private data class PasteboardBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** textCodec 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=decode/encode)。 */
    @Serializable
    private data class TextCodecBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** openUrl 跨语言传递 payload (序列化为 JSON 给 ArkTS, 同 Toast 模式 fire-and-forget)。 */
    @Serializable
    private data class OpenUrlPayload(
        val url: String,
        val mimeType: String? = null,
        val sourceKey: String? = null,
        val sourceTag: String? = null,
        val sourceType: Int = 0,
    )

    /** TTS 命令 payload (Kotlin → ArkTS, createEngine/speak/pause/resume/stop/shutdown)。 */
    @Serializable
    private data class TtsCommand(
        val action: String,
        val text: String? = null,
        val utteranceId: String? = null,
        val rate: Float? = null,
        val lang: String? = null,
    )
}
