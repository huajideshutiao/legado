package io.legado.desktop.help.webview.gtk

import com.sun.jna.Pointer
import io.legado.app.help.RssToolbarActions
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.gtk.GtkLibs.GDK_GRAVITY_NORTH_EAST
import io.legado.desktop.help.webview.gtk.GtkLibs.GDK_GRAVITY_SOUTH_EAST
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_ICON_SIZE_MENU
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_ORIENTATION_VERTICAL
import io.legado.desktop.help.webview.gtk.GtkLibs.GTK_RELIEF_NONE

/**
 * GTK3 工具栏: 返回/前进/刷新 (+RSS 收藏/朗读/分享/登录) + 确定 + 菜单按钮 + 细进度条。
 *
 * 行为对照 Windows [io.legado.desktop.help.webview.win.WebView2Toolbar] (2026-08-07 用户
 * 拍板三端一致):
 * - **菜单按钮 pack_end 贴窗口最右**, 左组按钮 (返回/前进/刷新/RSS/确定) 靠左;
 * - **菜单仅 浏览器打开/拷贝 URL** (无刷新项, 工具栏已有刷新按钮)
 *   + sourceKey 非空时额外显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08);
 * - RSS 模式 (rssActions 非空): 收藏/朗读/分享/登录 按钮, 插在刷新之后、确定之前,
 *   顺序同 Windows; 动作经 [onAction] 回引擎分发。
 *
 * 图标名 (均为 Adwaita 主题提供): go-previous/go-next/view-refresh/gtk-ok/open-menu/
 * starred/non-starred/audio-x-generic/emblem-shared/avatar-default。其中 starred/non-starred
 * 是 legacy 全彩名 (Adwaita 另有 -symbolic 变体), 若自定义主题缺图 GTK 显示空白。
 *
 * 线程: 所有控件创建与更新在 GTK 线程 (由引擎保证); [setStarred] 例外 —— 收藏态由
 * shared 侧 [RssToolbarActions.onStarChanged] 反推, 不保证在 GTK 线程, 经 [GtkLoop.post] 归队。
 */
