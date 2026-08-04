package io.legado.desktop.help.webview.mac

import com.sun.jna.Callback
import com.sun.jna.Pointer
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.NetworkUtils
import io.legado.desktop.help.webview.CHECK_HOST_COOKIE_TEXT
import io.legado.desktop.help.webview.DesktopWebViewEngine
import io.legado.desktop.help.webview.WebViewFetchRequest
import io.legado.desktop.help.webview.WebViewFetchResult
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import io.legado.desktop.help.webview.mac.ObjC.fromId
import io.legado.desktop.help.webview.mac.ObjC.ns
import io.legado.desktop.help.webview.mac.ObjC.property
import io.legado.desktop.help.webview.mac.ObjC.ptr
import io.legado.desktop.help.webview.mac.ObjC.void
import io.legado.desktop.help.webview.win.ToolbarAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Toolkit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * macOS 系统引擎: JNA + Objective-C 直绑系统框架 WKWebView (零体积增量)。
 *
 * 线程模型: AppKit 主线程 = AWT EDT (见 [ObjC] 说明), 所有 AppKit 操作经 [CocoaLoop] 投递。
 *
 * 语义对照 app 端 `BackstageWebView` / `WebViewActivity`:
 * - 无头抓取: 隐藏窗口 WKWebView → loadRequest (注入 header/UA) → 延时 → evaluateJavaScript
 *   取结果 (空则每秒重试 30 次) → cookie 回收;
 * - 嗅探: WKWebView 无子资源拦截 API (WKURLSchemeHandler 不能接管 http/https), 用
 *   document-start 注入 JS hook (fetch/XHR) + performance entries 轮询兜底静态资源;
 *   overrideUrlRegex 走导航完成地址。命中语义为"轮询发现即返回" (比 Android 拦截语义滞后
 *   一个轮询周期, 且无法阻止资源加载), KDoc 注明差异;
 * - cookie 注入/回收走 WKHTTPCookieStore (block API, 主线程发起 + 协程挂起等待);
 * - 可见窗口带 AppKit 工具栏, 行为对照 Windows WebView2Toolbar。
 */
internal object MacWebViewEngine : DesktopWebViewEngine {

    override val id: String get() = "wkwebview"

    private const val MAX_JS_RETRY = 30
    private const val JS_RETRY_INTERVAL_MS = 1000L
    private const val SNIFF_INTERVAL_MS = 500L
    private const val COOKIE_TIMEOUT_MS = 5_000L

    /** app 端 BackstageWebView.JS 默认脚本。 */
    const val DEFAULT_JS = "document.documentElement.outerHTML"

    /** document-start 注入的嗅探 hook: 捕获 fetch/XHR 的 URL。 */
    internal const val SNIFF_HOOK_JS = """
        (function(){
          if (window.__legadoSniff) return;
          window.__legadoSniff = [];
          var p = function(u){ try{ if(u) window.__legadoSniff.push(String(u)); }catch(e){} };
          try{ var f = window.fetch; if (f) window.fetch = function(){
            try{ p(arguments[0] && (arguments[0].url || arguments[0])); }catch(e){}
            return f.apply(this, arguments); }; }catch(e){}
          try{ var o = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function(m,u){
            try{ p(u); }catch(e){} return o.apply(this, arguments); }; }catch(e){}
        })();
    """

    /** 轮询取全部嗅探到的 URL (hook 结果 + performance entries 兜底静态资源), JSON 数组。 */
    private const val SNIFF_COLLECT_JS = """
        (function(){try{
          var a = (window.__legadoSniff || []).concat(
            performance.getEntriesByType('resource').map(function(e){ return e.name; }));
          return JSON.stringify(a);
        }catch(e){ return '[]'; }})()
    """

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var probed = false

    @Volatile
    private var available = false

    override fun isAvailable(): Boolean {
        if (probed) return available
        available = runCatching {
            // mac 上 WKWebView 框架必然存在; 探测做一次最小调用确认 runtime 可用
            ObjC.cls("WKWebView") != Pointer.NULL
        }.getOrDefault(false)
        probed = true
        return available
    }

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult {
        if (!isAvailable()) throw NoStackTraceException("WKWebView 引擎不可用")
        val sniffing =
            !request.sourceRegex.isNullOrBlank() || !request.overrideUrlRegex.isNullOrBlank()
        val session = CocoaLoop.await { MacSession.create(visible = false, sniff = sniffing) }
            ?: throw NoStackTraceException("WKWebView 会话创建失败")
        try {
            return withTimeout(AppConst.timeLimit) {
                if (sniffing) runSniffer(session, request) else runHtml(session, request)
            }
        } finally {
            CocoaLoop.post { session.destroy() }
        }
    }

