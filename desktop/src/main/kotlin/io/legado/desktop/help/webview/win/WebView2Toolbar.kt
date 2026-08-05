package io.legado.desktop.help.webview.win

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction

/**
 * WebView2 可见窗口的工具栏 —— 标准 ToolbarWindow32 控件 (2026-08-06 调研重做)。
 *
 * 取代手绘方案 (曾出现: 图标糊/按钮消失/resize 残留/菜单打不开)。主题渲染、hover、
 * 按下态、重绘、命中全部由 comctl32 处理; 图标复用项目 composeResources 矢量 XML
 * (ic_arrow_back/ic_arrow_forward/ic_refresh_black_24dp/ic_more_vert) 渲染进 ImageList。
 *
 * - 按钮: 返回/前进/刷新 (+验证模式"确定"文字按钮) + 溢出菜单 (⋮, BTNS_DROPDOWN);
 *   无关闭按钮 (原生标题栏自带 ✕), 无标题文字 (标题只同步 OS 窗口标题栏);
 * - 菜单: TBN_DROPDOWN → TrackPopupMenu (AppendMenuW 中文正常, 需先
 *   SetForegroundWindow 否则点击外部不关闭) —— 刷新/浏览器打开/拷贝 URL;
 * - 进度条: msctls_progress32 不确定模式 (PBM_SETMARQUEE), 细条贴工具栏底部;
 * - 事件路由: 子窗口 WM_COMMAND (按钮 ID) / WM_NOTIFY (TBN_DROPDOWN) 发给宿主,
 *   由 WebView2Instance 的 hook 转发到 [onCommand]/[onNotify];
 * - DPI: 图标尺寸 = 24dp × GetDpiForWindow/96 (PerMonitorV2 下按物理像素)。
 *
 * 线程: 创建/消息在 WebView2 loop 线程 (与宿主同线程); 状态方法任意线程可调
 * (经 [WebView2Loop.post] 归队)。
 */
