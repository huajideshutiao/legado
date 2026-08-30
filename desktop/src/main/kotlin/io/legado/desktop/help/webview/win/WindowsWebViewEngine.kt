package io.legado.desktop.help.webview.win

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
import io.legado.desktop.help.webview.unwrapScriptResult
import io.legado.desktop.help.webview.win.WindowsWebViewEngine.fetch
import io.legado.desktop.help.webview.win.WindowsWebViewEngine.openWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Windows 系统引擎: 直调系统自带的 WebView2 Runtime (JNA + COM), 不随包发任何 native 文件。
 *
 * 语义对照 app 端 `BackstageWebView` / `WebViewActivity`: 无头抓取走 [fetch],
 * 登录/网页验证走 [openWindow] 的独立顶层窗口 (见 [WebView2Loop] 为何不嵌进 Compose)。
 * 抓取主循环 / 常量 / cookie 闭环在 [DesktopWebViewEngineBase], 这里只留 WebView2 差异。
 */
internal object WindowsWebViewEngine : DesktopWebViewEngineBase() {

    override val id: String get() = "webview2"

    override val platformLabel = PLATFORM_LABEL

    override fun isAvailable(): Boolean = WebView2Runtime.detect() != null

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult {
        val sniffing = isSniffing(request)
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
        val navigation = NavigationState()
        instance.onNavigationStarting = { _, isRedirected ->
            navigation.onNavigation(isRedirected)
            false
        }
        instance.onNavigationCompleted = { url ->
            harvestTagCookies(url, request.cookieTag) {
                instance.cookies(url, COOKIE_TIMEOUT_MS)
            }
        }
        start(instance, request)
        return awaitScriptBody(
            request,
            navigation,
            evaluate = { script ->
                unwrapScriptResult(instance.executeScript(script, AppConst.timeLimit))
            },
            currentUrl = { instance.currentUrl() },
        )
    }

    /** 对应 app 端 SnifferWebClient: 命中 overrideUrlRegex / sourceRegex 即以该地址作为结果。 */
    private suspend fun runSniffer(
        instance: WebView2Instance,
        request: WebViewFetchRequest,
    ): WebViewFetchResult {
        val hit = CompletableDeferred<String>()
        val (overrideRegex, sourceRegex) = snifferRegexes(request)

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
            harvestTagCookies(url, request.cookieTag) {
                instance.cookies(url, COOKIE_TIMEOUT_MS)
            }
            // app 端在 onPageStarted 延时注入 JS, 这里等页面就绪后补一次
            injectJsOnPageReady(request) { instance.navigate("javascript:$it") }
        }
        start(instance, request)
        val resultUrl = hit.await()
        return snifferResult(request, resultUrl)
    }

    /** 对应 app 端 load(): html 优先, 否则加载 url; UA 走 Settings2。 */
    private suspend fun start(instance: WebView2Instance, request: WebViewFetchRequest) {
        instance.setUserAgent(request.headerMap.getUserAgent())
        injectWebViewCookies(request.url, platformLabel) { domain, cookie ->
            instance.setCookies(domain, cookie)
        }
        val html = request.html
        when {
            !html.isNullOrEmpty() -> instance.navigateToString(html)
            !request.url.isNullOrEmpty() -> instance.navigate(request.url)
            else -> throw NoStackTraceException("url 与 html 不能同时为空")
        }
    }

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? {
        if (!isAvailable()) return null
        val handle = WebView2WindowHandle(request)
        scope.launch { handle.open() }
        return handle
    }
}

/** 可见窗口导航超时 (页面挂起时 loading 停止并提示, 防卡死感)。 */
private const val NAV_TIMEOUT_MS = 30_000L

/** 日志里的平台名 (cookie 注入/回写失败信息前缀, 引擎与窗口句柄共用)。 */
private const val PLATFORM_LABEL = "WebView2"

