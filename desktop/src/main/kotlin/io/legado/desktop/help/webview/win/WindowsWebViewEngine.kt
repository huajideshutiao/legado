package io.legado.desktop.help.webview.win

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.utils.NetworkUtils
import io.legado.desktop.help.webview.DesktopWebViewEngine
import io.legado.desktop.help.webview.WebViewFetchRequest
import io.legado.desktop.help.webview.WebViewFetchResult
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import io.legado.desktop.help.webview.unwrapScriptResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows 系统引擎: 直调系统自带的 WebView2 Runtime (JNA + COM), 不随包发任何 native 文件。
 *
 * 语义对照 app 端 `BackstageWebView` / `WebViewActivity`: 无头抓取走 [fetch],
 * 登录/网页验证走 [openWindow] 的独立顶层窗口 (见 [WebView2Loop] 为何不嵌进 Compose)。
 */
internal object WindowsWebViewEngine : DesktopWebViewEngine {

    override val id: String get() = "webview2"

    /** JS 取不到结果时的重试上限, 与 app 端 EvalJsRunnable 的 `retry > 30` 一致。 */
    private const val MAX_JS_RETRY = 30

    private const val JS_RETRY_INTERVAL_MS = 1000L

    private const val COOKIE_TIMEOUT_MS = 5_000L

    /** app 端 BackstageWebView.JS 默认脚本。 */
    const val DEFAULT_JS = "document.documentElement.outerHTML"

    /** cookie 回收与窗口创建都不能占用 WebView2 消息泵线程, 统一挪到这里。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isAvailable(): Boolean = WebView2Runtime.detect() != null

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult {
        val sniffing =
            !request.sourceRegex.isNullOrBlank() || !request.overrideUrlRegex.isNullOrBlank()
        val instance = WebView2Instance.create(
            visible = false,
            title = "legado-backstage",
            sniffResources = !request.sourceRegex.isNullOrBlank(),
        ) ?: throw NoStackTraceException("WebView2 实例创建失败")
        try {
            return withTimeout(AppConst.timeLimit) {
                if (sniffing) runSniffer(instance, request) else runHtml(instance, request)
            }
        } finally {
            instance.close()
        }
    }

    /** 对应 app 端 HtmlWebViewClient: 等页面 + 延时 + 反复执行 JS 直到拿到非空结果。 */
    private suspend fun runHtml(
        instance: WebView2Instance,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val redirected = AtomicBoolean(false)
        instance.onNavigationStarting = { _, isRedirected ->
            if (isRedirected) redirected.set(true)
            false
        }
        instance.onNavigationCompleted = { url -> harvestCookies(instance, url, request.cookieTag) }
        start(instance, request)

        delay(request.delayTime)
        val script = request.javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        repeat(MAX_JS_RETRY + 1) {
            val body = unwrapScriptResult(instance.executeScript(script, AppConst.timeLimit))
            if (!body.isNullOrEmpty()) {
                val url = instance.currentUrl() ?: request.url.orEmpty()
                return WebViewFetchResult(url, body, redirected.get())
            }
            delay(JS_RETRY_INTERVAL_MS)
        }
        throw NoStackTraceException("js执行超时")
    }

    /** 对应 app 端 SnifferWebClient: 命中 overrideUrlRegex / sourceRegex 即以该地址作为结果。 */
    private suspend fun runSniffer(
        instance: WebView2Instance,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val hit = CompletableDeferred<String>()
        val overrideRegex = request.overrideUrlRegex?.takeIf { it.isNotBlank() }?.toRegex()
        val sourceRegex = request.sourceRegex?.takeIf { it.isNotBlank() }?.toRegex()

        instance.onNavigationStarting = { url, _ ->
            val matched = overrideRegex?.matches(url) == true
            if (matched) hit.complete(url)
            // 命中即取消导航, 与 app 端 shouldOverrideUrlLoading 返回 true 等价
            matched
        }
        instance.onResourceRequested = { url ->
            if (sourceRegex?.matches(url) == true) hit.complete(url)
        }
        instance.onNavigationCompleted = { url ->
            harvestCookies(instance, url, request.cookieTag)
            // app 端在 onPageStarted 延时注入 JS, 这里等页面就绪后补一次
            request.javaScript?.takeIf { it.isNotEmpty() }
                ?.let { instance.navigate("javascript:$it") }
        }
        start(instance, request)
        val resultUrl = hit.await()
        return WebViewFetchResult(request.url ?: resultUrl, resultUrl, false)
    }

