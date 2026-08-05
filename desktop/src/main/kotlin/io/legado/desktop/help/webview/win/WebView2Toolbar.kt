package io.legado.desktop.help.webview.win

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.legado.app.help.RssToolbarActions
import io.legado.desktop.help.webview.BrowserToolbar
import io.legado.desktop.help.webview.ToolbarAction

/**
 * WebView2 可见窗口的工具栏 —— 标准 Button 控件 (2026-08-06 重做, 2026-08-07 图标化)。
 *
 * 背景: comctl32 的 ToolbarWindow32 在 JVM 进程里不可用 (TB_ADDBUTTONS 返回 0、
 * TB_SETSTATE 访问违规)。根因是 java.exe manifest 使 JVM 启动链预加载 comctl32 v6,
 * 与 comctl32 内部按名自查找错模块 (最小纯 JNA 程序可复现, 原生 C 程序正常),
 * 应用层无法修复。同进程 user32 系统类 (BUTTON/STATIC) 与 comctl32 进度条均实测
 * 正常, 故工具栏改用标准按钮。
 *
 * 2026-08-07 用户拍板 (三端一致):
 * - **按钮全部图标化**: 按钮文本 = Segoe MDL2 Assets 字体图标字符 (系统自带字体,
 *   码点已在本机逐一渲染验证非空: 返回 E72B/前进 E72A/刷新 E72C/确定 E73E/菜单 E712/
 *   星 E734·E735/朗读 E15D/分享 E72D/登录 E77B), 创建逻辑字体后 WM_SETFONT 到按钮;
 * - **菜单按钮右对齐**窗口右缘, 返回/前进/刷新/确定/RSS 按钮组靠左;
 * - **菜单无刷新项** (工具栏已有刷新按钮), 仅 浏览器打开/拷贝 URL;
 *   + sourceKey 非空时额外显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08);
 * - RSS 模式 (rssActions 非空): 额外显示 收藏/朗读/分享/登录 按钮, 动作回调回 shared。
 *
 * - 按钮: 返回/前进/刷新 (+验证/登录模式"确定") (+RSS 模式 收藏/朗读/分享/登录) + 菜单;
 * - 菜单: 按钮点击 → TrackPopupMenu (AppendMenuW 中文正常, 需先 SetForegroundWindow);
 * - 进度条: msctls_progress32 不确定模式 (PBM_SETMARQUEE), 细条贴工具栏底部;
 * - 事件: 按钮点击 → WM_COMMAND (控件 ID 在 LOWORD) → 宿主 hook 转发 [onCommand];
 * - 禁用态: EnableWindow (user32), 经 [WebView2Loop.post] 归队到 loop 线程执行。
 *
 * 线程: 创建/消息在 WebView2 loop 线程 (与宿主同线程); 状态方法任意线程可调
 * (经 [WebView2Loop.post] 归队)。
 */
