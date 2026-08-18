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

/**
 * 启动界面设置页（迁 pref_config_welcome.xml）。逐条对齐原条目。
 * 图片路径 XML key = welcomeImagePath/welcomeImagePathDark（= PreferKey.welcomeImage/welcomeImageDark）。
 * 点击型（启动时长/背景图选择）由宿主提供回调与动态 summary。
 *
 * 白天/夜间各有独立的"显示文字"和"显示图标"开关（还原原版配置）:
 * - 白天: welcomeShowText / welcomeShowIcon
 * - 夜间: welcomeShowTextDark / welcomeShowIconDark
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

            preferenceCategory(labelDay)
            preference(
                title = titleBgImage,
                summary = imageSummary,
                onClick = { onPickImage(false) },
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

            preferenceCategory(labelNight)
            preference(
                title = titleBgImage,
                summary = imageDarkSummary,
                onClick = { onPickImage(true) },
            )
            switchPreference(
                prefKey = PreferKey.welcomeShowTextDark,
                title = titleShowText,
                summary = summaryShowText,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.welcomeShowIconDark,
                title = titleShowIcon,
                summary = summaryShowIcon,
                defaultValue = true,
            )
        }
    }
}
