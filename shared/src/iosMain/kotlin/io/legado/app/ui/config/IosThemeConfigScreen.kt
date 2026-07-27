package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端主题设置页入口 (包装 shared/sharedUiMain 的 [ThemeConfigScreen])。
 *
 * @param onBack 返回回调
 * @param onCoverConfig 跳转封面设置 (由 IosNavHost 注入切到 COVER_CONFIG 路由)
 * @param onWelcomeStyle 跳转启动界面设置 (由 IosNavHost 注入切到 WELCOME_CONFIG 路由)
 */
@Composable
fun IosThemeConfigScreen(
    onBack: () -> Unit,
    onCoverConfig: () -> Unit = {},
    onWelcomeStyle: () -> Unit = {},
) {
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val bookshelfLayoutText = rememberString("ios_bookshelf_layout_not_implemented")
    val searchLayoutText = rememberString("ios_search_layout_not_implemented")
    val bottomBarConfigText = rememberString("ios_bottom_bar_config_not_implemented")
    val themeListText = rememberString("ios_theme_list_not_implemented")
    val customLightThemeText = rememberString("ios_custom_light_theme_not_implemented")
    val customDarkThemeText = rememberString("ios_custom_dark_theme_not_implemented")
    val fontScaleText = rememberString("ios_font_scale_not_implemented")
    val sourceEditMaxLineText = rememberString("ios_source_edit_max_line_not_implemented")
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("theme_setting"),
            onBack = onBack,
        )
        // KP-iOS: 主题列表/自定义主题/底栏/书架布局/搜索布局/字体缩放/源编辑行数 stub
        ThemeConfigScreen(
            fontScaleSummary = "",
            sourceEditMaxLineSummary = "",
            onBookshelfLayout = {
                Toasters.get().toast(bookshelfLayoutText)
            },
            onSearchLayout = {
                Toasters.get().toast(searchLayoutText)
            },
            onCoverConfig = onCoverConfig,
            onWelcomeStyle = onWelcomeStyle,
            onBottomNavConfig = {
                Toasters.get().toast(bottomBarConfigText)
            },
            onThemeList = {
                Toasters.get().toast(themeListText)
            },
            onCustomizeDayTheme = {
                Toasters.get().toast(customLightThemeText)
            },
            onCustomizeNightTheme = {
                Toasters.get().toast(customDarkThemeText)
            },
            onFontScale = {
                Toasters.get().toast(fontScaleText)
            },
            onSourceEditMaxLine = {
                Toasters.get().toast(sourceEditMaxLineText)
            },
        )
    }
}
