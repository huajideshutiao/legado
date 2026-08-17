package io.legado.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import io.legado.app.constant.AppLog
import io.legado.desktop.help.win.DwmApi
import java.awt.Window
import javax.swing.JFrame

/**
 * Windows 原生标题栏 (最小化/最大化/关闭按钮那一条) 跟随应用主题。
 *
 * # 背景 (对照原版)
 * app 端状态栏/导航栏经 ThemeStore 跟随主题色; 桌面端窗口带系统装饰标题栏,
 * 深色主题下标题栏仍是系统浅色, 观感割裂。这里经 DWM API 把标题栏同步成
 * AppTheme 的深浅色 + 主题背景色 (仅 Windows 生效, 其他系统静默 no-op)。
 *
 * # 实现
 * - 深浅两档: `DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE=20, 1/0)`
 *   (Win10 1809+ 支持)
 * - Windows 11 (22000+) 额外支持 `DWMWA_CAPTION_COLOR=35` (标题栏底色) 与
 *   `DWMWA_TEXT_COLOR=36` (标题/按钮文字色): 底色用主题背景色, 文字色按背景亮度
 *   反推 (浅底深字/深底浅字, 与 AppTheme.primaryText 同语义); Win10 不认这两个属性,
 *   DwmSetWindowAttribute 返回 E_INVALIDARG, 静默忽略只保留深浅两档。
 * - 全程 runCatching, 失败只记 AppLog 不崩溃; hwnd 拿不到 (AWT 窗口未 realize) 时
 *   静默跳过, 由调用方等窗口显示后再试。
 */

// DWM 窗口属性常量与 dwmapi.dll 绑定统一收口在 help/win/DwmApi
// (原先与 WebView2WindowTheme / DesktopTaskbarDwm 各写一份)

/**
 * Win11 22H2+: 设置无边框窗口的圆角偏好。
 * 自绘标题栏去系统装饰后 DWM 默认不画圆角, 显式声明 [DwmApi.DWMWCP_ROUND] 恢复;
 * 真全屏时铺满方角屏幕, 应关闭圆角 ([DwmApi.DWMWCP_DONOTROUND], 用户拍板 2026-08)。
 * Win10/旧版 Win11 不认该属性返回 E_INVALIDARG, 静默忽略保持直角 (无副作用)。
 */
fun applyWindowCornerPreference(window: Window?, round: Boolean) {
    if (!Platform.isWindows()) return
    val win = window ?: return
    if (!win.isDisplayable) return
    val hwnd = runCatching { Native.getComponentID(win) }.getOrDefault(0L)
    if (hwnd == 0L) return
    if (DwmApi.dwmapi == null) return
    setAttribute(
        WinDef.HWND(Pointer.createConstant(hwnd)),
        DwmApi.DWMWA_WINDOW_CORNER_PREFERENCE,
        if (round) DwmApi.DWMWCP_ROUND else DwmApi.DWMWCP_DONOTROUND,
    )
}

/**
 * 窗口圆角统一决策: 无边框真全屏 ([DesktopWindowChrome.fullscreen]) / 最大化铺满 (贴边)
 * 时窗口应为方角 (去圆角), 其余状态保留圆角。所有圆角设置点 (自绘控制栏重组 / 真全屏
 * 进出) 共用本决策, 防止一处恢复圆角覆盖另一处已去除的圆角。
 */
fun shouldRoundWindowCorner(window: Window): Boolean =
    !DesktopWindowChrome.fullscreen &&
        (window as? JFrame)?.let { (it.extendedState and JFrame.MAXIMIZED_BOTH) == 0 } ?: true

/** 单条 DWM 属性写入; 非零 HRESULT (如 Win10 不认 35/36) 只记调试日志, 不中断后续属性 */
private fun setAttribute(hwnd: WinDef.HWND, attribute: Int, value: Int) {
    val hr = DwmApi.setAttribute(hwnd, attribute, value) ?: return
    if (hr != 0) {
        AppLog.putDebug("WindowsTitleBar: DwmSetWindowAttribute(attr=$attribute) 返回 HRESULT=$hr")
    }
}

/**
 * 标题栏文字色: 按背景亮度反推 (浅底深字/深底浅字), 与 AppTheme.primaryText
 * (readAppColors 的「背景亮度反推」语义) 保持一致。
 *
 * internal: 自绘窗口控制栏 (DesktopTitleBar) 复用同一亮度反推规则。
 */
internal fun textColorFor(bg: Color): Color =
    if (bg.luminance() >= 0.5f) Color(0xFF212121) else Color(0xFFF8F8F8)

/**
 * 阅读页激活时的窗口标题栏着色 (由 DesktopReaderPlatformProvider.onEnter/onExit 维护):
 * 把桌面窗口系统标题栏视为状态栏, 跟随小说阅读界面的背景色 (用户要求 2026-08-06);
 * null = 无阅读页激活, 回落 AppTheme 主题色。
 */
internal val readerWindowTint = mutableStateOf<Color?>(null)

/**
 * AWT 窗口 → 原生 HWND (Windows 专用; 非 Windows / 未 realize / 取不到时返回 null)。
 *
 * 用 JNA 官方 [Native.getComponentID] 而非反射 `peer.getHWnd`: 后者依赖 JDK 内部 API 与
 * `--add-opens java.desktop/java.awt`, 且曾在三个文件里逐字重复三份 (全屏控制器 / 任务栏卡片 /
 * 任务栏媒体)。此处统一收口。
 */
internal fun Window.hwndOrNull(): WinDef.HWND? {
    if (!Platform.isWindows() || !isDisplayable) return null
    val id = runCatching { Native.getComponentID(this) }.getOrDefault(0L)
    return if (id == 0L) null else WinDef.HWND(Pointer.createConstant(id))
}
