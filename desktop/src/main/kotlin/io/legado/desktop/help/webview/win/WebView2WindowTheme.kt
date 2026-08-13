package io.legado.desktop.help.webview.win

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.lib.theme.ThemeStorePrefKeys

/**
 * WebView2 可见窗口的原生标题栏跟随应用主题, 与主窗口原生控制栏观感统一
 * (用户拍板 2026-08: 主窗口转原生控制栏, webview 窗口同步适配)。
 *
 * - DWMWA_USE_IMMERSIVE_DARK_MODE=20: 标题栏深色两档 (Win10 1809+);
 * - DWMWA_CAPTION_COLOR=35 / DWMWA_TEXT_COLOR=36: 标题栏底色/文字色 (Win11 22000+,
 *   Win10 不认返回 E_INVALIDARG, 静默忽略只保留深浅两档 —— 主窗口 WindowTitleBar
 *   同款已验证策略);
 * - 工具栏标准控件深色: SetWindowTheme(hwnd, "DarkMode_Explorer", null)
 *   (Win10 1809+, uxtheme; 官方 apply-windows-themes 推荐做法)。
 */
internal object WebView2WindowTheme {

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hWnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: IntByReference,
            cbAttribute: Int,
        ): Int
    }

    private interface UxTheme : StdCallLibrary {
        fun SetWindowTheme(hWnd: WinDef.HWND, pszSubAppName: String?, pszSubIdList: String?): Int
    }

    private val dwmapi: Dwmapi? by lazy {
        runCatching {
            Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    private val uxTheme: UxTheme? by lazy {
        runCatching {
            Native.load("uxtheme", UxTheme::class.java, W32APIOptions.UNICODE_OPTIONS)
        }.getOrNull()
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    /** 应用当前深色主题 (themeMode == "2"; 读不到时按浅色)。 */
    fun isDarkTheme(): Boolean = runCatching {
        PreferenceProviders.get().getString(PreferKey.themeMode, "0") == "2"
    }.getOrDefault(false)

    /** 应用主题背景色 (Int ARGB; 未设置时 null → 只做深浅两档)。 */
    private fun themeBgArgb(): Int? = runCatching {
        val p = PreferenceProviders.get()
        if (p.contains(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)) {
            p.getInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)
        } else {
            null
        }
    }.getOrNull()

    /** 窗口背景画刷用 COLORREF (工具栏区背景; 未设置时白)。 */
    fun themeBgColorRef(): Int = toColorRef(themeBgArgb() ?: 0xFFFFFFFF.toInt())

    /** ARGB → COLORREF (0x00BBGGRR, R/B 交换, 去 alpha; 同 WindowTitleBar.toColorRef 语义)。 */
    private fun toColorRef(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    /** 把窗口原生标题栏同步成应用主题 (深浅两档 + Win11 标题栏染色)。 */
    fun apply(hwnd: WinDef.HWND) {
        val dwm = dwmapi ?: return
        val dark = isDarkTheme()
        runCatching {
            dwm.DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                IntByReference(if (dark) 1 else 0),
                4,
            )
            themeBgArgb()?.let { bg ->
                dwm.DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_CAPTION_COLOR,
                    IntByReference(toColorRef(bg)),
                    4,
                )
                val fg = if (dark) 0xFFF2F2F2.toInt() else 0xFF1F1F1F.toInt()
                dwm.DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_TEXT_COLOR,
                    IntByReference(toColorRef(fg)),
                    4,
                )
            }
        }.onFailure {
            AppLog.put("WebView2WindowTheme: DWM 主题同步失败", it)
        }
        applyDarkControls(hwnd, dark)
    }

    /** 工具栏标准控件深色 (对窗口/每个 BUTTON 子窗口调用)。 */
    fun applyDarkControls(hwnd: WinDef.HWND, dark: Boolean) {
        val theme = uxTheme ?: return
        runCatching {
            theme.SetWindowTheme(hwnd, if (dark) "DarkMode_Explorer" else null, null)
        }.onFailure {
            AppLog.put("WebView2WindowTheme: SetWindowTheme 失败", it)
        }
    }
}
