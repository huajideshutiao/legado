@file:Suppress("unused")

package io.legado.app.napi

import io.legado.app.constant.AppLog
import io.legado.app.utils.KS_JSON
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

typealias OhosTsfnCallback = (String) -> Unit

/**
 * webView 桥专用 tsfn 回调类型 (混合协议): 控制面 JSON + 数据面裸 html 字符串。
 *
 * 与 [OhosTsfnCallback] 的单字符串不同, 书源 webView 规则可能携带大段 HTML
 * (整章/整页, 数百 KB~数 MB)。若把 html 塞进 JSON payload, KS_JSON 转义会膨胀体积
 * (引号/反斜杠/换行 → \\uXXXX 等) 且 K/N 与 ArkTS 双端各多一次编码/解码拷贝。
 * 故 html 作为第二参数裸字符串经 napi_create_string_utf8 直接传递, 不做任何 JSON 转义。
 *
 * @param jsonControl 控制面 JSON 字符串 (含 requestId/action/payload, payload 为
 *   WebViewRequestPayload 但不含 html 字段)
 * @param htmlRaw 数据面裸 HTML 字符串 (html 入参, 空串表示无 html)
 */
typealias OhosWebViewTsfnCallback = (jsonControl: String, htmlRaw: String) -> Unit

/**
 * Http/Image 桥专用 tsfn 回调类型 (混合协议): 控制面 JSON + 数据面裸字节。
 *
 * 与 [OhosWebViewTsfnCallback] (裸 html 字符串) 同思路, 但 HTTP 请求/响应 body 与图片字节是
 * **任意二进制** (下载场景可能是图片/压缩包等), 不能走 UTF-8 字符串保真, 故用 napi ArrayBuffer:
 * C++ tsfn call-js 回调把 [bytes] 用 napi_create_external_arraybuffer 包装成 ArrayBuffer
 * 作为第二参数传给 ArkTS 回调, 不经 base64 (避免 33% 体积膨胀 + 双端编解码拷贝)。
 *
 * @param jsonControl 控制面 JSON 字符串 (含 requestId/action/payload, payload 不含大字节字段)
 * @param bytes 数据面裸字节 (null 表示无字节面, C++ 侧转成 undefined 参数)
 */
typealias OhosBinaryTsfnCallback = (jsonControl: String, bytes: ByteArray?) -> Unit

/**
 * Http/Image 同步桥统一响应 (混合协议): 控制面 JSON + 数据面裸字节。
 * ArkTS → Kotlin 经 @CName legado_xxx_callback(requestId, resultJson, arrayBuffer) 回传,
 * C++ 用 napi_get_arraybuffer_info 零拷贝取 ArrayBuffer 数据指针, Kotlin 侧拷入 ByteArray。
 *
 * @param json 控制面结果 JSON (ok/error 等小字段, 不含大字节)
 * @param bytes 数据面裸字节 (HTTP 响应 body / encode 的 packed 图片; 无字节面时为 null)
 */
