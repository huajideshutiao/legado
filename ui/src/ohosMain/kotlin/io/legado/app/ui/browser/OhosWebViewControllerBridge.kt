@file:Suppress("unused")
// napi 桥需要 @CName 导出与 CValue/CPointer 的 native 侧实验 API (与同目录其余 napi 文件同款)
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package io.legado.app.ui.browser

import kotlin.concurrent.Volatile

import io.legado.app.constant.AppLog
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.native.CName

/**
 * 鸿蒙前台 Web 控制器桥 (napi ↔ ArkTS 可见 Web 组件)。
 *
 * # 与后台 WebView 桥的分工
 * `WebViewBridgeHandler.ets` + `legado.webViewCallback` 服务的是**后台抓取** (书源 webView 规则,
 * 隐藏 Web 组件, 单实例 FIFO 串行)。本桥服务的是 **Compose 前台 WebView slot**
 * (`WebViewRoute` / `ReadRssRoute` / 登录 Overlay, 见 [OhosWebViewSlot]), 由 ArkTS 侧以**可见**
 * Web 组件承载并直接挂到屏幕 (K/N 无法在 Compose 树内实例化 ArkUI Web), Kotlin 侧只做透明占位与
 * 显示/隐藏控制。两条链各有独立的 tsfn 与 napi 回调, 互不抢占 Web 实例。
 *
 * # 四层桥 (照抄仓库既有 Battery/Crypto 模式: tsfn 发请求 + @CName 回调返回结果)
 * 1. Kotlin 本对象 `OhosVisibleWebViewBridge` 经 tsfn lambda 发命令 JSON;
 * 2. C++ `ohos_web_controller_dispatch` → `napi_call_threadsafe_function` → ArkTS 主线程
 *    `WebControllerCallJs` → ArkTS `registerWebController` 注册的回调;
 * 3. ArkTS 完成操作后调 `legado.webControllerCallback(requestId, resultJson)` → C++ dlsym
 *    `legado_web_controller_callback` → 本文件 [onOhosWebControllerCallback] → 唤醒
 *    [OhosVisibleWebViewBridge.invokeSync] 中阻塞的 CompletableDeferred, 或投递页面事件;
 * 4. tsfn 注入由 C++ `registerWebController` 经 @CName [registerOhosWebControllerFn] 完成。
 *
 * # 协议 (与 ArkTS 侧逐字段对齐, 详见 ArkTS handler 注释)
 * 请求 (Kotlin → ArkTS) 为 `{ "requestId": <number>, "action": <string>, "payload": "<JSON>" }`
 * (与 `WebViewBridgeRequest`/`ImageBridgeRequest` 同形)。action:
 * - `"show"`: payload `OhosWebViewControllerPayload` (url/html/headers/wideViewPort/isLogin/sourceKey),
 *   ArkTS 显示可见 Web 并加载;
 * - `"hide"`: 隐藏并 dismiss 前台 Web;
 * - `"loadUrl"`: payload `OhosWebUrlPayload` (仅 url);
 * - `"runJavaScript"`: payload `OhosWebScriptPayload` (script), 结果经 `result` 字段回送;
 * - `"getCookies"`: payload `OhosWebUrlPayload` (url), 结果经 `cookie` 字段回送;
 * - `"canGoBack"` / `"getUrl"` / `"goBack"` / `"reload"` / `"exitFullScreen"`.
 *
 * 响应/事件 (ArkTS → Kotlin) 为单个 JSON, 经 `webControllerCallback(requestId, resultJson)`:
 * - `requestId > 0`: 命令响应 `OhosWebControllerResult` (`ok` + `result`/`cookie`/`canGoBack`/`url`
 *   或 `ok=false,error`);
 * - `requestId == 0`: 主动页面事件 (同一 JSON 结构, 置 `event` 字段):
 *   `"onPageFinished"` (带 `url`/`cookie`, 可选 `title`)、`"onReceivedTitle"` (`title`)、
 *   `"onProgressChanged"` (`progress` 0..100)、`"onUrlChanged"` (`url`)、`"onCloseWindow"`。
 *
 * cookie 同步: `onPageFinished` 事件带页面 host 域 cookie, 由 [OhosWebViewSlot] 写回
 * [io.legado.app.help.http.CookieStoreProviders] (对齐 Android/Ios slot 的 onPageFinished 语义)。
 *
 * # 未就绪降级
 * ArkTS 未调 `legado.registerWebController` 时 [isReady] 为 false, 所有命令静默丢弃, slot 保持占位
 * (与既有桥 "未注册 tsfn 即降级" 一致), 不影响其他端。
 *
 * # 线程
 * 命令侧 `runBlocking + withTimeoutOrNull` 阻塞 JS 引擎线程等待 ArkTS 回调 (同 Image/Http/WebView
 * 桥, 二者为不同线程, 无死锁); 反向结果/事件经 @CName 在 ArkTS 主线程直推, 监听器回调即在该线程
 * 执行 (与 [io.legado.app.napi.OhosPlatformEventChannel] 同口径, Compose 状态更新即在此线程)。
 */

