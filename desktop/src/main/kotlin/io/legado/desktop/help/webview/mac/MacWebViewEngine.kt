package io.legado.desktop.help.webview.mac

import com.sun.jna.Callback
import com.sun.jna.Pointer
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.getUserAgent
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.browseUrl
import io.legado.desktop.help.webview.DesktopWebViewEngineBase
import io.legado.desktop.help.webview.DesktopWebViewWindowHandleBase
import io.legado.desktop.help.webview.NavigationState
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.WebViewFetchRequest
import io.legado.desktop.help.webview.WebViewFetchResult
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import io.legado.desktop.help.webview.injectWebViewCookies
import io.legado.desktop.help.webview.mac.ObjC.fromId
import io.legado.desktop.help.webview.mac.ObjC.ns
import io.legado.desktop.help.webview.mac.ObjC.property
import io.legado.desktop.help.webview.mac.ObjC.ptr
import io.legado.desktop.help.webview.mac.ObjC.void
import kotlinx.coroutines.CompletableDeferred
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
 * 抓取主循环 / 常量在 [DesktopWebViewEngineBase], 这里只留 WKWebView 差异。
 */
internal object MacWebViewEngine : DesktopWebViewEngineBase() {

    override val id: String get() = "wkwebview"

    override val platformLabel = PLATFORM_LABEL

    private const val SNIFF_INTERVAL_MS = 500L

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
        val sniffing = isSniffing(request)
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
        // WKWebView 只暴露 didFinishNavigation, 故按"每次导航完成"重新计时 (见 awaitScriptBody)
        val navigation = NavigationState()
        injectWebViewCookies(request.url, platformLabel) { domain, cookie ->
            session.addCookies(domain, cookie, COOKIE_TIMEOUT_MS)
        }
        CocoaLoop.await {
            session.onNavigated = { url ->
                navigation.onNavigation(redirected = !url.isNullOrBlank() && url != request.url)
                if (!url.isNullOrBlank()) {
                    harvestTagCookies(url, request.cookieTag) { session.cookies(COOKIE_TIMEOUT_MS) }
                }
            }
            session.start(request)
        }

        return awaitScriptBody(
            request,
            navigation,
            evaluate = { script -> session.evaluateJavascript(script) },
            currentUrl = {
                CocoaLoop.await { session.currentUrl() }.takeIf { !it.isNullOrBlank() }
            },
        )
    }

    /** 嗅探: hook + performance 轮询; overrideRegex 走导航地址。 */
    private suspend fun runSniffer(
        session: MacSession,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val (overrideRegex, sourceRegex) = snifferRegexes(request)
        val seen = HashSet<String>()
        val sniffed = CompletableDeferred<String>()
        injectWebViewCookies(request.url, platformLabel) { domain, cookie ->
            session.addCookies(domain, cookie, COOKIE_TIMEOUT_MS)
        }
        CocoaLoop.await {
            session.onNavigated = { url ->
                if (!url.isNullOrBlank()) {
                    harvestTagCookies(url, request.cookieTag) { session.cookies(COOKIE_TIMEOUT_MS) }
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
                return snifferResult(request, hitUrl)
            }
            delay(SNIFF_INTERVAL_MS)
        }
        throw NoStackTraceException("资源嗅探超时")
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!isAvailable()) return null
        val handle = MacWindowHandle(request)
        scope.launch { handle.open() }
        return handle
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

    /** 同步 OS 窗口标题 (工具栏已不绘制标题文字, 2026-08-06)。 */
    fun setWindowTitle(title: String) {
        val w = window ?: return
        void(w, "setTitle:", ns(title))
    }

    /** 全屏切换 (NSWindow toggleFullScreen:, 对照原版 menu_full_screen)。 */
    fun toggleFullScreen() {
        val w = window ?: return
        void(w, "toggleFullScreen:", null)
    }

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
                    // 弹窗语义: 默认屏幕居中 (与 Windows 引擎行为一致);
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
        setUserAgent(request.headerMap.getUserAgent())
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
        // holder: completionHandler 可能在本函数超时返回之后才触发, 生命周期只能由回调自己结束
        val holder = arrayOfNulls<ObjC.ObjCBlock>(1)
        val block = ObjC.ObjCBlock(object : Callback {
            fun invoke(block: Pointer, result: Pointer?, error: Pointer?): Pointer? {
                val value = if (error != null) {
                    val msg = fromId(ptr(error, "localizedDescription"))
                    AppLog.put("WKWebView JS 执行失败: $msg")
                    null
                } else {
                    fromId(result)
                }
                future.complete(value)
                holder[0]?.dispose()
                return null
            }
        })
        holder[0] = block
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
            val holder = arrayOfNulls<ObjC.ObjCBlock>(1)
            val block = ObjC.ObjCBlock(object : Callback {
                fun invoke(block: Pointer): Pointer? {
                    done.countDown()
                    holder[0]?.dispose()
                    return null
                }
            })
            holder[0] = block
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
        if (cookie == null) {
            AppLog.put("WKWebView cookie 构造失败: $name")
            return null
        }
        return cookie
    }

    /** 取全部 cookie, 拼 "k=v; k=v" (suspend)。 */
    suspend fun cookies(timeoutMs: Long): String? {
        val store = cookieStore ?: return null
        val future = CompletableFuture<String?>()
        val holder = arrayOfNulls<ObjC.ObjCBlock>(1)
        val block = ObjC.ObjCBlock(object : Callback {
            fun invoke(block: Pointer, cookies: Pointer?): Pointer? {
                val parts = ArrayList<String>()
                if (cookies != null) {
                    val count = ObjC.arrayCount(cookies)
                    for (i in 0 until count) {
                        val cookie = ObjC.arrayObject(cookies, i) ?: continue
                        val name = fromId(ObjC.property(cookie, "name"))
                        val value = fromId(ObjC.property(cookie, "value"))
                        if (!name.isNullOrEmpty()) parts.add("$name=$value")
                    }
                }
                future.complete(parts.joinToString("; ").takeIf { it.isNotBlank() })
                holder[0]?.dispose()
                return null
            }
        })
        holder[0] = block
        CocoaLoop.post {
            void(store, "getAllCookiesWithCompletionHandler:", block.pointer())
        }
        return withTimeoutOrNull(timeoutMs) { future.await() }
    }
}

