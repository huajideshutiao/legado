package io.legado.desktop.help.webview.mac

import com.sun.jna.Pointer
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction
import io.legado.desktop.help.webview.mac.ObjC.NSRect
import io.legado.desktop.help.webview.mac.ObjC.ns
import io.legado.desktop.help.webview.mac.ObjC.ptr
import io.legado.desktop.help.webview.mac.ObjC.sel
import io.legado.desktop.help.webview.mac.ObjC.void

/**
 * AppKit 工具栏 (NSView 手工 frame 布局): 返回/前进/刷新/确定/关闭 + 标题 + 细进度条。
 *
 * 行为对照 Windows [io.legado.desktop.help.webview.win.WebView2Toolbar]。按钮经
 * target-action 回调 (动态子类), 窗口 resize 时用 autoresizingMask 自适应。
 * **所有方法必须在主线程 (EDT) 调用。**
 */
internal class MacToolbar(
    override var onAction: ((ToolbarAction) -> Unit)?,
) : BrowserToolbar {

    /** 顶层容器 NSView。 */
    val view: Pointer

    /** 细进度条 NSProgressIndicator。 */
    val progress: Pointer

    private val backBtn: Pointer
    private val forwardBtn: Pointer
    private val refreshBtn: Pointer
    private val okBtn: Pointer

    /** target-action 动态子类实例 (NSControl target 是弱引用, 必须由我方持有)。 */
    private val target: Pointer

    /** sender 指针 → 动作映射。 */
    private val actionBySender = HashMap<Long, ToolbarAction>()

    companion object {
        private const val TOOLBAR_H = 32.0
        private const val PROGRESS_H = 3.0

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
            listOf("toolbarAction:" to "v@:@"),
        ) { _, _, args ->
            val sender = args.firstOrNull() as? Pointer
            if (sender != null) actionBySender[Pointer.nativeValue(sender)]?.let {
                onAction?.invoke(
                    it
                )
            }
        }

        backBtn = button("←", 8.0, 2.0, 32.0, 28.0, ToolbarAction.BACK, NSViewMaxXMargin)
        forwardBtn = button("→", 44.0, 2.0, 32.0, 28.0, ToolbarAction.FORWARD, NSViewMaxXMargin)
        refreshBtn = button("⟳", 80.0, 2.0, 32.0, 28.0, ToolbarAction.REFRESH, NSViewMaxXMargin)

        okBtn = button("确定", 1000.0 - 68.0, 2.0, 32.0, 28.0, ToolbarAction.OK, NSViewMinXMargin)

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

    /** 进度 0.0~1.0。 */
    fun setProgress(fraction: Double?) {
        if (fraction != null) {
            void(progress, "setDoubleValue", fraction.coerceIn(0.0, 1.0) * 100.0)
        }
    }
}
