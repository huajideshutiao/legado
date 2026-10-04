package io.legado.desktop.help.webview.win

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import io.legado.app.constant.AppLog
import io.legado.app.help.RssToolbarActions
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** ICoreWebView2_2: CookieManager 所在接口 (SDK 1.0.705 起, 是第一个扩展接口)。 */
private val IID_ICORE_WEBVIEW2_2 = Guid.GUID("9E8F0CF8-E670-4B5E-B2BC-73E061E3184C")

/** ICoreWebView2Settings2: put_UserAgent 所在接口。 */
private val IID_ICORE_WEBVIEW2_SETTINGS2 = Guid.GUID("EE9A0F68-F46C-4E32-AC23-EF8CAC224D2A")

/**
 * ICoreWebView2Environment2: CreateWebResourceRequest 所在接口
 * (IDL uuid 41f3632b-5ef4-404f-ad82-2d606c5a9a21), 用于导航时携带全量 headerMap。
 */
private val IID_ICORE_WEBVIEW2_ENVIRONMENT2 =
    Guid.GUID("41F3632B-5EF4-404F-AD82-2D606C5A9A21")

/**
 * ICoreWebView2_14: add_ServerCertificateErrorDetected 所在接口
 * (IDL uuid 6daa4f10-4a90-4753-8898-77c5df534165), 用于证书错误放行 (等价 SslErrorHandler.proceed)。
 */
private val IID_ICORE_WEBVIEW2_14 = Guid.GUID("6DAA4F10-4A90-4753-8898-77C5DF534165")

/** 环境/控制器创建超时: 冷启动要拉起 msedgewebview2.exe, 给足余量。 */
private const val CREATE_TIMEOUT_MS = 20_000L

/**
 * 进程级共享的 ICoreWebView2Environment。
 *
 * 一个 userDataFolder 同时只能被一个环境持有, 故全进程只建一次; cookie/localStorage 落
 * `{cacheDir}/webview2`, 与 app 端 android.webkit 进程级 CookieManager 的"跨调用持久"语义一致。
 */
internal object WebView2Environment {

    @Volatile
    private var environment: Pointer? = null

    private var pending: CompletableDeferred<Pointer?>? = null

    /** 取(或懒创建)环境; runtime 不可用或创建失败返回 null。任意线程可调。 */
    suspend fun get(): Pointer? {
        environment?.let { return it }
        val deferred = synchronized(this) {
            environment?.let { return it }
            pending ?: CompletableDeferred<Pointer?>().also {
                pending = it
                startCreate(it)
            }
        }
        val env = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
        synchronized(this) {
            if (env != null) environment = env
            // 无论成败都清 pending: 失败/超时后复用同一个已结束的 deferred 会让
            // 后续所有调用立刻拿到 null, 进程内永远无法重试 (必须重启才恢复)
            pending = null
        }
        if (env == null) {
            AppLog.put("WebView2 环境创建失败或超时 (${CREATE_TIMEOUT_MS}ms), 内嵌浏览器不可用")
        }
        return env
    }

    private fun startCreate(deferred: CompletableDeferred<Pointer?>) {
        WebView2Loop.post {
            val runtime = WebView2Runtime.detect()
            if (runtime == null) {
                deferred.complete(null)
                return@post
            }
            fun newHandler() = ComHandler(object : ComInvokeResultCb {
                override fun callback(self: Pointer, errorCode: Int, result: Pointer?): Int {
                    if (errorCode == S_OK && result != null) {
                        vtbl(result, 1) // AddRef: 出参是借用引用, 长期持有必须自己加
                        deferred.complete(result)
                    } else {
                        AppLog.put("WebView2 环境创建回调失败 (HRESULT=${hex(errorCode)})")
                        deferred.complete(null)
                    }
                    return S_OK
                }
            })

            val userDataDir = File(AppFilesDirs.get().cacheDir, "webview2")
                .apply { mkdirs() }.absolutePath
            // mixed content / autoplay 放行 (对照 Android mixedContentMode=ALWAYS_ALLOW 与
            // mediaPlaybackRequiresUserGesture=false): WebView2 无对应设置项, 唯一等价途径
            // 是环境级浏览器参数。同时把 TargetCompatibleBrowserVersion 给值, 否则该属性
            // 为 NULL 会 E_INVALIDARG。
            val options = Wv2EnvironmentOptions(
                additionalBrowserArguments = MIXED_CONTENT_BROWSER_ARGS,
                targetCompatibleBrowserVersion = runtime.version,
            )
            val handler = newHandler()
            var hr = runtime.createEnvironment.invokeInt(
                arrayOf(
                    1, // 上游 loader 固定传 true
                    WebView2Runtime.RUNTIME_TYPE_INSTALLED,
                    wide(userDataDir),
                    options.pointer,
                    handler.pointer,
                )
            )
            // runtime 已 AddRef 过 options (若有); 归还创建方那一份
            options.disown()
            handler.disown()
            // 兜底: 自定义 options 同步失败时退回原先的 null options 路径, 保证
            // "至少能建环境" (只是拿不到 mixed content/autoplay 放行)。
            // 用全新 handler: 失败路径上 runtime 可能已释放旧 handler, 不能重用。
            if (hr != S_OK) {
                AppLog.put(
                    "WebView2 带浏览器参数的环境创建失败 (HRESULT=${hex(hr)}), 退回默认参数重试"
                )
                val retry = newHandler()
                hr = runtime.createEnvironment.invokeInt(
                    arrayOf(
                        1,
                        WebView2Runtime.RUNTIME_TYPE_INSTALLED,
                        wide(userDataDir),
                        null,
                        retry.pointer,
                    )
                )
                retry.disown()
            }
            if (hr != S_OK) {
                AppLog.put("WebView2 环境创建调用失败 (HRESULT=${hex(hr)})")
                deferred.complete(null)
            }
        }
    }
}

