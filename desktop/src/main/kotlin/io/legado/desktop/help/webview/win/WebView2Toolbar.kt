package io.legado.desktop.help.webview.win

import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider

/** 工具栏按钮动作 (WebView2 loop 线程回调 [WebView2Toolbar.onAction])。 */
internal enum class ToolbarAction { BACK, FORWARD, REFRESH, OK, CLOSE }

/**
 * WebView2 可见窗口的 CustomTab 式工具栏 (纯 Win32 自绘, 不依赖 comctl32 / Swing)。
 *
 * 背景: WebView2 实例必须跑在自建的 Win32 消息泵线程 (见 [WebView2Loop]), 刻意不复用
 * AWT EDT, 因此工具栏也随宿主窗口自绘: 顶部 40px 工具条 + 底部 2px 进度细条,
 * 全部在宿主 HWND 的 WndProc 里画 (WM_PAINT) 并命中测试 (WM_LBUTTONDOWN/UP/WM_MOUSEMOVE),
 * 与 [io.legado.desktop.help.webview.JavaFxBrowserToolbar] 组成对齐 (对照 shared
 * `WebViewRoute` 标题栏 + 进度条 + menu_ok):
 * - 返回 ← / 前进 → (手动历史栈, 由调用方维护并 [setCanNavigate]); 刷新 ↻;
 * - 网页标题 (动态更新, 省略号截断, 对齐 onReceivedTitle 语义); 关闭 ✕;
 * - 确定按钮 (仅 isLogin / saveResult 时显示, 对照 menu_ok);
 * - 加载进度: WebView2 无 0..100 进度 API, 用导航事件驱动的不确定扫动条
 *   (NavigationStarting → 显示, NavigationCompleted → 隐藏, 对照 RefreshProgressBar)。
 *
 * 颜色取桌面端主题 (DesktopThemeStoreProvider): 底 = bottomBackground, 强调 = accent,
 * 文字按深浅主题派生; 主题在窗口打开时读取一次 (独立窗口不随 RECREATE 实时换肤)。
 *
 * 线程: 所有绘制/命中在 WebView2 loop 线程; 状态更新 ([updateTitle] / [setCanNavigate] /
 * [setLoading]) 任意线程可调 (volatile + InvalidateRect 线程安全), 定时器经 [WebView2Loop.post]
 * 归队到 loop 线程。
 */
