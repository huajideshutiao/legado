package io.legado.desktop.help.webview.mac

import com.sun.jna.Pointer
import io.legado.app.help.RssToolbarActions
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.mac.ObjC.NSRect
import io.legado.desktop.help.webview.mac.ObjC.ns
import io.legado.desktop.help.webview.mac.ObjC.ptr
import io.legado.desktop.help.webview.mac.ObjC.sel
import io.legado.desktop.help.webview.mac.ObjC.void

/**
 * AppKit 工具栏 (NSView 手工 frame 布局): 返回/前进/刷新/确定 (+RSS 组 收藏/朗读/分享/登录)
 * + 菜单按钮 (⋯) + 细进度条。
 *
 * 行为对照 Windows [io.legado.desktop.help.webview.win.WebView2Toolbar] (2026-08-07 三端一致):
 * - 按钮全部图标 (Mac 保持符号字符风格): ← → ⟳ ✓ ★/☆ ♪ ⤴ ✉ ⋯;
 * - 菜单按钮右对齐窗口右缘 (NSViewMinXMargin), 左组按钮靠左顺序排列 (NSViewMaxXMargin);
 * - 菜单无刷新项 (工具栏已有刷新按钮), 仅 浏览器打开/拷贝 URL;
 *   + sourceKey 非空时额外显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08);
 * - RSS 模式 (rssActions 非空): 额外显示 收藏/朗读/分享/登录 按钮, 动作回调回 shared,
 *   星收藏态经 [setStarred] 由 shared 侧 onStarChanged 反推更新。
 *
 * 按钮经 target-action 回调 (动态子类); 菜单按钮 action 回调内直接
 * [NSMenu popUpMenuPositioningItem:atLocation:inView:] 弹出 (macOS 10.6+, 左键可用);
 * 窗口 resize 时用 autoresizingMask 自适应。
 * **方法必须在主线程 (EDT) 调用** ([setStarred] 除外: 任意线程, 内部经 [CocoaLoop] 归队)。
 */
