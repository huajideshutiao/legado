@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.getUserAgent
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS 端 [BackstageWebViewFactory]: 隐藏 [WKWebView] + WKNavigationDelegate 异步桥,
 * 语义对齐 app 端 `BackstageWebView`。
 *
 * 与原版的对照:
 * - **加载完成判定**: 原版在 `onPageStarted` 起算 `delayTime` 后跑 JS; 这里 didFinishNavigation
 *   先到就提前跑, 否则等到 delayTime, 之后同样按 30 次 × 1s 重试直到拿到非空结果;
 * - **超时**: 同为 [AppConst.timeLimit] (15s), 被取消时主线程 stopLoading + 断 delegate;
 * - **cookie**: didFinishNavigation 时读 WKHTTPCookieStore 写回 [CookieStoreProviders]
 *   (对齐原版 `onPageFinished` → `setCookie(tag, cookie)`); 加载前反向注入一次,
 *   替代安卓全局 CookieManager 的隐式共享;
 * - **取源码**: `evaluateJavaScript` 直接回传 JS 原生值, 不像安卓需 unescapeJson + 去引号。
 *
 * 已知缺口: `sourceRegex` 资源级嗅探需同步拦截每个子请求, WKWebView 只有主框架导航的
 * decidePolicyForNavigationAction 与自定义 scheme 的 WKURLSchemeHandler, 无等价能力,
 * 该场景抛 [UnsupportedOperationException] 由调用方 runCatching 回退 HTTP 直连;
 * 自签证书也无 `onReceivedSslError` 等价旁路。`overrideUrlRegex` 走 decidePolicy 精确命中。
 */
private object IosBackstageWebViewFactory : BackstageWebViewFactory {

    override fun create(
        url: String?,
        html: String?,
        encode: String?,
        tag: String?,
        headerMap: Map<String, String>?,
        sourceRegex: String?,
        overrideUrlRegex: String?,
        javaScript: String?,
        delayTime: Long,
    ): BackstageWebViewHandle = IosBackstageWebViewHandle(
        url = url,
        // encode 未用: loadHTMLString 固定按 UTF-8 解析, iOS 无 loadData 的编码入口
        html = html,
        tag = tag,
        headerMap = headerMap,
        sourceRegex = sourceRegex,
        overrideUrlRegex = overrideUrlRegex,
        javaScript = javaScript,
        delayTime = delayTime,
    )
}

/** 宿主启动早期注册一次 (任何 AnalyzeUrl webView 调用之前)。 */
fun registerIosBackstageWebView() {
    BackstageWebViewProviders.register(IosBackstageWebViewFactory)
}

/** app 端 BackstageWebView.JS 默认脚本。 */
private const val DEFAULT_JS = "document.documentElement.outerHTML"

/** JS 取不到结果时的重试上限与间隔, 与 app 端 EvalJsRunnable 的 `retry > 30` / 1s 一致。 */
private const val MAX_JS_RETRY = 30
private const val JS_RETRY_INTERVAL_MS = 1000L

/** 等页面/等嗅探命中的轮询粒度。 */
private const val POLL_INTERVAL_MS = 100L

/** cookie 回写走 IO, 不占主线程 (对照 desktop WindowsWebViewEngine.scope)。 */
private val cookieScope = CoroutineScope(SupervisorJob() + IoDispatcher)

/**
 * 单次后台抓取。[WKWebView] 的创建与全部操作都在主线程 (UIKit 要求),
 * 状态字段也只在主线程读写, 无需原子化。
 */
