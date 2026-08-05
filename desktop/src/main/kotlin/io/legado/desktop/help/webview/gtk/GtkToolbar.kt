package io.legado.desktop.help.webview.gtk

import com.sun.jna.Pointer
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_ICON_SIZE_MENU
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_ORIENTATION_VERTICAL
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_RELIEF_NONE

/**
 * GTK3 工具栏: 返回/前进/刷新/确定/关闭 按钮 + 标题标签 + 细进度条。
 *
 * 行为对照 Windows [io.legado.desktop.help.webview.win.WebView2Toolbar] (即 shared
 * `WebViewRoute` 标题栏 + 进度条语义)。所有控件创建与更新必须在 GTK 线程 (由引擎保证)。
 */
internal class GtkToolbar(
    override var onAction: ((ToolbarAction) -> Unit)?,
) : BrowserToolbar {

    /** 工具栏行 (HBox)。 */
    val bar: Pointer

    /** 细进度条 (单独一行, 加载中显示, 完成隐藏)。 */
    val progress: Pointer

    private val backBtn: Pointer
    private val forwardBtn: Pointer
    private val refreshBtn: Pointer
    private val okBtn: Pointer

    /** JNA 回调强引用 (GC 后 GTK 调用即崩)。 */
    private val clickCallbacks = ArrayList<GtkLibs.ClickedCallback>()

    init {
        val gtk = GtkLibs.gtk
        val vertical = gtk.gtk_box_new(GTK_ORIENTATION_VERTICAL, 0)
        bar = gtk.gtk_box_new(0 /* HORIZONTAL */, 2)

        backBtn = navButton("go-previous", ToolbarAction.BACK)
        forwardBtn = navButton("go-next", ToolbarAction.FORWARD)
        refreshBtn = navButton("view-refresh", ToolbarAction.REFRESH)
        okBtn = navButton("gtk-ok", ToolbarAction.OK)

        gtk.gtk_box_pack_start(bar, backBtn, 0, 0, 2)
        gtk.gtk_box_pack_start(bar, forwardBtn, 0, 0, 2)
        gtk.gtk_box_pack_start(bar, refreshBtn, 0, 0, 2)
        gtk.gtk_box_pack_start(bar, okBtn, 0, 0, 2)

        progress = gtk.gtk_progress_bar_new()
        gtk.gtk_progress_bar_set_fraction(progress, 0.0)
        // 细条: 固定高度 + 去掉边框文字
        gtk.gtk_widget_set_size_request(progress, -1, 3)

        gtk.gtk_box_pack_start(vertical, bar, 0, 0, 0)
        gtk.gtk_box_pack_start(vertical, progress, 0, 0, 0)
    }

    private fun navButton(iconName: String, action: ToolbarAction): Pointer {
        val gtk = GtkLibs.gtk
        val btn = gtk.gtk_button_new_from_icon_name(iconName, GTK_ICON_SIZE_MENU)
        gtk.gtk_button_set_relief(btn, GTK_RELIEF_NONE)
        val cb = object : GtkLibs.ClickedCallback {
            override fun invoke(button: Pointer, userData: Pointer?) {
                onAction?.invoke(action)
            }
        }
        clickCallbacks.add(cb)
        GtkLibs.gobject.g_signal_connect(btn, "clicked", cb, null)
        return btn
    }

    override fun setCanNavigate(back: Boolean, forward: Boolean) {
        GtkLibs.gtk.gtk_widget_set_sensitive(backBtn, if (back) 1 else 0)
        GtkLibs.gtk.gtk_widget_set_sensitive(forwardBtn, if (forward) 1 else 0)
    }

    /** 加载状态: 进度条显示/隐藏 (对齐 RefreshProgressBar 100 隐藏)。 */
    override fun setLoading(loading: Boolean) {
        GtkLibs.gtk.gtk_widget_set_visible(progress, if (loading) 1 else 0)
    }

    /** 进度 0.0~1.0; 只更新条值不控制可见性 (可见性由加载状态管理)。 */
    fun setProgressFraction(fraction: Double?) {
        if (fraction != null) {
            GtkLibs.gtk.gtk_progress_bar_set_fraction(progress, fraction.coerceIn(0.0, 1.0))
        }
    }

    /** 进度 0.0~1.0; null 隐藏。 */
    fun setProgress(fraction: Double?) {
        if (fraction == null) {
            setLoading(false)
        } else {
            setProgressFraction(fraction)
            setLoading(true)
        }
    }
}