    private suspend fun runHtml(
        session: MacSession,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val redirected = AtomicBoolean(false)
        injectCookies(session, request)
        CocoaLoop.await {
            session.onNavigated = { url ->
                if (!url.isNullOrBlank() && url != request.url) redirected.set(true)
                if (!url.isNullOrBlank()) harvestCookies(session, url, request.cookieTag)
            }
            session.start(request)
        }

        delay(request.delayTime)
        val script = request.javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        repeat(MAX_JS_RETRY + 1) {
            val body = session.evaluateJavascript(script)
            if (!body.isNullOrEmpty() && body != "null") {
                val url = CocoaLoop.await { session.currentUrl() }.takeIf { !it.isNullOrBlank() }
                    ?: request.url.orEmpty()
                return WebViewFetchResult(url, body, redirected.get())
            }
            delay(JS_RETRY_INTERVAL_MS)
        }
        throw NoStackTraceException("js执行超时")
    }

    /** 嗅探: hook + performance 轮询; overrideRegex 走导航地址。 */
    private suspend fun runSniffer(
        session: MacSession,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val overrideRegex = request.overrideUrlRegex?.takeIf { it.isNotBlank() }?.toRegex()
        val sourceRegex = request.sourceRegex?.takeIf { it.isNotBlank() }?.toRegex()
        val seen = HashSet<String>()
        val sniffed = CompletableDeferred<String>()
        injectCookies(session, request)
        CocoaLoop.await {
            session.onNavigated = { url ->
                if (!url.isNullOrBlank()) {
                    harvestCookies(session, url, request.cookieTag)
                    if (overrideRegex?.matches(url) == true) sniffed.complete(url)
                }
            }
            session.start(request)
        }

        val deadline = System.currentTimeMillis() + AppConst.timeLimit
        while (System.currentTimeMillis() < deadline) {
            val body = session.evaluateJavascript(SNIFF_COLLECT_JS) ?: ""
            runCatching {
                val urls = Json.parseToJsonElement(body).jsonArray
                for (element in urls) {
                    val url = element.jsonPrimitive.contentOrNull ?: continue
                    if (seen.add(url) && sourceRegex?.matches(url) == true) {
                        sniffed.complete(url)
                        break
                    }
                }
            }
            val hitUrl = withTimeoutOrNull(SNIFF_INTERVAL_MS + 100) { sniffed.await() }
            if (hitUrl != null) {
                return WebViewFetchResult(request.url ?: hitUrl, hitUrl, false)
            }
            delay(SNIFF_INTERVAL_MS)
        }
        throw NoStackTraceException("资源嗅探超时")
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!isAvailable()) return null
        val handle = MacWindowHandle(request)
        CocoaLoop.post { handle.open() }
        return handle
    }

    /** 注入 CookieStore 已有 cookie (suspend: block 回调等待), 导航前调用。 */
    private suspend fun injectCookies(session: MacSession, request: WebViewFetchRequest) {
        val url = request.url?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val store = CookieStoreProviders.get() ?: return
            val cookie = store.getCookie(url).takeIf { it.isNotBlank() } ?: return
            val domain = NetworkUtils.getSubDomain(url).takeIf { it.isNotBlank() } ?: return
            session.addCookies(domain, cookie, COOKIE_TIMEOUT_MS)
        }.onFailure { AppLog.put("WKWebView cookie 注入失败", it) }
    }

    /** 回收 cookie 写入 CookieStore (仅存 tag 名下)。 */
    private fun harvestCookies(session: MacSession, url: String, tag: String?) {
        if (tag.isNullOrBlank() || url.isBlank()) return
        scope.launch {
            val cookie = session.cookies(COOKIE_TIMEOUT_MS) ?: return@launch
            runCatching { CookieStoreProviders.get()?.setCookie(tag, cookie) }
                .onFailure { AppLog.put("WKWebView cookie 回写失败", it) }
        }
    }

    /** 可见窗口的 cookie 回收 (按地址 + 按 tag 各存一份)。 */
    internal fun harvestWindowCookies(session: MacSession, url: String, tag: String?) {
        if (url.isBlank()) return
        scope.launch {
            val cookie = session.cookies(COOKIE_TIMEOUT_MS) ?: return@launch
            runCatching {
                val store = CookieStoreProviders.get() ?: return@runCatching
                store.setCookie(url, cookie)
                if (!tag.isNullOrBlank()) store.setCookie(tag, cookie)
            }.onFailure { AppLog.put("WKWebView 窗口 cookie 回写失败", it) }
        }
    }
}

