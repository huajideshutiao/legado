package io.legado.desktop.help.webview.gtk

import com.sun.jna.Pointer
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.getUserAgent
import io.legado.desktop.help.webview.WebViewFetchRequest
import io.legado.desktop.help.webview.gtk.GtkLibs.GErrorRef
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_ORIENTATION_VERTICAL
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_WINDOW_TOPLEVEL

/**
 * 一个 WebKitGTK 会话: 隐藏窗口 (无头抓取) 或可见窗口 (登录/验证) + WebKitWebView +
 * 信号接线。**所有方法必须在 GTK 线程调用** (经 [GtkLoop.await]/[GtkLoop.post])。
 *
 * 信号回调闭包直接引用本对象, JNA 回调对象由本对象强引用保活 (GC 后 GTK 调用即崩)。
 */
internal class GtkSession private constructor(
    private val window: Pointer?,
    val view: Pointer,
    private val cookieManager: Pointer?,
    val toolbar: GtkToolbar?,
) {

    /** 同步 GTK 窗口标题 (工具栏已不绘制标题文字, 2026-08-06)。 */
    fun setWindowTitle(title: String) {
        val w = window ?: return
        GtkLibs.gtk.gtk_window_set_title(w, title)
    }

    /**
     * 删除源确认 (GTK 线程): 模态对话框 (确定/取消), 返回是否确认删除。
     * 对照原版 menu_delete_source 的 alert (sure_del + 源名); 三端对齐
     * Windows MessageBox / Mac NSAlert (2026-08-08)。
     */
    fun confirmDelete(message: String): Boolean {
        val parent = window ?: return false
        val gtk = GtkLibs.gtk
        // 全非 varargs 组合 (GtkDialog + label + 按钮), 避开 JNA 对 C varargs 的 ABI 坑
        val dialog = gtk.gtk_dialog_new()
        gtk.gtk_window_set_title(dialog, "删除源")
        gtk.gtk_window_set_transient_for(dialog, parent)
        gtk.gtk_window_set_modal(dialog, 1)
        gtk.gtk_window_set_destroy_with_parent(dialog, 1)
        val content = gtk.gtk_dialog_get_content_area(dialog)
        gtk.gtk_box_pack_start(content, gtk.gtk_label_new(message), 0, 0, 8)
        gtk.gtk_dialog_add_action_widget(
            dialog, gtk.gtk_button_new_with_label("确认"), GtkLibs.GTK_RESPONSE_YES
        )
        gtk.gtk_dialog_add_action_widget(
            dialog, gtk.gtk_button_new_with_label("取消"), GtkLibs.GTK_RESPONSE_NO
        )
        gtk.gtk_widget_show_all(dialog)
        val response = gtk.gtk_dialog_run(dialog)
        gtk.gtk_widget_destroy(dialog)
        return response == GtkLibs.GTK_RESPONSE_YES
    }

    /** 最大化/还原切换 (对照原版 menu_full_screen)。 */
    fun toggleMaximize() {
        val w = window ?: return
        if (GtkLibs.gtk.gtk_window_is_maximized(w) != 0) {
            GtkLibs.gtk.gtk_window_unmaximize(w)
        } else {
            GtkLibs.gtk.gtk_window_maximize(w)
        }
    }

    /** 导航事件回调 (GTK 线程)。event 为 WebKitLoadEvent。 */
    var onLoadChanged: ((view: Pointer, event: Int) -> Unit)? = null

    /** uri 变化回调 (GTK 线程), 用于 overrideUrlRegex 嗅探。 */
    var onUriChanged: ((String) -> Unit)? = null

    /** 子资源加载开始回调 (GTK 线程), 用于 sourceRegex 嗅探。 */
    var onResourceStarted: ((String) -> Unit)? = null

    /** 窗口关闭回调 (用户点 X / 页面 window.close()/destroy), 触发句柄 close 语义。 */
    var onClosed: (() -> Unit)? = null

    /** 页面元素全屏状态变化回调 (GTK 线程), 对应 WebKitWebView::fullscreen-changed。 */
    var onFullscreenChanged: ((Boolean) -> Unit)? = null

    // JNA 回调强引用 (防 GC 后 GTK 调用即崩)
    private val loadChangedCb = object : GtkLibs.LoadChangedCallback {
        override fun invoke(view: Pointer, loadEvent: Int, userData: Pointer?) {
            onLoadChanged?.invoke(view, loadEvent)
        }
    }

    private val uriNotifyCb = object : GtkLibs.NotifyCallback {
        override fun invoke(obj: Pointer, pspec: Pointer, userData: Pointer?) {
            val uri = runCatching { GtkLibs.webkit.webkit_web_view_get_uri(obj) }.getOrNull()
            if (!uri.isNullOrBlank()) onUriChanged?.invoke(uri)
        }
    }

    private val progressNotifyCb = object : GtkLibs.NotifyCallback {
        override fun invoke(obj: Pointer, pspec: Pointer, userData: Pointer?) {
            // 只更新条值, 可见性由 load-changed 控制 (避免加载完成后又被显示)
            val progress = runCatching {
                GtkLibs.webkit.webkit_web_view_get_estimated_load_progress(obj)
            }.getOrNull()
            toolbar?.setProgressFraction(progress)
        }
    }

    private val resourceStartedCb = object : GtkLibs.ResourceLoadStartedCallback {
        override fun invoke(
            view: Pointer,
            resource: Pointer,
            request: Pointer,
            userData: Pointer?
        ) {
            val uri = runCatching { GtkLibs.webkit.webkit_web_resource_get_uri(resource) }
                .getOrNull()
            if (!uri.isNullOrBlank()) onResourceStarted?.invoke(uri)
        }
    }

    private val closeCb = object : GtkLibs.WebViewCloseCallback {
        override fun invoke(view: Pointer, userData: Pointer?): Int {
            runCatching { onClosed?.invoke() }
            return 0
        }
    }

    private val deleteEventCb = object : GtkLibs.DeleteEventCallback {
        override fun invoke(widget: Pointer, event: Pointer, userData: Pointer?): Int {
            runCatching { onClosed?.invoke() }
            return 0 // FALSE: 允许 GTK 默认销毁, destroy 信号兜底清理
        }
    }

    private val destroyCb = object : GtkLibs.NotifyCallback {
        override fun invoke(obj: Pointer, pspec: Pointer, userData: Pointer?) {
            runCatching { onClosed?.invoke() }
        }
    }

    private val fullscreenChangedCb = object : GtkLibs.FullscreenChangedCallback {
        override fun invoke(view: Pointer, fullscreen: Int, userData: Pointer?) {
            runCatching { onFullscreenChanged?.invoke(fullscreen != 0) }
        }
    }

    companion object {
        const val COOKIE_TIMEOUT = 5_000L

        /**
         * 创建会话 (GTK 线程)。可见窗口带工具栏; 无头模式窗口隐藏 (realize 保证有原生
         * 窗口支撑渲染管线, 但不映射显示)。
         * 返回 null = 创建失败 (库不可用)。
         */
        fun create(
            visible: Boolean,
            title: String = "legado",
            toolbar: GtkToolbar? = null,
        ): GtkSession? {
            if (!GtkLibs.ensureLoaded()) return null
            val gtk = GtkLibs.gtk
            val webkit = GtkLibs.webkit
            val window = gtk.gtk_window_new(GTK_WINDOW_TOPLEVEL)
            gtk.gtk_window_set_title(window, title)
            if (visible) {
                gtk.gtk_window_set_default_size(window, 1000, 700)
            } else {
                gtk.gtk_window_set_default_size(window, 1, 1)
            }
            val root = gtk.gtk_box_new(GTK_ORIENTATION_VERTICAL, 0)
            gtk.gtk_container_add(window, root)
            val view = webkit.webkit_web_view_new()
            gtk.gtk_widget_set_hexpand(view, 1)
            gtk.gtk_widget_set_vexpand(view, 1)
            if (toolbar != null) {
                gtk.gtk_container_add(root, toolbar.bar)
                gtk.gtk_container_add(root, toolbar.progress)
                gtk.gtk_widget_set_visible(toolbar.progress, 0)
            }
            gtk.gtk_container_add(root, view)

            val context = getWebContext(view)
            val cookieManager = context?.let { webkit.webkit_web_context_get_cookie_manager(it) }

            val session = GtkSession(window, view, cookieManager, toolbar)
            GtkLibs.gobject.g_signal_connect(view, "load-changed", session.loadChangedCb, null)
            // 页面元素全屏 (HTML5 Fullscreen API) 状态变化, 如 <video> 全屏播放
            GtkLibs.gobject.g_signal_connect(
                view, "fullscreen-changed", session.fullscreenChangedCb, null
            )
            GtkLibs.gobject.g_signal_connect(view, "notify::uri", session.uriNotifyCb, null)
            GtkLibs.gobject.g_signal_connect(
                view, "notify::estimated-load-progress", session.progressNotifyCb, null
            )
            GtkLibs.gobject.g_signal_connect(
                view,
                "resource-load-started",
                session.resourceStartedCb,
                null
            )
            GtkLibs.gobject.g_signal_connect(view, "close", session.closeCb, null)
            GtkLibs.gobject.g_signal_connect(window, "delete-event", session.deleteEventCb, null)
            GtkLibs.gobject.g_signal_connect(window, "destroy", session.destroyCb, null)

            if (visible) {
                gtk.gtk_widget_show_all(window)
                // 弹窗语义: 置前显示 (gtk_widget_show_all 不改变 Z 序, 曾出现
                // 浏览器窗口启动在主窗口后面; gtk_window_present 提升窗口并请求焦点)
                gtk.gtk_window_present(window)
            } else {
                // 无头: realize 建原生窗口但不 map (WebKitGTK offscreen 加载照常执行网络与 JS)
                gtk.gtk_widget_realize(window)
            }
            return session
        }

        /** 兼容 2.38 (get_web_context) 与 2.42+ (get_context) 的符号名。 */
        private fun getWebContext(view: Pointer): Pointer? {
            runCatching { return GtkLibs.webkit.webkit_web_view_get_context(view) }
                .onFailure { /* 老版本无此符号 */ }
            return runCatching { GtkLibs.webkit.webkit_web_view_get_web_context(view) }.getOrNull()
        }
    }

    // ==================== 生命周期 ====================

    /** 销毁窗口 (级联销毁 WebView, 幂等由 closedOnce 保证; GTK 线程)。 */
    fun destroy() {
        window?.let { runCatching { GtkLibs.gtk.gtk_widget_destroy(it) } }
    }

    // ==================== 加载 ====================

    /** UA 注入 (settings 级, 2.6+ 全版本可用)。 */
    fun setUserAgent(userAgent: String) {
        val settings = GtkLibs.webkit.webkit_web_view_get_settings(view)
        GtkLibs.webkit.webkit_settings_set_user_agent(settings, userAgent)
    }

    /** 对应 app 端 load(): 先对齐 UA (按 UA 绑定的 cookie 换到 HTTP 侧才有效), html 优先, 否则加载 url。 */
    fun start(request: WebViewFetchRequest) {
        setUserAgent(request.headerMap.getUserAgent())
        val html = request.html
        when {
            !html.isNullOrEmpty() -> loadHtml(html, request.url)
            !request.url.isNullOrEmpty() -> loadUri(request.url)
            else -> throw NoStackTraceException("url 与 html 不能同时为空")
        }
    }

    fun loadUri(uri: String) {
        GtkLibs.webkit.webkit_web_view_load_uri(view, uri)
    }

    fun loadHtml(html: String, baseUri: String?) {
        // 对照 app 端 loadDataWithBaseURL: baseUri 为 null 时按 about:blank 根解析
        GtkLibs.webkit.webkit_web_view_load_html(view, html, baseUri)
    }

    fun reload() {
        GtkLibs.webkit.webkit_web_view_reload(view)
    }

    /** 当前地址 (GTK 线程)。 */
    fun currentUri(): String? = GtkLibs.webkit.webkit_web_view_get_uri(view)

    // ==================== JS ====================

    /**
     * 同步执行 JS 并取回结果 (GTK 线程内驱动异步回调)。
     * 返回 JS 值 toString (与 JavaFX executeScript 相同, 非 JSON 编码); null/异常返回 null。
     */
    fun executeScript(script: String, timeoutMs: Long = GtkLoop.DRIVE_TIMEOUT_MS): String? =
        GtkLoop.driveAsync(
            timeoutMs,
            start = { cb ->
                GtkLibs.webkit.webkit_web_view_run_javascript(view, script, null, cb, null)
            },
            produce = { res ->
                val err = GErrorRef()
                val jsResult = GtkLibs.webkit.webkit_web_view_run_javascript_finish(view, res, err)
                if (jsResult == null) {
                    GtkLoop.errorMessage(err)?.let { AppLog.put("WebKitGTK JS 执行失败: $it") }
                    null
                } else {
                    val value = GtkLibs.webkit.webkit_javascript_result_get_js_value(jsResult)
                    val str = GtkLibs.jsc.jsc_value_to_string(value)
                    // 2.40 前 WebKitJavascriptResult 是 GObject 需要 unref; 之后 opaque 无此符号
                    GtkLibs.javascriptResultUnref(jsResult)
                    str
                }
            },
        )

    // ==================== Cookie ====================

    /**
     * 注入 cookie (形如 "k=v; k=v") 到 [domain] (GTK 线程)。
     * 逐条 add_cookie 并等待完成; 任一失败返回 false。
     */
    fun addCookies(domain: String, cookie: String, timeoutMs: Long = COOKIE_TIMEOUT): Boolean {
        val manager = cookieManager ?: return false
        var ok = true
        cookie.split(';').forEach { entry ->
            val index = entry.indexOf('=')
            if (index <= 0) return@forEach
            val name = entry.substring(0, index).trim()
            val value = entry.substring(index + 1).trim()
            if (name.isEmpty()) return@forEach
            val gCookie = GtkLibs.webkit.webkit_cookie_new(name, value, domain, "/", 0.0)
            val added = GtkLoop.driveAsync(
                timeoutMs,
                start = { cb ->
                    GtkLibs.webkit.webkit_cookie_manager_add_cookie(
                        manager,
                        gCookie,
                        null,
                        cb,
                        null
                    )
                },
                produce = { res ->
                    val err = GErrorRef()
                    val result =
                        GtkLibs.webkit.webkit_cookie_manager_add_cookie_finish(manager, res, err)
                    if (result == 0) {
                        GtkLoop.errorMessage(err)
                            ?.let { AppLog.put("WebKitGTK cookie 写入失败: $it") }
                    }
                    result
                },
            )
            if (added != 1) ok = false
        }
        return ok
    }

    /** 取 [url] 域下全部 cookie, 拼 "k=v; k=v"; 无 cookie 返回 null (GTK 线程)。 */
    fun cookies(url: String, timeoutMs: Long = COOKIE_TIMEOUT): String? {
        val manager = cookieManager ?: return null
        return GtkLoop.driveAsync(
            timeoutMs,
            start = { cb ->
                GtkLibs.webkit.webkit_cookie_manager_get_cookies(manager, url, null, cb, null)
            },
            produce = { res ->
                val err = GErrorRef()
                val list = GtkLibs.webkit.webkit_cookie_manager_get_cookies_finish(manager, res, err)
                if (list == null) {
                    GtkLoop.errorMessage(err)?.let { AppLog.put("WebKitGTK cookie 读取失败: $it") }
                    null
                } else try {
                    val parts = ArrayList<String>()
                    var node: Pointer? = list
                    while (node != null) {
                        val cookie = node.getPointer(0)
                        if (cookie != null) {
                            val name = runCatching { GtkLibs.webkit.webkit_cookie_get_name(cookie) }
                                .getOrNull()
                            val value =
                                runCatching { GtkLibs.webkit.webkit_cookie_get_value(cookie) }
                                    .getOrNull()
                            if (!name.isNullOrEmpty()) parts.add("$name=$value")
                        }
                        node = GtkLibs.glib.g_list_next(node)
                    }
                    parts.joinToString("; ").takeIf { it.isNotBlank() }
                } finally {
                    GtkLibs.glib.g_list_free(list)
                }
            },
        )
    }
}