/** NSAlert.runModal 返回值: 第一个按钮 (确定) = 1000 (NSAlertFirstButtonReturn)。 */
private const val NS_ALERT_FIRST_BUTTON = 1000

/** 日志里的平台名 (cookie 注入/回写失败信息前缀, 引擎与窗口句柄共用)。 */
private const val PLATFORM_LABEL = "WKWebView"

/**
 * 可见窗口句柄。历史栈 / cookie 回收 / 禁用源 / 删除源 / 确定按钮等公共语义见
 * [DesktopWebViewWindowHandleBase], 这里只留 AppKit 差异。
 */
private class MacWindowHandle(
    request: WebViewWindowRequest,
) : DesktopWebViewWindowHandleBase(request) {

    override val platformLabel = PLATFORM_LABEL

    @Volatile
    private var session: MacSession? = null

    suspend fun open() {
        val created = CocoaLoop.await {
            MacSession.create(
                visible = true,
                title = request.title,
                toolbar = MacToolbar(
                    onAction = { action -> onToolbarAction(action) },
                    rssActions = request.rssActions,
                    // 确定按钮仅登录/验证模式显示 (三端对齐 Windows)
                    showOk = request.isLogin || request.saveResult,
                    // 书源 key 非空时菜单显示 禁用源/删除源 (2026-08-08)
                    sourceKey = request.cookieTag,
                ),
            )
        }
        if (created == null) {
            AppLog.put("WKWebView 窗口创建失败: ${request.title}")
            close()
            return
        }
        if (closedOnce.get()) {
            CocoaLoop.post { created.destroy() }
            return
        }
        session = created
        currentUrl = request.url
        CocoaLoop.post { created.setUserAgent(request.effectiveUserAgent) }
        // RSS 收藏态反推: shared 侧书架操作完成后经 onStarChanged 更新窗口星图标 (对照 Windows 引擎)
        request.rssActions?.onStarChanged = { starred -> created.toolbar?.setStarred(starred) }
        // cookie 注入 (suspend): 确保在启动导航前完成注入
        injectWebViewCookies(
            request.url,
            platformLabel,
            subject = "窗口 cookie"
        ) { domain, cookie ->
            created.addCookies(domain, cookie, DesktopWebViewEngineBase.COOKIE_TIMEOUT_MS)
        }
        if (closedOnce.get()) {
            CocoaLoop.post { created.destroy() }
            return
        }
        CocoaLoop.post {
            created.onNavigated = { url ->
                currentUrl = url
                harvestWindowCookies(url, request.cookieTag) {
                    created.cookies(DesktopWebViewEngineBase.COOKIE_TIMEOUT_MS)
                }
                runCatching { request.onNavigated(url) }
                val toolbar = created.toolbar
                if (isLoginChecking()) {
                    // 对照 menu_ok isLogin 分支: reload 后下次导航完成即关窗
                    close()
                } else if (toolbar != null) {
                    onNavigationForHistory(url)
                    toolbar.setCanNavigate(canGoBackInHistory(), canGoForwardInHistory())
                    val docTitle = created.propertyString("title")
                    // 2026-08-06 去工具栏标题 (用户反馈多余): 仅同步 OS 窗口标题
                    if (!docTitle.isNullOrBlank() && !docTitle.startsWith("http")) {
                        created.setWindowTitle(docTitle)
                    }
                }
            }
            created.onClosed = { close() }
            created.start(WebViewFetchRequest(url = request.url, html = request.html))
        }
    }

    private fun onToolbarAction(action: ToolbarAction) {
        val target = session ?: return
        when (action) {
            ToolbarAction.BACK -> if (!navigateHistory(back = true)) {
                // 无历史时返回 = 关闭窗口 (对照原版 WebViewActivity toolbar 返回箭头
                // = finish(), 与 Windows 引擎行为一致, 避免"返回不可用"的困惑)
                close()
            }

            ToolbarAction.FORWARD -> navigateHistory(back = false)

            ToolbarAction.REFRESH -> target.reload()

            // 复制当前页 URL (对照原版 menu_copy_url, 与 Windows 引擎行为一致)
            ToolbarAction.COPY_URL -> {
                val url = currentUrl ?: request.url
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(url), null)
                    Toasters.get().toast("已复制 URL")
                }.onFailure { AppLog.put("复制 URL 失败", it) }
            }

            // 系统浏览器打开当前页 (对照原版 menu_open_in_browser)
            ToolbarAction.OPEN_IN_BROWSER -> {
                browseUrl(currentUrl ?: request.url)
            }

            // 菜单按钮 (⋯) 在工具栏内部直接弹 NSMenu, 不经过引擎分发
            ToolbarAction.MENU -> Unit

            // RSS 模式按钮 (2026-08-07: RSS 阅读去页面外壳, 功能移入窗口工具栏)
            ToolbarAction.STAR_TOGGLE -> request.rssActions?.onStarToggle()

            ToolbarAction.READ_ALOUD -> request.rssActions?.onReadAloud {
                // 页面还活着时抓 outerHTML (对照原版 readAloud 的 evaluateJavascript;
                // 与 onOkPressed saveResult 分支抓取方式一致)
                runCatching { target.evaluateJavascript(DesktopWebViewEngineBase.DEFAULT_JS) }.getOrNull()
            }

            ToolbarAction.SHARE -> request.rssActions?.onShare()

            ToolbarAction.LOGIN -> request.rssActions?.onLogin()

            // 最大化/全屏切换 (对照原版 menu_full_screen): NSWindow toggleFullScreen
            ToolbarAction.FULL_SCREEN -> {
                target.toggleFullScreen()
            }

            ToolbarAction.OK -> onOkPressed(
                reloadForCheck = { target.reload() },
                evalDefaultJs = { target.evaluateJavascript(DesktopWebViewEngineBase.DEFAULT_JS) },
            )

            // 禁用源 (对照原版 menu_disable_source → viewModel.disableSource { finish() }):
            // 成功后关窗, 失败记录日志不关窗
            ToolbarAction.DISABLE_SOURCE -> onDisableSource()

            // 删除源 (对照原版 menu_delete_source → alert 确认后 deleteSource { finish() })
            ToolbarAction.DELETE_SOURCE -> onDeleteSource { confirmViaAlert(it) }

            ToolbarAction.CLOSE -> close()
        }
    }

    /** NSAlert 模态 (EDT): 确定 = firstButton(1000), 取消 = second(1001)。 */
    private fun confirmViaAlert(message: String): Boolean {
        val alert = ptr(ObjC.cls("NSAlert"), "alloc")!!
        ptr(alert, "init")!!
        void(alert, "setMessageText:", ns("删除源"))
        void(alert, "setInformativeText:", ns(message))
        void(alert, "addButtonWithTitle:", ns("确认"))
        void(alert, "addButtonWithTitle:", ns("取消"))
        val confirmed = ObjC.int(alert, "runModal") == NS_ALERT_FIRST_BUTTON
        void(alert, "release")
        return confirmed
    }

    private fun loadUrl(session: MacSession, url: String) {
        val nsUrl = ptr(ObjC.cls("NSURL"), "URLWithString:", ns(url)) ?: return
        val req = ptr(ObjC.cls("NSMutableURLRequest"), "requestWithURL:", nsUrl)!!
        void(session.webView, "loadRequest:", req)
    }

    override fun navigateInWindow(url: String) {
        val target = session ?: return
        CocoaLoop.post { loadUrl(target, url) }
    }

    override suspend fun evaluateJavascript(script: String): String? {
        val target = session ?: return null
        return target.evaluateJavascript(script)
    }

    override fun reload() {
        val target = session ?: return
        CocoaLoop.post { target.reload() }
    }

    override fun destroySession() {
        val target = session
        session = null
        if (target != null) {
            // 先置 disposed 再销毁: 队列残留的 setStarred 任务直接跳过 (防悬垂句柄)
            target.toolbar?.dispose()
            CocoaLoop.post { target.destroy() }
        }
    }
}
