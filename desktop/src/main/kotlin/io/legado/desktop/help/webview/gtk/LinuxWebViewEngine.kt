package io.legado.desktop.help.webview.gtk

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.browseUrl
import io.legado.desktop.help.webview.DesktopWebViewEngineBase
import io.legado.desktop.help.webview.DesktopWebViewWindowHandleBase
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.WebViewFetchRequest
import io.legado.desktop.help.webview.WebViewFetchResult
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import io.legado.desktop.help.webview.gtk.GtkLibs.WEBKIT_LOAD_COMMITTED
import io.legado.desktop.help.webview.gtk.GtkLibs.WEBKIT_LOAD_FINISHED
import io.legado.desktop.help.webview.gtk.GtkLibs.WEBKIT_LOAD_REDIRECTED
import io.legado.desktop.help.webview.gtk.GtkLibs.WEBKIT_LOAD_STARTED
import io.legado.desktop.help.webview.gtk.LinuxWebViewEngine.fetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Linux 系统引擎: JNA 直绑系统自带的 webkit2gtk-4.1 (主流发行版预装, 零体积增量)。
 *
 * 线程模型同 Windows WebView2Loop: 专用 GTK 线程跑 gtk_init + GLib 主循环 (见 [GtkLoop]),
 * 所有 GTK/WebKit 调用与信号回调都在该线程, 与 AWT/Skiko 零交集; 可见窗口是独立顶层
 * GTK 窗口 (登录/验证本就是弹窗语义)。
 *
 * 语义对照 app 端 `BackstageWebView` / `WebViewActivity`:
 * - 无头抓取走 [fetch]: 隐藏窗口加载 → 延时 → run_javascript 取结果 (空则每秒重试 30 次);
 * - 资源嗅探走 "resource-load-started" 信号 (WebKit 子资源开始加载时触发, 等价 app 端
 *   shouldInterceptRequest 的命中时机), overrideUrlRegex 走 uri 变化通知;
 * - cookie 回收走 WebKitCookieManager (async API 在 GTK 线程驱动转同步);
 * - 可见窗口带 GTK 工具栏, 行为对照 Windows 的 WebView2Toolbar。
 * 抓取主循环 / 常量在 [DesktopWebViewEngineBase], 这里只留 WebKitGTK 差异。
 *
 * 已知差异 (KDoc 注明, 运行时回退 HTTP 兜底):
 * - resource-load-started 无法**阻止**资源加载 (WebKitGTK 无公开拦截 API, web extension
 *   方案需打包编译 .so 违背零体积), 嗅探语义为"命中即返回结果", 与 WebView2 的 Sniffer
 *   一致 (命中后页面继续加载无影响);
 * - run_javascript 取的是 JS 值 toString (与 JavaFX 相同), 非 JSON 编码;
 * - 无头机器 (无 X11/Wayland) gtk_init_check 失败 → 回退系统浏览器。
 */
internal object LinuxWebViewEngine : DesktopWebViewEngineBase() {

    override val id: String get() = "webkit2gtk"

    override val platformLabel = PLATFORM_LABEL

    @Volatile
    private var probed = false

    /** 系统是否装了 webkit2gtk-4.1 (供 [io.legado.desktop.help.webview.DesktopWebViewEngines.installGuide])。 */
    fun runtimeInstalled(): Boolean = GtkLibs.webkitPath() != null

    override fun isAvailable(): Boolean {
        if (probed) return available
        available = runCatching {
            GtkLibs.ensureLoaded() && GtkLoop.ensureStarted()
        }.getOrDefault(false)
        probed = true
        return available
    }

    @Volatile
    private var available = false

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult {
        if (!isAvailable()) throw NoStackTraceException("WebKitGTK 引擎不可用")
        val sniffing = isSniffing(request)
        val session = GtkLoop.await { GtkSession.create(visible = false) }
            ?: throw NoStackTraceException("WebKitGTK 会话创建失败")
        try {
            return withTimeout(AppConst.timeLimit) {
                if (sniffing) runSniffer(session, request) else runHtml(session, request)
            }
        } finally {
            GtkLoop.post { session.destroy() }
        }
    }