/**
 * WKWebView 会话: 隐藏窗口 (无头) 或可见窗口 + delegate + cookie store。
 * 创建/销毁在 EDT (主线程); JS/cookie 异步操作经 block 回调 + 协程挂起取回。
 */
internal class MacSession private constructor(
    val webView: Pointer,
    private val window: Pointer?,
    private val cookieStore: Pointer?,
    val toolbar: MacToolbar?,
) {

    /** 导航完成回调 (主线程), 参数为当前 URL。 */
    var onNavigated: ((String) -> Unit)? = null

    /** 窗口关闭回调 (主线程)。 */
    var onClosed: (() -> Unit)? = null

    /** delegate 强引用 (navigationDelegate 是 weak, 被 GC 后回调即断)。 */
    private lateinit var navDelegate: Pointer

    /** 窗口 delegate 强引用 (同上)。 */
    private var windowDelegate: Pointer? = null

    companion object {

        /** NSWindow styleMask: Titled|Closable|Miniaturizable|Resizable */
        private const val STYLE_MASK = 1 or 2 or 4 or 8
        private const val NSBackingStoreBuffered = 2

        private fun sniffUserScript(): Pointer {
            val script = ptr(ObjC.cls("WKUserScript"), "alloc")!!
            ptr(
                script,
                "initWithSource:injectionTime:forMainFrameOnly:",
                ns(MacWebViewEngine.SNIFF_HOOK_JS),
                0L, // WKUserScriptInjectionTimeAtDocumentStart
                1L, // forMainFrameOnly
            )!!
            return script
        }

        /**
         * 创建会话 (主线程)。[sniff] 时注入 document-start hook。
         * 返回 null = 创建失败。
         */
        fun create(
            visible: Boolean,
            title: String = "legado",
            bottomSheet: Boolean = false,
            toolbar: MacToolbar? = null,
            sniff: Boolean = false,
        ): MacSession? {
            return runCatching {
                val config = ptr(ObjC.cls("WKWebViewConfiguration"), "alloc")!!
                ptr(config, "init")!!
                if (sniff) {
                    val ucc = property(config, "userContentController")
                    void(ucc, "addUserScript:", sniffUserScript())
                }
                val webView = ptr(ObjC.cls("WKWebView"), "alloc")!!
                ptr(
                    webView,
                    "initWithFrame:configuration:",
                    frame(0.0, 0.0, 1000.0, 700.0),
                    config,
                )!!
                val cookieStore =
                    property(property(config, "websiteDataStore")!!, "httpCookieStore")

                val window: Pointer?
                if (visible) {
                    // 弹窗语义: 非 bottomSheet 默认屏幕居中 (与 Windows 引擎行为一致);
                    // Cocoa 坐标原点在左下, 居中 origin 需按屏幕尺寸计算
                    val screen = Toolkit.getDefaultToolkit().screenSize
                    val winW =
                        kotlin.math.min(1000, (screen.width * 0.8).toInt().coerceAtLeast(400))
                    val winH =
                        kotlin.math.min(700, (screen.height * 0.8).toInt().coerceAtLeast(300))
                    val originX = ((screen.width - winW) / 2).coerceAtLeast(0)
                    val originY = ((screen.height - winH) / 2).coerceAtLeast(0)
                    window = ptr(ObjC.cls("NSWindow"), "alloc")!!
                    ptr(
                        window,
                        "initWithContentRect:styleMask:backing:defer:",
                        frame(
                            originX.toDouble(),
                            originY.toDouble(),
                            winW.toDouble(),
                            winH.toDouble(),
                        ),
                        STYLE_MASK.toLong(),
                        NSBackingStoreBuffered.toLong(),
                        0L,
                    )!!
                    void(window, "setTitle:", ns(title))
                    if (bottomSheet) {
                        // 置底半屏语义: Cocoa 坐标原点在左下, 贴底 = y=0
                        val height = (screen.height / 2).coerceAtLeast(400)
                        void(window, "setFrameOrigin:", ObjC.point(0.0, 0.0))
                        void(
                            window, "setFrameSize:",
                            ObjC.size(screen.width.toDouble(), height.toDouble()),
                        )
                    }
                    void(window, "setContentView:", webView)
                    void(window, "makeKeyAndOrderFront:", null)
                } else {
                    // 无头: 隐藏窗口承载 WKWebView (不显示, 不 orderFront)
                    window = ptr(ObjC.cls("NSWindow"), "alloc")!!
                    ptr(
                        window,
                        "initWithContentRect:styleMask:backing:defer:",
                        frame(0.0, 0.0, 1.0, 1.0),
                        STYLE_MASK.toLong(),
                        NSBackingStoreBuffered.toLong(),
                        0L,
                    )!!
                    void(window, "setContentView:", webView)
                }

                // delegate 的 impl 需要引用 session, 而 session 依赖 delegate ——
                // 用 AtomicReference 先建 holder, session 创建后回填
                val sessionRef = AtomicReference<MacSession?>(null)
                val session = MacSession(webView, window, cookieStore, toolbar)
                sessionRef.set(session)

                session.navDelegate = ObjC.newDelegateClass(
                    "LegadoNavDelegate",
                    listOf(
                        "webView:didFinishNavigation:" to "v@:@@",
                        "webView:didFailNavigation:withError:" to "v@:@@@",
                    ),
                ) { method, _, args ->
                    when (method) {
                        "webView:didFinishNavigation:" -> {
                            val target = sessionRef.get() ?: return@newDelegateClass
                            val url = target.currentUrl()
                            if (!url.isNullOrBlank()) target.onNavigated?.invoke(url)
                        }

                        else -> Unit
                    }
                }
                void(webView, "setNavigationDelegate:", session.navDelegate)

                if (visible) {
                    session.windowDelegate = ObjC.newDelegateClass(
                        "LegadoWindowDelegate",
                        listOf("windowWillClose:" to "v@:@"),
                    ) { _, _, _ ->
                        sessionRef.get()?.onClosed?.invoke()
                    }
                    void(window, "setDelegate:", session.windowDelegate)
                }

                session
            }.onFailure { e ->
                AppLog.put("WKWebView 会话创建失败", e)
                null
            }.getOrNull()
        }

        private fun frame(x: Double, y: Double, w: Double, h: Double) =
            ObjC.NSRect().apply {
                origin.x = x; origin.y = y
                size.width = w; size.height = h
            }
    }

    // ==================== 生命周期 ====================

    fun destroy() {
        // close 释放窗口; webView 由 alloc 持有需手动 release (ARC 手动模式)
        runCatching { window?.let { void(it, "close") } }
        runCatching { void(webView, "release") }
    }

    // ==================== 加载 ====================

    fun setUserAgent(userAgent: String) {
        void(webView, "setCustomUserAgent:", ns(userAgent))
    }

    /** 对应 app 端 load(): html 优先, 否则加载 url (带 headerMap)。主线程。 */
    fun start(request: WebViewFetchRequest) {
        request.headerMap?.get(AppConst.UA_NAME)?.let { setUserAgent(it) }
        val html = request.html
        when {
            !html.isNullOrEmpty() -> {
                val base = request.url?.takeIf { it.isNotBlank() }
                    ?.let { ptr(ObjC.cls("NSURL"), "URLWithString:", ns(it)) }
                void(webView, "loadHTMLString:baseURL:", ns(html), base)
            }

            !request.url.isNullOrEmpty() -> {
                val nsUrl = ptr(ObjC.cls("NSURL"), "URLWithString:", ns(request.url)) ?: return
                val req = ptr(ObjC.cls("NSMutableURLRequest"), "requestWithURL:", nsUrl)!!
                request.headerMap?.forEach { (name, value) ->
                    void(req, "setValue:forHTTPHeaderField:", ns(value), ns(name))
                }
                void(webView, "loadRequest:", req)
            }

            else -> throw NoStackTraceException("url 与 html 不能同时为空")
        }
    }

    fun reload() {
        void(webView, "reload")
    }

    /** 当前地址 (主线程)。 */
    fun currentUrl(): String? {
        val url = property(webView, "URL")
        return url?.let { fromId(ptr(it, "absoluteString")) }
    }

    /** 属性读取 (主线程), 如 title。 */
    fun propertyString(name: String): String? = ObjC.propertyString(webView, name)

    // ==================== JS ====================

    /**
     * 执行 JS 并取回结果 (suspend; 主线程发起, block 回调完成)。
     * 返回归一文本 (NSString 原样 / NSNumber stringValue / 其余 null), 对齐 iOS 端
     * IosWebViewHost 的 evaluateJavaScript 归一逻辑。
     */
    suspend fun evaluateJavascript(script: String): String? {
        val future = CompletableFuture<String?>()
        val block = ObjC.ObjCBlock(object : Callback {
            fun invoke(block: Pointer, result: Pointer, error: Pointer): Pointer? {
                val value = if (error != null && error != Pointer.NULL) {
                    val msg = fromId(ptr(error, "localizedDescription"))
                    AppLog.put("WKWebView JS 执行失败: $msg")
                    null
                } else {
                    fromId(result)
                }
                future.complete(value)
                return null
            }
        })
        CocoaLoop.post {
            void(webView, "evaluateJavaScript:completionHandler:", ns(script), block.pointer())
        }
        return withTimeoutOrNull(AppConst.timeLimit) { future.await() }
    }

    // ==================== Cookie ====================

    /**
     * 注入 cookie (形如 "k=v; k=v") 到 [domain] (suspend)。
     * 逐条 NSHTTPCookie 构造 + setCookie:completionHandler: 等待全部完成。
     */
    suspend fun addCookies(domain: String, cookie: String, timeoutMs: Long): Boolean {
        val store = cookieStore ?: return false
        val entries = cookie.split(';').mapNotNull { entry ->
            val index = entry.indexOf('=')
            if (index <= 0) null
            else {
                val name = entry.substring(0, index).trim()
                val value = entry.substring(index + 1).trim()
                if (name.isEmpty()) null else name to value
            }
        }
        if (entries.isEmpty()) return false
        val done = CountDownLatch(entries.size)
        val failed = AtomicBoolean(false)
        for ((name, value) in entries) {
            val gCookie = buildCookie(name, value, domain) ?: run {
                failed.set(true)
                done.countDown()
                continue
            }
            val block = ObjC.ObjCBlock(object : Callback {
                fun invoke(block: Pointer): Pointer? {
                    done.countDown()
                    return null
                }
            })
            CocoaLoop.post {
                void(store, "setCookie:completionHandler:", gCookie, block.pointer())
            }
        }
        return runCatching {
            done.await(timeoutMs, TimeUnit.MILLISECONDS) && !failed.get()
        }.getOrDefault(false)
    }

    /** 构造 NSHTTPCookie (Name/Value/Domain/Path), 无效返回 null。 */
    private fun buildCookie(name: String, value: String, domain: String): Pointer? {
        val cookieCls = ObjC.cls("NSHTTPCookie")
        val objects = ObjC.nsArray(ns(name), ns(value), ns(domain), ns("/"))
        val keys = ObjC.nsArray(ns("Name"), ns("Value"), ns("Domain"), ns("Path"))
        val merged = ObjC.clsPtr(
            ObjC.cls("NSDictionary"),
            "dictionaryWithObjects:forKeys:count:",
            objects, keys, 4L,
        )!!
        val cookie = ObjC.clsPtr(cookieCls, "cookieWithProperties:", merged)
        if (cookie == null || cookie == Pointer.NULL) {
            AppLog.put("WKWebView cookie 构造失败: $name")
            return null
        }
        return cookie
    }

    /** 取全部 cookie, 拼 "k=v; k=v" (suspend)。 */
    suspend fun cookies(timeoutMs: Long): String? {
        val store = cookieStore ?: return null
        val future = CompletableFuture<String?>()
        val block = ObjC.ObjCBlock(object : Callback {
            fun invoke(block: Pointer, cookies: Pointer): Pointer? {
                val parts = ArrayList<String>()
                if (cookies != null && cookies != Pointer.NULL) {
                    val count = ObjC.arrayCount(cookies)
                    for (i in 0 until count) {
                        val cookie = ObjC.arrayObject(cookies, i) ?: continue
                        val name = fromId(ObjC.property(cookie, "name"))
                        val value = fromId(ObjC.property(cookie, "value"))
                        if (!name.isNullOrEmpty()) parts.add("$name=$value")
                    }
                }
                future.complete(parts.joinToString("; ").takeIf { it.isNotBlank() })
                return null
            }
        })
        CocoaLoop.post {
            void(store, "getAllCookiesWithCompletionHandler:", block.pointer())
        }
        return withTimeoutOrNull(timeoutMs) { future.await() }
    }
}