/** ArkTS `registerWebController` 发来的前台 Web 命令 (Kotlin → ArkTS, 与既有桥请求同形)。 */
@Serializable
private data class OhosWebControllerRequest(
    val requestId: Long,
    val action: String,
    val payload: String,
)

/**
 * `show` / `loadUrl` 命令 payload。
 *
 * @param url 目标 URL (与 html 二选一)
 * @param html 非空时以 loadDataWithBaseURL 加载 (对齐 Android loadDataWithBaseURL / iOS loadHTMLString)
 * @param headers 请求头 (含 User-Agent; 复用既有 [OhosNativeBridge.WebViewHeader], 字段 name/value 一致)
 * @param wideViewPort 是否 `useWideViewPort + loadWithOverviewMode` (对齐 Android slot)
 * @param isLogin 登录/验证场景 (cookie 需按 sourceKey 另存一份)
 * @param sourceKey 书源 key (isLogin 时按它回写 cookie)
 */
@Serializable
data class OhosWebViewControllerPayload(
    val url: String? = null,
    val html: String? = null,
    val headers: List<OhosNativeBridge.WebViewHeader>? = null,
    val wideViewPort: Boolean = false,
    val isLogin: Boolean = false,
    val sourceKey: String? = null,
)

/** `runJavaScript` 命令 payload。 */
@Serializable
data class OhosWebScriptPayload(
    val script: String,
)

/** 仅带 url 的命令 payload (`loadUrl` / `getCookies`); 无参命令省略该字段。 */
@Serializable
data class OhosWebUrlPayload(
    val url: String? = null,
)

/**
 * ArkTS → Kotlin 前台 Web 响应/事件 (与 ArkTS handler 逐字段对齐)。
 *
 * 命令响应 (`requestId > 0`) 用 [ok]/[result]/[cookie]/[canGoBack]/[url]/[error];
 * 主动页面事件 (`requestId == 0`) 用 [event] + 对应字段 ([url]/[cookie]/[title]/[progress])。
 */
@Serializable
data class OhosWebControllerResult(
    val ok: Boolean = true,
    val result: String? = null,
    val cookie: String? = null,
    val canGoBack: Boolean? = null,
    val url: String? = null,
    val error: String? = null,
    val event: String? = null,
    val title: String? = null,
    val progress: Int? = null,
)

/** 前台 Web 页面事件监听器 (由 [OhosWebViewSlot] 注册, 单个前台 Web 实例)。 */
fun interface OhosWebViewPageListener {
    fun onPageEvent(event: OhosWebControllerResult)
}

/**
 * 前台 Web 控制器桥真实实现 (napi tsfn ↔ ArkTS 可见 Web 组件)。
 *
 * 单实例: 前台 Web 是屏幕级覆盖层 (同一时刻只有一个), 故页面事件监听为单槽位
 * (与 [io.legado.app.napi.OhosNativeBridge] media 事件按 playerId 注册不同, 此处无需多实例)。
 */
object OhosVisibleWebViewBridge : OhosWebViewBridge {

    /** tsfn 引用保护锁 (与 [io.legado.app.napi.OhosNativeBridge] 同模式)。 */
    private val lock = SynchronizedObject()

    /** C++ tsfn dispatch 入口 (由 @CName registerOhosWebControllerFn 注入)。 */
    @Volatile
    private var dispatch: ((String) -> Unit)? = null

    /** 同步命令等待槽 Map<requestId, CompletableDeferred<resultJson>>。 */
    private val pending = mutableMapOf<Long, CompletableDeferred<String>>()

    /** 异步命令回调槽 Map<requestId, (result) -> Unit> (evaluateJavascript 用, 不阻塞调用线程)。 */
    private val asyncCallbacks = mutableMapOf<Long, (String?) -> Unit>()

    /** 请求自增 ID (原子性由 [lock] 保护)。 */
    private var requestCounter = 0L

    /** 页面事件监听器 (Compose slot 注册; 前台 Web 单实例)。 */
    @Volatile
    private var pageListener: OhosWebViewPageListener? = null

    /** ArkTS `registerWebController` 是否已完成 (tsfn 注入)。 */
    fun isReady(): Boolean = dispatch != null

    /** 注入 tsfn (由 @CName [registerOhosWebControllerFn] 调用)。 */
    fun registerDispatch(tsfn: (String) -> Unit) {
        synchronized(lock) {
            dispatch = tsfn
        }
    }

