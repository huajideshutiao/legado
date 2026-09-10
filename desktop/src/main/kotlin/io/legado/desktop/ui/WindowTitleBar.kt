package io.legado.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import io.legado.desktop.help.win.DwmApi
import java.awt.Window
import javax.swing.JFrame

/**
 * Windows 窗口原生层共用件: 圆角偏好 + 标题栏配色规则 + AWT→HWND 转换。
 *
 * # 背景 (对照原版)
 * app 端状态栏/导航栏经 ThemeStore 跟随主题色; 桌面端已改无边框窗口, 控制栏在 Windows 上
 * 由 native (legado_wndchrome) 画、Linux 上由 Compose 自绘 ([DesktopTitleBar]), 两者都不靠
 * `DWMWA_USE_IMMERSIVE_DARK_MODE` / `DWMWA_CAPTION_COLOR` 等 DWM 属性 (那一套现在只留给
 * WebView2 的系统装饰窗口, 见 [io.legado.desktop.help.webview.win.WebView2WindowTheme])。
 *
 * # 本文件职责
 * - [applyWindowCornerPreference]: 无边框窗口圆角 (Win11 22H2+ 的 `DWMWA_WINDOW_CORNER_PREFERENCE`)
 * - [shouldRoundWindowCorner]: 圆角统一决策 (全屏/最大化铺满时方角)
 * - [textColorFor]: 标题栏文字色亮度反推
 * - [readerWindowTint]: 阅读页激活时的标题栏染色
 * - [hwndOrNull]: AWT 窗口 → 原生 HWND
 *
 * hwnd 拿不到 (AWT 窗口未 realize) 时静默跳过, 由调用方等窗口显示后再试。
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
    // hwnd 提取走本文件的 [hwndOrNull] (已包含 isWindows/isDisplayable/id!=0 三道守卫);
    // dwmapi 未加载时 setAttributeChecked 自己就会提前返回, 无需在此重复判空
    val hwnd = window?.hwndOrNull() ?: return
    DwmApi.setAttributeChecked(
        hwnd,
        DwmApi.DWMWA_WINDOW_CORNER_PREFERENCE,
        if (round) DwmApi.DWMWCP_ROUND else DwmApi.DWMWCP_DONOTROUND,
        tag = "WindowsTitleBar",
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