private fun hex(value: Int) = "0x" + value.toUInt().toString(16)

/**
 * WebView2 环境级浏览器参数, 对齐 Android `TvBoxSniffer.createWebView` 的 settings:
 * - `--allow-running-insecure-content` ↔ `mixedContentMode = ALWAYS_ALLOW`
 *   (WebView2 无 mixedContentMode 设置项, 环境级开关是唯一等价途径);
 * - `--autoplay-policy=no-user-gesture-required` ↔ `mediaPlaybackRequiresUserGesture = false`
 *   (解析页靠 JS 自动起播才发出真实 m3u8 请求)。
 * 两者均为官方 WebView2 browser flags 文档列出的开关:
 * https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/webview-features-flags
 *
 * 注: `blockNetworkImage` / `setAcceptThirdPartyCookies` 在 WebView2 无环境参数等价物
 * (前者可在嗅探路径经 WebResourceRequested 返回空响应实现, 后者 WebView2 默认已接受第三方 cookie)。
 */
private const val MIXED_CONTENT_BROWSER_ARGS =
    "--allow-running-insecure-content --autoplay-policy=no-user-gesture-required"

/**
 * 一个 WebView2 实例: 宿主 HWND + Controller + CoreWebView2。
 *
 * COM 对象只属于 [WebView2Loop] 线程, 故所有方法内部都 post 过去, 调用方无需关心线程。
 */
