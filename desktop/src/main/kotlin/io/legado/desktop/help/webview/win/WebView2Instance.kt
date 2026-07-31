package io.legado.desktop.help.webview.win

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** ICoreWebView2_2: CookieManager 所在接口 (SDK 1.0.705 起, 是第一个扩展接口)。 */
private val IID_ICORE_WEBVIEW2_2 = Guid.GUID("9E8F0CF8-E670-4B5E-B2BC-73E061E3184C")

/** ICoreWebView2Settings2: put_UserAgent 所在接口。 */
private val IID_ICORE_WEBVIEW2_SETTINGS2 = Guid.GUID("EE9A0F68-F46C-4E32-AC23-EF8CAC224D2A")

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

    // 强引用: handler 的 vtable 蹦床被 GC 后 native 回调即崩
    private val retained = ArrayList<ComHandler>()

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
        return withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
            ?.also { environment = it }
    }

    private fun startCreate(deferred: CompletableDeferred<Pointer?>) {
        WebView2Loop.post {
            val runtime = WebView2Runtime.detect()
            if (runtime == null) {
                deferred.complete(null)
                return@post
            }
            val handler = ComHandler(object : ComInvokeResultCb {
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
            retained += handler
            val userDataDir = File(AppFilesDirs.get().cacheDir, "webview2")
                .apply { mkdirs() }.absolutePath
            val hr = runtime.createEnvironment.invokeInt(
                arrayOf(
                    1, // 上游 loader 固定传 true
                    WebView2Runtime.RUNTIME_TYPE_INSTALLED,
                    wide(userDataDir),
                    null,
                    handler.pointer,
                )
            )
            if (hr != S_OK) {
                AppLog.put("WebView2 环境创建调用失败 (HRESULT=${hex(hr)})")
                deferred.complete(null)
            }
        }
    }
}

private fun hex(value: Int) = "0x" + value.toUInt().toString(16)

/**
 * 一个 WebView2 实例: 宿主 HWND + Controller + CoreWebView2。
 *
 * COM 对象只属于 [WebView2Loop] 线程, 故所有方法内部都 post 过去, 调用方无需关心线程。
 */
internal class WebView2Instance private constructor(
    private val hwnd: WinDef.HWND,
    private val controller: Pointer,
    private val webview: Pointer,
) {

    // 强引用: 事件 handler 生命周期必须覆盖整个实例
    private val retained = ArrayList<ComHandler>()

    @Volatile
    private var closed = false

    /** 导航完成回调 (参数为当前地址), 对应 app 端 WebViewClient.onPageFinished。 */
    @Volatile
    var onNavigationCompleted: ((String) -> Unit)? = null

    /** 导航开始回调; 返回 true 取消本次导航, 对应 shouldOverrideUrlLoading。 */
    @Volatile
    var onNavigationStarting: ((url: String, redirected: Boolean) -> Boolean)? = null

    /** 子资源请求回调, 对应 app 端 WebViewClient.onLoadResource。 */
    @Volatile
    var onResourceRequested: ((String) -> Unit)? = null

    /** 用户点窗口 X 的回调。 */
    @Volatile
    var onWindowClose: (() -> Unit)? = null

    suspend fun currentUrl(): String? = WebView2Loop.runOnLoop {
        if (closed) null else readSource()
    }

    private fun readSource(): String? = PointerByReference()
        .takeIf { vtbl(webview, Wv2.WV_GET_SOURCE, it) == S_OK }
        ?.let { takeWideString(it) }

    fun navigate(url: String) = WebView2Loop.post {
        if (!closed) vtbl(webview, Wv2.WV_NAVIGATE, wide(url))
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
        synchronized(retained) { retained += handler }
        WebView2Loop.post {
            if (closed || vtbl(webview, Wv2.WV_EXECUTE_SCRIPT, wide(script), handler.pointer) != S_OK) {
                deferred.complete(null)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(retained) { retained -= handler }
        }
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
        synchronized(retained) { retained += handler }
        // GetCookies 是异步的, manager 必须活到回调返回, 故 Release 推迟到 await 之后
        val managerBox = arrayOfNulls<Pointer>(1)
        WebView2Loop.post {
            val manager = cookieManager()
            managerBox[0] = manager
            if (manager == null ||
                vtbl(manager, Wv2.COOKIE_MGR_GET_COOKIES, wide(url), handler.pointer) != S_OK
            ) {
                deferred.complete(null)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(retained) { retained -= handler }
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

    /** 关闭并释放 (幂等)。 */
    fun close() {
        if (closed) return
        closed = true
        WebView2Loop.post {
            runCatching { vtbl(controller, Wv2.CTRL_CLOSE) }
            comRelease(controller)
            WebView2Loop.unhookWindow(hwnd)
            User32.INSTANCE.DestroyWindow(hwnd)
            synchronized(retained) { retained.clear() }
        }
    }

    /** 必须在 loop 线程调用。 */
    private fun bindEvents(sniffResources: Boolean) {
        val token = Memory(8)

        val navCompleted = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                val url = readSource().orEmpty()
                runCatching { onNavigationCompleted?.invoke(url) }
                return S_OK
            }
        })
        retained += navCompleted
        vtbl(webview, Wv2.WV_ADD_NAVIGATION_COMPLETED, navCompleted.pointer, token)

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
        retained += navStarting
        vtbl(webview, Wv2.WV_ADD_NAVIGATION_STARTING, navStarting.pointer, token)

        // 资源嗅探才装: 全量拦截每个子请求开销不小, 非 sourceRegex 场景不需要
        if (!sniffResources) return
        vtbl(webview, Wv2.WV_ADD_WEB_RESOURCE_REQUESTED_FILTER, wide("*"), Wv2.RESOURCE_CONTEXT_ALL)
        val resource = ComHandler(object : ComInvokeEventCb {
            override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                args ?: return S_OK
                val callback = onResourceRequested ?: return S_OK
                val request = PointerByReference()
                    .takeIf { vtbl(args, Wv2.RES_ARGS_GET_REQUEST, it) == S_OK }?.value
                    ?: return S_OK
                try {
                    PointerByReference()
                        .takeIf { vtbl(request, Wv2.REQUEST_GET_URI, it) == S_OK }
                        ?.let { takeWideString(it) }
                        ?.let { url -> runCatching { callback(url) } }
                } finally {
                    comRelease(request)
                }
                return S_OK
            }
        })
        retained += resource
        vtbl(webview, Wv2.WV_ADD_WEB_RESOURCE_REQUESTED, resource.pointer, token)
    }

    /** 必须在 loop 线程调用。 */
    private fun applyLayout() {
        // 窗口本身不可见时也把 controller 置为可见: 否则 Chromium 按"被遮挡"降频, 脚本/定时器会停
        vtbl(controller, Wv2.CTRL_PUT_IS_VISIBLE, 1)
        vtbl(controller, Wv2.CTRL_PUT_BOUNDS, WebView2Loop.clientRect(hwnd))
    }

    /** 对齐 app 端 BackstageWebView: 开 JS, 关脚本弹窗/devtools/内建错误页。 */
    private fun applyDefaultSettings() {
        val settings = PointerByReference()
            .takeIf { vtbl(webview, Wv2.WV_GET_SETTINGS, it) == S_OK }?.value ?: return
        try {
            vtbl(settings, Wv2.SETTINGS_PUT_IS_SCRIPT_ENABLED, 1)
            vtbl(settings, Wv2.SETTINGS_PUT_ARE_DEFAULT_SCRIPT_DIALOGS_ENABLED, 0)
            vtbl(settings, Wv2.SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED, 0)
            vtbl(settings, Wv2.SETTINGS_PUT_IS_BUILT_IN_ERROR_PAGE_ENABLED, 0)
        } finally {
            comRelease(settings)
        }
    }

    companion object {

        /** 创建期 handler 的强引用池 (创建完成即移除)。 */
        private val creating = java.util.Collections.synchronizedList(ArrayList<ComHandler>())

        /**
         * 建实例。[visible] = false 为无头: 宿主窗口全程不显示 (屏幕外 + 无 WS_VISIBLE),
         * 但 controller 仍置可见, 保证 JS 与定时器照常跑。
         */
        suspend fun create(
            visible: Boolean,
            title: String,
            sniffResources: Boolean = false,
        ): WebView2Instance? {
            val environment = WebView2Environment.get() ?: return null
            val deferred = CompletableDeferred<Pair<WinDef.HWND, Pointer>?>()
            val handlerBox = arrayOfNulls<ComHandler>(1)
            WebView2Loop.post {
                val hwnd = runCatching { WebView2Loop.createWindow(visible, title) }
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
                handlerBox[0] = handler
                creating += handler
                vtbl(environment, Wv2.ENV_CREATE_CONTROLLER, hwnd.pointer, handler.pointer)
            }
            val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
            handlerBox[0]?.let { creating.remove(it) }
            val (hwnd, controller) = created ?: return null

            return WebView2Loop.runOnLoop {
                val webviewRef = PointerByReference()
                if (vtbl(controller, Wv2.CTRL_GET_CORE_WEBVIEW2, webviewRef) != S_OK) {
                    comRelease(controller)
                    User32.INSTANCE.DestroyWindow(hwnd)
                    return@runOnLoop null
                }
                WebView2Instance(hwnd, controller, webviewRef.value).apply {
                    applyLayout()
                    applyDefaultSettings()
                    bindEvents(sniffResources)
                    WebView2Loop.hookWindow(hwnd) { message ->
                        when (message) {
                            WinUser.WM_SIZE -> {
                                applyLayout()
                                false
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