    /** 对应 app 端 HtmlWebViewClient: 等页面 + 延时 + 反复执行 JS 直到拿到非空结果。 */
    private suspend fun runHtml(
        session: GtkSession,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val redirected = AtomicBoolean(false)
        GtkLoop.await {
            session.onLoadChanged = { _, event ->
                // 重定向: WebKit 主框架 REDIRECTED 事件 (对照 WebView2 isRedirected)
                if (event == WEBKIT_LOAD_REDIRECTED) redirected.set(true)
                // cookie 回收时机对齐 onPageFinished (COMMITTED 起 URL 已定, FINISHED 兜底)
                if (event == WEBKIT_LOAD_COMMITTED || event == WEBKIT_LOAD_FINISHED) {
                    val uri = session.currentUri()
                    if (!uri.isNullOrBlank()) {
                        harvestTagCookies(uri, request.cookieTag) {
                            GtkLoop.await { session.cookies(uri, COOKIE_TIMEOUT_MS) }
                        }
                    }
                }
            }
            session.start(request)
        }

        return awaitScriptBody(
            request,
            evaluate = { script ->
                GtkLoop.await { session.executeScript(script) }
                    ?.takeIf { it.isNotEmpty() && it != "null" }
            },
            currentUrl = { GtkLoop.await { session.currentUri() }.takeIf { !it.isNullOrBlank() } },
            redirected = { redirected.get() },
        )
    }

    /**
     * 对应 app 端 SnifferWebClient: 命中 overrideUrlRegex (uri 变化) / sourceRegex
     * (resource-load-started) 即以该地址作为结果。
     */
    private suspend fun runSniffer(
        session: GtkSession,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val hit = CompletableDeferred<String>()
        val (overrideRegex, sourceRegex) = snifferRegexes(request)
        GtkLoop.await {
            session.onUriChanged = { uri ->
                if (overrideRegex?.matches(uri) == true) hit.complete(uri)
            }
            session.onResourceStarted = { uri ->
                if (sourceRegex?.matches(uri) == true) hit.complete(uri)
            }
            session.onLoadChanged = { _, event ->
                if (event == WEBKIT_LOAD_FINISHED) {
                    // app 端在 onPageStarted 延时注入 JS, 这里等页面就绪后补一次
                    injectJsOnPageReady(request) { session.executeScript(it) }
                }
            }
            session.start(request)
        }
        val resultUrl = withTimeoutOrNull(AppConst.timeLimit) { hit.await() }
            ?: throw NoStackTraceException("资源嗅探超时")
        return snifferResult(request, resultUrl)
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!isAvailable()) return null
        val handle = GtkWindowHandle(request)
        GtkLoop.post { handle.open() }
        return handle
    }
}

/** 日志里的平台名 (cookie 回写失败信息前缀, 引擎与窗口句柄共用)。 */
private const val PLATFORM_LABEL = "WebKitGTK"

/**
 * 可见窗口句柄: 独立 GTK 顶层窗口 + 工具栏 + WebKitWebView。
 * 所有 GTK 操作经 [GtkLoop.post] / [GtkLoop.await] 在 GTK 线程执行;
 * 历史栈 / 禁用源 / 删除源 / 确定按钮等公共语义见 [DesktopWebViewWindowHandleBase]。
 */