internal class WebView2Instance private constructor(
    private val hwnd: WinDef.HWND,
    private val controller: Pointer,
    private val webview: Pointer,
    /** 进程级共享环境 (WebView2Environment 持有, 不在此释放), 构造 WebResourceRequest/Response 用。 */
    private val environment: Pointer,
) {

    @Volatile
    private var closed = false

    /** 导航完成回调 (参数为当前地址), 对应 app 端 WebViewClient.onPageFinished。 */
    @Volatile
    var onNavigationCompleted: ((String) -> Unit)? = null

    /** 导航失败回调 (NavigationCompleted IsSuccess=false, 参数为当前地址)。 */
    @Volatile
    var onNavigationFailed: ((String) -> Unit)? = null

    /** 导航开始回调; 返回 true 取消本次导航, 对应 shouldOverrideUrlLoading。 */
    @Volatile
    var onNavigationStarting: ((url: String, redirected: Boolean) -> Boolean)? = null

    /**
     * 子资源请求回调, 对应 app 端 `WebViewClient.shouldInterceptRequest`:
     * 参数为 (实际地址, 请求头惰性读取器)。[headers] 只在命中时调用 —— 读头需遍历 COM
     * 迭代器, 对未命中的大量子资源不必付出这份开销。读取器仅在本回调同步执行期间有效。
     * 返回 true 表示命中 —— 引擎以空响应吞掉该请求 (等价 Android 返回空 `WebResourceResponse`)。
     */
    @Volatile
    var onResourceRequested: ((url: String, headers: () -> Map<String, String>) -> Boolean)? = null

    /** 用户点窗口 X 的回调。 */
    @Volatile
    var onWindowClose: (() -> Unit)? = null

    /** 可见窗口的 CustomTab 工具栏 (无头实例为 null)。loop 线程创建, 之后只读。 */
    @Volatile
    var toolbar: WebView2Toolbar? = null

    suspend fun currentUrl(): String? = WebView2Loop.runOnLoop { readSource() }

    /** 事件回调里也会调, 故 closed 守卫放在此处: close() 之后 webview 已 Release。 */
    private fun readSource(): String? = if (closed) null else PointerByReference()
        .takeIf { vtbl(webview, Wv2.WV_GET_SOURCE, it) == S_OK }
        ?.let { takeWideString(it) }

    fun navigate(url: String) = WebView2Loop.post {
        if (!closed) vtbl(webview, Wv2.WV_NAVIGATE, wide(url))
    }

    /**
     * 携带全量 [headers] 导航 (对照 app 端 `WebView.loadUrl(url, headers)`):
     * 经 ICoreWebView2Environment2::CreateWebResourceRequest + ICoreWebView2_2::
     * NavigateWithWebResourceRequest 把 headerMap 全部写进导航请求。
     * 任一接口缺失 (runtime 过旧) 或构造失败时退回普通 [navigate], 不静默丢失导航。
     */
    fun navigateWithHeaders(url: String, headers: Map<String, String>?) = WebView2Loop.post {
        if (closed) return@post
        if (headers.isNullOrEmpty()) {
            vtbl(webview, Wv2.WV_NAVIGATE, wide(url))
            return@post
        }
        val webview2 = comQueryInterface(webview, IID_ICORE_WEBVIEW2_2)
        val environment2 = webview2?.let { comQueryInterface(environment, IID_ICORE_WEBVIEW2_ENVIRONMENT2) }
        if (webview2 == null || environment2 == null) {
            webview2?.let { comRelease(it) }
            environment2?.let { comRelease(it) }
            vtbl(webview, Wv2.WV_NAVIGATE, wide(url))
            return@post
        }
        try {
            // headers 参数是 CRLF 分隔的原始请求头串 (官方 IDL CreateWebResourceRequest 语义)
            val raw = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
            val created = PointerByReference()
            val hr = vtbl(
                environment2, Wv2.ENV2_CREATE_WEB_RESOURCE_REQUEST,
                wide(url), wide("GET"), Pointer.NULL, wide(raw), created,
            )
            val request = created.value
            if (hr == S_OK && request != null) {
                try {
                    vtbl(webview2, Wv2.WV2_NAVIGATE_WITH_WEB_RESOURCE_REQUEST, request)
                } finally {
                    comRelease(request)
                }
            } else {
                vtbl(webview, Wv2.WV_NAVIGATE, wide(url))
            }
        } finally {
            comRelease(environment2)
            comRelease(webview2)
        }
    }

    fun navigateToString(html: String) = WebView2Loop.post {
        if (!closed) vtbl(webview, Wv2.WV_NAVIGATE_TO_STRING, wide(html))
    }

    fun reload() = WebView2Loop.post {
        if (!closed) vtbl(webview, Wv2.WV_RELOAD)
    }

    fun setUserAgent(value: String) = WebView2Loop.post {
        if (closed) return@post
        val settings = PointerByReference()
            .takeIf { vtbl(webview, Wv2.WV_GET_SETTINGS, it) == S_OK }?.value ?: return@post
        try {
            comQueryInterface(settings, IID_ICORE_WEBVIEW2_SETTINGS2)?.let { settings2 ->
                vtbl(settings2, Wv2.SETTINGS2_PUT_USER_AGENT, wide(value))
                comRelease(settings2)
            }
        } finally {
            comRelease(settings)
        }
    }

    /** 执行 JS 取返回值; WebView2 回传 JSON 编码值, 与安卓 evaluateJavascript 同形。 */
    suspend fun executeScript(script: String, timeoutMs: Long): String? {
        if (closed) return null
        val deferred = CompletableDeferred<String?>()
        val handler = ComHandler(object : ComInvokeResultCb {
            override fun callback(self: Pointer, errorCode: Int, result: Pointer?): Int {
                deferred.complete(if (errorCode == S_OK) result?.getWideString(0) else null)
                return S_OK
            }
        })
        WebView2Loop.post {
            val handed = !closed &&
                vtbl(webview, Wv2.WV_EXECUTE_SCRIPT, wide(script), handler.pointer) == S_OK
            handler.disown()
            if (!handed) deferred.complete(null)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /** 读 [url] 的全部 cookie (含 httpOnly), 拼成 "k=v; k=v"; 无 cookie 返回 null。 */
    suspend fun cookies(url: String, timeoutMs: Long): String? {
        if (closed) return null
        val deferred = CompletableDeferred<String?>()
        val handler = ComHandler(object : ComInvokeResultCb {
            override fun callback(self: Pointer, errorCode: Int, result: Pointer?): Int {
                deferred.complete(
                    if (errorCode == S_OK && result != null) readCookieList(result) else null
                )
                return S_OK
            }
        })
        // GetCookies 是异步的, manager 必须活到回调返回, 故 Release 推迟到 await 之后
        val managerBox = arrayOfNulls<Pointer>(1)
        WebView2Loop.post {
            val manager = cookieManager()
            managerBox[0] = manager
            val handed = manager != null &&
                vtbl(manager, Wv2.COOKIE_MGR_GET_COOKIES, wide(url), handler.pointer) == S_OK
            handler.disown()
            if (!handed) deferred.complete(null)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            managerBox[0]?.let { WebView2Loop.post { comRelease(it) } }
        }
    }

    /**
     * 写入 cookie (对应 app 端把 CookieStore 注入 WebView 的方向)。
     * [cookie] 形如 "k=v; k2=v2"; 只带 name/value, 域取 [domain]、路径固定 `/`。
     */
    fun setCookies(domain: String, cookie: String) = WebView2Loop.post {
        if (closed) return@post
        val manager = cookieManager() ?: return@post
        try {
            cookie.split(';').forEach { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) return@forEach
                val name = entry.substring(0, index).trim()
                val value = entry.substring(index + 1).trim()
                if (name.isEmpty()) return@forEach
                val created = PointerByReference()
                val hr = vtbl(
                    manager, Wv2.COOKIE_MGR_CREATE_COOKIE,
                    wide(name), wide(value), wide(domain), wide("/"), created,
                )
                val item = created.value ?: return@forEach
                if (hr == S_OK) vtbl(manager, Wv2.COOKIE_MGR_ADD_OR_UPDATE_COOKIE, item)
                comRelease(item)
            }
        } finally {
            comRelease(manager)
        }
    }

    /** 调用方负责 Release。 */
    private fun cookieManager(): Pointer? {
        val webview2 = comQueryInterface(webview, IID_ICORE_WEBVIEW2_2) ?: return null
        return try {
            PointerByReference()
                .takeIf { vtbl(webview2, Wv2.WV2_GET_COOKIE_MANAGER, it) == S_OK }?.value
        } finally {
            comRelease(webview2)
        }
    }

    private fun readCookieList(list: Pointer): String? {
        val count = IntByReference()
        if (vtbl(list, Wv2.COOKIE_LIST_GET_COUNT, count) != S_OK) return null
        val pairs = ArrayList<String>(count.value)
        for (index in 0 until count.value) {
            val item = PointerByReference()
                .takeIf { vtbl(list, Wv2.COOKIE_LIST_GET_ITEM, index, it) == S_OK }?.value
                ?: continue
            try {
                val name = PointerByReference()
                    .takeIf { vtbl(item, Wv2.COOKIE_GET_NAME, it) == S_OK }
                    ?.let { takeWideString(it) } ?: continue
                val value = PointerByReference()
                    .takeIf { vtbl(item, Wv2.COOKIE_GET_VALUE, it) == S_OK }
                    ?.let { takeWideString(it) } ?: ""
                pairs += "$name=$value"
            } finally {
                comRelease(item)
            }
        }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * 删除源确认 (loop 线程): MessageBoxW 模态确认 (MB_YESNO), 返回是否确认删除。
     * 对照原版 menu_delete_source 的 alert (sure_del + 源名); 三端对齐
     * GTK 对话框 / Mac NSAlert (2026-08-08)。
     */
    fun confirmDelete(message: String): Boolean {
        if (closed) return false
        return user32Ex.MessageBoxW(
            hwnd, message, "删除源",
            MB_YESNO or MB_ICONQUESTION or MB_DEFBUTTON2,
        ) == IDYES
    }

    /** 关闭并释放 (幂等)。 */
    fun close() {
        if (closed) return
        closed = true
        // 关闭后 WebView2 回调仍可能触发 (CTRL_CLOSE 异步), 先断开工具栏引用,
        // 让 onNavigationCompleted 等回调里的 setLoading/setCanNavigate 直接跳过
        val tb = toolbar
        toolbar = null
        WebView2Loop.post {
            // 与 DestroyWindow 同一任务: dispose 后队列残留任务见句柄为 null 直接跳过
            tb?.dispose()
            runCatching { vtbl(controller, Wv2.CTRL_CLOSE) }
            // get_CoreWebView2 出参是 AddRef 过的; 少这次 Release 会让 CoreWebView2
            // 连同它持有的事件 handler 永不回收
            comRelease(webview)
            comRelease(controller)
            WebView2Loop.unhookWindow(hwnd)
            User32.INSTANCE.DestroyWindow(hwnd)
        }
    }

    /** 必须在 loop 线程调用。 */
    private fun bindEvents(sniffResources: Boolean) {
        val token = Memory(8)
        val navCompleted = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                // IsSuccess=false 表示导航失败 (网络错误/404/DNS), 对应 app 端
                // WebViewClient.onReceivedError; 桌面端无内建处理, 失败必须显式反馈
                val success = args?.let {
                    IntByReference().also { r -> vtbl(args, Wv2.NAV_COMPLETED_GET_IS_SUCCESS, r) }
                        .value != 0
                } ?: true
                val url = readSource().orEmpty()
                if (!success) runCatching { onNavigationFailed?.invoke(url) }
                runCatching { onNavigationCompleted?.invoke(url) }
                return S_OK
            }
        })
        vtbl(webview, Wv2.WV_ADD_NAVIGATION_COMPLETED, navCompleted.pointer, token)
        navCompleted.disown()

        val navStarting = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                args ?: return S_OK
                val callback = onNavigationStarting ?: return S_OK
                val url = PointerByReference()
                    .takeIf { vtbl(args, Wv2.NAV_START_GET_URI, it) == S_OK }
                    ?.let { takeWideString(it) } ?: return S_OK
                val redirected = IntByReference()
                    .also { vtbl(args, Wv2.NAV_START_GET_IS_REDIRECTED, it) }.value != 0
                if (runCatching { callback(url, redirected) }.getOrDefault(false)) {
                    vtbl(args, Wv2.NAV_START_PUT_CANCEL, 1)
                }
                return S_OK
            }
        })
        vtbl(webview, Wv2.WV_ADD_NAVIGATION_STARTING, navStarting.pointer, token)
        navStarting.disown()

        // 资源嗅探才装: 全量拦截每个子请求开销不小, 非 sourceRegex 场景不需要
        if (!sniffResources) {
            bindServerCertificateError()
            return
        }
        vtbl(webview, Wv2.WV_ADD_WEB_RESOURCE_REQUESTED_FILTER, wide("*"), Wv2.RESOURCE_CONTEXT_ALL)
        val resource = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                args ?: return S_OK
                val callback = onResourceRequested ?: return S_OK
                val request = PointerByReference()
                    .takeIf { vtbl(args, Wv2.RES_ARGS_GET_REQUEST, it) == S_OK }?.value
                    ?: return S_OK
                try {
                    val url = PointerByReference()
                        .takeIf { vtbl(request, Wv2.REQUEST_GET_URI, it) == S_OK }
                        ?.let { takeWideString(it) } ?: return S_OK
                    // 惰性读头: 仅命中时引擎才调 headers()
                    if (runCatching { callback(url) { readRequestHeaders(request) } }
                            .getOrDefault(false)
                    ) {
                        // 命中: 用空响应吞掉该请求 (等价 Android shouldInterceptRequest 返回空 WebResourceResponse)
                        blockWithEmptyResponse(args)
                    }
                } finally {
                    comRelease(request)
                }
                return S_OK
            }
        })
        vtbl(webview, Wv2.WV_ADD_WEB_RESOURCE_REQUESTED, resource.pointer, token)
        resource.disown()

        bindServerCertificateError()
    }

    /**
     * 读取 WebView 实际发出的全部请求头 (含 Referer/Cookie/User-Agent), 对应 Android
     * `WebResourceRequest.requestHeaders`。任一环节失败返回空 Map (不抛)。
     *
     * 路径: ICoreWebView2WebResourceRequest::get_Headers → ICoreWebView2HttpRequestHeaders::
     * GetIterator → ICoreWebView2HttpHeadersCollectionIterator 逐项 GetCurrentHeader/MoveNext。
     */
    private fun readRequestHeaders(request: Pointer): Map<String, String> {
        val headersRef = PointerByReference()
        if (vtbl(request, Wv2.REQUEST_GET_HEADERS, headersRef) != S_OK) return emptyMap()
        val headers = headersRef.value ?: return emptyMap()
        try {
            val iterRef = PointerByReference()
            if (vtbl(headers, Wv2.HEADERS_GET_ITERATOR, iterRef) != S_OK) return emptyMap()
            val iterator = iterRef.value ?: return emptyMap()
            try {
                val result = LinkedHashMap<String, String>()
                // 与官方 ScenarioWebViewEventMonitor 同序: HasCurrentHeader → GetCurrentHeader → MoveNext
                val hasCurrent = IntByReference(0)
                while (vtbl(iterator, Wv2.HEADERS_ITER_HAS_CURRENT, hasCurrent) == S_OK &&
                    hasCurrent.value != 0
                ) {
                    val nameRef = PointerByReference()
                    val valueRef = PointerByReference()
                    if (vtbl(iterator, Wv2.HEADERS_ITER_GET_CURRENT, nameRef, valueRef) == S_OK) {
                        val name = takeWideString(nameRef)
                        val value = takeWideString(valueRef)
                        if (!name.isNullOrEmpty() && value != null) result[name] = value
                    }
                    val hasNext = IntByReference(0)
                    if (vtbl(iterator, Wv2.HEADERS_ITER_MOVE_NEXT, hasNext) != S_OK ||
                        hasNext.value == 0
                    ) break
                }
                return result
            } finally {
                comRelease(iterator)
            }
        } finally {
            comRelease(headers)
        }
    }

    /**
     * 用空响应吞掉当前请求: Environment::CreateWebResourceResponse(null content, 200, ...)
     * 后 args::put_Response。对照官方 WebView2APISample 的图片屏蔽分支 (content=nullptr)。
     */
    private fun blockWithEmptyResponse(args: Pointer) {
        val response = PointerByReference()
        val hr = vtbl(
            environment, Wv2.ENV_CREATE_WEB_RESOURCE_RESPONSE,
            Pointer.NULL, 200, wide("OK"), wide("Content-Type: text/plain"), response,
        )
        val created = response.value
        if (hr == S_OK && created != null) {
            try {
                vtbl(args, Wv2.RES_ARGS_PUT_RESPONSE, created)
            } finally {
                comRelease(created)
            }
        }
    }

    /**
     * 证书错误放行: ICoreWebView2_14::add_ServerCertificateErrorDetected, 回调里
     * put_Action(ALWAYS_ALLOW), 等价 Android `SslErrorHandler.proceed()`。
     * runtime 低于 1.0.1245.22 无 ICoreWebView2_14, 静默跳过 (保持原有默认拦截行为)。
     */
    private fun bindServerCertificateError() {
        val webview14 = comQueryInterface(webview, IID_ICORE_WEBVIEW2_14) ?: return
        try {
            val token = Memory(8)
            val handler = ComHandler(object : ComInvokeEventCb {
                override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                    args ?: return S_OK
                    // ALWAYS_ALLOW = 0; 等价 proceed() 且对同 host+证书 缓存决定
                    vtbl(args, Wv2.CERT_ARGS_PUT_ACTION, Wv2.CERT_ACTION_ALWAYS_ALLOW)
                    return S_OK
                }
            })
            vtbl(webview14, Wv2.WV14_ADD_SERVER_CERTIFICATE_ERROR_DETECTED, handler.pointer, token)
            handler.disown()
        } finally {
            comRelease(webview14)
        }
    }

    /**
     * 自动播放放行: 订阅 ICoreWebView2::add_PermissionRequested, 对
     * `COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY` 直接置 ALLOW, 等价 Android
     * `mediaPlaybackRequiresUserGesture = false` (解析页靠 JS 自动起播才发出真实 m3u8 请求)。
     * WebView2 无 "requires user gesture" 布尔开关, 权限事件是官方等价途径。
     */
    private fun bindAutoplayPermission() {
        val token = Memory(8)
        val handler = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                args ?: return S_OK
                val kind = IntByReference()
                if (vtbl(args, Wv2.PERM_ARGS_GET_PERMISSION_KIND, kind) == S_OK &&
                    kind.value == Wv2.PERMISSION_KIND_AUTOPLAY
                ) {
                    vtbl(args, Wv2.PERM_ARGS_PUT_STATE, Wv2.PERMISSION_STATE_ALLOW)
                }
                return S_OK
            }
        })
        vtbl(webview, Wv2.WV_ADD_PERMISSION_REQUESTED, handler.pointer, token)
        handler.disown()
    }

    /** 必须在 loop 线程调用。 */
    private fun applyLayout() {
        // 窗口本身不可见时也把 controller 置为可见: 否则 Chromium 按"被遮挡"降频, 脚本/定时器会停
        vtbl(controller, Wv2.CTRL_PUT_IS_VISIBLE, 1)
        val rect = WebView2Loop.clientRect(hwnd)
        // 工具栏动态高度 (溢出菜单展开时加高, WebView2 同步下移)
        val toolbarTop = toolbar?.let { it.layoutHeight() } ?: 0
        vtbl(controller, Wv2.CTRL_PUT_BOUNDS, RectValue().apply {
            left = rect.left
            top = rect.top + toolbarTop
            right = rect.right
            bottom = rect.bottom
        })
    }

    /** 工具栏高度变化 (菜单展开/收起) 后刷新 WebView2 bounds。 */
    fun refreshToolbarBounds() {
        WebView2Loop.post { applyLayout() }
    }

    /** 最大化/还原切换 (对照原版 menu_full_screen 的最大化语义; 原生标题栏全屏由系统处理)。 */
    fun maximizeToggle() {
        WebView2Loop.post {
            val isZoomed = user32Ex.IsZoomed(hwnd)
            user32Ex.ShowWindow(hwnd, if (isZoomed) WinUser.SW_RESTORE else WinUser.SW_MAXIMIZE)
        }
    }

    /** 对齐 app 端 BackstageWebView: 开 JS, 关脚本弹窗/devtools。
     * 内建错误页保持开启: app 端关它是因有 onReceivedError 自定义处理, 桌面端
     * 无等价实现, 关闭会导致加载失败时一片空白 (曾表现为"页面错误无行为");
     * 开启后 Chromium 错误页自带重试按钮, 配合 [onNavigationFailed] 提示。
     *
     * 注意: WebView2 的 ICoreWebView2Settings/2/3/4 没有 mixedContentMode /
     * blockNetworkImage / acceptThirdPartyCookies 的等价开关 —— mixed content 只能经
     * 环境级 `--allow-running-insecure-content` 浏览器参数 (见 [WebView2Environment]),
     * 图片拦截/第三方 cookie 在 WebView2 侧无对应 API (如需拦截图片可经 WebResourceRequested
     * 返回空响应实现, 但那属于嗅探路径, 不在此默认设置)。 */
    private fun applyDefaultSettings() {
        val settings = PointerByReference()
            .takeIf { vtbl(webview, Wv2.WV_GET_SETTINGS, it) == S_OK }?.value ?: return
        try {
            vtbl(settings, Wv2.SETTINGS_PUT_IS_SCRIPT_ENABLED, 1)
            vtbl(settings, Wv2.SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED, 0)
            vtbl(settings, Wv2.SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED, 0)
            vtbl(settings, Wv2.SETTINGS_PUT_IS_BUILT_IN_ERROR_PAGE_ENABLED, 1)
        } finally {
            comRelease(settings)
        }
    }

    private fun loword(lParam: WinDef.LPARAM): Int =
        (lParam as Number).toLong().toInt() and 0xFFFF

    companion object {

        /**
         * 建实例。[visible] = false 为无头: 宿主窗口全程不显示 (屏幕外 + 无 WS_VISIBLE),
         * 但 controller 仍置可见, 保证 JS 与定时器照常跑。
         *
         * @param toolbarSpec 非空时给可见窗口挂 CustomTab 式工具栏 (自绘, 见 [WebView2Toolbar])
         */
        suspend fun create(
            visible: Boolean,
            title: String,
            sniffResources: Boolean = false,
            toolbarSpec: WebView2ToolbarSpec? = null,
        ): WebView2Instance? {
            val environment = WebView2Environment.get() ?: return null
            val deferred = CompletableDeferred<Pair<WinDef.HWND, Pointer>?>()
            WebView2Loop.post {
                val hwnd = runCatching {
                    WebView2Loop.createWindow(
                        visible,
                        title,
                        bounds = if (visible) WebView2Loop.centeredBounds()
                        else WebView2Loop.WindowBounds()
                    )
                }
                    .onFailure { AppLog.put("WebView2 宿主窗口创建失败", it) }
                    .getOrNull()
                if (hwnd == null) {
                    deferred.complete(null)
                    return@post
                }
                val handler = ComHandler(object : ComInvokeResultCb {
                    override fun callback(self: Pointer, errorCode: Int, result: Pointer?): Int {
                        if (errorCode == S_OK && result != null) {
                            vtbl(result, 1) // AddRef 长期持有
                            deferred.complete(hwnd to result)
                        } else {
                            AppLog.put("WebView2 controller 创建失败 (HRESULT=${hex(errorCode)})")
                            User32.INSTANCE.DestroyWindow(hwnd)
                            deferred.complete(null)
                        }
                        return S_OK
                    }
                })
                vtbl(environment, Wv2.ENV_CREATE_CONTROLLER, hwnd.pointer, handler.pointer)
                handler.disown()
            }
            val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
            val (hwnd, controller) = created ?: run {
                AppLog.put("WebView2 窗口/controller 创建超时或失败 (${CREATE_TIMEOUT_MS}ms)")
                return null
            }

            return WebView2Loop.runOnLoop {
                val webviewRef = PointerByReference()
                if (vtbl(controller, Wv2.CTRL_GET_CORE_WEBVIEW2, webviewRef) != S_OK) {
                    AppLog.put("WebView2 获取 CoreWebView2 失败")
                    comRelease(controller)
                    User32.INSTANCE.DestroyWindow(hwnd)
                    return@runOnLoop null
                }
                WebView2Instance(hwnd, controller, webviewRef.value, environment).apply {
                    toolbar = if (visible && toolbarSpec != null) {
                        val client = WebView2Loop.clientRect(hwnd)
                        WebView2Toolbar(
                            hwnd,
                            toolbarSpec.title,
                            toolbarSpec.isLogin,
                            toolbarSpec.saveResult,
                            toolbarSpec.rssActions,
                            toolbarSpec.sourceKey,
                        ).also {
                            // 2026-08-06 标准 ToolbarWindow32 控件: 创建子窗口 + 按钮 + 进度条
                            it.create()
                            it.resize(client.right)
                        }
                    } else null
                    applyLayout()
                    applyDefaultSettings()
                    // 解析页自动起播: 放行 AUTOPLAY 权限请求
                    // (等价 Android mediaPlaybackRequiresUserGesture=false)
                    bindAutoplayPermission()
                    bindEvents(sniffResources)
                    WebView2Loop.hookWindow(hwnd) { message, wParam, lParam ->
                        val t = toolbar
                        when (message) {
                            WinUser.WM_SIZE -> {
                                // 同步工具栏/进度条尺寸 (标准控件由系统重绘, 无残留)
                                val w = loword(lParam)
                                t?.resize(w)
                                applyLayout()
                                false
                            }

                            // 标准 Button 控件按钮点击 (jna WinUser 未定义 WM_COMMAND, 自定义常量)
                            WM_COMMAND -> {
                                t?.onCommand(wParam, lParam) == true
                            }

                            // 关闭统一走 onWindowClose -> close(), 不让 DefWindowProc 直接销毁
                            WinUser.WM_CLOSE -> {
                                runCatching { onWindowClose?.invoke() }
                                true
                            }

                            else -> false
                        }
                    }
                }
            }
        }
    }
}

