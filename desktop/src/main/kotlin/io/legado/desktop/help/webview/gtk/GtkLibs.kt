package io.legado.desktop.help.webview.gtk

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByReference
import io.legado.app.constant.AppLog
import java.io.File

/**
 * WebKitGTK 4.1 + GTK3 + GLib/GObject + JavaScriptCore 的 JNA 直绑。
 *
 * 覆盖 libwebkit2gtk-4.1 全系列 (2.38 ~ 2.52, 系统发行版预装), 只依赖各库的**最老稳定符号**
 * (run_javascript / get_web_context 等在 2.40+ 被标记 deprecated 但 ABI 全版本保留)。
 * 唯一需要版本分支的是 get_context (2.42+ 新名) 与 webkit_javascript_result_unref
 * (2.40 移除), 见 [WebKit4_1.getWebContext] / [GtkLibs.javascriptResultUnref]。
 *
 * 加载策略: 按 soname dlopen (ldconfig 标准路径, 兼容 /usr/lib/x86_64-linux-gnu 与
 * /usr/lib64 布局); 失败再试常见绝对路径 (用户自定义安装目录)。
 */
internal object GtkLibs {

    /** 各发行版常见的 webkit2gtk-4.1 绝对路径 (soname 解析失败时的兜底)。 */
    private val webkitCandidates = listOf(
        "/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.1.so.0",
        "/usr/lib/aarch64-linux-gnu/libwebkit2gtk-4.1.so.0",
        "/usr/lib/arm-linux-gnueabihf/libwebkit2gtk-4.1.so.0",
        "/usr/lib64/libwebkit2gtk-4.1.so.0",
        "/usr/lib/libwebkit2gtk-4.1.so.0",
    )

    /** 已探测到的 webkit 库绝对路径; null = 未装。 */
    fun webkitPath(): String? {
        webkitCandidates.firstOrNull { File(it).exists() }?.let { return it }
        return null
    }

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailed: String? = null

    private fun <T : Library> load(path: String, cls: Class<T>): T =
        Native.load(path, cls)