private class GtkWindowHandle(
    request: WebViewWindowRequest,
) : DesktopWebViewWindowHandleBase(request) {

    override val platformLabel = PLATFORM_LABEL

    @Volatile
    private var session: GtkSession? = null

    fun open() {
        val created = GtkSession.create(
            visible = true,
            title = request.title,
            toolbar = GtkToolbar(
                onAction = { action -> onToolbarAction(action) },
                rssActions = request.rssActions,
                // 确定按钮仅登录/验证模式显示 (三端对齐 Windows)
                showOk = request.isLogin || request.saveResult,
                // 书源 key 非空时菜单显示 禁用源/删除源 (2026-08-08)
                sourceKey = request.cookieTag,
            ),
        )
        if (created == null) {
            AppLog.put("WebKitGTK 窗口创建失败: ${request.title}")
            close()
            return
        }
        if (closedOnce.get()) {
            created.destroy()
            return
        }
        session = created
        currentUrl = request.url
        request.userAgent?.let { created.setUserAgent(it) }
        // RSS 收藏态反推: shared 侧书架操作完成后经 onStarChanged 更新窗口星图标 (同 Windows)
        request.rssActions?.onStarChanged = { starred -> created.toolbar?.setStarred(starred) }
        created.onLoadChanged = { _, event ->
            when (event) {
                WEBKIT_LOAD_STARTED -> created.toolbar?.setLoading(true)
                WEBKIT_LOAD_COMMITTED, WEBKIT_LOAD_FINISHED -> {
                    val url = created.currentUri()
                    if (!url.isNullOrBlank()) {
                        currentUrl = url
                        harvestWindowCookies(url, request.cookieTag) {
                            GtkLoop.await {
                                created.cookies(url, DesktopWebViewEngineBase.COOKIE_TIMEOUT_MS)
                            }
                        }
                        runCatching { request.onNavigated(url) }
                        val toolbar = created.toolbar
                        toolbar?.setLoading(false)
                        if (isLoginChecking()) {
                            // 对照 menu_ok isLogin 分支: reload 后下次导航完成即关窗
                            close()
                        } else if (toolbar != null && event == WEBKIT_LOAD_FINISHED) {
                            onNavigationForHistory(url)
                            toolbar.setCanNavigate(canGoBackInHistory(), canGoForwardInHistory())
                            // 动态标题 (对齐 onReceivedTitle): 非空且非 http 前缀才更新
                            val docTitle = runCatching {
                                created.executeScript("document.title")
                            }.getOrNull()
                            if (!docTitle.isNullOrBlank() && !docTitle.startsWith("http")) {
                                created.setWindowTitle(docTitle)
                            }
                        }
                    }
                }
            }
        }
        created.onClosed = { close() }
        val html = request.html
        if (!html.isNullOrEmpty()) {
            created.loadHtml(html, request.url)
        } else {
            created.loadUri(request.url)
        }
    }

    private fun onToolbarAction(action: ToolbarAction) {
        val target = session ?: return
        when (action) {
            ToolbarAction.BACK -> navigateHistory(back = true)

            ToolbarAction.FORWARD -> navigateHistory(back = false)

            ToolbarAction.REFRESH -> {
                target.toolbar?.setLoading(true)
                target.reload()
            }

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

            // RSS 模式按钮 (2026-08-07: RSS 阅读去页面外壳, 功能移入窗口工具栏)
            ToolbarAction.STAR_TOGGLE -> request.rssActions?.onStarToggle()

            ToolbarAction.READ_ALOUD -> request.rssActions?.onReadAloud {
                // 页面还活着时抓 outerHTML (对照原版 readAloud 的 evaluateJavascript;
                // GTK executeScript 返回 JS 值 toString, 非 JSON 编码, 无需 unwrapScriptResult)
                runCatching {
                    GtkLoop.await { target.executeScript(DesktopWebViewEngineBase.DEFAULT_JS) }
                }.getOrNull()
            }

            ToolbarAction.SHARE -> request.rssActions?.onShare()

            ToolbarAction.LOGIN -> request.rssActions?.onLogin()

            ToolbarAction.OK -> onOkPressed(
                reloadForCheck = {
                    target.toolbar?.setLoading(true)
                    target.reload()
                },
                evalDefaultJs = {
                    GtkLoop.await { target.executeScript(DesktopWebViewEngineBase.DEFAULT_JS) }
                },
            )

            // 禁用源 (对照原版 menu_disable_source → viewModel.disableSource { finish() }):
            // 成功后关窗, 失败记录日志不关窗
            ToolbarAction.DISABLE_SOURCE -> onDisableSource()

            // 删除源 (对照原版 menu_delete_source → alert 确认后 deleteSource { finish() })
            ToolbarAction.DELETE_SOURCE -> onDeleteSource { target.confirmDelete(it) }

            // 菜单按钮在 GtkToolbar 内部直接弹菜单 (浏览器打开/拷贝 URL/禁用源/删除源), 无动作分发;
            // Windows 的 MENU 仅用于刷新溢出菜单导致的动态高度, GTK 弹出菜单不改变布局
            ToolbarAction.MENU -> Unit

            // 最大化/还原切换 (对照原版 menu_full_screen)
            ToolbarAction.FULL_SCREEN -> {
                target.toggleMaximize()
            }

            ToolbarAction.CLOSE -> close()
        }
    }

    override fun navigateInWindow(url: String) {
        val target = session ?: return
        GtkLoop.post { target.loadUri(url) }
    }

    override suspend fun evaluateJavascript(script: String): String? {
        val target = session ?: return null
        return GtkLoop.await { target.executeScript(script) }
    }

    override fun reload() {
        val target = session ?: return
        GtkLoop.post { target.reload() }
    }

    override fun destroySession() {
        val target = session
        session = null
        if (target != null) {
            // 先置 disposed 再销毁: 队列残留的 setStarred 任务直接跳过 (防悬垂句柄)
            target.toolbar?.dispose()
            GtkLoop.post { target.destroy() }
        }
    }
}