    /** 对应 app 端 load(): html 优先, 否则加载 url; UA 走 Settings2。 */
    private fun start(instance: WebView2Instance, request: WebViewFetchRequest) {
        request.headerMap?.get(AppConst.UA_NAME)?.let { instance.setUserAgent(it) }
        injectCookies(instance, request)
        val html = request.html
        when {
            !html.isNullOrEmpty() -> instance.navigateToString(html)
            !request.url.isNullOrEmpty() -> instance.navigate(request.url)
            else -> throw NoStackTraceException("url 与 html 不能同时为空")
        }
    }

    /**
     * 把 CookieStore 里已有的 cookie 注入浏览器再导航。
     *
     * app 端不需要这步 —— 安卓 WebView 与 OkHttp 共用进程级 CookieManager; 桌面端两者是
     * 各自独立的存储, 不注入的话 webView 回源会丢掉此前 HTTP 侧登录拿到的会话。
     * 注入与回收 (见 [harvestCookies]) 合起来才是完整闭环。
     */
    private fun injectCookies(instance: WebView2Instance, request: WebViewFetchRequest) {
        val url = request.url?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val store = CookieStoreProviders.get() ?: return
            val cookie = store.getCookie(url).takeIf { it.isNotBlank() } ?: return
            val domain = NetworkUtils.getSubDomain(url).takeIf { it.isNotBlank() } ?: return
            instance.setCookies(domain, cookie)
        }.onFailure { AppLog.put("WebView2 cookie 注入失败", it) }
    }

    /**
     * 回收 cookie 写入 CookieStore, 对应 app 端 `setCookie(url)`:
     * 仅在有 tag 时保存, 且存到 tag (书源 key) 名下而非页面地址。
     */
    private fun harvestCookies(instance: WebView2Instance, url: String, tag: String?) {
        if (tag.isNullOrBlank() || url.isBlank()) return
        scope.launch {
            val cookie = instance.cookies(url, COOKIE_TIMEOUT_MS) ?: return@launch
            runCatching { CookieStoreProviders.get()?.setCookie(tag, cookie) }
                .onFailure { AppLog.put("WebView2 cookie 回写失败", it) }
        }
    }

    /**
     * 可见窗口的 cookie 回收, 对应 app 端 `WebViewActivity.onPageFinished`:
     * 按页面地址存一份, 有书源 key 时再存一份 (登录态要按 key 取)。
     */
    internal fun harvestWindowCookies(instance: WebView2Instance, url: String, tag: String?) {
        if (url.isBlank()) return
        scope.launch {
            val cookie = instance.cookies(url, COOKIE_TIMEOUT_MS) ?: return@launch
            runCatching {
                val store = CookieStoreProviders.get() ?: return@runCatching
                store.setCookie(url, cookie)
                if (!tag.isNullOrBlank()) store.setCookie(tag, cookie)
            }.onFailure { AppLog.put("WebView2 窗口 cookie 回写失败", it) }
        }
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!isAvailable()) return null
        val handle = WebView2WindowHandle(request)
        scope.launch { handle.open() }
        return handle
    }
}

/**
 * 可见窗口句柄。cookie 由调用方在 [WebViewWindowRequest.onNavigated] 里回收
 * (对齐 app 端 WebViewActivity.onPageFinished), 用户关窗触发 onClosed。
 */
private class WebView2WindowHandle(
    private val request: WebViewWindowRequest,
) : WebViewWindowHandle {

    @Volatile
    private var instance: WebView2Instance? = null

    /** 缓存最近一次导航地址: 属性读取不能阻塞去问 COM 线程。 */
    @Volatile
    override var currentUrl: String? = null
        private set

    private val closedOnce = AtomicBoolean(false)

    suspend fun open() {
        val created = WebView2Instance.create(visible = true, title = request.title)
        if (created == null) {
            AppLog.put("WebView2 窗口创建失败: ${request.title}")
            close()
            return
        }
        if (closedOnce.get()) {
            created.close()
            return
        }
        instance = created
        currentUrl = request.url
        request.userAgent?.let { created.setUserAgent(it) }
        created.onNavigationCompleted = { url ->
            if (url.isNotBlank()) {
                currentUrl = url
                WindowsWebViewEngine.harvestWindowCookies(created, url, request.cookieTag)
                runCatching { request.onNavigated(url) }
            }
        }
        created.onWindowClose = { close() }
        created.navigate(request.url)
    }

    override suspend fun currentHtml(): String? {
        val target = instance ?: return null
        return unwrapScriptResult(
            target.executeScript(WindowsWebViewEngine.DEFAULT_JS, AppConst.timeLimit)
        )
    }

    override fun reload() {
        instance?.reload()
    }

    override fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        instance?.close()
        instance = null
        runCatching { request.onClosed() }
    }
}