/**
 * 可见窗口句柄。cookie 在导航完成回调里回收 (对齐 app 端 WebViewActivity.onPageFinished),
 * 用户关窗触发 onClosed; 历史栈 / 禁用源 / 删除源 / 确定按钮等公共语义见
 * [DesktopWebViewWindowHandleBase]。
 *
 * 窗口带 CustomTab 式工具栏 ([WebView2Toolbar]), 行为对照 shared `WebViewRoute`:
 * - 标题/进度/返回/前进/刷新/关闭/确定, 确定按钮语义见 [WebViewWindowRequest.isLogin]/[saveResult];
 * - 返回/前进用手动历史栈 (WebView2 历史 API 需 ICoreWebView2_9, 运行时版本门槛高;
 *   手动栈在导航完成时维护, 前进后退时置 historyNavPending 避免重复入栈);
 * - 页面标题经 executeScript("document.title") 读取 (避免新增未验证的 vtable 序号),
 *   非空且非 http 前缀才更新 (对齐 onReceivedTitle)。
 */
private class WebView2WindowHandle(
    request: WebViewWindowRequest,
) : DesktopWebViewWindowHandleBase(request) {

    override val platformLabel = PLATFORM_LABEL

    @Volatile
    private var instance: WebView2Instance? = null

    /** 导航超时任务: 页面挂起 (网络黑洞/连接重置) 时 NavigationCompleted 永不触发,
     * loading 指示一直转 (= 卡死感)。超时后停止 loading 并提示。 */
    private var navTimeoutJob: Job? = null

    private fun scheduleNavTimeout(created: WebView2Instance) {
        navTimeoutJob?.cancel()
        navTimeoutJob = scope.launch {
            delay(NAV_TIMEOUT_MS)
            if (!closedOnce.get()) {
                created.toolbar?.setLoading(false)
                runCatching { Toasters.get().toast("页面加载超时") }
            }
        }
    }

    suspend fun open() {
        val created = WebView2Instance.create(
            visible = true,
            title = request.title,
            toolbarSpec = WebView2ToolbarSpec(
                request.title,
                request.isLogin,
                request.saveResult,
                request.rssActions,
                request.cookieTag,
            ),
        )
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
        created.setUserAgent(request.effectiveUserAgent)
        // 对照原版 WebViewActivity 的 AppCookieManager.applyToWebView(url): 打开前把
        // CookieStore 已有 cookie 灌进浏览器, 否则窗口里是未登录态 (与回收互补成闭环)
        scope.launch {
            injectWebViewCookies(request.url, platformLabel, "窗口 cookie") { domain, cookie ->
                created.setCookies(domain, cookie)
            }
        }
        created.toolbar?.onAction = { action -> onToolbarAction(created, action) }
        // RSS 收藏态反推: shared 侧书架操作完成后经 onStarChanged 更新窗口星图标
        request.rssActions?.onStarChanged = { starred -> created.toolbar?.setStarred(starred) }
        created.onNavigationStarting = { _, _ ->
            created.toolbar?.setLoading(true)
            scheduleNavTimeout(created)
            false
        }
        created.onNavigationFailed = { url ->
            navTimeoutJob?.cancel()
            created.toolbar?.setLoading(false)
            AppLog.put("WebView2 页面加载失败: $url")
            runCatching { Toasters.get().toast("页面加载失败") }
        }
        created.onNavigationCompleted = { url ->
            navTimeoutJob?.cancel()
            if (url.isNotBlank()) {
                currentUrl = url
                harvestWindowCookies(url, request.cookieTag) {
                    created.cookies(url, DesktopWebViewEngineBase.COOKIE_TIMEOUT_MS)
                }
                runCatching { request.onNavigated(url) }
                val toolbar = created.toolbar
                toolbar?.setLoading(false)
                if (isLoginChecking()) {
                    // 对照 menu_ok isLogin 分支: reload 后下次导航完成即关窗
                    close()
                } else if (toolbar != null) {
                    onNavigationForHistory(url)
                    toolbar.setCanNavigate(canGoBackInHistory(), canGoForwardInHistory())
                    // 动态标题 (对齐 WebViewRoute.onReceivedTitle): 非空且非 http 前缀才更新
                    scope.launch {
                        val docTitle = runCatching {
                            unwrapScriptResult(
                                created.executeScript(
                                    "document.title",
                                    AppConst.timeLimit
                                )
                            )
                        }.getOrNull()
                        if (!docTitle.isNullOrBlank() && !docTitle.startsWith("http")) {
                            toolbar.updateTitle(docTitle)
                        }
                    }
                }
            }
        }
        created.onWindowClose = { close() }
        val html = request.html
        if (!html.isNullOrEmpty()) {
            created.navigateToString(html)
        } else {
            created.navigate(request.url)
        }
    }

    private fun onToolbarAction(created: WebView2Instance, action: ToolbarAction) {
        when (action) {
            ToolbarAction.BACK -> if (!navigateHistory(back = true)) {
                // 无历史时返回 = 关闭窗口 (对照原版 WebViewActivity toolbar 返回箭头
                // = finish(), 避免"返回不可用"的困惑)
                close()
            }

            ToolbarAction.FORWARD -> navigateHistory(back = false)

            ToolbarAction.REFRESH -> {
                created.toolbar?.setLoading(true)
                created.reload()
            }

            // 溢出菜单展开/收起 (工具栏动态高度, 刷新 WebView2 bounds)
            ToolbarAction.MENU -> {
                created.refreshToolbarBounds()
            }

            // 最大化/还原切换 (对照原版 menu_full_screen)
            ToolbarAction.FULL_SCREEN -> {
                created.maximizeToggle()
            }

            // 复制当前页 URL (对照原版 menu_copy_url → sendToClip)
            ToolbarAction.COPY_URL -> {
                val url = currentUrl ?: request.url
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(url), null)
                    Toasters.get().toast("已复制 URL")
                }.onFailure { AppLog.put("复制 URL 失败", it) }
            }

            // 系统浏览器打开当前页 (对照原版 menu_open_in_browser → openUrl)
            ToolbarAction.OPEN_IN_BROWSER -> {
                browseUrl(currentUrl ?: request.url)
            }

            // RSS 模式按钮 (2026-08-07: RSS 阅读去页面外壳, 功能移入窗口工具栏)
            ToolbarAction.STAR_TOGGLE -> request.rssActions?.onStarToggle()

            ToolbarAction.READ_ALOUD -> request.rssActions?.onReadAloud {
                // 页面还活着时抓 outerHTML (对照原版 readAloud 的 evaluateJavascript)
                runCatching {
                    unwrapScriptResult(
                        created.executeScript(
                            DesktopWebViewEngineBase.DEFAULT_JS,
                            AppConst.timeLimit
                        )
                    )
                }.getOrNull()
            }

            ToolbarAction.SHARE -> request.rssActions?.onShare()

            ToolbarAction.LOGIN -> request.rssActions?.onLogin()

            ToolbarAction.OK -> onOkPressed(
                reloadForCheck = {
                    created.toolbar?.setLoading(true)
                    created.reload()
                },
                evalDefaultJs = {
                    unwrapScriptResult(
                        created.executeScript(
                            DesktopWebViewEngineBase.DEFAULT_JS,
                            AppConst.timeLimit
                        )
                    )
                },
            )

            // 禁用源 (对照原版 menu_disable_source → viewModel.disableSource { finish() }):
            // 成功后关窗, 失败记录日志不关窗 (窗口仍可继续用)
            ToolbarAction.DISABLE_SOURCE -> onDisableSource()

            // 删除源 (对照原版 menu_delete_source → alert 确认后 deleteSource { finish() })
            ToolbarAction.DELETE_SOURCE -> onDeleteSource { created.confirmDelete(it) }

            ToolbarAction.CLOSE -> close()
        }
    }

    override fun navigateInWindow(url: String) {
        instance?.navigate(url)
    }

    override suspend fun evaluateJavascript(script: String): String? {
        val target = instance ?: return null
        return unwrapScriptResult(target.executeScript(script, AppConst.timeLimit))
    }

    override fun reload() {
        instance?.reload()
    }

    override fun destroySession() {
        instance?.close()
        instance = null
    }
}
