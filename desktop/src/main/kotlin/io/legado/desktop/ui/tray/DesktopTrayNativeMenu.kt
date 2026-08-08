package io.legado.desktop.ui.tray

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef
import io.legado.app.constant.AppLog
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/**
 * Windows 托盘菜单 (Win11 原生观感): JNA 直调 Win32 菜单 API。
 *
 * # 为什么不用 Swing JPopupMenu / AWT PopupMenu
 * - Swing JPopupMenu: Java2D 自绘, 观感是"模拟菜单" (无 Win11 圆角/深色动效/键盘导航跟随),
 *   且曾引入整套 LAF hack (1x1 勾选列占位/文本居中/系统 LAF 切换) 才勉强可看;
 * - AWT PopupMenu: 底层虽是 TrackPopupMenu (原生渲染), 但 JDK 的 awt_PopupMenu.cpp
 *   用 owner-draw + 窄字节 TextOut 绘制文字, CJK 一律豆腐块 (实测 setFont 无效), 不可用。
 * - Win32 直调 (本实现): CreatePopupMenu + AppendMenuW (宽字符, 中文正常) +
 *   TrackPopupMenuEx(TPM_RETURNCMD), 菜单由 DWM 统一渲染 —— 与资源管理器托盘菜单一致
 *   (深色模式圆角、跟随系统主题、自动翻转避让任务栏), 零 LAF hack。
 *
 * # 机制 (对照 winuser.h / MSDN TrackPopupMenuEx, 已核对)
 * - TPM_RETURNCMD (0x0100): 函数返回值 = 用户选中菜单项的 ID (0 = 未选择/取消),
 *   无需 WM_COMMAND 窗口消息回调;
 * - hwnd 参数: MSDN 明确"必须传窗口句柄 (任意应用窗口均可, 即使 TPM_NONOTIFY)" ——
 *   传主窗口 HWND; TPM_RIGHTBUTTON: 左右键均可选择 (托盘菜单惯例);
 * - 坐标: 屏幕坐标 (物理像素, 非 AWT 逻辑坐标, 多屏/缩放场景须按所在屏 transform 换算);
 * - 模态: TrackPopupMenuEx 在返回前运行内部消息循环, 须在 EDT 上调用 (AWT 消息泵线程),
 *   菜单打开期间主窗口不响应 = 原生菜单的标准模态行为。
 */
internal object DesktopTrayNativeMenu {

    // ---- MF_* (winuser.h) ----
    private const val MF_STRING = 0x00000000
    private const val MF_SEPARATOR = 0x00000800
    private const val MF_GRAYED = 0x00000001
    private const val MF_DISABLED = 0x00000002

    // ---- TPM_* (winuser.h) ----
    private const val TPM_RIGHTBUTTON = 0x0002
    private const val TPM_RETURNCMD = 0x0100

    /** 菜单条目: label=null 为分隔线; enabled=false 为禁用项 (分组标题)。 */
    class Item(
        val label: String? = null,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
    )