internal class WebView2Toolbar(
    private val hwnd: WinDef.HWND,
    initialTitle: String,
    isLogin: Boolean,
    saveResult: Boolean,
) : BrowserToolbar {

    override var onAction: ((ToolbarAction) -> Unit)? = null

    @Volatile
    private var loading = false

    private var toolbarHwnd: WinDef.HWND? = null
    private var progressHwnd: WinDef.HWND? = null
    private var imageList: Pointer? = null

    private val showOk = isLogin || saveResult

    private var width = 0

    override fun layoutHeight(): Int = HEIGHT

    /** 创建 toolbar 子窗口 + ImageList + 按钮 + 进度条 (loop 线程, 宿主窗口已存在)。 */
    fun create() {
        if (toolbarHwnd != null) return
        // InitCommonControlsEx(ICC_BAR_CLASSES)
        val icc = Memory(8)
        icc.setInt(0, 8)
        icc.setInt(4, ComCtl32.ICC_BAR_CLASSES)
        val inited = ComCtl32.comctl.InitCommonControlsEx(icc)
        io.legado.app.constant.AppLog.put("工具栏: InitCommonControlsEx=$inited")

        val style = WinUser.WS_CHILD or WinUser.WS_VISIBLE or
            TBSTYLE_FLAT or TBSTYLE_LIST or TBSTYLE_TOOLTIPS
        toolbarHwnd = User32.INSTANCE.CreateWindowEx(
            0, TOOLBARCLASSNAME, null, style,
            0, 0, width, HEIGHT, hwnd, null,
            com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null),
            null,
        )
        val toolbar = toolbarHwnd ?: run {
            io.legado.app.constant.AppLog.put(
                "工具栏: CreateWindowEx 失败 err=${com.sun.jna.Native.getLastError()}"
            )
            return
        }
        io.legado.app.constant.AppLog.put("工具栏: 控件创建成功")
        // TB_BUTTONSTRUCTSIZE
        val tbSize = TBBUTTON().size()
        val sizeSet = send(toolbar, TB_BUTTONSTRUCTSIZE, tbSize, 0L)
        io.legado.app.constant.AppLog.put("工具栏: TBBUTTON.size()=$tbSize, TB_BUTTONSTRUCTSIZE 返回=$sizeSet")
        // ImageList: 图标尺寸按 DPI
        val dpi = runCatching { toolbarUser32.GetDpiForWindow(hwnd) }.getOrDefault(96)
        val iconSize = (24 * dpi / 96).coerceAtLeast(16)
        imageList = ComCtl32.comctl.ImageList_Create(
            iconSize, iconSize, ComCtl32.ILC_COLOR32 or ComCtl32.ILC_MASK, 6, 4
        )
        val icons = listOf(
            "ic_arrow_back.xml",
            "ic_arrow_forward.xml",
            "ic_refresh_black_24dp.xml",
            "ic_more_vert.xml",
        )
        val list = imageList ?: run {
            io.legado.app.constant.AppLog.put("工具栏: ImageList_Create 失败 (iconSize=$iconSize)")
            return
        }
        icons.forEachIndexed { index, name ->
            val image = ToolbarIcons.render(name, iconSize)
            if (image == null) {
                io.legado.app.constant.AppLog.put("工具栏: 图标渲染失败 $name")
                return@forEachIndexed
            }
            val hbm = bitmapOf(image)
            if (hbm == null) {
                io.legado.app.constant.AppLog.put("工具栏: DIB 创建失败 $name")
                return@forEachIndexed
            }
            val added = ComCtl32.comctl.ImageList_Add(list, hbm, Pointer.NULL)
            ComCtl32.gdi.DeleteObject(hbm)
            io.legado.app.constant.AppLog.put("工具栏: 图标 $name → index=$added")
        }
        val imgSet = send(toolbar, TB_SETIMAGELIST, 0, Pointer.nativeValue(list))
        io.legado.app.constant.AppLog.put("工具栏: TB_SETIMAGELIST 返回=$imgSet")
        // 按钮: 返回/前进/刷新 | (确定) | 菜单
        val buttons = ArrayList<TBBUTTON>()
        buttons += tbb(0, ID_BACK)
        buttons += tbb(1, ID_FORWARD)
        buttons += tbb(2, ID_REFRESH)
        buttons += TBBUTTON().also {
            it.iBitmap = -1
            it.fsStyle = BTNS_SEP.toByte()
        }
        if (showOk) {
            // "确定" 文字按钮: TB_ADDSTRING 注册字符串, iString 引用
            val strIndex = send(toolbar, TB_ADDSTRING, 0, Pointer.nativeValue(wide("确定")))
            buttons += TBBUTTON().also {
                it.idCommand = ID_OK
                it.fsState = TBSTATE_ENABLED.toByte()
                it.fsStyle = (BTNS_AUTOSIZE or BTNS_SHOWTEXT).toByte()
                it.iString = strIndex.toLong()
            }
        }
        buttons += TBBUTTON().also {
            it.iBitmap = 3 // ic_more_vert
            it.idCommand = ID_MENU
            it.fsState = TBSTATE_ENABLED.toByte()
            it.fsStyle = (BTNS_AUTOSIZE or BTNS_DROPDOWN).toByte()
        }
        // JNA 5.17 的 toArray 返回 Structure[] (泛型移除), 改手工连续内存布局
        val size = TBBUTTON().size()
        val mem = Memory((size * buttons.size).toLong())
        buttons.forEachIndexed { i, b ->
            b.write()
            mem.write(i * size.toLong(), b.pointer.getByteArray(0, size), 0, size)
        }
        val addedButtons = send(toolbar, TB_ADDBUTTONS, buttons.size, Pointer.nativeValue(mem))
        io.legado.app.constant.AppLog.put("工具栏: TB_ADDBUTTONS=${buttons.size} 返回=$addedButtons")
        // 主题 (Explorer 样式) + 自动尺寸
        runCatching { ComCtl32.uxtheme.SetWindowTheme(toolbar, "Explorer", null) }
        send(toolbar, TB_AUTOSIZE, 0, 0L)
        // 进度条: 细条贴工具栏底部
        progressHwnd = User32.INSTANCE.CreateWindowEx(
            0, PROGRESS_CLASS, null,
            WinUser.WS_CHILD or WinUser.WS_VISIBLE,
            0, HEIGHT - PROGRESS_H, width, PROGRESS_H, hwnd, null,
            com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null),
            null,
        )
    }

    /** 宿主窗口尺寸变化 (WM_SIZE): 同步 toolbar/进度条尺寸并自动布局。 */
    fun resize(newWidth: Int) {
        width = newWidth
        val toolbar = toolbarHwnd ?: return
        User32.INSTANCE.MoveWindow(toolbar, 0, 0, newWidth, HEIGHT, true)
        send(toolbar, TB_AUTOSIZE, 0, 0L)
        val progress = progressHwnd ?: return
        User32.INSTANCE.MoveWindow(progress, 0, HEIGHT - PROGRESS_H, newWidth, PROGRESS_H, true)
    }

    // -------------------- 宿主 hook 转发 (loop 线程) --------------------

    /** WM_COMMAND: 子窗口按钮点击 (lParam = toolbarHwnd 时处理)。 */
    fun onCommand(wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): Boolean {
        val th = toolbarHwnd ?: return false
        if ((lParam as Number).toLong() != Pointer.nativeValue(th.pointer)) {
            return false
        }
        val id = (wParam as Number).toLong().toInt() and 0xFFFF
        val action = when (id) {
            ID_BACK -> ToolbarAction.BACK
            ID_FORWARD -> ToolbarAction.FORWARD
            ID_REFRESH -> ToolbarAction.REFRESH
            ID_OK -> ToolbarAction.OK
            ID_MENU -> ToolbarAction.MENU
            else -> null
        } ?: return false
        runCatching { onAction?.invoke(action) }
        return true
    }

    /** WM_NOTIFY: TBN_DROPDOWN → TrackPopupMenu。 */
    fun onNotify(lParam: WinDef.LPARAM): Boolean {
        val toolbar = toolbarHwnd ?: return false
        val p = Pointer((lParam as Number).toLong())
        val code = p.getInt(8) // NMHDR.code
        if (code != TBN_DROPDOWN) return false
        val buttonLeft = p.getInt(20) // NMTOOLBAR.rcButton.left (屏幕坐标)
        val buttonBottom = p.getInt(36) // rcButton.bottom
        val menu = menuUser32.CreatePopupMenu()
        if (menu == null) return true
        try {
            menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_REFRESH, "刷新")
            menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_BROWSER, "浏览器打开")
            menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_COPY, "拷贝 URL")
            // 先置前, 否则点击菜单外部不会关闭 (Win32 菜单行为)
            menuUser32.SetForegroundWindow(toolbar)
            val cmd = menuUser32.TrackPopupMenu(
                menu, TPM_RETURNCMD, buttonLeft, buttonBottom, 0, toolbar, null
            )
            val action = when (cmd) {
                MENU_ID_REFRESH -> ToolbarAction.REFRESH
                MENU_ID_BROWSER -> ToolbarAction.OPEN_IN_BROWSER
                MENU_ID_COPY -> ToolbarAction.COPY_URL
                else -> null
            }
            action?.let { runCatching { onAction?.invoke(it) } }
        } finally {
            menuUser32.DestroyMenu(menu)
        }
        return true
    }

    // -------------------- BrowserToolbar 状态 (任意线程) --------------------

    override fun setCanNavigate(back: Boolean, forward: Boolean) {
        WebView2Loop.post {
            val toolbar = toolbarHwnd ?: return@post
            // runCatching: JNA native 层可能抛 Error (Invalid memory access),
            // 不能让它崩掉整个窗口 (曾实测崩溃)
            runCatching {
                send(toolbar, TB_SETSTATE, ID_BACK, if (back) TBSTATE_ENABLED.toLong() else 0L)
                send(
                    toolbar,
                    TB_SETSTATE,
                    ID_FORWARD,
                    if (forward) TBSTATE_ENABLED.toLong() else 0L
                )
            }.onFailure { io.legado.app.constant.AppLog.put("工具栏: setCanNavigate 失败", it) }
        }
    }

    override fun setLoading(value: Boolean) {
        WebView2Loop.post {
            if (loading == value) return@post
            loading = value
            progressHwnd?.let { send(it, PBM_SETMARQUEE, if (value) 1 else 0, 50L) }
        }
    }

    /** 页面标题: 仅同步 OS 窗口标题栏 (工具栏不绘制标题)。 */
    fun updateTitle(value: String) {
        menuUser32.SetWindowTextW(hwnd, value)
    }

    // -------------------- 工具 --------------------

    private fun tbb(iconIndex: Int, id: Int) = TBBUTTON().also {
        it.iBitmap = iconIndex
        it.idCommand = id
        it.fsState = TBSTATE_ENABLED.toByte()
        it.fsStyle = BTNS_AUTOSIZE.toByte()
    }

    /** BufferedImage (ARGB) → HBITMAP (32bpp 顶向下, BGRA 预乘 alpha)。 */
    private fun bitmapOf(image: java.awt.image.BufferedImage): Pointer? {
        val w = image.width
        val h = image.height
        val bmi = Memory(40)
        bmi.setInt(0, 40) // biSize
        bmi.setInt(4, w)
        bmi.setInt(8, -h) // 顶向下
        bmi.setShort(12, 1) // biPlanes
        bmi.setShort(14, 32) // biBitCount
        bmi.setInt(16, ComCtl32.BI_RGB)
        val bitsRef = PointerByReference()
        val hbm = ComCtl32.gdi.CreateDIBSection(
            Pointer.NULL, bmi, ComCtl32.DIB_RGB_COLORS, bitsRef, Pointer.NULL, 0
        ) ?: return null
        val bits = bitsRef.value ?: return null
        val argb = image.getRGB(0, 0, w, h, null, 0, w)
        for (i in argb.indices) {
            val a = (argb[i] ushr 24) and 0xFF
            val r = (argb[i] ushr 16) and 0xFF
            val g = (argb[i] ushr 8) and 0xFF
            val b = argb[i] and 0xFF
            val off = i * 4L
            bits.setByte(off, ((b * a) / 255).toByte())
            bits.setByte(off + 1, ((g * a) / 255).toByte())
            bits.setByte(off + 2, ((r * a) / 255).toByte())
            bits.setByte(off + 3, a.toByte())
        }
        return hbm
    }

    private fun send(h: WinDef.HWND, msg: Int, w: Int, l: Long): Long =
        (User32.INSTANCE.SendMessage(
            h,
            msg,
            WinDef.WPARAM(w.toLong()),
            WinDef.LPARAM(l)
        ) as Number).toLong()

    private fun wide(value: String): Memory {
        val mem = Memory((value.length + 1) * 2L)
        mem.setWideString(0, value)
        return mem
    }

    companion object {
        /** 工具栏条高度 (px)。 */
        const val HEIGHT = 44

        private const val PROGRESS_H = 2

        // 按钮 ID (WM_COMMAND LOWORD)
        private const val ID_BACK = 1
        private const val ID_FORWARD = 2
        private const val ID_REFRESH = 3
        private const val ID_OK = 4
        private const val ID_MENU = 5

        // 菜单项 ID (TrackPopupMenu 返回值)
        private const val MENU_ID_REFRESH = 101
        private const val MENU_ID_BROWSER = 102
        private const val MENU_ID_COPY = 103

        // commctrl / user32 常量
        private const val TOOLBARCLASSNAME = "ToolbarWindow32"
        private const val PROGRESS_CLASS = "msctls_progress32"
        private const val TBSTYLE_FLAT = 0x0800
        private const val TBSTYLE_LIST = 0x1000
        private const val TBSTYLE_TOOLTIPS = 0x0100
        private const val BTNS_AUTOSIZE = 0x0010
        private const val BTNS_SEP = 0x0001
        private const val BTNS_DROPDOWN = 0x0080
        private const val BTNS_SHOWTEXT = 0x0040
        private const val TBSTATE_ENABLED = 0x04
        private const val TB_BUTTONSTRUCTSIZE = 0x041F
        private const val TB_SETIMAGELIST = 0x0418
        private const val TB_ADDBUTTONS = 0x0414
        private const val TB_ADDSTRING = 0x041C
        private const val TB_AUTOSIZE = 0x041B
        private const val TB_SETSTATE = 0x041A
        private const val PBM_SETMARQUEE = 0x040A
        private const val TBN_DROPDOWN = -710
        private const val MF_STRING = 0x0000
        private const val TPM_RETURNCMD = 0x0100
    }
}