private class IosBackstageWebViewHandle(
    private val url: String?,
    private val html: String?,
    private val tag: String?,
    private val headerMap: Map<String, String>?,
    private val sourceRegex: String?,
    private val overrideUrlRegex: String?,
    private val javaScript: String?,
    private val delayTime: Long,
) : BackstageWebViewHandle {

    private var webView: WKWebView? = null

    /** navigationDelegate 是 weak 引用, 必须自己持强引用防 GC (同 IosFilePicker activeDelegates)。 */
    private var delegate: BackstageNavDelegate? = null

    private var finished = false
    private var failure: Throwable? = null
    private var hitUrl: String? = null

    override suspend fun getStrResponse(): StrResponse {
        if (!sourceRegex.isNullOrBlank()) {
            throw UnsupportedOperationException("iOS 端不支持 sourceRegex 资源嗅探, 已回退 HTTP 直连")
        }
        // cookie 读取会走 DB (SharedCookieStore 内部 runBlocking), 必须在切主线程之前做完
        val pendingCookie = readStoredCookie()
        return withTimeout(AppConst.timeLimit) {
            try {
                withContext(Dispatchers.Main) { load(pendingCookie) }
            } finally {
                // 超时/取消/异常都要停加载并断 delegate (对照原版 invokeOnCancellation { destroy() })
                dispatch_async(dispatch_get_main_queue()) { destroy() }
            }
        }
    }

    private suspend fun readStoredCookie(): String? = withContext(IoDispatcher) {
        val target = url?.takeIf { it.startsWith("http") } ?: return@withContext null
        runCatching { CookieStoreProviders.get()?.getCookie(target) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun load(pendingCookie: String?): StrResponse {
        val webView = createWebView()
        injectCookies(webView, pendingCookie)
        start(webView)
        return if (overrideUrlRegex.isNullOrBlank()) runHtml(webView) else runSniffer(webView)
    }

    private fun createWebView(): WKWebView {
        // 默认 websiteDataStore 即 defaultDataStore(): cookie/localStorage 与前台 IosWebViewSlot
        // 共用一份持久存储, 对齐安卓全局 CookieManager
        val configuration = WKWebViewConfiguration()
        // 不入视图层级的 WKWebView 照常发起网络请求并执行 JS (仅渲染/rAF 会被 WebKit 节流),
        // 故无需 attach; frame 给 iPhone 逻辑尺寸让响应式页面按移动端布局
        val view = WKWebView(CGRectMake(0.0, 0.0, 375.0, 667.0), configuration)
        view.customUserAgent = headerMap.getUserAgent()
        val navDelegate = BackstageNavDelegate(
            overrideRegex = overrideUrlRegex?.takeIf { it.isNotBlank() }?.toRegex(),
            onFinish = { finished = true; harvestCookies(it) },
            onFail = { failure = NoStackTraceException("webView加载失败: $it") },
            onHit = { hitUrl = it },
        )
        delegate = navDelegate
        view.navigationDelegate = navDelegate
        webView = view
        return view
    }

    private fun start(webView: WKWebView) {
        val htmlStr = html
        val urlStr = url
        when {
            !htmlStr.isNullOrEmpty() ->
                webView.loadHTMLString(htmlStr, urlStr?.let { NSURL.URLWithString(it) })

            !urlStr.isNullOrEmpty() -> {
                val nsUrl = NSURL.URLWithString(urlStr)
                    ?: throw NoStackTraceException("url解析失败: $urlStr")
                val request = NSMutableURLRequest(uRL = nsUrl)
                headerMap?.forEach { (name, value) ->
                    request.setValue(value, forHTTPHeaderField = name)
                }
                webView.loadRequest(request)
            }

            else -> throw NoStackTraceException("url与html不能同时为空")
        }
    }

    /** 对应 app 端 HtmlWebViewClient: 等页面 + 反复执行 JS 直到拿到非空结果。 */
    private suspend fun runHtml(webView: WKWebView): StrResponse {
        awaitPageSettled()
        val script = javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        repeat(MAX_JS_RETRY + 1) {
            failure?.let { cause -> throw cause }
            val body = evaluate(webView, script)
            if (!body.isNullOrEmpty() && body != "null") {
                return StrResponse(webView.URL?.absoluteString ?: url.orEmpty(), body)
            }
            delay(JS_RETRY_INTERVAL_MS)
        }
        throw NoStackTraceException("js执行超时")
    }

    /** 对应 app 端 SnifferWebClient: 命中 overrideUrlRegex 即以该地址作为结果。 */
    private suspend fun runSniffer(webView: WKWebView): StrResponse {
        var waited = 0L
        var injected = false
        while (true) {
            // 命中优先于 failure: 取消导航本身会触发 didFailProvisionalNavigation
            hitUrl?.let { hit -> return StrResponse(url ?: hit, hit) }
            failure?.let { cause -> throw cause }
            if (!injected && waited >= delayTime) {
                injected = true
                // 原版在 onPageStarted 延时后 loadUrl("javascript:...")
                javaScript?.takeIf { it.isNotEmpty() }?.let { evaluate(webView, it) }
            }
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
    }

    /** didFinish/didFail 先到就返回, 否则等到 delayTime (原版从 onPageStarted 起算, 不等页面完成)。 */
    private suspend fun awaitPageSettled() {
        var waited = 0L
        while (waited < delayTime) {
            if (finished || failure != null) return
            val step = minOf(POLL_INTERVAL_MS, delayTime - waited)
            delay(step)
            waited += step
        }
    }

    private suspend fun evaluate(webView: WKWebView, script: String): String? =
        suspendCancellableCoroutine { block ->
            webView.evaluateJavaScript(script) { result, error ->
                // iOS 回传 JS 原生值, 不像安卓 evaluateJavascript 返回 JSON 转义串, 无需 unescape
                if (block.isActive) block.resume(if (error != null) null else result?.toString())
            }
        }

    /** 加载前把业务层 cookie 注入 WKHTTPCookieStore, 替代安卓全局 CookieManager 的隐式共享。 */
    private suspend fun injectCookies(webView: WKWebView, cookie: String?) {
        if (cookie.isNullOrBlank()) return
        val host = url?.let { NSURL.URLWithString(it) }?.host ?: return
        val store = webView.configuration.websiteDataStore.httpCookieStore
        cookieToMap(cookie).forEach { (name, value) ->
            val properties = mapOf<Any?, Any?>(
                NSHTTPCookieName to name,
                NSHTTPCookieValue to value,
                NSHTTPCookieDomain to host,
                NSHTTPCookiePath to "/",
            )
            val nsCookie = NSHTTPCookie.cookieWithProperties(properties) ?: return@forEach
            // setCookie 是异步的, 必须等它落库再 loadRequest
            suspendCancellableCoroutine<Unit> { block ->
                store.setCookie(nsCookie) { if (block.isActive) block.resume(Unit) }
            }
        }
    }

    /** 对应 app 端 setCookie(url): 仅在有 tag 时保存, 且存到 tag (书源 key) 名下而非页面地址。 */
    private fun harvestCookies(webView: WKWebView) {
        val cookieTag = tag?.takeIf { it.isNotBlank() } ?: return
        val host = webView.URL?.host ?: return
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { all ->
            val cookie = all.orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .filter { matchesHost(host, it.domain) }
                .joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isNotBlank()) {
                cookieScope.launch {
                    runCatching { CookieStoreProviders.get()?.setCookie(cookieTag, cookie) }
                        .onFailure { AppLog.put("iOS后台WebView cookie回写失败", it) }
                }
            }
        }
    }

    /** getAllCookies 返回整个 dataStore 的 cookie, 按页面 host 过滤 (对齐 CookieManager.getCookie(url))。 */
    private fun matchesHost(host: String, domain: String): Boolean {
        val bare = domain.removePrefix(".")
        return host == bare || host.endsWith(".$bare")
    }

    private fun destroy() {
        webView?.let {
            it.stopLoading()
            it.navigationDelegate = null
        }
        // 未 attach 到视图层级, 引用清零后由 ARC 回收
        webView = null
        delegate = null
    }
}

/**
 * WKNavigationDelegate: didFinish 回收 cookie, didFailProvisionalNavigation 快速失败,
 * decidePolicyForNavigationAction 对应原版 shouldOverrideUrlLoading。
 *
 * 只实现 didFinishNavigation 与 didFailProvisionalNavigation 各一个:
 * 同形参签名的兄弟方法 (didStart/didCommit, didFailNavigation) 在 Kotlin 侧是冲突重载,
 * 每族只实现一个就无需 @ObjCSignatureOverride, 类级 @Suppress 即可。
 */
@Suppress("CONFLICTING_OVERLOADS")
private class BackstageNavDelegate(
    private val overrideRegex: Regex?,
    private val onFinish: (WKWebView) -> Unit,
    private val onFail: (String) -> Unit,
    private val onHit: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinish(webView)
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        onFail(withError.localizedDescription)
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (target != null && overrideRegex?.matches(target) == true) {
            onHit(target)
            // 对齐原版 shouldOverrideUrlLoading 返回 true: 命中即取消导航
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
    }
}