internal class GtkToolbar(
    override var onAction: ((ToolbarAction) -> Unit)?,
    private val rssActions: RssToolbarActions? = null,
    private val showOk: Boolean = true,
    /** 书源 key (cookieTag): 非空时溢出菜单显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08)。 */
    private val sourceKey: String? = null,
) : BrowserToolbar {

    /** 工具栏行 (HBox)。 */
    val bar: Pointer

    /** 细进度条 (单独一行, 加载中显示, 完成隐藏)。 */
    val progress: Pointer

    private val backBtn: Pointer
    private val forwardBtn: Pointer
    private val refreshBtn: Pointer
    private val okBtn: Pointer?
    private val menuBtn: Pointer

    /** RSS 收藏按钮 (持有可换图标句柄); 仅 RSS 模式创建。 */
    private val starButton: IconButton?

    /** 窗口销毁后置位: 队列里残留的状态任务直接跳过, 防悬垂句柄 (2026-08-07)。 */
    @Volatile
    private var disposed = false

    /** JNA 回调强引用 (GC 后 GTK 调用即崩; ClickedCallback/MenuItemActivateCallback 共同父类型)。 */
    private val callbacks = ArrayList<com.sun.jna.Callback>()

    /** 菜单项回调 (每次弹出新建菜单, 旧菜单销毁后旧回调不再需要, 弹出前清理防无界增长)。 */
    private val menuCallbacks = ArrayList<GtkLibs.MenuItemActivateCallback>()

    init {
        val gtk = GtkLibs.gtk
        val vertical = gtk.gtk_box_new(GTK_ORIENTATION_VERTICAL, 0)
        bar = gtk.gtk_box_new(0 /* HORIZONTAL */, 2)

        backBtn = navButton("go-previous", ToolbarAction.BACK)
        forwardBtn = navButton("go-next", ToolbarAction.FORWARD)
        refreshBtn = navButton("view-refresh", ToolbarAction.REFRESH)
        // RSS 按钮组 (2026-08-07): 插在刷新之后、确定之前, 顺序同 Windows
        starButton = rssActions?.let {
            iconButton(if (it.starred) "starred" else "non-starred", ToolbarAction.STAR_TOGGLE)
        }
        val readAloudBtn =
            rssActions?.let { navButton("audio-x-generic", ToolbarAction.READ_ALOUD) }
        val shareBtn = rssActions?.let { navButton("emblem-shared", ToolbarAction.SHARE) }
        val loginBtn = rssActions?.let { navButton("avatar-default", ToolbarAction.LOGIN) }
        // 确定按钮仅登录/验证模式显示 (2026-08-07 三端对齐 Windows: showOk = isLogin || saveResult)
        okBtn = if (showOk) navButton("gtk-ok", ToolbarAction.OK) else null
        menuBtn = menuButton()

        gtk.gtk_box_pack_start(bar, backBtn, 0, 0, 2)
        gtk.gtk_box_pack_start(bar, forwardBtn, 0, 0, 2)
        gtk.gtk_box_pack_start(bar, refreshBtn, 0, 0, 2)
        starButton?.let { gtk.gtk_box_pack_start(bar, it.button, 0, 0, 2) }
        readAloudBtn?.let { gtk.gtk_box_pack_start(bar, it, 0, 0, 2) }
        shareBtn?.let { gtk.gtk_box_pack_start(bar, it, 0, 0, 2) }
        loginBtn?.let { gtk.gtk_box_pack_start(bar, it, 0, 0, 2) }
        okBtn?.let { gtk.gtk_box_pack_start(bar, it, 0, 0, 2) }
        // 菜单按钮右对齐窗口右缘 (2026-08-07 用户拍板)
        gtk.gtk_box_pack_end(bar, menuBtn, 0, 0, 2)

        progress = gtk.gtk_progress_bar_new()
        gtk.gtk_progress_bar_set_fraction(progress, 0.0)
        // 细条: 固定高度 + 去掉边框文字
        gtk.gtk_widget_set_size_request(progress, -1, 3)

        gtk.gtk_box_pack_start(vertical, bar, 0, 0, 0)
        gtk.gtk_box_pack_start(vertical, progress, 0, 0, 0)
    }

    /** 普通图标按钮 (返回/前进/刷新/RSS 等)。 */
    private fun navButton(iconName: String, action: ToolbarAction): Pointer =
        iconButton(iconName, action).button

    /**
     * 可动态换图标的图标按钮 (收藏星切换用): 持有 gtk_button_new_from_icon_name 内部
     * 创建的 GtkImage, [setStarred] 经 gtk_image_set_from_icon_name 换图。
     */
    private fun iconButton(iconName: String, action: ToolbarAction): IconButton {
        val gtk = GtkLibs.gtk
        val btn = gtk.gtk_button_new_from_icon_name(iconName, GTK_ICON_SIZE_MENU)
        gtk.gtk_button_set_relief(btn, GTK_RELIEF_NONE)
        val image = gtk.gtk_button_get_image(btn)
        val cb = object : GtkLibs.ClickedCallback {
            override fun invoke(button: Pointer, userData: Pointer?) {
                onAction?.invoke(action)
            }
        }
        callbacks.add(cb)
        GtkLibs.gobject.g_signal_connect(btn, "clicked", cb, null)
        return IconButton(btn, image)
    }

    /** 菜单按钮 (open-menu): 点击直接弹 [showMenu], 不分发 ToolbarAction.MENU (同 Windows)。 */
    private fun menuButton(): Pointer {
        val gtk = GtkLibs.gtk
        val btn = gtk.gtk_button_new_from_icon_name("open-menu", GTK_ICON_SIZE_MENU)
        gtk.gtk_button_set_relief(btn, GTK_RELIEF_NONE)
        val cb = object : GtkLibs.ClickedCallback {
            override fun invoke(button: Pointer, userData: Pointer?) {
                showMenu()
            }
        }
        callbacks.add(cb)
        GtkLibs.gobject.g_signal_connect(btn, "clicked", cb, null)
        return btn
    }

    /**
     * 弹出菜单 (GTK 线程, clicked 信号内): 浏览器打开/拷贝 URL (2026-08-07: 无刷新项)
     *  + 禁用源/删除源 (sourceKey 非空时显示, 对照原版 web_view.xml, 2026-08-08)。
     * attach_to_widget 把菜单生命周期绑到按钮, 按钮销毁时菜单连带销毁, 无需手动释放。
     */
    private fun showMenu() {
        val gtk = GtkLibs.gtk
        // 旧菜单已随上次弹出销毁, 旧回调不再需要 (防无界增长)
        menuCallbacks.clear()
        val menu = gtk.gtk_menu_new()
        addMenuItem(menu, "浏览器打开", ToolbarAction.OPEN_IN_BROWSER)
        addMenuItem(menu, "拷贝 URL", ToolbarAction.COPY_URL)
        if (!sourceKey.isNullOrBlank()) {
            addMenuItem(menu, "禁用源", ToolbarAction.DISABLE_SOURCE)
            addMenuItem(menu, "删除源", ToolbarAction.DELETE_SOURCE)
        }
        // 菜单项默认不可见, 弹出前需 show_all; GTK >= 3.22 的 gtk_menu_popup_at_widget
        gtk.gtk_widget_show_all(menu)
        gtk.gtk_menu_attach_to_widget(menu, menuBtn, null)
        // 菜单右上角对齐按钮右下角: 菜单贴窗口右缘弹出不溢出 (同 Windows 右对齐语义)
        gtk.gtk_menu_popup_at_widget(
            menu, menuBtn, GDK_GRAVITY_SOUTH_EAST, GDK_GRAVITY_NORTH_EAST, null,
        )
    }

    private fun addMenuItem(menu: Pointer, label: String, action: ToolbarAction) {
        val gtk = GtkLibs.gtk
        val item = gtk.gtk_menu_item_new_with_label(label)
        gtk.gtk_menu_shell_append(menu, item)
        val cb = object : GtkLibs.MenuItemActivateCallback {
            override fun invoke(menuItem: Pointer, userData: Pointer?) {
                onAction?.invoke(action)
            }
        }
        menuCallbacks.add(cb)
        GtkLibs.gobject.g_signal_connect(item, "activate", cb, null)
    }

    override fun setCanNavigate(back: Boolean, forward: Boolean) {
        GtkLibs.gtk.gtk_widget_set_sensitive(backBtn, if (back) 1 else 0)
        GtkLibs.gtk.gtk_widget_set_sensitive(forwardBtn, if (forward) 1 else 0)
    }

    /** 加载状态: 进度条显示/隐藏 (对齐 RefreshProgressBar 100 隐藏)。 */
    override fun setLoading(loading: Boolean) {
        GtkLibs.gtk.gtk_widget_set_visible(progress, if (loading) 1 else 0)
    }

    /** RSS 收藏态: 星图标实心/空心切换 (2026-08-07, 同 Windows)。 */
    override fun setStarred(starred: Boolean) {
        val star = starButton ?: return
        // 收藏态经 shared onStarChanged 反推, 任意线程可调 → 归队 GTK 线程执行
        GtkLoop.post {
            // 销毁后入队的任务直接跳过 (防悬垂 GtkImage)
            if (disposed) return@post
            GtkLibs.gtk.gtk_image_set_from_icon_name(
                star.image,
                if (starred) "starred" else "non-starred",
                GTK_ICON_SIZE_MENU,
            )
        }
    }

    /** 窗口销毁时由引擎调用: 置位后 setStarred 队列任务不再触碰已释放控件。 */
    override fun dispose() {
        disposed = true
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

    /** 图标按钮 (button + 内部 GtkImage 句柄)。 */
    private class IconButton(val button: Pointer, val image: Pointer)
}