internal class WebView2Toolbar(
    private val hwnd: WinDef.HWND,
    initialTitle: String,
    isLogin: Boolean,
    saveResult: Boolean,
    private val rssActions: RssToolbarActions? = null,
    /** 书源 key (cookieTag): 非空时溢出菜单显示 禁用源/删除源 (对照原版 web_view.xml, 2026-08-08)。 */
    private val sourceKey: String? = null,
) : BrowserToolbar {

    override var onAction: ((ToolbarAction) -> Unit)? = null

    @Volatile
    private var loading = false

    /** 按钮 ID → 控件句柄 (loop 线程创建, 之后只读; 宿主销毁时连带销毁)。 */
    private val buttons = HashMap<Int, WinDef.HWND>()

    private var progressHwnd: WinDef.HWND? = null

    /** Segoe MDL2 Assets 逻辑字体 (图标字符渲染), dispose 时 DeleteObject。 */
    private var iconFont: Pointer? = null

    private val showOk = isLogin || saveResult

    private val showRss = rssActions != null

    private var width = 0

    override fun layoutHeight(): Int = HEIGHT

    /** 创建按钮 + 进度条 (loop 线程, 宿主窗口已存在)。 */
    fun create() {
        if (buttons.isNotEmpty()) return
        // 进度条控件类需要 ICC_PROGRESS_CLASS
        val icc = Memory(8)
        icc.setInt(0, 8)
        icc.setInt(4, ComCtl32.ICC_PROGRESS_CLASS)
        val inited = ComCtl32.comctl.InitCommonControlsEx(icc)
        io.legado.app.constant.AppLog.put("工具栏: InitCommonControlsEx(progress)=$inited")

        // 图标字体 (2026-08-07 图标化): 按钮文本 = MDL2 字符
        iconFont = toolbarGdi32.CreateFontW(
            ICON_FONT_HEIGHT, 0, 0, 0, FW_NORMAL,
            0, 0, 0, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY, DEFAULT_PITCH, ICON_FONT_FACE,
        )
        if (iconFont == null) {
            io.legado.app.constant.AppLog.put(
                "工具栏: CreateFontW($ICON_FONT_FACE) 失败 err=${com.sun.jna.Native.getLastError()}"
            )
        }

        buttonIds().forEach { id ->
            val h = User32.INSTANCE.CreateWindowEx(
                0, "BUTTON", buttonLabel(id),
                WinUser.WS_CHILD or WinUser.WS_VISIBLE or BS_PUSHBUTTON,
                0, 0, BTN_W, BTN_H, hwnd,
                WinDef.HMENU(Pointer.createConstant(id.toLong())),
                com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null),
                null,
            )
            if (h == null) {
                io.legado.app.constant.AppLog.put(
                    "工具栏: 按钮 $id 创建失败 err=${com.sun.jna.Native.getLastError()}"
                )
            } else {
                buttons[id] = h
                // WM_SETFONT 的 wParam 是 HFONT 指针 (x64 下 64 位, 勿截断 toInt)
                iconFont?.let { send(h, WM_SETFONT, Pointer.nativeValue(it), 1L) }
            }
        }
        io.legado.app.constant.AppLog.put("工具栏: 按钮创建 ${buttons.size}/${buttonIds().size}")
        progressHwnd = User32.INSTANCE.CreateWindowEx(
            0, "msctls_progress32", null,
            WinUser.WS_CHILD or WinUser.WS_VISIBLE,
            0, HEIGHT - PROGRESS_H, width, PROGRESS_H, hwnd, null,
            com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null),
            null,
        )
        layout()
    }

    /** 宿主窗口尺寸变化 (WM_SIZE): 重排按钮并同步进度条宽度。 */
    fun resize(newWidth: Int) {
        width = newWidth
        layout()
    }

    /** 释放句柄引用 (loop 线程, close 时由宿主调用): 窗口销毁后队列里残留的
     * setCanNavigate/setLoading 任务见句柄为 null 直接跳过, 不再触碰悬垂句柄。
     * (BrowserToolbar 接口成员, 2026-08-07) */
    override fun dispose() {
        buttons.clear()
        progressHwnd = null
        iconFont?.let { toolbarGdi32.DeleteObject(it) }
        iconFont = null
    }

    /** 布局: 左组按钮靠左顺序排列, 菜单按钮右对齐窗口右缘 (2026-08-07 用户拍板)。 */
    private fun layout() {
        var x = MARGIN
        leftButtonIds().forEach { id ->
            val h = buttons[id] ?: return@forEach
            User32.INSTANCE.MoveWindow(h, x, BTN_TOP, BTN_W, BTN_H, true)
            x += BTN_W + GAP
        }
        buttons[ID_MENU]?.let {
            User32.INSTANCE.MoveWindow(it, width - MARGIN - BTN_W, BTN_TOP, BTN_W, BTN_H, true)
        }
        progressHwnd?.let {
            User32.INSTANCE.MoveWindow(it, 0, HEIGHT - PROGRESS_H, width, PROGRESS_H, true)
        }
    }

    /** 左组按钮 (菜单按钮除外)。 */
    private fun leftButtonIds(): List<Int> = buttonIds().filter { it != ID_MENU }

    private fun buttonIds(): List<Int> = buildList {
        add(ID_BACK)
        add(ID_FORWARD)
        add(ID_REFRESH)
        if (showRss) {
            add(ID_STAR)
            add(ID_READ_ALOUD)
            add(ID_SHARE)
            add(ID_LOGIN)
        }
        if (showOk) add(ID_OK)
        add(ID_MENU)
    }

    /** 按钮文本 = Segoe MDL2 Assets 图标字符 (码点经本机渲染验证, 见类注释)。 */
    private fun buttonLabel(id: Int): String = when (id) {
        ID_BACK -> "\uE72B"       // Back
        ID_FORWARD -> "\uE72A"    // Forward
        ID_REFRESH -> "\uE72C"    // Refresh
        ID_OK -> "\uE73E"         // CheckMark (确定)
        ID_STAR -> if (rssActions?.starred == true) "\uE735" else "\uE734" // FavoriteStarFill/FavoriteStar
        ID_READ_ALOUD -> "\uE15D" // Volume (朗读)
        ID_SHARE -> "\uE72D"      // Share
        ID_LOGIN -> "\uE77B"      // Contact (登录)
        ID_MENU -> "\uE712"       // More (菜单, 三点)
        else -> ""
    }

    // -------------------- 宿主 hook 转发 (loop 线程) --------------------

    /** WM_COMMAND: 本工具栏按钮点击 (lParam = 按钮句柄)。 */
    fun onCommand(wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): Boolean {
        val lp = (lParam as Number).toLong()
        if (buttons.values.none { Pointer.nativeValue(it.pointer) == lp }) return false
        val id = (wParam as Number).toLong().toInt() and 0xFFFF
        when (id) {
            ID_BACK -> fire(ToolbarAction.BACK)
            ID_FORWARD -> fire(ToolbarAction.FORWARD)
            ID_REFRESH -> fire(ToolbarAction.REFRESH)
            ID_OK -> fire(ToolbarAction.OK)
            ID_STAR -> fire(ToolbarAction.STAR_TOGGLE)
            ID_READ_ALOUD -> fire(ToolbarAction.READ_ALOUD)
            ID_SHARE -> fire(ToolbarAction.SHARE)
            ID_LOGIN -> fire(ToolbarAction.LOGIN)
            ID_MENU -> showMenu()
            else -> return false
        }
        return true
    }

    private fun fire(action: ToolbarAction) {
        runCatching { onAction?.invoke(action) }
    }

    /** 溢出菜单 (loop 线程): 按钮下方弹出, 浏览器打开/拷贝 URL (2026-08-07: 无刷新项)
     *  + 禁用源/删除源 (sourceKey 非空时显示, 对照原版 web_view.xml, 2026-08-08)。 */
    private fun showMenu() {
        val btn = buttons[ID_MENU] ?: return
        val menu = menuUser32.CreatePopupMenu()
        if (menu == null) return
        try {
            menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_BROWSER, "浏览器打开")
            menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_COPY, "拷贝 URL")
            if (!sourceKey.isNullOrBlank()) {
                menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_DISABLE_SOURCE, "禁用源")
                menuUser32.AppendMenuW(menu, MF_STRING, MENU_ID_DELETE_SOURCE, "删除源")
            }
            // 先置前, 否则点击菜单外部不会关闭 (Win32 菜单行为)
            menuUser32.SetForegroundWindow(btn)
            val rect = WinDef.RECT()
            User32.INSTANCE.GetWindowRect(btn, rect)
            val cmd = menuUser32.TrackPopupMenu(
                menu, TPM_RETURNCMD, rect.left, rect.bottom, 0, btn, null
            )
            val action = when (cmd) {
                MENU_ID_BROWSER -> ToolbarAction.OPEN_IN_BROWSER
                MENU_ID_COPY -> ToolbarAction.COPY_URL
                MENU_ID_DISABLE_SOURCE -> ToolbarAction.DISABLE_SOURCE
                MENU_ID_DELETE_SOURCE -> ToolbarAction.DELETE_SOURCE
                else -> null
            }
            action?.let { fire(it) }
        } finally {
            menuUser32.DestroyMenu(menu)
        }
    }

    // -------------------- BrowserToolbar 状态 (任意线程) --------------------

    override fun setCanNavigate(back: Boolean, forward: Boolean) {
        WebView2Loop.post {
            // 窗口销毁后句柄悬垂: EnableWindow 对无效句柄返回 FALSE, 无副作用
            buttons[ID_BACK]?.let { toolbarUser32Ex.EnableWindow(it, back) }
            buttons[ID_FORWARD]?.let { toolbarUser32Ex.EnableWindow(it, forward) }
        }
    }

    override fun setLoading(value: Boolean) {
        WebView2Loop.post {
            if (loading == value) return@post
            loading = value
            progressHwnd?.let { send(it, PBM_SETMARQUEE, if (value) 1L else 0L, 50L) }
        }
    }

    /** RSS 收藏态: 星图标实心/空心切换 (2026-08-07)。 */
    override fun setStarred(starred: Boolean) {
        WebView2Loop.post {
            buttons[ID_STAR]?.let {
                menuUser32.SetWindowTextW(
                    it,
                    if (starred) "\uE735" else "\uE734"
                )
            }
        }
    }

    /** 页面标题: 仅同步 OS 窗口标题栏 (工具栏不绘制标题)。 */
    fun updateTitle(value: String) {
        menuUser32.SetWindowTextW(hwnd, value)
    }

    // -------------------- 工具 --------------------

    private fun send(h: WinDef.HWND, msg: Int, w: Long, l: Long): Long =
        (User32.INSTANCE.SendMessage(
            h,
            msg,
            WinDef.WPARAM(w),
            WinDef.LPARAM(l)
        ) as Number).toLong()

    companion object {
        /** 工具栏条高度 (px)。 */
        const val HEIGHT = 44

        private const val PROGRESS_H = 2
        private const val BTN_W = 44
        private const val BTN_H = 30
        private const val BTN_TOP = (HEIGHT - PROGRESS_H - BTN_H) / 2
        private const val GAP = 4
        private const val MARGIN = 4

        // 图标字体 (Segoe MDL2 Assets, Win10+ 系统自带; 码点渲染已在本机逐一验证)
        private const val ICON_FONT_FACE = "Segoe MDL2 Assets"
        private const val ICON_FONT_HEIGHT = -18
        private const val FW_NORMAL = 400
        private const val DEFAULT_CHARSET = 1
        private const val OUT_DEFAULT_PRECIS = 0
        private const val CLIP_DEFAULT_PRECIS = 0
        private const val CLEARTYPE_QUALITY = 5
        private const val DEFAULT_PITCH = 0
        private const val WM_SETFONT = 0x0030

        // 按钮 ID (WM_COMMAND LOWORD)
        private const val ID_BACK = 1
        private const val ID_FORWARD = 2
        private const val ID_REFRESH = 3
        private const val ID_OK = 4
        private const val ID_MENU = 5
        private const val ID_STAR = 6
        private const val ID_READ_ALOUD = 7
        private const val ID_SHARE = 8
        private const val ID_LOGIN = 9

        // 菜单项 ID (TrackPopupMenu 返回值); 无刷新项 (2026-08-07: 工具栏已有刷新按钮)
        private const val MENU_ID_BROWSER = 102
        private const val MENU_ID_COPY = 103

        // 书源菜单 (2026-08-08: 对照原版 web_view.xml 禁用源/删除源)
        private const val MENU_ID_DISABLE_SOURCE = 104
        private const val MENU_ID_DELETE_SOURCE = 105

        private const val BS_PUSHBUTTON = 0x0000
        private const val PBM_SETMARQUEE = 0x040A
        private const val MF_STRING = 0x0000
        private const val TPM_RETURNCMD = 0x0100
    }
}