/** 可见窗口句柄。 */
private class MacWindowHandle(
    private val request: WebViewWindowRequest,
) : WebViewWindowHandle {

    @Volatile
    private var session: MacSession? = null

    @Volatile
    override var currentUrl: String? = null
        private set

    /** 手动历史栈 (同 Windows 实现)。 */
    private val history = ArrayList<String>()
    private var historyIndex = -1
    private var historyNavPending = false

    private val checking = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val closedOnce = AtomicBoolean(false)

    fun open() {
        val created = MacSession.create(
            visible = true,
            title = request.title,
            bottomSheet = request.bottomSheet,
            toolbar = MacToolbar { action -> onToolbarAction(action) },
        )
        if (created == null) {
            AppLog.put("WKWebView 窗口创建失败: ${request.title}")
            close()
            return
        }
        if (closedOnce.get()) {
            created.destroy()
            return
        }
        session = created
        currentUrl = request.url
        // cookie 注入 (suspend) 在协程里做, 不等导航完成
        scope.launch {
            runCatching {
                val store = CookieStoreProviders.get() ?: return@runCatching
                val cookie = request.url.takeIf { it.isNotBlank() }
                    ?.let { url -> store.getCookie(url) }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@runCatching
                val domain = NetworkUtils.getSubDomain(request.url).takeIf { it.isNotBlank() }
                    ?: return@runCatching
                created.addCookies(domain, cookie, 5_000L)
            }.onFailure { AppLog.put("WKWebView 窗口 cookie 注入失败", it) }
        }
        created.onNavigated = { url ->
            currentUrl = url
            MacWebViewEngine.harvestWindowCookies(created, url, request.cookieTag)
            runCatching { request.onNavigated(url) }
            val toolbar = created.toolbar
            if (checking.get()) {
                // 对照 menu_ok isLogin 分支: reload 后下次导航完成即关窗
                close()
            } else if (toolbar != null) {
                onNavigationForHistory(url)
                toolbar.setCanNavigate(historyIndex > 0, historyIndex < history.size - 1)
                val docTitle = created.propertyString("title")
                if (!docTitle.isNullOrBlank() && !docTitle.startsWith("http")) {
                    toolbar.setTitle(docTitle)
                }
            }
        }
        created.onClosed = { close() }
        created.start(WebViewFetchRequest(url = request.url, html = request.html))
    }

    private fun onNavigationForHistory(url: String) {
        if (historyNavPending) {
            historyNavPending = false
        } else if (history.isEmpty() || history[historyIndex] != url) {
            while (history.size - 1 > historyIndex) history.removeAt(history.size - 1)
            history.add(url)
            historyIndex = history.size - 1
        }
    }

    private fun onToolbarAction(action: ToolbarAction) {
        val target = session ?: return
        when (action) {
            ToolbarAction.BACK -> if (historyIndex > 0) {
                historyNavPending = true
                historyIndex--
                loadUrl(target, history[historyIndex])
            }

            ToolbarAction.FORWARD -> if (historyIndex < history.size - 1) {
                historyNavPending = true
                historyIndex++
                loadUrl(target, history[historyIndex])
            }

            ToolbarAction.REFRESH -> target.reload()

            ToolbarAction.OK -> onOkPressed(target)

            ToolbarAction.CLOSE -> close()
        }
    }

    private fun loadUrl(session: MacSession, url: String) {
        val nsUrl = ptr(ObjC.cls("NSURL"), "URLWithString:", ns(url)) ?: return
        val req = ptr(ObjC.cls("NSMutableURLRequest"), "requestWithURL:", nsUrl)!!
        void(session.webView, "loadRequest:", req)
    }

    private fun onOkPressed(target: MacSession) {
        when {
            request.isLogin -> {
                if (checking.compareAndSet(false, true)) {
                    runCatching { Toasters.get().toast(CHECK_HOST_COOKIE_TEXT) }
                    target.reload()
                }
            }

            request.saveResult -> {
                // 页面还活着时抓 outerHTML 回传 (对照 saveVerificationResult 的 html 分支)
                scope.launch {
                    val html =
                        runCatching { target.evaluateJavascript(MacWebViewEngine.DEFAULT_JS) }
                            .getOrNull()
                    request.onSaveResult?.invoke(html)
                }
            }
        }
    }

    override suspend fun currentHtml(): String? =
        evaluateJavascript(MacWebViewEngine.DEFAULT_JS)

    override suspend fun evaluateJavascript(script: String): String? {
        val target = session ?: return null
        return target.evaluateJavascript(script)
    }

    override fun reload() {
        val target = session ?: return
        CocoaLoop.post { target.reload() }
    }

    override fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        val target = session
        session = null
        if (target != null) {
            CocoaLoop.post { target.destroy() }
        }
        runCatching { request.onClosed() }
    }
}
