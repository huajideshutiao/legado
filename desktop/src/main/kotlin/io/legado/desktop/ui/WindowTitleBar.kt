package io.legado.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import java.awt.Window

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

// DWM 窗口属性常量 (dwmapi.h / Windows SDK; 35/36 为 Win11 22000+ 新增)
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36

// Win11 22H2+ (22621+) 新增: 无边框窗口恢复系统圆角 (DWMWCP_ROUND=2); 全屏时关闭 (DONOTROUND=1)
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_DONOTROUND = 1
private const val DWMWCP_ROUND = 2

/**
 * dwmapi.dll 声明。DwmSetWindowAttribute 不在 jna-platform 的 User32 里,
 * 需自行声明 (对照 WindowsFileDialogs.kt 的 Shell32Ex 声明方式)。
 */
private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        hwnd: WinDef.HWND,
        dwAttribute: Int,
        pvAttribute: IntByReference,
        cbAttribute: Int,
    ): Int
}

private val dwmapi: Dwmapi? by lazy {
    runCatching {
        Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }.getOrNull()
}

/**
 * 把 AWT 窗口的原生标题栏同步成应用主题 (仅 Windows, 其他系统静默 no-op)。
 *
 * @param window AWT 窗口; null / 未 realize (AWT peer 未就绪) 时静默跳过,
 *               由调用方在窗口显示后重试
 * @param dark 深色主题 (true=深色标题栏 + 浅色按钮/文字)
 * @param accentBg 主题背景色; 非 null 时 Windows 11 顺带设置标题栏底色与文字色,
 *                 拿不到 (null) 时只做深浅两档
 */
fun applyTitleBarTheme(window: Window?, dark: Boolean, accentBg: Color?) {
    // 仅 Windows 有 DWM 标题栏概念, 其他系统静默 no-op
    if (!Platform.isWindows()) return
    val win = window ?: return
    // AWT peer 未 realize 时 getComponentID 拿不到 HWND (返回 0), 静默跳过等窗口就绪
    if (!win.isDisplayable) return
    val hwnd = runCatching { Native.getComponentID(win) }.getOrDefault(0L)
    if (hwnd == 0L) return
    val dwm = dwmapi ?: return
    runCatching {
        val hwndPtr = WinDef.HWND(Pointer.createConstant(hwnd))
        // 1. 深浅两档 (深色主题 → 深色标题栏 + 浅色按钮文字)
        setAttribute(dwm, hwndPtr, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
        // 2. Win11 专属: 标题栏底色跟随主题背景色 + 文字色
        //    (Win10 不认 35/36 属性返回 E_INVALIDARG, 静默忽略只保留深浅两档)
        if (accentBg != null) {
            setAttribute(dwm, hwndPtr, DWMWA_CAPTION_COLOR, accentBg.toColorRef())
            setAttribute(dwm, hwndPtr, DWMWA_TEXT_COLOR, textColorFor(accentBg).toColorRef())
        }
    }.onFailure {
        AppLog.put("WindowsTitleBar: 设置标题栏主题失败", it)
    }
}

/**
 * Win11 22H2+: 设置无边框窗口的圆角偏好。
 * 自绘标题栏去系统装饰后 DWM 默认不画圆角, 显式声明 [DWMWCP_ROUND] 恢复;
 * 真全屏时铺满方角屏幕, 应关闭圆角 ([DWMWCP_DONOTROUND], 用户拍板 2026-08)。
 * Win10/旧版 Win11 不认该属性返回 E_INVALIDARG, 静默忽略保持直角 (无副作用)。
 */
fun applyWindowCornerPreference(window: Window?, round: Boolean) {
    if (!Platform.isWindows()) return
    val win = window ?: return
    if (!win.isDisplayable) return
    val hwnd = runCatching { Native.getComponentID(win) }.getOrDefault(0L)
    if (hwnd == 0L) return
    val dwm = dwmapi ?: return
    setAttribute(
        dwm,
        WinDef.HWND(Pointer.createConstant(hwnd)),
        DWMWA_WINDOW_CORNER_PREFERENCE,
        if (round) DWMWCP_ROUND else DWMWCP_DONOTROUND,
    )
}

/** 单条 DWM 属性写入; 非零 HRESULT (如 Win10 不认 35/36) 只记调试日志, 不中断后续属性 */
private fun setAttribute(dwm: Dwmapi, hwnd: WinDef.HWND, attribute: Int, value: Int) {
    // JNA 里 int 值要传 IntByReference (映射 int*), 不能传裸 Int (会被 JNA 当指针解引用)
    val hr = dwm.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), Int.SIZE_BYTES)
    if (hr != 0) {
        AppLog.putDebug("WindowsTitleBar: DwmSetWindowAttribute(attr=$attribute) 返回 HRESULT=$hr")
    }
}

/** Compose Color (ARGB) → DWM COLORREF (0x00BBGGRR): 交换 R/B 字节并丢弃 alpha (标题栏不支持透明度) */
private fun Color.toColorRef(): Int {
    val rgb = toArgb() and 0xFFFFFF
    return ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb and 0xFF0000) ushr 16)
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
 * 主题色 → 原生标题栏的同步桥 (须在 AppTheme 组合内调用, 读 [AppTheme.colors])。
 *
 * 深/浅主题或背景色变化 (共享层 eventBus.emitRecreate → AppTheme 重组, 重新读取
 * ThemeStore 派生色) 时, LaunchedEffect 的 key (isDark/background) 变化自动重跑,
 * 实时同步标题栏; 启动早期 AWT 窗口可能尚未 realize, 每 100ms 轮询重试至多约 3s。
 */
/**
 * 阅读页激活时的窗口标题栏着色 (由 DesktopReaderPlatformProvider.onEnter/onExit 维护):
 * 把桌面窗口系统标题栏视为状态栏, 跟随小说阅读界面的背景色 (用户要求 2026-08-06);
 * null = 无阅读页激活, 回落 AppTheme 主题色。
 */
internal val readerWindowTint = mutableStateOf<Color?>(null)

@Composable
fun DesktopWindowTitleBarSync(windowHandle: DesktopWindowHandle) {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val background = colors.background
    // 阅读页激活时优先用阅读背景色着色标题栏 (深色背景 → 深色标题栏 + 浅色文字)
    val tint = readerWindowTint.value
    val dark = tint?.let { it.luminance() < 0.5f } ?: isDark
    val bg = tint ?: background
    LaunchedEffect(dark, bg) {
        repeat(30) {
            val window = windowHandle.window
            if (window != null && window.isDisplayable) {
                applyTitleBarTheme(window, isDark, background)
                return@LaunchedEffect
            }
            delay(100)
        }
    }
}
