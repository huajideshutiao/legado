package io.legado.desktop.ui

import com.sun.jna.LastErrorException
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import io.legado.app.constant.AppLog
import java.awt.Component
import java.awt.Window

/**
 * Windows 真全屏控制器: 无边框独占窗口铺满主屏 (覆盖任务栏区域, shell 自动隐藏任务栏),
 * 区别于 AWT GraphicsDevice.setFullScreenWindow 的非独占全屏。
 *
 * # 机制
 * - HWND 获取: 反射 AWT Window.peer.getHWnd (同 DesktopTaskbarMedia.hwndOf;
 *   desktop/build.gradle.kts 已加 --add-opens java.desktop/java.awt=ALL-UNNAMED +
 *   --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED, 反射可用)
 * - enter: GetWindowLongPtr(GWL_STYLE) 保存原样式 → GetWindowRect 保存原 bounds
 *   (须在改样式前取, 样式影响窗口矩形) → 样式去 WS_CAPTION|WS_THICKFRAME 加 WS_POPUP
 *   (LONG_PTR 64 位, 样式位在低位) → SetWindowLongPtr +
 *   SetWindowPos(0,0,SM_CXSCREEN,SM_CYSCREEN, SWP_FRAMECHANGED|SWP_NOZORDER|SWP_NOACTIVATE)
 * - exit: 还原原样式 + SetWindowPos 还原原 bounds (SWP_FRAMECHANGED)
 *
 * # 纪律 (用户裁决)
 * - 不做 AWT fallback: 非 Windows 平台 / HWND 拿不到 / JNA 调用失败 → AppLog.put 显式
 *   记录 (带异常), 直接 return, 不静默降级
 * - SetWindowPos 失败必须立即还原样式 (之前版本缺陷: 只记日志不还原)
 * - 严格幂等: enter 重复 no-op; exit 无状态无害; exit 清状态
 * - 进入后任何一步失败 (样式已改) 都还原样式 + 清状态
 */
object DesktopFullscreenController {

    // ==================== 常量 (Win32) ====================

    // GetWindowLongPtr / SetWindowLongPtr nIndex
    private const val GWL_STYLE = -16

    // 窗口样式
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_POPUP = 0x80000000L

    // GetSystemMetrics nIndex
    private const val SM_CXSCREEN = 0
    private const val SM_CYSCREEN = 1

    // SetWindowPos uFlags
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010

    // ==================== 状态 (仅 setFullscreen 锁内访问) ====================

    /** 当前处于真全屏的窗口 (null = 未全屏)。 */
    private var fullscreenHwnd: WinDef.HWND? = null

    /** 进入全屏前的原始窗口样式 (LONG_PTR; 样式位在低位)。 */
    private var originalStyle: Long = 0L

    /** 进入全屏前的原始窗口位置/尺寸。 */
    private var originalBounds: WinDef.RECT? = null

    // ==================== 入口 ====================

    /**
     * 切换真全屏 (Windows: 无边框独占窗口覆盖任务栏; 非 Windows 平台暂不支持, 显式日志)。
     * 严格幂等: 重复 enter no-op; exit 无状态时无害。
     *
     * @return 调用后全屏状态是否与 [enabled] 一致 (非 Windows / 任一 Win32 步骤失败为
     *         false); 调用方 (DesktopWindowController) 据此同步全局全屏状态。
     */
    @Synchronized
    fun setFullscreen(window: Window, enabled: Boolean): Boolean {
        if (!Platform.isWindows()) {
            AppLog.put("真全屏: 非 Windows 平台暂不支持")
            return false
        }
        return if (enabled) enterFullscreen(window) else exitFullscreen(window)
    }

    // ==================== 进入全屏 ====================

    private fun enterFullscreen(window: Window): Boolean {
        // 幂等: 已在全屏 → 状态一致
        if (fullscreenHwnd != null) return true

        val hwnd = hwndOf(window)
        if (hwnd == null) {
            AppLog.put("真全屏: 无法获取窗口 HWND (peer 反射/getHWnd 失败)")
            return false
        }

        // 保存原样式 (LONG_PTR 以 Pointer 收发, 同 DesktopTaskbarDwm.SubclassWin32)
        val style = try {
            Pointer.nativeValue(FullscreenWin32.INSTANCE.GetWindowLongPtrW(hwnd, GWL_STYLE))
        } catch (e: Throwable) {
            AppLog.put("真全屏: GetWindowLongPtr 失败", e)
            return false
        }

        // 保存原位置/尺寸 (须在改样式前取, 样式变化影响窗口矩形)
        val bounds = WinDef.RECT()
        try {
            if (!FullscreenWin32.INSTANCE.GetWindowRect(hwnd, bounds)) {
                AppLog.put("真全屏: GetWindowRect 失败 (err=${Native.getLastError()})")
                return false
            }
        } catch (e: Throwable) {
            AppLog.put("真全屏: GetWindowRect 失败", e)
            return false
        }

        fullscreenHwnd = hwnd
        originalStyle = style
        originalBounds = bounds

        // 去标题栏/可调边框, 加 WS_POPUP (铺满时覆盖任务栏区域, shell 自动隐藏任务栏)
        val newStyle = (style and (WS_CAPTION or WS_THICKFRAME).inv()) or WS_POPUP
        try {
            FullscreenWin32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, Pointer(newStyle))
        } catch (e: Throwable) {
            AppLog.put("真全屏: SetWindowLongPtr 失败", e)
            clearState()
            return false
        }