    /** 注册页面事件监听器 (slot 组合时设置)。 */
    fun setPageListener(listener: OhosWebViewPageListener?) {
        pageListener = listener
    }

    /** 清除页面事件监听器 (slot 离开组合时调用, 避免旧 slot 收到事件)。 */
    fun clearPageListener() {
        pageListener = null
    }

    /** 取下一个请求 ID。 */
    private fun nextRequestId(): Long = synchronized(lock) { ++requestCounter }

    /** 发送请求 JSON 到 ArkTS (tsfn 未注入返回 false)。 */
    private fun sendRequest(requestId: Long, action: String, payloadJson: String): Boolean {
        val tsfn = dispatch ?: return false
        val json = KS_JSON.encodeToString(
            OhosWebControllerRequest(requestId = requestId, action = action, payload = payloadJson)
        )
        return runCatching { tsfn(json) }.isSuccess
    }

    /** fire-and-forget 命令 (无结果等待; ArkTS 若回送 requestId 无 pending 则丢弃)。 */
    private fun fire(action: String, payloadJson: String) {
        sendRequest(nextRequestId(), action, payloadJson)
    }

    /**
     * 同步命令: 阻塞等待 ArkTS 返回原始结果 JSON。
     *
     * @param action 命令 action
     * @param payloadJson payload JSON 字符串
     * @param timeoutMs 超时毫秒 (前台操作含页面加载, 给 15s, 对齐 AppConst.timeLimit)
     * @return ArkTS 返回的结果 JSON; tsfn 未注册/发送失败/超时返回 null
     */
    fun invokeSync(action: String, payloadJson: String, timeoutMs: Long = 15000L): String? {
        val requestId = nextRequestId()
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { pending[requestId] = deferred }
        if (!sendRequest(requestId, action, payloadJson)) {
            synchronized(lock) { pending.remove(requestId) }
            return null
        }
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        synchronized(lock) { pending.remove(requestId) }
        return result
    }

    /** 解析结果 JSON (失败返回 null)。 */
    private fun parseResult(json: String?): OhosWebControllerResult? {
        if (json.isNullOrEmpty()) return null
        return runCatching {
            KS_JSON.decodeFromString(OhosWebControllerResult.serializer(), json)
        }.getOrNull()
    }

    /** 安全调用监听器 (ArkTS 主线程直推; Compose 状态更新即在该线程, 与平台事件通道同口径)。 */
    private fun invokeListenerSafely(block: () -> Unit) {
        runCatching(block)
    }

    /** 显示并加载前台 Web (含 headers/html/宽视口/登录标记)。 */
    fun show(config: WebViewConfig) {
        val payload = OhosWebViewControllerPayload(
            url = config.url,
            html = config.html,
            headers = config.headerMap.takeIf { it.isNotEmpty() }
                ?.map { (name, value) -> OhosNativeBridge.WebViewHeader(name, value) },
            wideViewPort = config.wideViewPort,
            isLogin = config.isLogin,
            sourceKey = config.sourceKey.takeIf { it.isNotBlank() },
        )
        fire("show", KS_JSON.encodeToString(payload))
    }

    /** 隐藏并 dismiss 前台 Web (路由返回/离开组合时调用)。 */
    fun hide() {
        fire("hide", KS_JSON.encodeToString(OhosWebUrlPayload()))
    }

    /** 加载 URL (对齐 [OhosWebViewBridge.loadUrl]; 不携带 headers, show 才带完整配置)。 */
    override fun loadUrl(url: String) {
        fire("loadUrl", KS_JSON.encodeToString(OhosWebUrlPayload(url = url)))
    }

    /** 同步执行 JS 并取回结果 (接口保留; 前台 host 走异步 [evaluateJavascript] 避免阻塞回调线程)。 */
    override fun runJavaScript(script: String): String? {
        val json = invokeSync("runJavaScript", KS_JSON.encodeToString(OhosWebScriptPayload(script)))
        return parseResult(json)?.result
    }