/** 可见窗口工具栏的构造参数 (仅 [WebView2Instance.create] visible=true 时传入)。 */
internal class WebView2ToolbarSpec(
    val title: String,
    val isLogin: Boolean,
    val saveResult: Boolean,
    val rssActions: RssToolbarActions? = null,
    /** 书源 key (cookieTag): 非空时菜单显示 禁用源/删除源 (2026-08-08)。 */
    val sourceKey: String? = null,
)

private const val WM_COMMAND = 0x0111

// MessageBoxW 常量 (winuser.h): 确认框样式与返回值
private const val MB_YESNO = 0x00000004
private const val MB_ICONQUESTION = 0x00000020
private const val MB_DEFBUTTON2 = 0x00000100
private const val IDYES = 6

/** user32 补充接口: jna-platform User32 未覆盖 IsZoomed/MessageBoxW。 */
private interface WebView2User32Ex : StdCallLibrary {
    fun IsZoomed(hWnd: WinDef.HWND): Boolean
    fun ShowWindow(hWnd: WinDef.HWND, nCmdShow: Int): Boolean
    fun MessageBoxW(hWnd: WinDef.HWND, lpText: String?, lpCaption: String?, uType: Int): Int
}

private val user32Ex: WebView2User32Ex by lazy {
    com.sun.jna.Native.load(
        "user32",
        WebView2User32Ex::class.java,
        com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS
    )
}
