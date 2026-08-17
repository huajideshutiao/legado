package io.legado.desktop.help.win

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinUser
import io.legado.app.constant.AppLog

/**
 * Win32 隐藏消息窗口样板收敛 (WNDCLASSEX 注册 + CreateWindowEx 建窗)。
 *
 * 需要自建消息泵的地方 (任务栏媒体 DesktopTaskbarMedia / WebView2 消息泵
 * WebView2Loop) 原先各写一份「注册窗口类 + 建屏幕外隐藏窗口」样板, 连
 * 「重复注册返回 ERROR_CLASS_ALREADY_EXISTS 无害」的注释都同款, 收敛于此。
 *
 * TODO(迁移待办): WebView2Loop.registerWindowClass / createWindow(可见分支)
 * 尚未切换到本 helper (该文件本轮不动)。迁移时注意两点差异:
 * - WebView2Loop 的注册给了 hbrBackground (工具栏区背景画刷), 用 [hbrBackground] 参数对齐;
 * - WebView2Loop 的可见窗口分支 (WS_OVERLAPPEDWINDOW + 置前 + 主题同步) 是弹窗语义,
 *   不属于「隐藏消息窗口」, 需留在 WebView2Loop 或另行扩展 helper。
 */

/** 屏幕外坐标: 隐藏窗口摆放位置 (与 WebView2Loop.OFFSCREEN 同值)。 */
private const val OFFSCREEN = -32000

/**
 * 注册 Win32 窗口类 (WNDCLASSEX 填充 + RegisterClassEx)。
 *
 * - 重复注册 (同进程二次 install) 返回 0 + ERROR_CLASS_ALREADY_EXISTS, 无害;
 *   其他失败会让 CreateWindowEx 找不到类 (同样表现为 err=0), 必须留痕便于诊断。
 * - ATOM 是 IntegerType 子类, 不能与 Int 直接比较; 用等值构造比较
 *   (equals 按 value 判等, 等价 intValue() == 0)。
 *
 * @param owner 日志归属标签 (如 "任务栏媒体"), 便于定位是哪个泵注册失败
 * @param hbrBackground 窗口背景画刷, null 用系统默认
 */
internal fun registerMessageWindowClass(
    className: String,
    wndProc: WinUser.WindowProc,
    owner: String,
    hbrBackground: WinDef.HBRUSH? = null,
) {
    val wndClass = WinUser.WNDCLASSEX()
    wndClass.cbSize = wndClass.size()
    wndClass.lpszClassName = className
    wndClass.lpfnWndProc = wndProc
    // HMODULE 继承自 HINSTANCE, 直接给
    wndClass.hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
    if (hbrBackground != null) {
        wndClass.hbrBackground = hbrBackground
    }
    val atom = User32.INSTANCE.RegisterClassEx(wndClass)
    if (atom == WinDef.ATOM(0) && Native.getLastError() != WinError.ERROR_CLASS_ALREADY_EXISTS) {
        AppLog.put("$owner: 注册窗口类 $className 失败 (err=${Native.getLastError()})")
    }
}

/**
 * 创建隐藏消息窗口: WS_POPUP + 屏幕外摆放, 窗口全程不显示 (无头),
 * 但仍是真 HWND, 消息收发 / COM STA 宿主均正常。
 *
 * 建窗失败抛 IllegalStateException (含 GetLastError), 由调用方 runCatching 记录。
 */
internal fun createHiddenMessageWindow(
    className: String,
    title: String,
    width: Int = 1,
    height: Int = 1,
): WinDef.HWND {
    val hwnd = User32.INSTANCE.CreateWindowEx(
        0,
        className,
        title,
        WinUser.WS_POPUP,
        OFFSCREEN, OFFSCREEN, width, height,
        null, null,
        Kernel32.INSTANCE.GetModuleHandle(null),
        null,
    ) ?: error("CreateWindowEx 失败 (err=${Native.getLastError()})")
    return hwnd
}