    /** 异步执行 JS, 结果经 [onResult] 回调 (在 ArkTS 主线程回推; 不阻塞调用线程)。 */
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        val requestId = nextRequestId()
        synchronized(lock) { asyncCallbacks[requestId] = onResult }
        if (!sendRequest(requestId, "runJavaScript", KS_JSON.encodeToString(OhosWebScriptPayload(script)))) {
            synchronized(lock) { asyncCallbacks.remove(requestId) }
            onResult(null)
        }
    }

    /** 取当前页面 cookie (同步; 失败返回 null)。 */
    override fun getCookies(url: String): String? {
        val json = invokeSync("getCookies", KS_JSON.encodeToString(OhosWebUrlPayload(url = url)))
        return parseResult(json)?.cookie
    }

    /** 当前页 URL (同步; 失败返回 null, 调用方回退预取 URL)。 */
    fun getUrl(): String? {
        val json = invokeSync("getUrl", KS_JSON.encodeToString(OhosWebUrlPayload()))
        return parseResult(json)?.url
    }

    /** 是否可后退 (同步; 失败返回 false)。 */
    fun canGoBack(): Boolean {
        val json = invokeSync("canGoBack", KS_JSON.encodeToString(OhosWebUrlPayload()))
        return parseResult(json)?.canGoBack ?: false
    }

    /** 后退一步 (fire-and-forget)。 */
    fun goBack() {
        fire("goBack", KS_JSON.encodeToString(OhosWebUrlPayload()))
    }

    /** 重新加载当前页 (fire-and-forget)。 */
    fun reload() {
        fire("reload", KS_JSON.encodeToString(OhosWebUrlPayload()))
    }

    /** 退出网页 HTML5 视频全屏 (fire-and-forget)。 */
    fun exitFullScreen() {
        fire("exitFullScreen", KS_JSON.encodeToString(OhosWebUrlPayload()))
    }

    /**
     * ArkTS → Kotlin 前台 Web 结果/事件入口 (由 @CName [onOhosWebControllerCallback] 调用)。
     *
     * @param requestId >0 = 命令响应 (唤醒等待方); =0 = 主动页面事件 (投递给 [pageListener])
     * @param resultJson 结果/事件 JSON ([OhosWebControllerResult])
     */
    fun onCallback(requestId: Long, resultJson: String) {
        if (requestId == 0L) {
            val event = parseResult(resultJson) ?: return
            invokeListenerSafely { pageListener?.onPageEvent(event) }
            return
        }
        // 先查异步槽 (evaluateJavascript), 再查同步槽
        val asyncCallback = synchronized(lock) { asyncCallbacks.remove(requestId) }
        if (asyncCallback != null) {
            val result = parseResult(resultJson)
            invokeListenerSafely { asyncCallback(result?.result) }
            return
        }
        val deferred = synchronized(lock) { pending.remove(requestId) }
        if (deferred == null) {
            AppLog.putDebug("未知 requestId 的前台 Web 回调: $requestId", tag = "ohos-webview")
            return
        }
        deferred.complete(resultJson)
    }
}

/**
 * 注入前台 Web 控制器 tsfn (由 C++ legado_napi.cpp RegisterWebControllerCallback 调用)。
 *
 * 调用链: `ArkTS legado.registerWebController(cb)` → C++ 创建 napi_threadsafe_function →
 * C++ dlsym("legado_register_web_controller_fn") → 本函数 → [OhosVisibleWebViewBridge.registerDispatch]。
 *
 * # 顶层 @CName (非 object 成员)
 * 本函数刻意定义为顶层函数: C++ 侧 dlsym 后按 `void(*)(const char*)` 调用, 只压入逻辑实参
 * (与既有 `legado_register_toast_fn` 等由 object 成员导出、编译后仍额外占一个 thiz 寄存器的
 * 历史形态不同)。顶层函数无 thiz, x0 即 dispatch 指针, 调用约定与 C++ 侧逐字一致。
 *
 * @param dispatch C++ tsfn dispatch 入口 (`ohos_web_controller_dispatch`), 类型 `void(*)(const char*)`
 */
@CName("legado_register_web_controller_fn")
fun registerOhosWebControllerFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
    OhosVisibleWebViewBridge.registerDispatch { json ->
        memScoped {
            dispatch(json.cstr.getPointer(this))
        }
    }
    // 兼容既有 expect/actual 骨架: 让 OhosWebViewBridge.get() 返回真实实现
    OhosWebViewBridge.register(OhosVisibleWebViewBridge)
}

/**
 * ArkTS → Kotlin 前台 Web 结果/事件回调 (由 C++ legado_napi.cpp WebControllerCallback 调用)。
 *
 * 调用链: `ArkTS legado.webControllerCallback(requestId, resultJson)` → napi (C++) →
 * dlsym("legado_web_controller_callback") → 本函数 → [OhosVisibleWebViewBridge.onCallback]。
 *
 * # 顶层 @CName
 * 同 [registerOhosWebControllerFn]: 顶层函数保证 x0=requestId、x1=result 指针, 与 C++
 * `legado_int64_cstr_void_fn` 调用约定一致。
 *
 * @param requestId 与 [OhosVisibleWebViewBridge.invokeSync]/[OhosVisibleWebViewBridge.evaluateJavascript]
 *   生成的 requestId 对应; 0 表示主动页面事件
 * @param result 结果/事件 JSON (UTF-8 C 字符串)
 */
@CName("legado_web_controller_callback")
fun onOhosWebControllerCallback(requestId: Long, result: CPointer<ByteVar>) {
    OhosVisibleWebViewBridge.onCallback(requestId, result.toKString())
}