data class OhosBinaryBridgeResponse(
    val json: String,
    val bytes: ByteArray? = null,
)

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
 * 未注册 tsfn 时命令丢弃, 只经 [dispatchTsfn] 留一条 recordLog 门控的降级痕迹
 * (兼容当前未接入 napi 阶段)。
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

    /** tsfn 引用保护锁 (与 NativeLocalBookLocator.pathCache 同模式)。 */
    private val lock = SynchronizedObject()

    /**
     * fire-and-forget tsfn 统一派发口 (全部 Kotlin -> ArkTS 命令共用)。
     *
     * 调用失败按错误记账 ([AppLog.put], 必然可见); 未注入 tsfn 只是 napi 未接入阶段的降级痕迹,
     * 且 window/keyboard/media 命令高频触发, 走 [AppLog.putDebug] 的 recordLog 门控,
     * 免得把环形日志刷满顶掉真错误。
     *
     * @param detail 日志定位信息 (payload / url / 选中文本)
     */
    private fun dispatchTsfn(
        tsfn: OhosTsfnCallback?,
        json: String,
        tag: String,
        detail: String,
    ) {
        if (tsfn == null) {
            AppLog.putDebug("tsfn 未注册, 丢弃: $detail", tag = tag)
            return
        }
        runCatching { tsfn(json) }.onFailure {
            AppLog.put("tsfn 调用失败: $detail", it, tag = tag)
        }
    }

    /** toast threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var toastTsfn: OhosTsfnCallback? = null

    /** notification threadsafe_function 引用。 */
    @Volatile
    private var notificationTsfn: OhosTsfnCallback? = null

    /** 注入 toast tsfn (由 legado_napi.cpp registerToastCallback 调用)。 */
    fun registerToastFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            toastTsfn = tsfn
        }
    }

    /** 注入 notification tsfn。 */
    fun registerNotificationFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            notificationTsfn = tsfn
        }
    }

    /**
     * 显示 toast。未注册 tsfn 时丢弃 (见 [dispatchTsfn])。
     *
     * @param message toast 文本
     * @param durationMs 显示时长 (对齐 Android Toast: 2000ms 短 / 3500ms 长)
     */
    fun showToast(message: String, durationMs: Int) {
        val json = KS_JSON.encodeToString(ToastPayload(message = message, durationMs = durationMs))
        dispatchTsfn(synchronized(lock) { toastTsfn }, json, "ohos-toast", message)
    }

    /**
     * 显示/更新进度通知。未注册 tsfn 时丢弃 (见 [dispatchTsfn])。
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
        val progressText = if (max > 0) "$content ($progress/$max)" else content
        dispatchTsfn(
            synchronized(lock) { notificationTsfn },
            json,
            "ohos-notification",
            "$title | $progressText",
        )
    }

    /**
     * 取消通知。未注册 tsfn 时丢弃 (见 [dispatchTsfn])。
     *
     * @param id 通知 id (与 showNotification 传入的 id 一致)
     */
    fun cancelNotification(id: Int) {
        val json = KS_JSON.encodeToString(
            NotificationPayload(action = NotificationAction.CANCEL, id = id)
        )
        dispatchTsfn(
            synchronized(lock) { notificationTsfn },
            json,
            "ohos-notification",
            "cancel id=$id",
        )
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
     * 与历史行为一致; 对齐 toast 未注册 tsfn 时丢弃命令的思路: 保证未接入阶段可运行)。
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

    // ===== 屏幕尺寸注入 (ArkTS → Kotlin 同步推送, 同 FileDir 模式) =====

    /**
     * 显示物理像素尺寸 (ArkTS EntryAbility.onWindowStageCreate 调 [registerScreenSizeFn] 注入)。
     *
     * # 与 FileDir 同模型
     * 屏幕尺寸是启动早期即知的静态配置, 消费方 ([ScreenInfoProviders] / sharedUiMain AppDialogSizes)
     * 需同步读取, tsfn 无法回传值, 故采用 ArkTS → Kotlin 同步推送: ArkTS 取
     * `display.getDefaultDisplaySync()` (vp 尺寸 × densityPixels → 物理像素) 后, 经
     * @CName (legado_register_screen_size) 直接把宽高注入本字段。
     *
     * # 降级策略
     * 未注入 (0) 时 [OhosScreenInfoProvider] 回退默认 1080x2340 (兼容 napi 未接入阶段;
     * 对齐 AppFilesDir 未注入回退 POSIX user.dir 的思路, 保证不崩)。
     */
    @Volatile
    private var screenWidthPx: Int = 0

    /** 显示物理像素高度 (同 [screenWidthPx], ArkTS 注入)。 */
    @Volatile
    private var screenHeightPx: Int = 0

    /**
     * 注入显示物理像素尺寸 (由 @CName legado_register_screen_size 调用)。
     *
     * 时机: EntryAbility.onWindowStageCreate 中 loadContent 之前 (任何 shared 对话框
     * 尺寸计算之前; AppDialogSizes 未注册时 get() 直接 error 导致对话框崩溃)。
     */
    fun registerScreenSizeFn(widthPx: Int, heightPx: Int) {
        synchronized(lock) {
            screenWidthPx = widthPx
            screenHeightPx = heightPx
        }
    }

    /** 读取已注入的显示物理像素尺寸, 未注入返回 (0, 0) (调用方自行兜底)。 */
    fun getScreenSizePx(): Pair<Int, Int> = synchronized(lock) { screenWidthPx to screenHeightPx }

    // ===== Image 同步桥 (tsfn + callback, 混合协议: 控制面 JSON + 数据面裸字节, KP8+) =====
    // 图片操作 (decode/encode/size/crop/split/stitch) 是同步接口, 但 ArkTS @ohos.multimedia.image
    // 只能通过 napi 桥接异步调用。采用 "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式,
    // 传输为混合协议 (同 WebView 桥思路, 大字节面改走 napi 裸参数):
    //
    // 请求侧 (Kotlin → ArkTS):
    //   - 控制面: ImageBridgeRequest(requestId, action, payload) JSON (小字段);
    //   - 数据面: decode 的图片字节作为 tsfn 回调第二参数 napi ArrayBuffer 裸传
    //     (不经 base64, 避免 33% 膨胀 + 双端编解码拷贝; 图片是任意二进制, ArrayBuffer 保真);
    // 结果侧 (ArkTS → Kotlin):
    //   - 控制面: resultJson ({ok,pixelMapId,...} / {ok:false,error}, 不含字节);
    //   - 数据面: encode 的 packed 字节作为 legado.imageCallback 第三参数 ArrayBuffer 回传。
    //
    // 调用链 (以 decode 为例):
    // KMP OhosImageOps.decode(bytes)
    //   → invokeImageSync("decode", json, bytes)
    //   → 生成 requestId, 存入 imagePendingRequests (CompletableDeferred<OhosBinaryBridgeResponse>)
    //   → imageTsfn(requestJson, bytes)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞 JS 引擎线程等待结果)
    //   → [ArkTS 主线程] image callback 收到 (requestJson, bytesArrayBuffer)
    //   → image.createImageSource(bytes).createPixelMap() → 存入 pixelMaps Map<id, PixelMap>
    //   → legado.imageCallback(requestId, resultJson, packed?)  (napi → @CName legado_image_callback)
    //   → onImageResult(requestId, resultJson, bodyBytes) → deferred.complete(OhosBinaryBridgeResponse)
    //   → runBlocking 返回, OhosImageOps.decode 返回 ImageRef(pixelMapId)
    //
    // 线程安全: JS 引擎线程阻塞等待, ArkTS 主线程回调 complete, 不同线程无死锁。

    /** image threadsafe_function 引用 (Kotlin → ArkTS 发送图片操作请求, 混合协议双参)。 */
    @Volatile
    private var imageTsfn: OhosBinaryTsfnCallback? = null

    /** 待响应的图片同步请求 Map<requestId, CompletableDeferred<OhosBinaryBridgeResponse>>。 */
    private val imagePendingRequests =
        mutableMapOf<Long, CompletableDeferred<OhosBinaryBridgeResponse>>()

    /** 图片请求自增 ID (原子性由 [lock] 保护)。 */
    private var imageRequestCounter = 0L

    /** 注入 image tsfn (由 legado_napi.cpp registerImageCallback 调用)。 */
    fun registerImageFn(tsfn: OhosBinaryTsfnCallback) {
        synchronized(lock) {
            imageTsfn = tsfn
        }
    }

    /**
     * 图片操作结果回调 (由 ArkTS 侧调 @CName legado_image_callback 触发, 混合协议)。
     * ArkTS 完成 decode/encode/size/crop/split/stitch 后, 把控制面结果 JSON 与数据面
     * 裸字节 (encode 的 packed 图片) 分两路推送给 Kotlin, 唤醒 [invokeImageSync] 中阻塞的
     * CompletableDeferred。
     *
     * @param requestId 对应 [invokeImageSync] 生成的请求 ID
     * @param resultJson 控制面结果 JSON (含 ok/pixelMapId 或 ok/error 字段, 不含字节)
     * @param bodyBytes 数据面裸字节 (encode 时非空; 其余操作/失败时为 null)
     */
    fun onImageResult(requestId: Long, resultJson: String, bodyBytes: ByteArray? = null) {
        val deferred = synchronized(lock) { imagePendingRequests.remove(requestId) }
        deferred?.complete(OhosBinaryBridgeResponse(resultJson, bodyBytes))
    }

    /**
     * 同步调用图片操作 (阻塞等待 ArkTS 返回结果, 混合协议)。
     *
     * @param action 操作类型: "decode" / "encode" / "size" / "crop" / "split" / "stitch" / "release"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 控制面小字段; decode 的图片字节
     *   改走 [bytes] 裸参数, 不再 base64 进 JSON)
     * @param bytes 数据面裸字节 (仅 decode 传图片字节; 其余操作传 null)
     * @param timeoutMs 超时毫秒 (默认 15s, 防止 ArkTS 无响应永久阻塞)
     * @return ArkTS 返回的 [OhosBinaryBridgeResponse] (控制面 JSON + 数据面裸字节);
     *         tsfn 未注册或超时返回 null (调用方降级处理)
     */
    fun invokeImageSync(
        action: String,
        payloadJson: String,
        bytes: ByteArray? = null,
        timeoutMs: Long = 15000L,
    ): OhosBinaryBridgeResponse? {
        val requestId = synchronized(lock) { ++imageRequestCounter }
        val deferred = CompletableDeferred<OhosBinaryBridgeResponse>()
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
        runCatching { tsfn(requestJson, bytes) }.onFailure {
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

    /** 视频书播放实例 id (OhosVideoPlayerController 使用, 独占 AVPlayer 实例)。 */
    const val PLAYER_ID_VIDEO_BOOK: String = "videoBook"

    /** media threadsafe_function 引用 (Kotlin → ArkTS 发送播放器命令)。 */
    @Volatile
    private var mediaTsfn: OhosTsfnCallback? = null

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
    fun registerMediaFn(tsfn: OhosTsfnCallback) {
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
        dispatchTsfn(synchronized(lock) { mediaTsfn }, commandJson, "ohos-media", commandJson)
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
    private var ttsTsfn: OhosTsfnCallback? = null

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
    fun registerTtsFn(tsfn: OhosTsfnCallback) {
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

    /**
     * 当前监听器仍是 [listener] 时才清除并发送 shutdown，避免旧引擎释放新引擎。
     *
     * "查监听器 + 清空" 在锁内原子完成; 派发放到锁外 —— [dispatchTsfn] 会走 AppLog,
     * 而 AppLog 的 toast 出口在鸿蒙端反过来要 [lock] (Toasters → showToast),
     * 锁内派发即构成 AppLog.lock / [lock] 的反序死锁。
     */
    fun shutdownTtsIfListener(listener: TtsEventListener): Boolean {
        val commandJson = KS_JSON.encodeToString(TtsCommand(action = "shutdown"))
        val tsfn = synchronized(lock) {
            if (ttsEventListener !== listener) return false
            ttsEventListener = null
            ttsTsfn
        }
        dispatchTsfn(tsfn, commandJson, "ohos-tts", commandJson)
        return true
    }

    /**
     * 发送 tts 命令到 ArkTS (fire-and-forget, 无返回值)。
     * 命令类型: "createEngine" / "speak" / "pause" / "resume" / "stop" / "shutdown"。
     *
     * @param commandJson 命令 JSON (含 action + text/utteranceId/rate/lang, 由调用方序列化)
     */
    fun sendTtsCommand(commandJson: String) {
        dispatchTsfn(synchronized(lock) { ttsTsfn }, commandJson, "ohos-tts", commandJson)
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
    private var cryptoTsfn: OhosTsfnCallback? = null

    /** 待响应的 crypto 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val cryptoPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** crypto 请求自增 ID (原子性由 [lock] 保护)。 */
    private var cryptoRequestCounter = 0L

    /** 注入 crypto tsfn (由 legado_napi.cpp registerCryptoCallback 调用)。 */
    fun registerCryptoFn(tsfn: OhosTsfnCallback) {
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

    // ===== Http 同步桥 (tsfn + callback, 混合协议: 控制面 JSON + 数据面裸字节, KP8+) =====
    // KmpHttpClient 的 newCall/execute 是同步接口, 但 ArkTS @ohos.net.http 只能通过 napi 桥接异步调用。
    // 采用与 Image/Crypto 完全一致的 "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式,
    // 传输为混合协议 (同 WebView 桥思路, 大字节面改走 napi 裸参数):
    //
    // 请求侧 (Kotlin → ArkTS):
    //   - 控制面: HttpBridgeRequest(requestId, action, payload) JSON (url/method/headers/timeout 等小字段);
    //   - 数据面: 请求 body 字节作为 tsfn 回调第二参数 napi ArrayBuffer 裸传
    //     (不经 base64, 避免 33% 膨胀 + 双端编解码拷贝; body 是任意二进制, ArrayBuffer 保真);
    // 结果侧 (ArkTS → Kotlin):
    //   - 控制面: resultJson ({ok,code,message,headers} / {ok:false,error}, 不含 body);
    //   - 数据面: 响应 body 作为 legado.httpCallback 第三参数 ArrayBuffer 回传。
    //
    // 调用链 (以 execute 为例):
    // KMP OhosKmpCall.execute
    //   → invokeHttpSync("execute", json, bodyBytes)
    //   → 生成 requestId, 存入 httpPendingRequests (CompletableDeferred<OhosBinaryBridgeResponse>)
    //   → httpTsfn(requestJson, bodyBytes)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] HttpBridgeHandler.handleHttpRequest(requestJson, bodyArrayBuffer)
    //   → http.createHttp().request(url, options, callback)
    //   → legado.httpCallback(requestId, resultJson, bodyArrayBuffer)  (napi → @CName legado_http_callback)
    //   → onHttpResult(requestId, resultJson, bodyBytes) → deferred.complete(OhosBinaryBridgeResponse)
    //   → runBlocking 返回, OhosKmpCall 解析响应

    /** http threadsafe_function 引用 (Kotlin → ArkTS 发送 HTTP 请求, 混合协议双参)。 */
    @Volatile
    private var httpTsfn: OhosBinaryTsfnCallback? = null

    /** 待响应的 http 同步请求 Map<requestId, CompletableDeferred<OhosBinaryBridgeResponse>>。 */
    private val httpPendingRequests =
        mutableMapOf<Long, CompletableDeferred<OhosBinaryBridgeResponse>>()

    /** http 请求自增 ID (原子性由 [lock] 保护)。 */
    private var httpRequestCounter = 0L

    /** 注入 http tsfn (由 legado_napi.cpp registerHttpCallback 调用)。 */
    fun registerHttpFn(tsfn: OhosBinaryTsfnCallback) {
        synchronized(lock) {
            httpTsfn = tsfn
        }
    }

    /**
     * http 请求结果回调 (由 ArkTS 侧调 @CName legado_http_callback 触发, 混合协议)。
     * ArkTS 完成 HTTP 请求后, 把控制面结果 JSON 与数据面响应 body 分两路推送给 Kotlin,
     * 唤醒 [invokeHttpSync] 中阻塞的 CompletableDeferred。
     *
     * @param requestId 对应 [invokeHttpSync] 生成的请求 ID
     * @param resultJson 控制面结果 JSON (含 ok/code/message/headers 或 ok/error, 不含 body)
     * @param bodyBytes 数据面响应 body 字节 (二进制保真; 无 body 时为 null)
     */
    fun onHttpResult(requestId: Long, resultJson: String, bodyBytes: ByteArray? = null) {
        val deferred = synchronized(lock) { httpPendingRequests.remove(requestId) }
        deferred?.complete(OhosBinaryBridgeResponse(resultJson, bodyBytes))
    }

    /**
     * 同步调用 http 操作 (阻塞等待 ArkTS 返回结果, 混合协议)。
     *
     * @param action 操作类型: "execute" / "cancel"
     * @param payloadJson 操作参数 JSON (由调用方序列化, 控制面小字段: url/method/headers/超时/代理;
     *   不含 body, body 字节改走 [bodyBytes] 裸参数)
     * @param bodyBytes 数据面请求 body 字节 (napi ArrayBuffer 裸传; 无 body 时传 null)
     * @param timeoutMs 超时毫秒 (默认 60s, HTTP 请求可能较慢)
     * @return ArkTS 返回的 [OhosBinaryBridgeResponse] (控制面 JSON + 数据面响应 body);
     *         tsfn 未注册或超时返回 null (调用方抛异常)
     */
    fun invokeHttpSync(
        action: String,
        payloadJson: String,
        bodyBytes: ByteArray? = null,
        timeoutMs: Long = 60000L,
    ): OhosBinaryBridgeResponse? {
        val requestId = synchronized(lock) { ++httpRequestCounter }
        val deferred = CompletableDeferred<OhosBinaryBridgeResponse>()
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
        runCatching { tsfn(requestJson, bodyBytes) }.onFailure {
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

    // ===== WebView 同步桥 (tsfn + callback, 混合协议: 控制面 JSON + 数据面裸字符串) =====
    // 后台 WebView (书源 webView/webViewGetSource 规则) 在 ArkTS 侧用隐藏 Web 组件承载
    // (K/N 无法直接实例化 ArkUI Web 组件), 采用与 Image/Http 一致的
    // "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式, 但传输格式为混合协议:
    //
    // 请求侧 (Kotlin → ArkTS):
    //   - 控制面: WebViewBridgeRequest(requestId, action, payload) JSON,
    //     payload = WebViewRequestPayload (仅 url/tag/encode/headers/sourceRegex/overrideUrlRegex/js/delayTime/cookie, 不含 html);
    //   - 数据面: html 作为 tsfn 回调第二参数裸字符串 (不经 JSON 转义,
    //     避免大段 HTML 的转义膨胀 + 双端 JSON 编解码拷贝);
    // 结果侧 (ArkTS → Kotlin):
    //   - 控制面: resultJson ({ok,url,cookie} / {ok:false,error}, 不含 body);
    //   - 数据面: 源码/命中 URL 作为 legado.webViewCallback 第三参数裸字符串回传。
    //
    // 调用链:
    // KMP OhosBackstageWebViewHandle.getStrResponse
    //   → invokeWebViewSync(jsonControl, htmlRaw)
    //   → 生成 requestId, 存入 webViewPendingRequests (CompletableDeferred<WebViewBridgeResponse>)
    //   → webViewTsfn(requestJson, htmlRaw)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞 JS 引擎线程等待结果)
    //   → [ArkTS 主线程] WebViewBridgeHandler.handleWebViewRequest(requestJson, htmlRaw)
    //   → 隐藏 Web 组件 loadUrl/loadData + onPageEnd 后 runJavaScript 取源码
    //   → legado.webViewCallback(requestId, resultJson, bodyRaw)  (napi → @CName legado_webview_callback)
    //   → onWebViewResult(requestId, resultJson, bodyRaw) → deferred.complete(WebViewBridgeResponse)
    //   → runBlocking 返回, OhosBackstageWebViewHandle 解析响应
    //
    // 未注册 tsfn (宿主未接入 WebViewBridgeHandler) 时 invokeWebViewSync 返回 null,
    // 调用方抛带明确说明的异常 (规则层 runCatching 成书源错误, 不再裸崩 IllegalStateException)。

    /** webView threadsafe_function 引用 (Kotlin → ArkTS 发送后台 WebView 请求, 混合协议双参)。 */
    @Volatile
    private var webViewTsfn: OhosWebViewTsfnCallback? = null

    /** 待响应的 webView 同步请求 Map<requestId, CompletableDeferred<WebViewBridgeResponse>>。 */
    private val webViewPendingRequests =
        mutableMapOf<Long, CompletableDeferred<WebViewBridgeResponse>>()

    /** webView 请求自增 ID (原子性由 [lock] 保护)。 */
    private var webViewRequestCounter = 0L

    /** 注入 webView tsfn (由 legado_napi.cpp registerWebViewCallback 调用)。 */
    fun registerWebViewFn(tsfn: OhosWebViewTsfnCallback) {
        synchronized(lock) {
            webViewTsfn = tsfn
        }
    }

    /**
     * webView 请求结果回调 (由 ArkTS 侧调 @CName legado_webview_callback 触发, 混合协议)。
     * ArkTS 完成页面加载 + JS 执行后, 把控制面结果 JSON 与数据面裸源码分两路推送给 Kotlin,
     * 唤醒 [invokeWebViewSync] 中阻塞的 CompletableDeferred。
     *
     * @param requestId 对应 [invokeWebViewSync] 生成的请求 ID
     * @param resultJson 控制面结果 JSON (`{ok:true,url,cookie}` / `{ok:false,error}`, 不含 body)
     * @param bodyRaw 数据面裸源码/命中 URL (不经 JSON 转义; 失败或空结果时为空串)
     */
    fun onWebViewResult(requestId: Long, resultJson: String, bodyRaw: String) {
        val deferred = synchronized(lock) { webViewPendingRequests.remove(requestId) }
        deferred?.complete(WebViewBridgeResponse(resultJson = resultJson, bodyRaw = bodyRaw))
    }

    /**
     * 同步调用后台 WebView 请求 (阻塞等待 ArkTS 返回结果, 混合协议)。
     *
     * @param jsonControl 控制面 JSON (WebViewRequestPayload, 不含 html 字段)
     * @param htmlRaw 数据面裸 HTML 入参 (null/空串表示无 html, 走 url 加载)
     * @param timeoutMs 超时毫秒 (默认 [io.legado.app.constant.AppConst.timeLimit] 15s)
     * @return ArkTS 返回的 [WebViewBridgeResponse] (控制面 JSON + 裸源码);
     *         tsfn 未注册或超时返回 null (调用方抛明确异常)
     */
    fun invokeWebViewSync(
        jsonControl: String,
        htmlRaw: String?,
        timeoutMs: Long = 15000L
    ): WebViewBridgeResponse? {
        val requestId = synchronized(lock) { ++webViewRequestCounter }
        val deferred = CompletableDeferred<WebViewBridgeResponse>()
        synchronized(lock) { webViewPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            WebViewBridgeRequest(requestId = requestId, action = "request", payload = jsonControl)
        )
        val tsfn = synchronized(lock) { webViewTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (宿主未接入 WebViewBridgeHandler), 移除 pending 请求返回 null
            synchronized(lock) { webViewPendingRequests.remove(requestId) }
            return null
        }
        // html 裸传: 空串等价无 html (ArkTS 侧按 length==0 判定), C 边界不出现 null 指针
        runCatching { tsfn(requestJson, htmlRaw ?: "") }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { webViewPendingRequests.remove(requestId) }
            return null
        }

        // 阻塞等待 ArkTS 回调 (JS 引擎线程阻塞, ArkTS 主线程回调 complete, 不同线程无死锁)
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { webViewPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 webView 桥是否已就绪 (tsfn 已注入)。
     * OhosBackstageWebViewHandle 据此判断走真实隐藏 Web 组件还是抛明确失败信息。
     */
    fun isWebViewBridgeReady(): Boolean = synchronized(lock) { webViewTsfn != null }

    // ===== Markdown 查看器桥 (tsfn fire-and-forget + @CName event callback) =====
    // KMP MarkdownContent (ArkUIView2 混排的鸿蒙 Web 组件, viewer HTML 由 composeResources 直读
    // 内联拼装, marked + hljs 渲染) 的渲染请求经 tsfn dispatch 到 ArkTS MarkdownBridgeHandler;
    // viewer 页面链接点击经 @CName legado_markdown_event 回调推送,
    // Kotlin 侧 [MarkdownEventListener] 接收后走系统浏览器。

    /** markdown threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var markdownTsfn: OhosTsfnCallback? = null

    /**
     * Markdown 查看器事件监听器 (ArkTS → Kotlin 推送 viewer 页面交互事件, 如链接点击)。
     * 由 ohosMain 启动期 (OhosProviderRegistry) 注册, 事件 JSON 与 ArkTS MarkdownBridgeHandler 对齐。
     */
    fun interface MarkdownEventListener {
        fun onMarkdownEvent(eventJson: String)
    }

    /** markdown 事件监听器 (单实例: Markdown 查看器全局唯一)。 */
    @Volatile
    private var markdownEventListener: MarkdownEventListener? = null

    /** 注入 markdown tsfn (由 legado_napi.cpp registerMarkdownCallback 调用)。 */
    fun registerMarkdownFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            markdownTsfn = tsfn
        }
    }

    /** 设置 markdown 事件监听器 (null 表示注销)。 */
    fun setMarkdownEventListener(listener: MarkdownEventListener?) {
        synchronized(lock) {
            markdownEventListener = listener
        }
    }

    /**
     * 发送 Markdown 渲染请求到 ArkTS (fire-and-forget, 无返回值)。
     * 未注册 tsfn 时丢弃 (内容不渲染, 正常路径下 EntryAbility 必然已注册)。
     *
     * @param payload 渲染参数 (markdown 原文 + 主题 + 字号)
     */
    fun sendMarkdown(payload: MarkdownRenderPayload) {
        val json = KS_JSON.encodeToString(MarkdownRenderPayload.serializer(), payload)
        dispatchTsfn(
            synchronized(lock) { markdownTsfn },
            json,
            "ohos-markdown",
            "render isDark=${payload.isDark} len=${payload.content.length}",
        )
    }

    /**
     * markdown 事件回调 (由 ArkTS 侧调 @CName legado_markdown_event 触发)。
     * viewer 页面链接点击 → javaScriptProxy → ArkTS MarkdownBridgeHandler → 本函数 →
     * 转发给 [MarkdownEventListener] (ohosMain 启动期注册, 走系统浏览器打开)。
     *
     * @param eventJson 事件 JSON, 如 `{"action":"openLink","url":"https://..."}`
     */
    fun onMarkdownEvent(eventJson: String) {
        val listener = synchronized(lock) { markdownEventListener }
        listener?.onMarkdownEvent(eventJson)
    }

    // ===== 统一平台事件桥 (ArkTS → Kotlin 单向推送, 单通道复用) =====
    // 与 mediaEvent/ttsEvent 同方向: ArkTS 侧经 legado.platformEvent(eventJson)
    // (napi → @CName legado_platform_event) 推送统一 JSON 事件, 本入口解析 type 后
    // 分发到 OhosDownloadProgressEvents (HTTP 下载进度) / OhosAppLifecycle (应用生命周期)。
    // 两个功能共用一个通道与一个 @CName 符号, 避免每个功能各建一套桥。

    /**
     * 平台事件回调 (由 ArkTS 侧调 legado.platformEvent 触发, 经 @CName legado_platform_event 转发)。
     *
     * 事件协议与解析见 [OhosPlatformEventChannel] (io.legado.app.napi.OhosPlatformEvents.kt):
     * - HTTP 下载进度: `{type:"httpProgress", url, bytesReceived, totalBytes, isComplete}`
     * - 应用生命周期: `{type:"lifecycle", event:"onForeground"|"onBackground"}`
     *
     * 监听 API:
     * - HTTP 进度: [OhosDownloadProgressEvents.addListener]/[removeListener] (同 ProgressManager 形)
     * - 生命周期: [OhosAppLifecycle.addListener]/[removeListener] (阅读页 onEnter/onExit 挂卸)
     *
     * 执行线程: ArkTS 主线程直推 (无跨线程 dispatch); 解析/分发失败静默丢弃 (事件通道尽力而为)。
     */
    fun onPlatformEvent(eventJson: String) {
        OhosPlatformEventChannel.onEvent(eventJson)
    }

    // ===== OpenUrl tsfn (KMP → ArkTS, fire-and-forget, 同 Toast 模式) =====
    // OhosOpenUrlProvider.openUrl 经 tsfn dispatch 到 ArkTS, 由 SystemBridgeHandler.handleOpenUrl
    // 调 context.startAbility(Want.uri=url) 打开 URL (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接)。

    /** openUrl threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var openUrlTsfn: OhosTsfnCallback? = null

    /** 注入 openUrl tsfn (由 legado_napi.cpp RegisterOpenUrlCallback 调用)。 */
    fun registerOpenUrlFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            openUrlTsfn = tsfn
        }
    }

    /**
     * 打开 URL (跨线程 dispatch 到 ArkTS context.startAbility)。未注册 tsfn 时丢弃。
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
        dispatchTsfn(synchronized(lock) { openUrlTsfn }, json, "ohos-open-url", url)
    }

    /**
     * 检查 openUrl 桥是否已就绪 (tsfn 已注入)。
     * OhosOpenUrlProviderImpl 据此判断走真实 startAbility 还是降级记日志 (兼容 napi 未接入阶段)。
     */
    fun isOpenUrlBridgeReady(): Boolean = synchronized(lock) { openUrlTsfn != null }

    // ===== TextAction tsfn (KMP → ArkTS 显示浮动菜单) + ArkTS → KMP 动作回调 =====
    // 阅读页文本操作浮动菜单: KMP 长按选字完成 → 跨线程 dispatch 菜单请求到 ArkTS
    // (Index.ets 叠层浮动菜单, 平台原生 ArkUI 组件), 菜单项点击经 @CName
    // legado_text_action_callback (legado_napi.cpp TextActionCallback) 回送动作字符串。

    /** textAction threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var textActionTsfn: OhosTsfnCallback? = null

    /** 菜单动作回调 (由 OhosReaderPlatformProvider 注册; ArkTS 菜单项点击 → [onTextActionResult])。
     *  @param src 图片 src (图片菜单动作携带; 文本菜单动作为空串)。 */
    @Volatile
    var textActionHandler: ((action: String, text: String, src: String) -> Unit)? = null

    /** 注入 textAction tsfn (由 legado_napi.cpp RegisterTextActionCallback 调用)。 */
    fun registerTextActionFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            textActionTsfn = tsfn
        }
    }

    /**
     * 显示文本操作浮动菜单 (跨线程 dispatch 到 ArkTS)。未注册 tsfn 时丢弃。
     *
     * @param text 选中文本 (菜单动作参数)
     * @param x/y 选区起点锚点 (阅读页内坐标)
     */
    fun showTextActionMenu(text: String, x: Float, y: Float) {
        val json = KS_JSON.encodeToString(
            TextActionMenuPayload(text = text, x = x, y = y, menuItems = TEXT_ACTION_MENU_ITEMS)
        )
        dispatchTsfn(synchronized(lock) { textActionTsfn }, json, "ohos-text-action", text)
    }

    /**
     * 显示图片操作浮动菜单 (跨线程 dispatch 到 ArkTS, 同 [showTextActionMenu] 共用一个 tsfn)。
     * 未注册 tsfn 时丢弃。
     *
     * payload 带 `type="image"` + `src`, ArkTS TextActionBridgeHandler 据此切换菜单项
     * (查看/刷新/保存到相册, 对照原版 ReadBookActivity.onImageLongPress 图片菜单)。
     *
     * @param src 图片地址 (菜单动作参数)
     * @param x/y 长按点锚点 (阅读页内坐标)
     */
    fun showImageActionMenu(src: String, x: Float, y: Float) {
        val json = KS_JSON.encodeToString(
            TextActionMenuPayload(
                text = "",
                x = x,
                y = y,
                src = src,
                type = "image",
                menuItems = IMAGE_ACTION_MENU_ITEMS,
            )
        )
        dispatchTsfn(
            synchronized(lock) { textActionTsfn },
            json,
            "ohos-text-action",
            "image menu $src",
        )
    }

    /** 隐藏文本操作浮动菜单 (菜单项点击/点外部收起后由 ArkTS 自行隐藏, 此方法供取消选择联动)。 */
    fun hideTextActionMenu() {
        val json = KS_JSON.encodeToString(TextActionMenuPayload(text = "", x = -1f, y = -1f))
        dispatchTsfn(synchronized(lock) { textActionTsfn }, json, "ohos-text-action", "hide")
    }

    /**
     * ArkTS → KMP 菜单动作结果 (由 legado_napi.cpp TextActionCallback 调用,
     * 经 [LegadoNativeExports.textActionCallback] 转发)。
     *
     * @param src 图片 src (图片菜单动作携带; 文本菜单动作为空串)
     */
    fun onTextActionResult(action: String, text: String, src: String) {
        textActionHandler?.invoke(action, text, src)
    }

    // ===== Window tsfn (KMP → ArkTS, fire-and-forget, 同 OpenUrl 模式) =====
    // 窗口策略 (全屏/常亮/方向/系统栏) 走 ArkTS @ohos.window API (setWindowLayoutFullScreen /
    // setWindowKeepScreenOn / setPreferredOrientation / setWindowSystemBarEnable), 无 NDK C 接口,
    // 经 tsfn dispatch 到 ArkTS 主线程执行。命令 fire-and-forget (无返回值), 未注册时丢弃。

    /** window threadsafe_function 引用 (EntryAbility.ets 注册后注入)。 */
    @Volatile
    private var windowTsfn: OhosTsfnCallback? = null

    /** 注入 window tsfn (由 legado_napi.cpp RegisterWindowCallback 调用)。 */
    fun registerWindowFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            windowTsfn = tsfn
        }
    }

    /** 发送 window 命令到 ArkTS (fire-and-forget)。未注册 tsfn 时丢弃。 */
    fun sendWindowCommand(commandJson: String) {
        dispatchTsfn(synchronized(lock) { windowTsfn }, commandJson, "ohos-window", commandJson)
    }

    /** 切换窗口全屏布局 (对照 @ohos.window setWindowLayoutFullScreen)。 */
    fun setWindowFullScreenLayout(enabled: Boolean) {
        sendWindowCommand(
            KS_JSON.encodeToString(WindowCommand(action = "setFullScreenLayout", enabled = enabled))
        )
    }

    /** 切换保持屏幕常亮 (对照 @ohos.window setWindowKeepScreenOn)。 */
    fun setWindowKeepScreenOn(enabled: Boolean) {
        sendWindowCommand(
            KS_JSON.encodeToString(WindowCommand(action = "setKeepScreenOn", enabled = enabled))
        )
    }

    /** 设置首选方向 (对照 @ohos.window setPreferredOrientation, orientation 为方向枚举值)。 */
    fun setWindowPreferredOrientation(orientation: Int) {
        sendWindowCommand(
            KS_JSON.encodeToString(
                WindowCommand(
                    action = "setPreferredOrientation",
                    orientation = orientation
                )
            )
        )
    }

    /** 设置系统栏显隐 (对照 @ohos.window setWindowSystemBarEnable, enabled=true 显示 / false 隐藏)。 */
    fun setWindowSystemBarEnable(enabled: Boolean) {
        sendWindowCommand(
            KS_JSON.encodeToString(WindowCommand(action = "setSystemBarEnable", enabled = enabled))
        )
    }

    /** 设置窗口亮度 (对照 @ohos.window setWindowBrightness, brightness 范围 0.0f..1.0f, -1.0f 为跟随系统)。 */
    fun setWindowBrightness(brightness: Float) {
        sendWindowCommand(
            KS_JSON.encodeToString(
                WindowCommand(
                    action = "setBrightness",
                    brightness = brightness,
                )
            )
        )
    }

    /**
     * 退出应用 (经 window tsfn dispatch 到 ArkTS UIAbilityContext.terminateSelf)。
     *
     * 鸿蒙无 Activity.finish 等价物, 退出需 ArkTS `context.terminateSelf()` (仅 UIAbility 自身
     * 可调, 由宿主 window callback 处理 action="exitApplication" 分支)。复用 window tsfn 通道
     * (同全屏/常亮/方向/系统栏 fire-and-forget 模式), 避免为单条命令新增独立桥。
     * 未注册 window tsfn (EntryAbility 未 registerWindowCallback) 时丢弃。
     */
    fun exitApplication() {
        sendWindowCommand(
            KS_JSON.encodeToString(WindowCommand(action = "exitApplication"))
        )
    }

    /** 检查 window 桥是否已就绪 (tsfn 已注入)。 */
    fun isWindowBridgeReady(): Boolean = synchronized(lock) { windowTsfn != null }

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
    private var filePickerTsfn: OhosTsfnCallback? = null

    /** 待响应的 filePicker 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val filePickerPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** filePicker 请求自增 ID (原子性由 [lock] 保护)。 */
    private var filePickerRequestCounter = 0L

    /** 注入 filePicker tsfn (由 legado_napi.cpp RegisterFilePickerCallback 调用)。 */
    fun registerFilePickerFn(tsfn: OhosTsfnCallback) {
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
    private var pasteboardTsfn: OhosTsfnCallback? = null

    /** 待响应的 pasteboard 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val pasteboardPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** pasteboard 请求自增 ID (原子性由 [lock] 保护)。 */
    private var pasteboardRequestCounter = 0L

    /** 注入 pasteboard tsfn (由 legado_napi.cpp RegisterPasteboardCallback 调用)。 */
    fun registerPasteboardFn(tsfn: OhosTsfnCallback) {
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
     * OhosPlatformOps 据此判断走真实 SystemPasteboard 还是降级记日志/返回 null。
     */
    fun isPasteboardBridgeReady(): Boolean = synchronized(lock) { pasteboardTsfn != null }

    // ===== Network 同步桥 (tsfn + callback, 同 Pasteboard 模式) =====
    // 网络状态查询 (isNetworkAvailable/isWifiConnect) 走 ArkTS @ohos.net.connection
    // (getDefaultNetSync + getConnectionPropertiesSync, 同步 API 但仅 ArkTS 侧可调),
    // 采用与 Pasteboard 完全一致的 "tsfn 发请求 + @CName 回调返回结果" 的同步等待模式:
    //
    // 调用链 (以 query 为例):
    // KMP NetworkAvailability.ohos.kt
    //   → invokeNetworkSync("query", "{}")
    //   → 生成 requestId, 存入 networkPendingRequests (CompletableDeferred)
    //   → networkTsfn(requestJson)  (fire-and-forget, dispatch 到 ArkTS 主线程)
    //   → runBlocking { deferred.await() }  (阻塞等待结果)
    //   → [ArkTS 主线程] NetworkBridgeHandler.handleNetworkRequest
    //   → connection.getDefaultNetSync() + getConnectionPropertiesSync() → bearerType
    //   → legado.networkCallback(requestId, resultJson)  (napi → @CName legado_network_callback)
    //   → onNetworkResult(requestId, resultJson) → deferred.complete(resultJson)

    /** network threadsafe_function 引用 (Kotlin → ArkTS 发送网络状态查询)。 */
    @Volatile
    private var networkTsfn: OhosTsfnCallback? = null

    /** 待响应的 network 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val networkPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** network 请求自增 ID (原子性由 [lock] 保护)。 */
    private var networkRequestCounter = 0L

    /** 注入 network tsfn (由 legado_napi.cpp RegisterNetworkCallback 调用)。 */
    fun registerNetworkFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            networkTsfn = tsfn
        }
    }

    /**
     * network 查询结果回调 (由 ArkTS 侧调 @CName legado_network_callback 触发)。
     *
     * @param requestId 对应 [invokeNetworkSync] 生成的请求 ID
     * @param resultJson ArkTS 返回的结果 JSON (`{ ok: true, network, wifi }` / `{ ok: false, error }`)
     */
    fun onNetworkResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { networkPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步查询网络状态 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action 操作类型: "query" (网络可用性 + 是否 WiFi 一并返回)
     * @param payloadJson 操作参数 JSON (当前 "{}")
     * @param timeoutMs 超时毫秒 (默认 2s, 同步 API 查询应当极快)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册或超时返回 null (调用方降级 true)
     */
    fun invokeNetworkSync(action: String, payloadJson: String, timeoutMs: Long = 2000L): String? {
        val requestId = synchronized(lock) { ++networkRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { networkPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            NetworkBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { networkTsfn }
        if (tsfn == null) {
            // 降级: tsfn 未注册 (napi 未接入阶段), 移除 pending 请求返回 null
            synchronized(lock) { networkPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            // tsfn 调用失败 (module 卸载 / 线程异常), 移除 pending 请求返回 null
            synchronized(lock) { networkPendingRequests.remove(requestId) }
            return null
        }

        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { networkPendingRequests.remove(requestId) }
        return result
    }

    /**
     * 检查 network 桥是否已就绪 (tsfn 已注入)。
     * NetworkAvailability.ohos.kt 据此判断走真实查询还是降级 true。
     */
    fun isNetworkBridgeReady(): Boolean = synchronized(lock) { networkTsfn != null }

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
    private var textCodecTsfn: OhosTsfnCallback? = null

    /** 待响应的 textCodec 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val textCodecPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** textCodec 请求自增 ID (原子性由 [lock] 保护)。 */
    private var textCodecRequestCounter = 0L

    /** 注入 textCodec tsfn (由 legado_napi.cpp RegisterTextCodecCallback 调用)。 */
    fun registerTextCodecFn(tsfn: OhosTsfnCallback) {
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

    // ===== Battery 同步桥 (tsfn + callback, 同 Image/Crypto 模式) =====
    // 阅读页电池电量走 ArkTS @ohos.batteryInfo (无 NDK C 接口), 经 tsfn 同步请求/响应获取。
    // 调用链: KMP getBatteryLevel → invokeBatterySync("getLevel") → tsfn → ArkTS @ohos.batteryInfo.batterySOC
    //   → legado.batteryCallback(requestId, {"level":85}) → deferred.complete → 返回缓存值
    // ArkTS 侧实现为 TODO (legado_napi.cpp registerBatteryCallback + BatteryBridgeHandler.ets)

    /** battery threadsafe_function 引用 (Kotlin → ArkTS 发送电量查询)。 */
    @Volatile
    private var batteryTsfn: OhosTsfnCallback? = null

    /** 待响应的 battery 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val batteryPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** battery 请求自增 ID (原子性由 [lock] 保护)。 */
    private var batteryRequestCounter = 0L

    /** 注入 battery tsfn (由 legado_napi.cpp registerBatteryCallback 调用)。 */
    fun registerBatteryFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            batteryTsfn = tsfn
        }
    }

    /** battery 查询结果回调 (由 ArkTS 侧调 @CName legado_battery_callback 触发)。 */
    fun onBatteryResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { batteryPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步查询电池电量 (阻塞等待 ArkTS 返回)。
     * 桥未就绪/超时返回 null, 调用方降级返回 -1 (不显示)。
     */
    fun invokeBatterySync(action: String, timeoutMs: Long = 2000L): String? {
        val requestId = synchronized(lock) { ++batteryRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { batteryPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            BatteryBridgeRequest(requestId = requestId, action = action)
        )
        val tsfn = synchronized(lock) { batteryTsfn }
        if (tsfn == null) {
            synchronized(lock) { batteryPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            synchronized(lock) { batteryPendingRequests.remove(requestId) }
            return null
        }

        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { batteryPendingRequests.remove(requestId) }
        return result
    }

    /** 检查 battery 桥是否已就绪 (tsfn 已注入)。 */
    fun isBatteryBridgeReady(): Boolean = synchronized(lock) { batteryTsfn != null }

    // ===== Share tsfn (KMP → ArkTS, fire-and-forget, 同 OpenUrl 模式) =====
    // 系统分享走 ArkTS @ohos.share.systemShare (SharePanel), 无 NDK C 接口。
    // 分享面板由用户操作, 结果对调用方无意义, 故 fire-and-forget。

    /** share threadsafe_function 引用。 */
    @Volatile
    private var shareTsfn: OhosTsfnCallback? = null

    /** 注入 share tsfn (由 legado_napi.cpp RegisterShareCallback 调用)。 */
    fun registerShareFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            shareTsfn = tsfn
        }
    }

    /** 分享纯文本 (对照 Android Intent.ACTION_SEND text/plain)。 */
    fun shareText(text: String) {
        sendShareCommand(KS_JSON.encodeToString(SharePayload(action = "text", text = text)))
    }

    /** 分享文件 (filePath 为沙盒绝对路径, ArkTS 侧转 fileUri 后交给 SharePanel)。 */
    fun shareFile(filePath: String, mimeType: String) {
        sendShareCommand(
            KS_JSON.encodeToString(
                SharePayload(action = "file", filePath = filePath, mimeType = mimeType)
            )
        )
    }

    private fun sendShareCommand(json: String) {
        dispatchTsfn(synchronized(lock) { shareTsfn }, json, "ohos-share", json)
    }

    /** 检查 share 桥是否已就绪; 未就绪时调用方降级到剪贴板 (对照 desktop shareText)。 */
    fun isShareBridgeReady(): Boolean = synchronized(lock) { shareTsfn != null }

    // ===== Keyboard tsfn (KMP → ArkTS, fire-and-forget, 同 Window 模式) =====
    // 软键盘显隐走 @ohos.inputMethod.getController().stopInputSession / showSoftKeyboard,
    // 输入法避让策略走 UIContext.setKeyboardAvoidMode, 均只有 ArkTS API。

    /** keyboard threadsafe_function 引用。 */
    @Volatile
    private var keyboardTsfn: OhosTsfnCallback? = null

    /** 注入 keyboard tsfn (由 legado_napi.cpp RegisterKeyboardCallback 调用)。 */
    fun registerKeyboardFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            keyboardTsfn = tsfn
        }
    }

    private fun sendKeyboardCommand(json: String) {
        dispatchTsfn(synchronized(lock) { keyboardTsfn }, json, "ohos-keyboard", json)
    }

    /** 收起软键盘 (对照 inputMethod.getController().stopInputSession)。 */
    fun hideSoftInput() {
        sendKeyboardCommand(KS_JSON.encodeToString(KeyboardCommand(action = "hide")))
    }

    /** 拉起软键盘 (对照 inputMethod.getController().showSoftKeyboard)。 */
    fun showSoftInput() {
        sendKeyboardCommand(KS_JSON.encodeToString(KeyboardCommand(action = "show")))
    }

    /**
     * 设置键盘避让模式 (对照 UIContext.setKeyboardAvoidMode)。
     *
     * @param mode 0=OFFSET (页面整体上推, 对齐 Android ADJUST_PAN),
     *   1=RESIZE (页面收缩, 对齐 ADJUST_RESIZE), 2=NONE (不避让)
     */
    fun setKeyboardAvoidMode(mode: Int) {
        sendKeyboardCommand(
            KS_JSON.encodeToString(KeyboardCommand(action = "setAvoidMode", mode = mode))
        )
    }

    /** 检查 keyboard 桥是否已就绪。 */
    fun isKeyboardBridgeReady(): Boolean = synchronized(lock) { keyboardTsfn != null }

    // ===== Permission 同步桥 (tsfn + callback, 同 Pasteboard 模式) =====
    // 权限查询/申请走 @ohos.abilityAccessCtrl (checkAccessTokenSync / requestPermissionsFromUser),
    // 查询需返回值故走同步请求/响应模式; 申请只回"是否成功发起"(与 Android 同语义)。

    /** permission threadsafe_function 引用。 */
    @Volatile
    private var permissionTsfn: OhosTsfnCallback? = null

    /** 待响应的 permission 同步请求 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val permissionPendingRequests = mutableMapOf<Long, CompletableDeferred<String>>()

    /** permission 请求自增 ID (原子性由 [lock] 保护)。 */
    private var permissionRequestCounter = 0L

    /** 注入 permission tsfn (由 legado_napi.cpp RegisterPermissionCallback 调用)。 */
    fun registerPermissionFn(tsfn: OhosTsfnCallback) {
        synchronized(lock) {
            permissionTsfn = tsfn
        }
    }

    /**
     * permission 结果回调 (由 ArkTS 侧调 @CName legado_permission_callback 触发)。
     *
     * @param requestId 对应 [invokePermissionSync] 生成的请求 ID
     * @param resultJson `{ ok: true, granted: boolean }` 或 `{ ok: false, error: "..." }`
     */
    fun onPermissionResult(requestId: Long, resultJson: String) {
        val deferred = synchronized(lock) { permissionPendingRequests.remove(requestId) }
        deferred?.complete(resultJson)
    }

    /**
     * 同步调用权限操作 (阻塞等待 ArkTS 返回结果)。
     *
     * @param action "check" (checkAccessTokenSync) / "request" (requestPermissionsFromUser)
     * @param payloadJson `{"permission":"ohos.permission.XXX"}`
     * @param timeoutMs check 极快, request 需用户操作故给 60s
     * @return 结果 JSON; 桥未就绪或超时返回 null (调用方按"无权限"降级)
     */
    fun invokePermissionSync(
        action: String,
        payloadJson: String,
        timeoutMs: Long = 60000L,
    ): String? {
        val requestId = synchronized(lock) { ++permissionRequestCounter }
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { permissionPendingRequests[requestId] = deferred }

        val requestJson = KS_JSON.encodeToString(
            PermissionBridgeRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        val tsfn = synchronized(lock) { permissionTsfn }
        if (tsfn == null) {
            synchronized(lock) { permissionPendingRequests.remove(requestId) }
            return null
        }
        runCatching { tsfn(requestJson) }.onFailure {
            synchronized(lock) { permissionPendingRequests.remove(requestId) }
            return null
        }

        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { permissionPendingRequests.remove(requestId) }
        return result
    }

    /** 检查 permission 桥是否已就绪。 */
    fun isPermissionBridgeReady(): Boolean = synchronized(lock) { permissionTsfn != null }

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

    /** webView 桥请求 payload (Kotlin → ArkTS, 同 HttpBridgeRequest 结构, action=request)。 */
    @Serializable
    private data class WebViewBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /**
     * webView 后台抓取控制面 payload (Kotlin → ArkTS, 与 ArkTS WebViewBridgeHandler 对齐)。
     * 字段语义对齐 [io.legado.app.help.http.BackstageWebViewFactory.create]。
     *
     * # 混合协议 (html 不在本 JSON 中)
     * html 入参可能携带大段 HTML (整章/整页, 数百 KB~数 MB), JSON 转义会膨胀体积且
     * 双端多两次编解码拷贝; 故 html 字段已移出, 作为 [invokeWebViewSync] 第二参数裸字符串
     * 经 tsfn 直接传 (见 [OhosWebViewTsfnCallback])。ArkTS 侧据回调第二参数取 html。
     */
    @Serializable
    data class WebViewRequestPayload(
        val url: String? = null,
        val encode: String? = null,
        val tag: String? = null,
        val headers: List<WebViewHeader>? = null,
        val sourceRegex: String? = null,
        val overrideUrlRegex: String? = null,
        val js: String? = null,
        val delayTime: Long = 1000L,
        /** 加载前注入的业务层 cookie ("k1=v1; k2=v2")。 */
        val cookie: String? = null,
    )

    /** 请求头单项 (Kotlin → ArkTS)。 */
    @Serializable
    data class WebViewHeader(
        val name: String,
        val value: String,
    )

    /**
     * webView 后台抓取控制面结果 (ArkTS → Kotlin, 混合协议)。
     * - 成功: `{ ok: true, url, cookie? }` (cookie 为页面 host 域 cookie, 供 Kotlin 回写业务层;
     *   源码/命中 URL 走 [WebViewBridgeResponse.bodyRaw] 裸字符串, 不在本 JSON 中)
     * - 失败: `{ ok: false, error }`
     */
    @Serializable
    data class WebViewResult(
        val ok: Boolean,
        val url: String? = null,
        val cookie: String? = null,
        val error: String? = null,
    )

    /**
     * webView 桥同步响应 (ArkTS → Kotlin, 混合协议)。
     *
     * @param resultJson 控制面结果 JSON (WebViewResult 格式, 不含 body)
     * @param bodyRaw 数据面裸源码/命中 URL (不经 JSON 转义; 失败或空结果时为空串)
     */
    data class WebViewBridgeResponse(
        val resultJson: String,
        val bodyRaw: String,
    )

    /**
     * Markdown 查看器渲染请求 payload (Kotlin → ArkTS, 与 ArkTS MarkdownBridgeHandler
     * MarkdownRenderPayload 对齐)。viewer 页面内 marked.parse + hljs.highlightAll 渲染。
     *
     * @param content Markdown 原文
     * @param isDark 是否暗色主题 (AppColors.isDark, 切换 github-markdown 亮/暗 css)
     * @param fontSize 基准字号 (vp; 与 14sp 对齐, viewer 内作 --md-font-size)
     */
    @Serializable
    data class MarkdownRenderPayload(
        val content: String,
        val isDark: Boolean,
        val fontSize: Float,
    )

    /**
     * Markdown 查看器事件 payload (ArkTS → Kotlin, 与 ArkTS MarkdownBridgeHandler 对齐)。
     *
     * @param action 事件类型: "openLink" = viewer 页面链接点击
     * @param url 链接 URL (action=openLink 时有效)
     */
    @Serializable
    data class MarkdownEventPayload(
        val action: String,
        val url: String? = null,
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

    /** network 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=query)。 */
    @Serializable
    private data class NetworkBridgeRequest(
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

    /** battery 桥请求 payload (Kotlin → ArkTS, action=getLevel)。 */
    @Serializable
    private data class BatteryBridgeRequest(
        val requestId: Long,
        val action: String,
    )

    /** permission 桥请求 payload (Kotlin → ArkTS, 同 ImageBridgeRequest 结构, action=check/request)。 */
    @Serializable
    private data class PermissionBridgeRequest(
        val requestId: Long,
        val action: String,
        val payload: String,
    )

    /** share payload (Kotlin → ArkTS, action=text/file, fire-and-forget)。 */
    @Serializable
    private data class SharePayload(
        val action: String,
        val text: String? = null,
        val filePath: String? = null,
        val mimeType: String? = null,
    )

    /** keyboard 命令 payload (Kotlin → ArkTS, action=show/hide/setAvoidMode)。 */
    @Serializable
    private data class KeyboardCommand(
        val action: String,
        val mode: Int? = null,
    )

    /**
     * 文本/图片操作菜单 payload (Kotlin → ArkTS: 选中文本或图片 src + 锚点; x/y = -1 表示隐藏菜单)。
     * type="text" 为文本操作菜单 (text 为选中文本), type="image" 为图片操作菜单 (src 为图片地址),
     * ArkTS TextActionBridgeHandler 据 type 切换菜单项 ([TEXT_ACTION_MENU_ITEMS] / [IMAGE_ACTION_MENU_ITEMS])。
     */
    @Serializable
    private data class TextActionMenuPayload(
        val text: String,
        val x: Float,
        val y: Float,
        /** 图片 src (type="image" 时携带; 文本菜单为 null)。 */
        val src: String? = null,
        /** 菜单类型: "text" = 文本操作菜单 / "image" = 图片操作菜单。 */
        val type: String = "text",
        /**
         * 菜单项清单 (K/N 唯一一份, 下发替代 ArkTS 硬编码 TEXT_ACTION_ITEMS/IMAGE_ACTION_ITEMS;
         * 空/缺失时 ArkTS 回退其内置清单, 兼容旧宿主)。
         */
        val menuItems: List<TextActionMenuItem>? = null,
    )

    /** 浮动菜单单项 (K/N 唯一一份的菜单项定义, 见 [TEXT_ACTION_MENU_ITEMS] / [IMAGE_ACTION_MENU_ITEMS])。 */
    @Serializable
    data class TextActionMenuItem(val label: String, val action: String)

    /** 文本操作菜单项 (对标原版 content_select_action.xml 顺序: replace/copy/bookmark/aloud/dict/search_content/browser/share)。 */
    private val TEXT_ACTION_MENU_ITEMS = listOf(
        TextActionMenuItem("替换", "replace"),
        TextActionMenuItem("复制", "copy"),
        TextActionMenuItem("书签", "bookmark"),
        TextActionMenuItem("朗读", "aloud"),
        TextActionMenuItem("查词", "dict"),
        TextActionMenuItem("全文搜索", "search_content"),
        TextActionMenuItem("浏览器", "browser"),
        TextActionMenuItem("分享", "share"),
    )

    /** 图片菜单项 (对标原版 ReadBookActivity.onImageLongPress; 无"选择目录" → 保存到相册)。 */
    private val IMAGE_ACTION_MENU_ITEMS = listOf(
        TextActionMenuItem("查看", "view"),
        TextActionMenuItem("刷新", "refresh"),
        TextActionMenuItem("保存到相册", "save"),
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

    /** window 命令 payload (Kotlin → ArkTS, 全屏/常亮/方向/系统栏, fire-and-forget)。 */
    @Serializable
    private data class WindowCommand(
        val action: String,
        val enabled: Boolean? = null,
        val orientation: Int? = null,
        val brightness: Float? = null,
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