/** user32 补充: GetDpiForWindow (PerMonitorV2 下按窗口 DPI 缩放图标)。 */
internal interface ToolbarUser32Dpi : com.sun.jna.win32.StdCallLibrary {
    fun GetDpiForWindow(hWnd: WinDef.HWND): Int
}

internal val toolbarUser32: ToolbarUser32Dpi by lazy {
    com.sun.jna.Native.load(
        "user32",
        ToolbarUser32Dpi::class.java,
        com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS
    )
}

/** 菜单 API (jna-platform User32 未覆盖): CreatePopupMenu/AppendMenuW/TrackPopupMenu/DestroyMenu/SetForegroundWindow。 */
internal interface ToolbarMenuUser32 : com.sun.jna.win32.StdCallLibrary {
    fun CreatePopupMenu(): com.sun.jna.platform.win32.WinNT.HANDLE
    fun AppendMenuW(
        hMenu: com.sun.jna.platform.win32.WinNT.HANDLE,
        uFlags: Int,
        uIDNewItem: Int,
        lpNewItem: String
    ): Boolean

    fun TrackPopupMenu(
        hMenu: com.sun.jna.platform.win32.WinNT.HANDLE, uFlags: Int, x: Int, y: Int,
        nReserved: Int, hWnd: WinDef.HWND, prcRect: com.sun.jna.platform.win32.WinDef.RECT?
    ): Int

    fun DestroyMenu(hMenu: com.sun.jna.platform.win32.WinNT.HANDLE): Boolean
    fun SetForegroundWindow(hWnd: WinDef.HWND): Boolean
    fun SetWindowTextW(hWnd: WinDef.HWND, lpString: String): Boolean
}

internal val menuUser32: ToolbarMenuUser32 by lazy {
    com.sun.jna.Native.load(
        "user32",
        ToolbarMenuUser32::class.java,
        com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS
    )
}
