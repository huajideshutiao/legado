package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberLauncherIconPainters
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.iconListPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.book_info_horizontal_layout
import legado.shared.generated.resources.book_info_horizontal_layout_summary
import legado.shared.generated.resources.bookshelf_layout
import legado.shared.generated.resources.bottom_nav_config
import legado.shared.generated.resources.bottom_nav_config_summary
import legado.shared.generated.resources.change_icon
import legado.shared.generated.resources.change_icon_summary
import legado.shared.generated.resources.cover_config
import legado.shared.generated.resources.cover_config_summary
import legado.shared.generated.resources.customize_day_theme
import legado.shared.generated.resources.customize_night_theme
import legado.shared.generated.resources.font_scale
import legado.shared.generated.resources.search_layout
import legado.shared.generated.resources.source_edit_text_max_line
import legado.shared.generated.resources.theme_list
import legado.shared.generated.resources.theme_list_summary
import legado.shared.generated.resources.welcome_style
import legado.shared.generated.resources.welcome_style_summary
import legado.shared.generated.resources.icon_names
import legado.shared.generated.resources.icons
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 主题设置页（迁 pref_config_theme.xml）。逐条对齐原条目顺序/key/默认值。
 * 换图标 launcherIcon 写 prefs 后仍走宿主的 OnSharedPreferenceChangeListener 触发 LauncherIconHelp；
 * 动态 summary（字体缩放/源编辑行数）与点击型交互（布局/搜索/底栏/主题弹窗/NumberPicker/跳转）由宿主传入。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx) (key-based, 跨平台)
 * - stringArrayResource(R.array.icon_names/icons) → rememberStringArray("icon_names"/"icons")
 * - LocalContext + getCompatDrawable + BitmapPainter 图标加载链 → rememberLauncherIconPainters
 *   (AdaptiveIconDrawable 不支持 painterResource, Android actual 内部转 Bitmap)
 * - PreferenceScreen/switchPreference/iconListPreference/preference 走 shared/sharedUiMain 的
 *   io.legado.app.ui.compose.preference 包, 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun ThemeConfigScreen(
    fontScaleSummary: String,
    sourceEditMaxLineSummary: String,
    onBookshelfLayout: () -> Unit,
    onSearchLayout: () -> Unit,
    onCoverConfig: () -> Unit,
    onWelcomeStyle: () -> Unit,
    onBottomNavConfig: () -> Unit,
    onThemeList: () -> Unit,
    onCustomizeDayTheme: () -> Unit,
    onCustomizeNightTheme: () -> Unit,
    onFontScale: () -> Unit,
    onSourceEditMaxLine: () -> Unit,
) {
    val iconNames = stringArrayResource(Res.array.icon_names)
    val icons = stringArrayResource(Res.array.icons)
    // 换图标图集：按 mipmap 名解析预览 painter（复刻 IconListPreference：getIdentifier + getCompatDrawable）。
    // 图标是自适应图标(AdaptiveIconDrawable)，painterResource 不支持，Android actual 内部转 bitmap 后包 BitmapPainter。
    val iconPainters = rememberLauncherIconPainters(icons)

    val titleBookshelfLayout = stringResource(Res.string.bookshelf_layout)
    val titleSearchLayout = stringResource(Res.string.search_layout)
    val titleBookInfoHLayout = stringResource(Res.string.book_info_horizontal_layout)
    val summaryBookInfoHLayout = stringResource(Res.string.book_info_horizontal_layout_summary)
    val titleCoverConfig = stringResource(Res.string.cover_config)
    val summaryCoverConfig = stringResource(Res.string.cover_config_summary)
    val titleChangeIcon = stringResource(Res.string.change_icon)
    val summaryChangeIcon = stringResource(Res.string.change_icon_summary)
    val titleWelcomeStyle = stringResource(Res.string.welcome_style)
    val summaryWelcomeStyle = stringResource(Res.string.welcome_style_summary)
    val titleBottomNav = stringResource(Res.string.bottom_nav_config)
    val summaryBottomNav = stringResource(Res.string.bottom_nav_config_summary)
    val titleThemeList = stringResource(Res.string.theme_list)
    val summaryThemeList = stringResource(Res.string.theme_list_summary)
    val titleCustomizeDay = stringResource(Res.string.customize_day_theme)
    val titleCustomizeNight = stringResource(Res.string.customize_night_theme)
    val titleFontScale = stringResource(Res.string.font_scale)
    val titleSourceEditMaxLine = stringResource(Res.string.source_edit_text_max_line)

    AppTheme {
        PreferenceScreen {
            preference(
                title = titleBookshelfLayout,
                onClick = onBookshelfLayout,
            )
            preference(
                title = titleSearchLayout,
                onClick = onSearchLayout,
            )
            switchPreference(
                prefKey = PreferKey.bookInfoHorizontalLayout,
                title = titleBookInfoHLayout,
                summary = summaryBookInfoHLayout,
                defaultValue = false,
            )
            preference(
                title = titleCoverConfig,
                summary = summaryCoverConfig,
                onClick = onCoverConfig,
            )
            iconListPreference(
                prefKey = PreferKey.launcherIcon,
                title = titleChangeIcon,
                summary = summaryChangeIcon,
                entries = iconNames,
                values = icons,
                icons = iconPainters,
                defaultValue = "ic_launcher",
            )
            preference(
                title = titleWelcomeStyle,
                summary = summaryWelcomeStyle,
                onClick = onWelcomeStyle,
            )
            preference(
                title = titleBottomNav,
                summary = summaryBottomNav,
                onClick = onBottomNavConfig,
            )
            preference(
                title = titleThemeList,
                summary = summaryThemeList,
                onClick = onThemeList,
            )
            preference(
                title = titleCustomizeDay,
                onClick = onCustomizeDayTheme,
            )
            preference(
                title = titleCustomizeNight,
                onClick = onCustomizeNightTheme,
            )
            preference(
                title = titleFontScale,
                summary = fontScaleSummary,
                onClick = onFontScale,
            )
            preference(
                title = titleSourceEditMaxLine,
                summary = sourceEditMaxLineSummary,
                onClick = onSourceEditMaxLine,
            )
        }
    }
}

// ===== @Preview 合并自 androidMain 的 config/ThemeConfigScreenPreviews.kt =====

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
