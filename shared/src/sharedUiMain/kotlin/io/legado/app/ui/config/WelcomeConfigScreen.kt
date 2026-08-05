package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.background_image
import legado.shared.generated.resources.day
import legado.shared.generated.resources.enable_welcome
import legado.shared.generated.resources.enable_welcome_summary
import legado.shared.generated.resources.night
import legado.shared.generated.resources.show_default_book_icon
import legado.shared.generated.resources.show_icon
import legado.shared.generated.resources.show_welcome_text
import legado.shared.generated.resources.welcome_show_time
import legado.shared.generated.resources.welcome_text
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 启动界面设置页（迁 pref_config_welcome.xml）。逐条对齐原条目。
 * 图片路径 XML key = welcomeImagePath/welcomeImagePathDark（= PreferKey.welcomeImage/welcomeImageDark）。
 * 点击型（启动时长/背景图选择）由宿主提供回调与动态 summary。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx) (key-based, 跨平台)
 * - 保留原结构：rememberString 调用放在 AppTheme 内部（与 app 端原版 stringResource 位置一致）
 * - PreferenceScreen/switchPreference/preference/preferenceCategory 走 shared/sharedUiMain 的
 *   io.legado.app.ui.compose.preference 包, 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun WelcomeConfigScreen(
    onShowTime: () -> Unit,
    onPickImage: (isNight: Boolean) -> Unit,
    showTimeSummary: String,
    imageSummary: String,
    imageDarkSummary: String,
) {
    AppTheme {
        // 保留原 app 版结构：stringResource 调用位于 AppTheme 内部、PreferenceScreen 外部
        val titleEnable = stringResource(Res.string.enable_welcome)
        val summaryEnable = stringResource(Res.string.enable_welcome_summary)
        val titleShowTime = stringResource(Res.string.welcome_show_time)
        val titleShowText = stringResource(Res.string.show_welcome_text)
        val summaryShowText = stringResource(Res.string.welcome_text)
        val titleShowIcon = stringResource(Res.string.show_icon)
        val summaryShowIcon = stringResource(Res.string.show_default_book_icon)
        val labelDay = stringResource(Res.string.day)
        val labelNight = stringResource(Res.string.night)
        val titleBgImage = stringResource(Res.string.background_image)
        PreferenceScreen {
            switchPreference(
                prefKey = PreferKey.enableWelcome,
                title = titleEnable,
                summary = summaryEnable,
                defaultValue = true,
            )
            preference(
                title = titleShowTime,
                summary = showTimeSummary,
                onClick = onShowTime,
            )
            switchPreference(
                prefKey = PreferKey.welcomeShowText,
                title = titleShowText,
                summary = summaryShowText,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.welcomeShowIcon,
                title = titleShowIcon,
                summary = summaryShowIcon,
                defaultValue = true,
            )

            preferenceCategory(labelDay)
            preference(
                title = titleBgImage,
                summary = imageSummary,
                onClick = { onPickImage(false) },
            )

            preferenceCategory(labelNight)
            preference(
                title = titleBgImage,
                summary = imageDarkSummary,
                onClick = { onPickImage(true) },
            )
        }
    }
}

// ===== @Preview 合并自 androidMain 的 config/WelcomeConfigScreenPreviews.kt =====

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
