package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberLauncherIconPainters
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.iconListPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 主题设置页（迁 pref_config_theme.xml）。逐条对齐原条目顺序/key/默认值。
 * 换图标 launcherIcon 写 prefs 后仍走宿主的 OnSharedPreferenceChangeListener 触发 LauncherIconHelp；
 * 动态 summary（字体缩放/源编辑行数）与点击型交互（布局/搜索/底栏/主题弹窗/NumberPicker/跳转）由宿主传入。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → rememberString("xxx") (key-based, 跨平台)
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
    val iconNames = rememberStringArray("icon_names")
    val icons = rememberStringArray("icons")
    // 换图标图集：按 mipmap 名解析预览 painter（复刻 IconListPreference：getIdentifier + getCompatDrawable）。
    // 图标是自适应图标(AdaptiveIconDrawable)，painterResource 不支持，Android actual 内部转 bitmap 后包 BitmapPainter。
    val iconPainters = rememberLauncherIconPainters(icons)

    val titleBookshelfLayout = rememberString("bookshelf_layout")
    val titleSearchLayout = rememberString("search_layout")
    val titleBookInfoHLayout = rememberString("book_info_horizontal_layout")
    val summaryBookInfoHLayout = rememberString("book_info_horizontal_layout_summary")
    val titleCoverConfig = rememberString("cover_config")
    val summaryCoverConfig = rememberString("cover_config_summary")
    val titleChangeIcon = rememberString("change_icon")
    val summaryChangeIcon = rememberString("change_icon_summary")
    val titleWelcomeStyle = rememberString("welcome_style")
    val summaryWelcomeStyle = rememberString("welcome_style_summary")
    val titleBottomNav = rememberString("bottom_nav_config")
    val summaryBottomNav = rememberString("bottom_nav_config_summary")
    val titleThemeList = rememberString("theme_list")
    val summaryThemeList = rememberString("theme_list_summary")
    val titleCustomizeDay = rememberString("customize_day_theme")
    val titleCustomizeNight = rememberString("customize_night_theme")
    val titleFontScale = rememberString("font_scale")
    val titleSourceEditMaxLine = rememberString("source_edit_text_max_line")

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