/** gdi32 补充: CreateFontW/DeleteObject (图标字体; gdi32 函数不能并入 user32 接口)。 */
internal interface ToolbarGdi32 : com.sun.jna.win32.StdCallLibrary {
    fun CreateFontW(
        cHeight: Int, cWidth: Int, cEscapement: Int, cOrientation: Int, cWeight: Int,
        dwItalic: Int, dwUnderline: Int, dwStrikeOut: Int, dwCharSet: Int,
        dwOutPrecision: Int, dwClipPrecision: Int, dwQuality: Int,
        dwPitchAndFamily: Int, pszFaceName: String,
    ): Pointer

    fun DeleteObject(hObject: Pointer): Boolean
}

internal val toolbarGdi32: ToolbarGdi32 by lazy {
    com.sun.jna.Native.load(
        "gdi32",
        ToolbarGdi32::class.java,
        com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS
    )
}

/** user32 补充: EnableWindow (jna-platform User32 未覆盖)。 */
internal interface ToolbarUser32Enable : com.sun.jna.win32.StdCallLibrary {
    fun EnableWindow(hWnd: WinDef.HWND, bEnable: Boolean): Boolean
}

internal val toolbarUser32Ex: ToolbarUser32Enable by lazy {
    com.sun.jna.Native.load(
        "user32",
        ToolbarUser32Enable::class.java,
        com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS
    )
}

/** user32 菜单 API (jna-platform User32 未覆盖): CreatePopupMenu/AppendMenuW/TrackPopupMenu/DestroyMenu/SetForegroundWindow。 */
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