    /**
     * 在屏幕物理坐标 (x, y) 弹出原生菜单。
     *
     * # owner 窗口必须是纯 Win32 隐藏窗口 (不能是 AWT 窗口句柄)
     * 实测 (对照实验): hwnd 传主窗口 (AWT JFrame) 时 TrackPopupMenuEx 立即返回
     * 0 + ERROR_INVALID_PARAMETER(87) 菜单不显示; 传 CreateWindowEx 创建的 STATIC
     * 隐藏窗口则完全正常 —— AWT 接管了自身窗口的 WndProc, 菜单模态循环与其交互失败。
     *
     * # 模态循环在专用线程 (不阻塞 EDT)
     * TrackPopupMenuEx 的模态循环若跑在 EDT, 菜单打开期间主窗口 (Compose 渲染/事件
     * 同线程) 整体冻结。这里在独立守护线程内: 创建 owner → 弹菜单 → 销毁 owner,
     * 全部同线程 (窗口消息路由一致); 菜单关闭后经 [onResult] 回调 (调用线程=菜单线程,
     * 命令执行方自行切回 UI 线程)。本函数立即返回, 不阻塞调用方。
     *
     * @param onResult 菜单关闭后的回调 (选中项 action; null=取消/未选择/调用失败)。
     */
    fun show(x: Int, y: Int, items: List<Item>, onResult: (((() -> Unit)?) -> Unit)) {
        Thread({
            var action: (() -> Unit)? = null
            var owner: WinDef.HWND? = null
            try {
                owner = createOwnerWindow()
                if (owner == null) {
                    onResult(null)
                    return@Thread
                }
                val menu = User32Ex.INSTANCE.CreatePopupMenu()
                if (menu == null || menu.pointer == null) {
                    AppLog.put("托盘原生菜单: CreatePopupMenu 失败")
                    onResult(null)
                    return@Thread
                }
                val actionById = HashMap<Int, () -> Unit>()
                var nextId = 1
                try {
                    items.forEach { item ->
                        val id = nextId++
                        val appended = when {
                            item.label == null -> User32Ex.INSTANCE.AppendMenuW(
                                menu, MF_SEPARATOR, NativeLong(0), null
                            )

                            !item.enabled -> User32Ex.INSTANCE.AppendMenuW(
                                menu, MF_STRING or MF_GRAYED or MF_DISABLED,
                                NativeLong(id.toLong()), WString(item.label)
                            )

                            else -> {
                                val ok = User32Ex.INSTANCE.AppendMenuW(
                                    menu, MF_STRING, NativeLong(id.toLong()), WString(item.label)
                                )
                                if (ok && item.action != null) actionById[id] = item.action
                                ok
                            }
                        }
                        if (!appended) {
                            AppLog.put("托盘原生菜单: AppendMenuW 失败 ($id)")
                        }
                    }
                    if (actionById.isNotEmpty()) {
                        // TPM_RETURNCMD: 返回选中项 ID (0 = 未选择/取消)
                        val selected = User32Ex.INSTANCE.TrackPopupMenuEx(
                            menu,
                            TPM_RIGHTBUTTON or TPM_RETURNCMD,
                            x,
                            y,
                            owner,
                            null,
                        )
                        action = actionById[selected]
                    }
                } finally {
                    User32Ex.INSTANCE.DestroyMenu(menu)
                }
            } catch (e: Throwable) {
                AppLog.put("托盘原生菜单弹出异常", e)
            } finally {
                owner?.let { User32Ex.INSTANCE.DestroyWindow(it) }
            }
            onResult(action)
        }, "legado-tray-menu").apply {
            isDaemon = true
            start()
        }
    }

    /** 纯 Win32 隐藏 owner 窗口 (STATIC 系统类, 无需注册; 与模态循环同线程创建)。 */
    private fun createOwnerWindow(): WinDef.HWND? {
        val hwnd = User32Ex.INSTANCE.CreateWindowExW(
            0,
            WString("STATIC"),
            WString("legado-tray-menu-owner"),
            0,
            0, 0, 0, 0,
            null, null, null, null,
        )
        if (hwnd == null || hwnd.pointer == null) {
            AppLog.put("托盘原生菜单: 创建隐藏 owner 窗口失败")
        }
        return hwnd
    }

    /**
     * AWT 逻辑坐标 → Win32 屏幕物理坐标 (按点所在屏幕的缩放换算; TrackPopupMenuEx
     * 以物理像素定位, 多屏 DPI 不同时不能只乘主屏缩放)。
     */
    fun toPhysical(x: Int, y: Int): Pair<Int, Int> {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val config = env.screenDevices.asSequence()
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(x, y) }
            ?: env.defaultScreenDevice.defaultConfiguration
        val t = config.defaultTransform
        return (x * t.scaleX).roundToInt() to (y * t.scaleY).roundToInt()
    }

    /** user32 最小绑定 (dwmapi/FullscreenWin32 同款声明方式; AppendMenuW 宽字符)。 */
    private interface User32Ex : com.sun.jna.Library {
        fun CreatePopupMenu(): WinDef.HMENU

        fun AppendMenuW(
            hMenu: WinDef.HMENU,
            uFlags: Int,
            uIDNewItem: NativeLong,
            lpNewItem: WString?,
        ): Boolean

        /** TPM_RETURNCMD 时返回 UINT_PTR = 选中项 ID (0 = 未选择); 声明 Int (读低 32 位, 菜单 ID 为小整数)。 */
        fun TrackPopupMenuEx(
            hMenu: WinDef.HMENU,
            uFlags: Int,
            x: Int,
            y: Int,
            hwnd: WinDef.HWND,
            lptpm: Pointer?,
        ): Int

        fun DestroyMenu(hMenu: WinDef.HMENU): Boolean

        /** 隐藏 owner 窗口 (TrackPopupMenuEx 的 hwnd 不能是 AWT 窗口句柄, 须纯 Win32 窗口)。 */
        fun CreateWindowExW(
            dwExStyle: Int,
            lpClassName: WString,
            lpWindowName: WString,
            dwStyle: Int,
            x: Int,
            y: Int,
            nWidth: Int,
            nHeight: Int,
            hWndParent: WinDef.HWND?,
            hMenu: WinDef.HMENU?,
            hInstance: Pointer?,
            lpParam: Pointer?,
        ): WinDef.HWND

        fun DestroyWindow(hWnd: WinDef.HWND): Boolean

        companion object {
            val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java)
        }
    }
}
