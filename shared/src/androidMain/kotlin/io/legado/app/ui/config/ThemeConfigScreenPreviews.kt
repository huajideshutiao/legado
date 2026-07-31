package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ThemeConfigScreen.kt] 中 [ThemeConfigScreen] 的 @Preview。
 *
 * ThemeConfigScreen 用 rememberStringArray 取图标名/值, rememberLauncherIconPainters
 * 解析 launcher 图标 painter (平台 actual: Android 端转 Bitmap, 其他端可能返回空/占位)。
 * rememberString 在 jvm Preview 端未命中 key 时返回 key 本身, 部分文案为 key 字符串。
 */

@Preview
@Composable
fun ThemeConfigScreenPreview() = LegadoThemePreview {
    ThemeConfigScreen(
        fontScaleSummary = "1.0 倍",
        sourceEditMaxLineSummary = "10 行",
        onBookshelfLayout = {},
        onSearchLayout = {},
        onCoverConfig = {},
        onWelcomeStyle = {},
        onBottomNavConfig = {},
        onThemeList = {},
        onCustomizeDayTheme = {},
        onCustomizeNightTheme = {},
        onFontScale = {},
        onSourceEditMaxLine = {},
    )
}

@Preview
@Composable
fun ThemeConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    ThemeConfigScreen(
        fontScaleSummary = "1.0 倍",
        sourceEditMaxLineSummary = "10 行",
        onBookshelfLayout = {},
        onSearchLayout = {},
        onCoverConfig = {},
        onWelcomeStyle = {},
        onBottomNavConfig = {},
        onThemeList = {},
        onCustomizeDayTheme = {},
        onCustomizeNightTheme = {},
        onFontScale = {},
        onSourceEditMaxLine = {},
    )
}

@Preview
@Composable
fun ThemeConfigScreenLargeFontPreview() = LegadoThemePreview {
    // 大字体 + 多行的态
    ThemeConfigScreen(
        fontScaleSummary = "1.3 倍",
        sourceEditMaxLineSummary = "20 行",
        onBookshelfLayout = {},
        onSearchLayout = {},
        onCoverConfig = {},
        onWelcomeStyle = {},
        onBottomNavConfig = {},
        onThemeList = {},
        onCustomizeDayTheme = {},
        onCustomizeNightTheme = {},
        onFontScale = {},
        onSourceEditMaxLine = {},
    )
}