internal class MacToolbar(
    override var onAction: ((ToolbarAction) -> Unit)?,
    private val rssActions: RssToolbarActions? = null,
    private val showOk: Boolean = true,
    /** 书源 key (cookieTag): 非空时溢出菜单显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08)。 */
    private val sourceKey: String? = null,
) : BrowserToolbar {

    /** 顶层容器 NSView。 */
    val view: Pointer

    /** 细进度条 NSProgressIndicator。 */
    val progress: Pointer

    private val backBtn: Pointer
    private val forwardBtn: Pointer
    private val refreshBtn: Pointer
    private val okBtn: Pointer?

    /** RSS 收藏按钮 (仅 rssActions 非空时存在)。 */
    private val starBtn: Pointer?

    /** 窗口销毁后置位: 队列里残留的状态任务直接跳过, 防悬垂句柄 (2026-08-07)。 */
    @Volatile
    private var disposed = false

    /** 菜单按钮 (⋯, 右对齐窗口右缘)。 */
    private val menuBtn: Pointer

    /** target-action 动态子类实例 (NSControl target 是弱引用, 必须由我方持有)。 */
    private val target: Pointer

    /** sender 指针 → 动作映射 (按钮)。 */
    private val actionBySender = HashMap<Long, ToolbarAction>()

    /** sender 指针 → 动作映射 (菜单项)。 */
    private val menuActionBySender = HashMap<Long, ToolbarAction>()

    companion object {
        private const val TOOLBAR_H = 32.0
        private const val PROGRESS_H = 3.0

        private const val BTN_W = 32.0
        private const val BTN_H = 28.0
        private const val BTN_Y = 2.0
        private const val GAP = 4.0
        private const val MARGIN = 8.0

        // autoresizingMask
        private const val NSViewMinXMargin = 1
        private const val NSViewWidthSizable = 2
        private const val NSViewMaxXMargin = 4
        private const val NSViewMinYMargin = 8
        private const val NSViewHeightSizable = 16
        private const val NSViewMaxYMargin = 32
    }

    init {
        // 必须在主线程 (由引擎保证)
        val cls = ObjC.cls("NSView")
        view = ptr(cls, "alloc")!!
        ptr(view, "initWithFrame:", frame(0.0, 0.0, 1000.0, TOOLBAR_H))!!

        target = ObjC.newDelegateClass(
            "LegadoToolbarTarget",
            listOf(
                "toolbarAction:" to "v@:@",
                "menuAction:" to "v@:@",
            ),
        ) { method, _, args ->
            val sender = args.firstOrNull() as? Pointer
            if (sender != null) {
                val key = Pointer.nativeValue(sender)
                when (method) {
                    "menuAction:" -> {
                        // 菜单按钮自身 → 弹菜单; 其余 sender (菜单项) → 分发动作
                        if (key == Pointer.nativeValue(menuBtn)) {
                            showMenu()
                        } else {
                            menuActionBySender[key]?.let { onAction?.invoke(it) }
                        }
                    }

                    else -> actionBySender[key]?.let { onAction?.invoke(it) }
                }
            }
        }

        // 左组按钮靠左顺序排列 (x 间距 = BTN_W + GAP), 全部 NSViewMaxXMargin 跟随左缘
        backBtn = button("←", 8.0, BTN_Y, BTN_W, BTN_H, ToolbarAction.BACK, NSViewMaxXMargin)
        forwardBtn = button("→", 44.0, BTN_Y, BTN_W, BTN_H, ToolbarAction.FORWARD, NSViewMaxXMargin)
        refreshBtn = button("⟳", 80.0, BTN_Y, BTN_W, BTN_H, ToolbarAction.REFRESH, NSViewMaxXMargin)
        // 确定按钮仅登录/验证模式显示 (2026-08-07 三端对齐 Windows: showOk = isLogin || saveResult)
        okBtn = if (showOk) {
            button("\u2713", 116.0, BTN_Y, BTN_W, BTN_H, ToolbarAction.OK, NSViewMaxXMargin)
        } else null

        // RSS 模式按钮组 (2026-08-07: RSS 阅读去页面外壳, 功能移入窗口工具栏);
        // 无确定按钮时 RSS 组从 116 起排, 不留空位
        val rssStartX = if (showOk) 152.0 else 116.0
        starBtn = if (rssActions != null) {
            button(
                if (rssActions.starred) "\u2605" else "\u2606", rssStartX, BTN_Y, BTN_W, BTN_H,
                ToolbarAction.STAR_TOGGLE, NSViewMaxXMargin,
            )
        } else null
        if (rssActions != null) {
            button(
                "\u266A",
                rssStartX + 36.0,
                BTN_Y,
                BTN_W,
                BTN_H,
                ToolbarAction.READ_ALOUD,
                NSViewMaxXMargin
            )
            button(
                "\u2934",
                rssStartX + 72.0,
                BTN_Y,
                BTN_W,
                BTN_H,
                ToolbarAction.SHARE,
                NSViewMaxXMargin
            )
            button(
                "\u2709",
                rssStartX + 108.0,
                BTN_Y,
                BTN_W,
                BTN_H,
                ToolbarAction.LOGIN,
                NSViewMaxXMargin
            )
        }

        // 菜单按钮 (⋯) 右对齐窗口右缘 (假想宽 1000: 1000 - MARGIN - BTN_W = 960)
        menuBtn = menuButton()

        progress = ptr(ObjC.cls("NSProgressIndicator"), "alloc")!!
        ptr(progress, "initWithFrame:", frame(0.0, 0.0, 1000.0, PROGRESS_H))!!
        void(progress, "setStyle", 0L) // bar
        void(progress, "setIndeterminate", 0L)
        void(progress, "setDisplayedWhenStopped", 0L)
        void(progress, "setDoubleValue", 0.0)
        void(progress, "setHidden", 1L)
        void(progress, "setAutoresizingMask", NSViewWidthSizable.toLong())
        void(view, "addSubview:", progress)
    }

    private fun button(
        title: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        action: ToolbarAction,
        mask: Int,
    ): Pointer {
        val btn = ptr(
            ObjC.cls("NSButton"),
            "buttonWithTitle:target:action:",
            ns(title), target, sel("toolbarAction:"),
        )!!
        frameView(btn, x, y, w, h, mask)
        actionBySender[Pointer.nativeValue(btn)] = action
        void(view, "addSubview:", btn)
        return btn
    }

    /** 菜单按钮 (⋯): 右对齐窗口右缘, action 回调内直接弹 NSMenu (不经过 [onAction])。 */
    private fun menuButton(): Pointer {
        val btn = ptr(
            ObjC.cls("NSButton"),
            "buttonWithTitle:target:action:",
            ns("\u22EF"), target, sel("menuAction:"),
        )!!
        frameView(btn, 1000.0 - MARGIN - BTN_W, BTN_Y, BTN_W, BTN_H, NSViewMinXMargin)
        void(view, "addSubview:", btn)
        return btn
    }

    /** 溢出菜单: 菜单按钮下方弹出, 浏览器打开/拷贝 URL (2026-08-07: 无刷新项)
     *  + 禁用源/删除源 (sourceKey 非空时显示, 对照原版 web_view.xml, 2026-08-08)。 */
    private fun showMenu() {
        // 旧菜单已销毁 (popUp 同步返回后已 release), 旧 sender → 动作映射不再需要 (防无界增长)
        menuActionBySender.clear()
        val menu = ptr(ObjC.cls("NSMenu"), "alloc")!!
        ptr(menu, "initWithTitle:", ns(""))!!
        void(menu, "addItem:", menuItem("浏览器打开", ToolbarAction.OPEN_IN_BROWSER))
        void(menu, "addItem:", menuItem("拷贝 URL", ToolbarAction.COPY_URL))
        if (!sourceKey.isNullOrBlank()) {
            void(menu, "addItem:", menuItem("禁用源", ToolbarAction.DISABLE_SOURCE))
            void(menu, "addItem:", menuItem("删除源", ToolbarAction.DELETE_SOURCE))
        }
        // 同步跟踪模式弹出 (返回时菜单已关闭); 位置用按钮自身坐标系 (0,0)=左下角下方 4px,
        // 规避 NSRect 返回值的 stret 调用 (ObjC.kt 注释), resize 后仍自动跟随按钮
        void(
            menu,
            "popUpMenuPositioningItem:atLocation:inView:",
            null, ObjC.point(0.0, -4.0), menuBtn,
        )
        void(menu, "release") // alloc/init 持有, 用完显式释放 (对照 ObjC.kt 内存管理)
    }

    /** 菜单项 (autoreleased 类方法创建, 由 menu 持有; 点击经 target-action 分发 [onAction])。 */
    private fun menuItem(title: String, action: ToolbarAction): Pointer {
        val item = ObjC.clsPtr(
            ObjC.cls("NSMenuItem"),
            "menuItemWithTitle:action:keyEquivalent:",
            ns(title), sel("menuAction:"), ns(""),
        )!!
        void(item, "setTarget:", target)
        menuActionBySender[Pointer.nativeValue(item)] = action
        return item
    }

    private fun frameView(widget: Pointer, x: Double, y: Double, w: Double, h: Double, mask: Int) {
        // setFrame: 参数是 32 字节 NSRect 需要 stret; 拆成 16 字节的 origin+size 两个调用
        void(widget, "setFrameOrigin:", ObjC.point(x, y))
        void(widget, "setFrameSize:", ObjC.size(w, h))
        void(widget, "setAutoresizingMask", mask.toLong())
    }

    private fun frame(x: Double, y: Double, w: Double, h: Double): NSRect =
        NSRect().apply {
            this.origin.x = x; this.origin.y = y
            this.size.width = w; this.size.height = h
        }

    override fun setCanNavigate(back: Boolean, forward: Boolean) {
        void(backBtn, "setEnabled:", if (back) 1L else 0L)
        void(forwardBtn, "setEnabled:", if (forward) 1L else 0L)
    }

    /** 加载状态: 进度条显示/隐藏。 */
    override fun setLoading(loading: Boolean) {
        void(progress, "setHidden", if (loading) 0L else 1L)
    }

    /** RSS 收藏态: 星图标 ★/☆ 切换 (任意线程可调, 经 [CocoaLoop] 归队到 EDT)。 */
    override fun setStarred(starred: Boolean) {
        val btn = starBtn ?: return
        CocoaLoop.post {
            // 销毁后入队的任务直接跳过 (防悬垂 NSButton)
            if (disposed) return@post
            void(btn, "setTitle:", ns(if (starred) "\u2605" else "\u2606"))
        }
    }

    /** 窗口销毁时由引擎调用: 置位后 setStarred 队列任务不再触碰已释放控件。 */
    override fun dispose() {
        disposed = true
    }

    /** 进度 0.0~1.0。 */
    fun setProgress(fraction: Double?) {
        if (fraction != null) {
            void(progress, "setDoubleValue", fraction.coerceIn(0.0, 1.0) * 100.0)
        }
    }
}