internal class WebView2Toolbar(
    private val hwnd: WinDef.HWND,
    initialTitle: String,
    isLogin: Boolean,
    saveResult: Boolean,
) {

    /** 按钮点击回调 (WebView2 loop 线程同步调用)。 */
    @Volatile
    var onAction: ((ToolbarAction) -> Unit)? = null

    @Volatile
    private var title = initialTitle

    @Volatile
    private var loading = false

    @Volatile
    private var canBack = false

    @Volatile
    private var canForward = false

    private var hovered: ToolbarAction? = null
    private var pressed: ToolbarAction? = null
    private var w = 0
    private var sweepPos = -SWEEP_W

    private val showOk = isLogin || saveResult

    private val theme = DesktopThemeStoreProvider()
    private val bgColor = theme.bottomBackground.toArgb()
    private val accentColor = theme.accentColor.toArgb()
    private val textColor = if (theme.isDark) 0xFFD9D9D9.toInt() else 0xFF1F2329.toInt()

    private val backRect = WinDef.RECT()
    private val forwardRect = WinDef.RECT()
    private val refreshRect = WinDef.RECT()
    private val okRect = WinDef.RECT()
    private val closeRect = WinDef.RECT()
    private val titleRect = WinDef.RECT()
    private val progressRect = WinDef.RECT()

    /**
     * 处理宿主窗口消息 (WebView2 loop 线程)。返回 true 表示已消费 (不再走 DefWindowProc)。
     * WM_SIZE 返回 false: 布局后由调用方继续 applyLayout (WebView2 边界下移)。
     */
    fun onWindowMessage(msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): Boolean =
        when (msg) {
            WinUser.WM_SIZE -> {
                layout(loword(lParam))
                false
            }

            WM_PAINT -> {
                paint()
                true
            }

            WM_ERASEBKGND -> true

            WM_MOUSEMOVE -> {
                onMouseMove(coordX(lParam), coordY(lParam))
                true
            }

            WM_MOUSELEAVE -> {
                onMouseLeave()
                true
            }

            WM_LBUTTONDOWN -> {
                onLButtonDown(coordX(lParam), coordY(lParam))
                true
            }

            WM_LBUTTONUP -> {
                onLButtonUp(coordX(lParam), coordY(lParam))
                true
            }

            WM_SETCURSOR -> onSetCursor()

            WM_TIMER -> {
                if ((wParam as Number).toLong().toInt() == TIMER_ID) onTimer()
                true
            }

            else -> false
        }

    /** 重算按钮/标题/进度条矩形 (WM_SIZE 或创建时调用)。 */
    fun layout(width: Int) {
        if (width <= 0) return
        w = width
        val y = (HEIGHT - BTN) / 2
        backRect.set(8, y, BTN, BTN)
        forwardRect.set(8 + BTN + BTN_GAP, y, BTN, BTN)
        refreshRect.set(8 + (BTN + BTN_GAP) * 2, y, BTN, BTN)
        val closeX = width - 8 - BTN
        closeRect.set(closeX, y, BTN, BTN)
        if (showOk) {
            okRect.set(closeX - BTN_GAP - OK_W, y, OK_W, BTN)
            titleRect.set(refreshRect.right + 12, 0, okRect.left - refreshRect.right - 24, HEIGHT)
        } else {
            okRect.set(0, 0, 0, 0)
            titleRect.set(refreshRect.right + 12, 0, closeX - refreshRect.right - 20, HEIGHT)
        }
        progressRect.set(0, HEIGHT - PROGRESS_H, width, PROGRESS_H)
    }

    /** 页面标题更新 (任意线程); 同时同步 OS 窗口标题。 */
    fun updateTitle(value: String) {
        if (value == title) return
        title = value
        native.SetWindowTextW(hwnd, value)
        invalidate(titleRect)
    }

    /** 返回/前进可用态 (任意线程)。 */
    fun setCanNavigate(canBack: Boolean, canForward: Boolean) {
        if (this.canBack == canBack && this.canForward == canForward) return
        this.canBack = canBack
        this.canForward = canForward
        invalidate(null)
    }

    /**
     * 加载态 (任意线程, 经 loop 线程归队): true 显示不确定扫动进度条并起定时器,
     * false 隐藏并停定时器 (对照 RefreshProgressBar 100 隐藏)。
     */
    fun setLoading(value: Boolean) {
        WebView2Loop.post {
            if (loading == value) return@post
            loading = value
            if (value) {
                sweepPos = -SWEEP_W
                native.SetTimer(hwnd, TIMER_ID, TIMER_MS, null)
            } else {
                native.KillTimer(hwnd, TIMER_ID)
            }
            invalidate(progressRect)
        }
    }

    // -------------------- 绘制 (loop 线程) --------------------

    private fun paint() {
        val ps = PaintStruct()
        val hdc = native.BeginPaint(hwnd, ps) ?: return
        try {
            fill(hdc, 0, 0, w, HEIGHT, bgColor)
            drawButton(hdc, backRect, ToolbarAction.BACK, "←")
            drawButton(hdc, forwardRect, ToolbarAction.FORWARD, "→")
            drawButton(hdc, refreshRect, ToolbarAction.REFRESH, "↻")
            if (showOk) drawButton(hdc, okRect, ToolbarAction.OK, "确定")
            drawButton(hdc, closeRect, ToolbarAction.CLOSE, "✕")
            drawTitle(hdc)
            if (loading) drawProgress(hdc)
        } finally {
            native.EndPaint(hwnd, ps)
        }
    }

    private fun drawButton(hdc: Pointer, rect: WinDef.RECT, action: ToolbarAction, glyph: String) {
        val fillColor = when {
            pressed == action -> blend(bgColor, textColor, 0.18f)
            hovered == action -> blend(bgColor, textColor, 0.10f)
            else -> bgColor
        }
        fill(hdc, rect, fillColor)
        withFont(hdc) {
            gdi.SetBkMode(hdc, TRANSPARENT)
            gdi.SetTextColor(
                hdc, colorRef(if (enabled(action)) textColor else blend(bgColor, textColor, 0.35f))
            )
            val inner = WinDef.RECT().also {
                it.left = rect.left + 2
                it.top = rect.top + 2
                it.right = rect.right - 2
                it.bottom = rect.bottom - 2
            }
            native.DrawTextW(
                hdc, glyph, -1, inner,
                DT_CENTER or DT_VCENTER or DT_SINGLELINE or DT_NOPREFIX,
            )
        }
    }

    private fun drawTitle(hdc: Pointer) {
        withFont(hdc) {
            gdi.SetBkMode(hdc, TRANSPARENT)
            gdi.SetTextColor(hdc, colorRef(textColor))
            native.DrawTextW(
                hdc, title, -1, titleRect,
                DT_SINGLELINE or DT_VCENTER or DT_END_ELLIPSIS or DT_NOPREFIX,
            )
        }
    }

    private fun drawProgress(hdc: Pointer) {
        val brush = gdi.CreateSolidBrush(colorRef(accentColor)) ?: return
        try {
            val rect = WinDef.RECT().also {
                it.left = sweepPos.toInt()
                it.top = HEIGHT - PROGRESS_H
                it.right = sweepPos.toInt() + SWEEP_W.toInt()
                it.bottom = HEIGHT
            }
            native.FillRect(hdc, rect, brush)
        } finally {
            gdi.DeleteObject(brush)
        }
    }

    private fun fill(hdc: Pointer, x: Int, y: Int, width: Int, height: Int, color: Int) {
        fill(hdc, WinDef.RECT().also { it.set(x, y, width, height) }, color)
    }

    private fun fill(hdc: Pointer, rect: WinDef.RECT, color: Int) {
        val brush = gdi.CreateSolidBrush(colorRef(color)) ?: return
        try {
            native.FillRect(hdc, rect, brush)
        } finally {
            gdi.DeleteObject(brush)
        }
    }

    private inline fun withFont(hdc: Pointer, block: () -> Unit) {
        val old = gdi.SelectObject(hdc, gdi.GetStockObject(DEFAULT_GUI_FONT))
        try {
            block()
        } finally {
            gdi.SelectObject(hdc, old)
        }
    }

    // -------------------- 命中测试 (loop 线程) --------------------

    private fun enabled(action: ToolbarAction): Boolean = when (action) {
        ToolbarAction.BACK -> canBack
        ToolbarAction.FORWARD -> canForward
        else -> true
    }

    private fun hitTest(x: Int, y: Int): ToolbarAction? {
        if (y < 0 || y >= HEIGHT) return null
        fun hit(rect: WinDef.RECT, action: ToolbarAction): ToolbarAction? =
            if (enabled(action) && x in rect.left until rect.right && y in rect.top until rect.bottom) {
                action
            } else {
                null
            }
        hit(backRect, ToolbarAction.BACK)?.let { return it }
        hit(forwardRect, ToolbarAction.FORWARD)?.let { return it }
        hit(refreshRect, ToolbarAction.REFRESH)?.let { return it }
        if (showOk) hit(okRect, ToolbarAction.OK)?.let { return it }
        hit(closeRect, ToolbarAction.CLOSE)?.let { return it }
        return null
    }

    private fun onMouseMove(x: Int, y: Int) {
        val hit = hitTest(x, y)
        if (hit != hovered) {
            hovered = hit
            trackMouseLeave()
            invalidate(null)
        }
    }

    private fun onMouseLeave() {
        if (hovered != null || pressed != null) {
            hovered = null
            pressed = null
            invalidate(null)
        }
    }

    private fun onLButtonDown(x: Int, y: Int) {
        val hit = hitTest(x, y)
        if (hit != pressed) {
            pressed = hit
            invalidate(null)
        }
    }

    private fun onLButtonUp(x: Int, y: Int) {
        val hit = hitTest(x, y)
        val wasPressed = pressed
        pressed = null
        if (wasPressed != null && wasPressed == hit) {
            runCatching { onAction?.invoke(wasPressed) }
        }
        invalidate(null)
    }

    private fun onSetCursor(): Boolean {
        if (hovered != null) {
            native.SetCursor(native.LoadCursor(null, Pointer(IDC_HAND.toLong())))
            return true
        }
        return false
    }

    private fun onTimer() {
        sweepPos += SWEEP_STEP
        if (sweepPos > w) sweepPos = -SWEEP_W
        invalidate(progressRect)
    }

    private fun trackMouseLeave() {
        val tme = TrackMouseEventStruct().apply {
            cbSize = size()
            dwFlags = TME_LEAVE
            hwndTrack = hwnd
        }
        native.TrackMouseEvent(tme)
    }

    private fun invalidate(rect: WinDef.RECT?) {
        native.InvalidateRect(hwnd, rect, false)
    }

    // -------------------- 工具 --------------------

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val f = (from ushr shift) and 0xFF
            val target = (to ushr shift) and 0xFF
            return (f + ((target - f) * t).toInt()).coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * ARGB → GDI COLORREF (0x00BBGGRR)。COLORREF 高字节必须为 0,
     * Compose 的 toArgb() 带 0xFF alpha 直接传 CreateSolidBrush 会渲染纯黑。
     */
    private fun colorRef(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    private fun coordX(lParam: WinDef.LPARAM): Int =
        (lParam as Number).toLong().toInt().toShort().toInt()

    private fun coordY(lParam: WinDef.LPARAM): Int =
        ((lParam as Number).toLong() ushr 16).toInt().toShort().toInt()

    private fun loword(lParam: WinDef.LPARAM): Int = (lParam as Number).toLong().toInt() and 0xFFFF

    private fun WinDef.RECT.set(x: Int, y: Int, width: Int, height: Int) {
        left = x
        top = y
        right = x + width
        bottom = y + height
    }

    companion object {

        /** 工具栏条高度 (px); [WebView2Instance.applyLayout] 用它下移 WebView2 边界。 */
        const val HEIGHT = 40

        private const val PROGRESS_H = 2
        private const val BTN = 28
        private const val BTN_GAP = 6
        private const val OK_W = 56

        private const val TIMER_ID = 1
        private const val TIMER_MS = 16
        private const val SWEEP_W = 80f
        private const val SWEEP_STEP = 6f

        // winuser.h / wingdi.h 常量
        private const val WM_PAINT = 0x000F
        private const val WM_ERASEBKGND = 0x0014
        private const val WM_MOUSEMOVE = 0x0200
        private const val WM_LBUTTONDOWN = 0x0201
        private const val WM_LBUTTONUP = 0x0202
        private const val WM_MOUSELEAVE = 0x02A3
        private const val WM_SETCURSOR = 0x0020
        private const val WM_TIMER = 0x0113
        private const val IDC_HAND = 32649
        private const val TME_LEAVE = 0x0002
        private const val TRANSPARENT = 1
        private const val DEFAULT_GUI_FONT = 17
        private const val DT_SINGLELINE = 0x0020
        private const val DT_CENTER = 0x0001
        private const val DT_VCENTER = 0x0004
        private const val DT_END_ELLIPSIS = 0x8000
        private const val DT_NOPREFIX = 0x0800
    }
}

/** PAINTSTRUCT (windows.h) 最小声明; jna-platform 未内置。 */
private class PaintStruct : Structure() {
    @JvmField
    var hdc: Pointer? = null

    @JvmField
    var fErase: Int = 0

    @JvmField
    var rcPaint: WinDef.RECT = WinDef.RECT()

    @JvmField
    var fRestore: Int = 0

    @JvmField
    var fIncUpdate: Int = 0

    @JvmField
    var rgbReserved: ByteArray = ByteArray(32)

    override fun getFieldOrder(): List<String> =
        listOf("hdc", "fErase", "rcPaint", "fRestore", "fIncUpdate", "rgbReserved")
}

/** TRACKMOUSEEVENT (winuser.h) 最小声明; jna-platform 未内置。 */
private class TrackMouseEventStruct : Structure() {
    @JvmField
    var cbSize: Int = 0

    @JvmField
    var dwFlags: Int = 0

    @JvmField
    var hwndTrack: WinDef.HWND? = null

    @JvmField
    var dwHoverTime: Int = 0

    override fun getFieldOrder(): List<String> =
        listOf("cbSize", "dwFlags", "hwndTrack", "dwHoverTime")
}

/** 工具栏用到的 user32 函数 (jna-platform 5.17 的 User32 未覆盖, 参照 Dwmapi 声明方式)。 */
private interface ToolbarUser32 : StdCallLibrary {
    fun BeginPaint(hWnd: WinDef.HWND, lpPaint: PaintStruct): Pointer
    fun EndPaint(hWnd: WinDef.HWND, lpPaint: PaintStruct): Boolean
    fun InvalidateRect(hWnd: WinDef.HWND?, lpRect: WinDef.RECT?, bErase: Boolean): Boolean
    fun SetTimer(hWnd: WinDef.HWND?, nIDEvent: Int, uElapse: Int, lpTimerFunc: Pointer?): Long
    fun KillTimer(hWnd: WinDef.HWND?, nIDEvent: Int): Boolean
    fun TrackMouseEvent(lpEventTrack: TrackMouseEventStruct): Boolean
    fun SetCursor(hCursor: WinDef.HCURSOR?): WinDef.HCURSOR?
    fun LoadCursor(hInstance: WinDef.HINSTANCE?, lpCursorName: Pointer): WinDef.HCURSOR?
    fun SetWindowTextW(hWnd: WinDef.HWND, lpString: String): Boolean
    fun DrawTextW(
        hdc: Pointer,
        lpchText: String,
        cchText: Int,
        lprc: WinDef.RECT,
        uFormat: Int
    ): Int

    fun FillRect(hdc: Pointer, lprc: WinDef.RECT, hbr: Pointer): Int
}

/** 工具栏用到的 gdi32 函数。
 *
 * 注意: SetBkMode/SetTextColor 是 gdi32 导出, 不能声明进 user32 接口 ——
 * 曾误放 ToolbarUser32 导致 paint() 抛 UnsatisfiedLinkError (JNA 回调内异常被静默
 * 吞, BeginPaint 已消费无效区域, 工具栏永不再重绘 = 白条, 鼠标命中正常)。
 */
private interface ToolbarGdi32 : StdCallLibrary {
    fun CreateSolidBrush(crColor: Int): Pointer
    fun SelectObject(hdc: Pointer, obj: Pointer): Pointer
    fun DeleteObject(obj: Pointer): Boolean
    fun GetStockObject(i: Int): Pointer
    fun SetBkMode(hdc: Pointer, iMode: Int): Int
    fun SetTextColor(hdc: Pointer, crColor: Int): Int
}

private val native: ToolbarUser32 by lazy {
    com.sun.jna.Native.load("user32", ToolbarUser32::class.java, W32APIOptions.UNICODE_OPTIONS)
}

private val gdi: ToolbarGdi32 by lazy {
    com.sun.jna.Native.load("gdi32", ToolbarGdi32::class.java, W32APIOptions.UNICODE_OPTIONS)
}
