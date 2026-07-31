package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [WelcomeConfigScreen.kt] 中 [WelcomeConfigScreen] 的 @Preview。
 *
 * WelcomeConfigScreen 仅用 rememberString 取 i18n 资源, 无 AppConfigProviders 依赖,
 * 由 [LegadoThemePreview] 提供的 CompositionLocal stub 即可渲染。
 * jvm Preview 端未命中 key 时 rememberString 返回 key 本身, 部分文案为 key 字符串。
 */

@Preview
@Composable
fun WelcomeConfigScreenPreview() = LegadoThemePreview {
    WelcomeConfigScreen(
        onShowTime = {},
        onPickImage = {},
        showTimeSummary = "2 秒",
        imageSummary = "默认封面",
        imageDarkSummary = "默认封面",
    )
}

@Preview
@Composable
fun WelcomeConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    WelcomeConfigScreen(
        onShowTime = {},
        onPickImage = {},
        showTimeSummary = "2 秒",
        imageSummary = "默认封面",
        imageDarkSummary = "默认封面",
    )
}

@Preview
@Composable
fun WelcomeConfigScreenCustomImagePreview() = LegadoThemePreview {
    // 已自定义背景图的态
    WelcomeConfigScreen(
        onShowTime = {},
        onPickImage = {},
        showTimeSummary = "0 秒",
        imageSummary = "/storage/emulated/0/legado/welcome_day.jpg",
        imageDarkSummary = "/storage/emulated/0/legado/welcome_night.jpg",
    )
}