    /** 首次成功加载全部库; 失败记原因。任意线程可调。 */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loadFailed?.let { return false }
        val webkitPath = webkitPath()
        if (webkitPath == null) {
            loadFailed = "libwebkit2gtk-4.1.so.0 未安装"
            return false
        }
        return runCatching {
            glib = load("libglib-2.0.so.0", Glib::class.java)
            gobject = load("libgobject-2.0.so.0", GObject::class.java)
            gtk = load("libgtk-3.so.0", Gtk3::class.java)
            gdk = load("libgdk-3.so.0", Gdk3::class.java)
            webkit = load(webkitPath, WebKit4_1::class.java)
            jsc = load("libjavascriptcoregtk-4.1.so.0", JSCore::class.java)
            loaded = true
            true
        }.onFailure { e ->
            loadFailed = "WebKitGTK 库加载失败: ${e.message}"
            AppLog.put(loadFailed!!, e)
        }.getOrDefault(false)
    }

    /** 库加载失败原因 (仅日志/引导用)。 */
    fun loadError(): String? = loadFailed

    lateinit var glib: Glib
        private set
    lateinit var gobject: GObject
        private set
    lateinit var gtk: Gtk3
        private set
    lateinit var gdk: Gdk3
        private set
    lateinit var webkit: WebKit4_1
        private set
    lateinit var jsc: JSCore
        private set

    /** GError 输出参数 (所有 finish/异步函数共用)。读 [message] 后应 [Glib.g_error_free]。 */
    class GErrorRef : ByReference(Native.POINTER_SIZE) {
        /** GError* 指针; null = 无错误。 */
        fun errorPointer(): Pointer? {
            val error = getPointer().getPointer(0)
            return if (error == Pointer.NULL) null else error
        }

        fun message(): String? = errorPointer()?.getString(0)
    }

    /** 动态探测 webkit_javascript_result_unref (2.40 前存在, 之后移除)。 */
    fun javascriptResultUnref(result: Pointer) {
        runCatching { webkit.webkit_javascript_result_unref(result) }
            .onFailure { /* 2.40+ 无此符号, opaque 类型由 WebKit 管理 */ }
    }

    // ==================== GLib (libglib-2.0.so.0) ====================

    interface Glib : Library {
        fun g_free(ptr: Pointer)
        fun g_error_free(error: Pointer)
        fun g_main_context_default(): Pointer
        fun g_main_context_iteration(context: Pointer?, mayBlock: Int): Int
        fun g_main_context_wakeup(context: Pointer?)
        fun g_idle_add(function: GSourceFunc, data: Pointer?): Int
        fun g_source_remove(source: Int): Int
        fun g_list_next(list: Pointer): Pointer
        fun g_list_free(list: Pointer)
        fun g_list_length(list: Pointer): Int
    }

    interface GSourceFunc : Callback {
        fun invoke(userData: Pointer?): Int
    }

    interface GDestroyNotify : Callback {
        fun invoke(data: Pointer?)
    }

    // ==================== GObject (libgobject-2.0.so.0) ====================

    interface GObject : Library {
        fun g_object_ref(instance: Pointer): Pointer
        fun g_object_unref(instance: Pointer)
        fun g_signal_connect(
            instance: Pointer,
            detailedSignal: String,
            cHandler: Callback,
            data: Pointer?,
        ): NativeLong

        fun g_signal_connect_data(
            instance: Pointer,
            detailedSignal: String,
            cHandler: Callback,
            data: Pointer?,
            destroyData: GDestroyNotify?,
            connectFlags: Int,
        ): NativeLong

        fun g_signal_handler_disconnect(instance: Pointer, handlerId: NativeLong)
    }

    // ==================== GTK3 (libgtk-3.so.0) ====================

    /** GtkWindowType: GTK_WINDOW_TOPLEVEL = 0 */
    const val GTK_WINDOW_TOPLEVEL = 0

    /** GtkOrientation: GTK_ORIENTATION_HORIZONTAL = 0, VERTICAL = 1 */
    const val GTK_ORIENTATION_VERTICAL = 1

    /** GtkIconSize: GTK_ICON_SIZE_MENU = 1, BUTTON = 4 */
    const val GTK_ICON_SIZE_MENU = 1

    /** PangoEllipsizeMode: PANGO_ELLIPSIZE_END = 3 */
    const val PANGO_ELLIPSIZE_END = 3

    /** GtkReliefStyle: GTK_RELIEF_NONE = 1 */
    const val GTK_RELIEF_NONE = 1

    /** GtkAlign: GTK_ALIGN_FILL = 0, START = 1, CENTER = 3 */
    const val GTK_ALIGN_FILL = 0
    const val GTK_ALIGN_START = 1
    const val GTK_ALIGN_CENTER = 3

    /** GtkPositionType (进度条): GTK_POS_TOP = 0 */
    const val GTK_WIN_POS_CENTER = 1

    /** GdkGravity (菜单弹出对齐, GTK3 稳定 ABI 值): NORTH_EAST = 3, SOUTH_EAST = 9 */
    const val GDK_GRAVITY_NORTH_EAST = 3
    const val GDK_GRAVITY_SOUTH_EAST = 9

    /** GtkResponseType (gtktypes.h, GTK3 稳定 ABI 值): 对话框返回值, 删除源确认用 (2026-08-08)。 */
    const val GTK_RESPONSE_YES = -8
    const val GTK_RESPONSE_NO = -9

    interface Gtk3 : Library {
        fun gtk_init_check(argc: Pointer?, argv: Pointer?): Int
        fun gtk_main_quit()
        fun gtk_window_new(type: Int): Pointer
        fun gtk_window_set_title(window: Pointer, title: String)
        fun gtk_window_set_default_size(window: Pointer, width: Int, height: Int)
        fun gtk_window_move(window: Pointer, x: Int, y: Int)
        fun gtk_window_resize(window: Pointer, width: Int, height: Int)
        fun gtk_window_get_screen(window: Pointer): Pointer
        fun gtk_window_set_position(window: Pointer, position: Int)
        fun gtk_window_present(window: Pointer)
        fun gtk_window_is_maximized(window: Pointer): Int
        fun gtk_window_maximize(window: Pointer)
        fun gtk_window_unmaximize(window: Pointer)
        fun gtk_widget_show(widget: Pointer)
        fun gtk_widget_hide(widget: Pointer)
        fun gtk_widget_destroy(widget: Pointer)
        fun gtk_widget_show_all(widget: Pointer)
        fun gtk_widget_realize(widget: Pointer)
        fun gtk_widget_set_size_request(widget: Pointer, width: Int, height: Int)
        fun gtk_widget_set_visible(widget: Pointer, visible: Int)
        fun gtk_widget_set_sensitive(widget: Pointer, sensitive: Int)
        fun gtk_container_add(container: Pointer, widget: Pointer)
        fun gtk_box_new(orientation: Int, spacing: Int): Pointer
        fun gtk_box_pack_start(box: Pointer, child: Pointer, expand: Int, fill: Int, padding: Int)
        fun gtk_box_pack_end(box: Pointer, child: Pointer, expand: Int, fill: Int, padding: Int)
        fun gtk_button_new_with_label(label: String): Pointer
        fun gtk_button_new_from_icon_name(iconName: String, size: Int): Pointer
        fun gtk_button_set_relief(button: Pointer, relief: Int)
        fun gtk_button_get_image(button: Pointer): Pointer
        fun gtk_image_set_from_icon_name(image: Pointer, iconName: String, size: Int)

        // --- GtkMenu (弹出菜单; gtk_menu_popup_at_widget 需 GTK >= 3.22, webkit2gtk-4.1 满足) ---
        fun gtk_menu_new(): Pointer
        fun gtk_menu_item_new_with_label(label: String): Pointer
        fun gtk_menu_shell_append(menuShell: Pointer, child: Pointer)
        fun gtk_menu_attach_to_widget(
            menu: Pointer,
            attachWidget: Pointer,
            detacher: GDestroyNotify?
        )

        fun gtk_menu_popup_at_widget(
            menu: Pointer,
            widget: Pointer,
            widgetAnchor: Int,
            menuAnchor: Int,
            triggerEvent: Pointer?,
        )
        fun gtk_label_new(text: String): Pointer
        fun gtk_label_set_text(label: Pointer, text: String)
        fun gtk_label_set_ellipsize(label: Pointer, mode: Int)
        fun gtk_label_set_max_width_chars(label: Pointer, nChars: Int)
        fun gtk_progress_bar_new(): Pointer
        fun gtk_progress_bar_set_fraction(bar: Pointer, fraction: Double)
        fun gtk_widget_set_hexpand(widget: Pointer, expand: Int)
        fun gtk_widget_set_vexpand(widget: Pointer, expand: Int)
        fun gtk_widget_set_halign(widget: Pointer, align: Int)
        fun gtk_widget_set_valign(widget: Pointer, align: Int)
        fun gtk_widget_get_allocated_height(widget: Pointer): Int
        fun gtk_widget_get_allocated_width(widget: Pointer): Int

        // --- GtkDialog (删除源确认, 2026-08-08; 全非 varargs 组合, 避开 JNA 对
        // C varargs (如 gtk_message_dialog_new/gtk_dialog_add_button) 的 ABI 坑) ---
        fun gtk_dialog_new(): Pointer
        fun gtk_dialog_run(dialog: Pointer): Int
        fun gtk_dialog_get_content_area(dialog: Pointer): Pointer
        fun gtk_dialog_add_action_widget(dialog: Pointer, child: Pointer, responseId: Int)
        fun gtk_window_set_transient_for(window: Pointer, parent: Pointer)
        fun gtk_window_set_modal(window: Pointer, modal: Int)
        fun gtk_window_set_destroy_with_parent(window: Pointer, setting: Int)
    }

    // ==================== GDK3 (libgdk-3.so.0) ====================

    interface Gdk3 : Library {
        fun gdk_screen_get_height(screen: Pointer): Int
        fun gdk_screen_get_width(screen: Pointer): Int
    }

    // ==================== WebKitGTK 4.1 (libwebkit2gtk-4.1.so.0) ====================

    /** WebKitLoadEvent: STARTED=0, REDIRECTED=1, COMMITTED=2, FINISHED=3 */
    const val WEBKIT_LOAD_STARTED = 0
    const val WEBKIT_LOAD_REDIRECTED = 1
    const val WEBKIT_LOAD_COMMITTED = 2
    const val WEBKIT_LOAD_FINISHED = 3

    interface WebKit4_1 : Library {

        // --- WebKitWebView ---
        fun webkit_web_view_new(): Pointer
        fun webkit_web_view_load_uri(view: Pointer, uri: String)
        fun webkit_web_view_load_html(view: Pointer, content: String, baseUri: String?)
        fun webkit_web_view_load_plain_text(view: Pointer, text: String)
        fun webkit_web_view_run_javascript(
            view: Pointer,
            script: String,
            cancellable: Pointer?,
            callback: AsyncReadyCallback?,
            userData: Pointer?,
        )

        fun webkit_web_view_run_javascript_finish(
            view: Pointer,
            res: Pointer,
            error: GErrorRef?,
        ): Pointer

        fun webkit_web_view_reload(view: Pointer)
        fun webkit_web_view_stop_loading(view: Pointer)
        fun webkit_web_view_go_back(view: Pointer)
        fun webkit_web_view_go_forward(view: Pointer)
        fun webkit_web_view_can_go_back(view: Pointer): Int
        fun webkit_web_view_can_go_forward(view: Pointer): Int
        fun webkit_web_view_get_uri(view: Pointer): String?
        fun webkit_web_view_get_title(view: Pointer): String?
        fun webkit_web_view_get_estimated_load_progress(view: Pointer): Double
        fun webkit_web_view_get_settings(view: Pointer): Pointer
        fun webkit_web_view_get_web_context(view: Pointer): Pointer

        /** 2.42+ 新名 (老名 [webkit_web_view_get_web_context] 2.40 起 deprecated, 2.38 仍无此符号)。 */
        fun webkit_web_view_get_context(view: Pointer): Pointer
        fun webkit_web_view_get_user_content_manager(view: Pointer): Pointer

        // --- WebKitWebContext ---
        fun webkit_web_context_get_cookie_manager(context: Pointer): Pointer

        // --- WebKitSettings ---
        fun webkit_settings_set_user_agent(settings: Pointer, userAgent: String)
        fun webkit_settings_set_javascript_enabled(settings: Pointer, enabled: Int)
        fun webkit_settings_set_enable_developer_extras(settings: Pointer, enabled: Int)

        // --- WebKitCookieManager ---
        fun webkit_cookie_manager_get_cookies(
            manager: Pointer,
            uri: String,
            cancellable: Pointer?,
            callback: AsyncReadyCallback?,
            userData: Pointer?,
        )

        fun webkit_cookie_manager_get_cookies_finish(
            manager: Pointer,
            res: Pointer,
            error: GErrorRef?,
        ): Pointer

        fun webkit_cookie_manager_add_cookie(
            manager: Pointer,
            cookie: Pointer,
            cancellable: Pointer?,
            callback: AsyncReadyCallback?,
            userData: Pointer?,
        )

        fun webkit_cookie_manager_add_cookie_finish(
            manager: Pointer,
            res: Pointer,
            error: GErrorRef?,
        ): Int

        fun webkit_cookie_manager_set_accept_policy(manager: Pointer, policy: Int)
        fun webkit_cookie_manager_set_persistent_storage(
            manager: Pointer,
            filename: String,
            storageManager: Int,
        )

        // --- WebKitCookie (2.40 起 opaque, 仅用 getter) ---
        fun webkit_cookie_new(
            name: String,
            value: String,
            domain: String,
            path: String,
            expires: Double,
        ): Pointer

        fun webkit_cookie_get_name(cookie: Pointer): String
        fun webkit_cookie_get_value(cookie: Pointer): String

        // --- WebKitWebResource ---
        fun webkit_web_resource_get_uri(resource: Pointer): String?

        // --- WebKitJavascriptResult (2.40 起 opaque) ---
        fun webkit_javascript_result_get_js_value(result: Pointer): Pointer

        /** 仅 2.40 前存在; 经 [GtkLibs.javascriptResultUnref] 动态调用。 */
        fun webkit_javascript_result_unref(result: Pointer)
    }

    interface AsyncReadyCallback : Callback {
        fun invoke(source: Pointer?, res: Pointer, userData: Pointer?)
    }

    // ==================== JavaScriptCoreGTK (libjavascriptcoregtk-4.1.so.0) ====================

    interface JSCore : Library {
        fun jsc_value_to_string(value: Pointer): String?
    }

    // ==================== 信号回调 (GObject GCallback) ====================

    /** WebKitWebView::load-changed (WebKitWebView*, WebKitLoadEvent, gpointer) */
    interface LoadChangedCallback : Callback {
        fun invoke(view: Pointer, loadEvent: Int, userData: Pointer?)
    }

    /** WebKitWebView::resource-load-started (WebKitWebView*, WebKitWebResource*, WebKitURIRequest*, gpointer) */
    interface ResourceLoadStartedCallback : Callback {
        fun invoke(view: Pointer, resource: Pointer, request: Pointer, userData: Pointer?)
    }

    /** GObject notify::* (GObject*, GParamSpec*, gpointer) */
    interface NotifyCallback : Callback {
        fun invoke(obj: Pointer, pspec: Pointer, userData: Pointer?)
    }

    /** WebKitWebView::close → gboolean */
    interface WebViewCloseCallback : Callback {
        fun invoke(view: Pointer, userData: Pointer?): Int
    }

    /** GtkWidget::delete-event → gboolean */
    interface DeleteEventCallback : Callback {
        fun invoke(widget: Pointer, event: Pointer, userData: Pointer?): Int
    }

    /** WebKitWebView::fullscreen-changed (WebKitWebView*, gboolean, gpointer) — 页面元素全屏状态。 */
    interface FullscreenChangedCallback : Callback {
        fun invoke(view: Pointer, fullscreen: Int, userData: Pointer?)
    }

    /** GtkButton::clicked (GtkButton*, gpointer) */
    interface ClickedCallback : Callback {
        fun invoke(button: Pointer, userData: Pointer?)
    }

    /** GtkMenuItem::activate (GtkMenuItem*, gpointer) — 菜单项点击。 */
    interface MenuItemActivateCallback : Callback {
        fun invoke(menuItem: Pointer, userData: Pointer?)
    }
}