        // 铺满主屏 (含任务栏区域); 以下任何失败都还原样式
        val screenW: Int
        val screenH: Int
        try {
            screenW = FullscreenWin32.INSTANCE.GetSystemMetrics(SM_CXSCREEN)
            screenH = FullscreenWin32.INSTANCE.GetSystemMetrics(SM_CYSCREEN)
        } catch (e: Throwable) {
            AppLog.put("真全屏: GetSystemMetrics 失败", e)
            restoreWindow(hwnd, style, bounds)
            clearState()
            return false
        }
        return try {
            val ok = FullscreenWin32.INSTANCE.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                screenW,
                screenH,
                SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOACTIVATE,
            )
            if (!ok) {
                AppLog.put("真全屏: SetWindowPos 失败 (err=${Native.getLastError()})")
                // 失败即还原样式 (之前版本缺陷: 只记日志不还原)
                restoreWindow(hwnd, style, bounds)
                clearState()
            } else {
                // 全屏铺满方角屏幕, 关闭系统圆角 (用户拍板 2026-08: 无圆角屏上带圆角很怪)
                applyWindowCornerPreference(window, round = false)
            }
            ok
        } catch (e: Throwable) {
            AppLog.put("真全屏: SetWindowPos 失败", e)
            restoreWindow(hwnd, style, bounds)
            clearState()
            false
        }
    }

    // ==================== 退出全屏 ====================

    private fun exitFullscreen(window: Window): Boolean {
        // 无状态时状态已一致
        val hwnd = fullscreenHwnd ?: return true
        val ok = restoreWindow(hwnd, originalStyle, originalBounds)
        clearState()
        if (ok) {
            // 退出全屏恢复系统圆角 (与 enterFullscreen 的关闭配对)
            applyWindowCornerPreference(window, round = true)
        }
        return ok
    }

    /** 还原窗口样式与原位置/尺寸 (enter 失败 / exit 共用; 失败显式记日志, 不静默)。 */
    private fun restoreWindow(hwnd: WinDef.HWND, style: Long, bounds: WinDef.RECT?): Boolean {
        var ok = true
        if (style != 0L) {
            try {
                FullscreenWin32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, Pointer(style))
            } catch (e: Throwable) {
                AppLog.put("真全屏: 还原窗口样式失败", e)
                ok = false
            }
        }
        if (bounds != null) {
            try {
                val restored = FullscreenWin32.INSTANCE.SetWindowPos(
                    hwnd,
                    null,
                    bounds.left,
                    bounds.top,
                    bounds.right - bounds.left,
                    bounds.bottom - bounds.top,
                    SWP_FRAMECHANGED or SWP_NOZORDER or SWP_NOACTIVATE,
                )
                if (!restored) {
                    AppLog.put("真全屏: 还原窗口位置失败 (err=${Native.getLastError()})")
                    ok = false
                }
            } catch (e: Throwable) {
                AppLog.put("真全屏: 还原窗口位置失败", e)
                ok = false
            }
        }
        return ok
    }

    /** 清状态 (幂等退出 / 失败路径共用)。 */
    private fun clearState() {
        fullscreenHwnd = null
        originalStyle = 0L
        originalBounds = null
    }

    // ==================== HWND 获取 ====================

    /** AWT Window → 原生 HWND (反射 peer.getHWnd, 同 DesktopTaskbarMedia.hwndOf)。 */
    private fun hwndOf(window: Window): WinDef.HWND? {
        return runCatching {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(window) ?: return null
            val method = peer.javaClass.methods.firstOrNull { it.name == "getHWnd" }
                ?: peer.javaClass.declaredMethods.firstOrNull { it.name == "getHWnd" }
                ?: return null
            method.isAccessible = true
            val value = method.invoke(peer) as? Long ?: return null
            if (value == 0L) null else WinDef.HWND(Pointer(value))
        }.getOrNull()
    }

    // ==================== user32 最小绑定 ====================

    /**
     * user32 最小绑定: GetWindowLongPtr/SetWindowLongPtr 以 Pointer 收发 LONG_PTR
     * (与 jna-platform 版本解耦, 同 DesktopTaskbarDwm.SubclassWin32); 全部声明抛
     * LastErrorException, 调用失败直接以异常暴露 (JNA 调用前清零 last error,
     * 成功调用不抛)。
     */
    private interface FullscreenWin32 : com.sun.jna.Library {
        @Throws(LastErrorException::class)
        fun GetWindowLongPtrW(hWnd: WinDef.HWND, nIndex: Int): Pointer

        @Throws(LastErrorException::class)
        fun SetWindowLongPtrW(hWnd: WinDef.HWND, nIndex: Int, dwNewLong: Pointer): Pointer

        @Throws(LastErrorException::class)
        fun GetSystemMetrics(nIndex: Int): Int

        @Throws(LastErrorException::class)
        fun GetWindowRect(hWnd: WinDef.HWND, lpRect: WinDef.RECT): Boolean

        @Throws(LastErrorException::class)
        fun SetWindowPos(
            hWnd: WinDef.HWND,
            hWndInsertAfter: WinDef.HWND?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean

        companion object {
            val INSTANCE: FullscreenWin32 = Native.load("user32", FullscreenWin32::class.java)
        }
    }
}
