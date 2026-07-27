package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 鸿蒙端主题设置页入口 (包装 shared/sharedUiMain 的 [ThemeConfigScreen])。
 *
 * 实现模式参考 iOS 端 [IosThemeConfigScreen]: 复用 sharedUiMain 跨平台 Composable,
 * 避免复制代码; 顶栏用 sharedUiMain 的 [AppTitleBar] (项目锁 MD2 视觉, 不用 material3 TopAppBar)。
 *
 * @param onBack 返回回调
 * @param onCoverConfig 跳转封面设置
 * @param onWelcomeStyle 跳转启动界面设置
 */
@Composable
fun OhosThemeConfigScreen(
    onBack: () -> Unit,
    onCoverConfig: () -> Unit = {},
    onWelcomeStyle: () -> Unit = {},
) {
    // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val bookshelfLayoutText = rememberString("ohos_bookshelf_layout_not_implemented")
    val searchLayoutText = rememberString("ohos_search_layout_not_implemented")
    val bottomBarConfigText = rememberString("ohos_bottom_bar_config_not_implemented")
    val themeListText = rememberString("ohos_theme_list_not_implemented")
    val customLightThemeText = rememberString("ohos_custom_light_theme_not_implemented")
    val customDarkThemeText = rememberString("ohos_custom_dark_theme_not_implemented")
    val fontScaleLabel = rememberString("font_scale")
    val sourceEditMaxLineLabel = rememberString("source_edit_text_max_line")

    // fontScale / sourceEditMaxLine 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    val prefs = remember { PreferenceProviders.get() }
    var fontScale by remember { mutableIntStateOf(prefs.getInt(PreferKey.fontScale, 100)) }
    var sourceEditMaxLine by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.sourceEditMaxLine, 10))
    }
    var showFontScaleDialog by remember { mutableStateOf(false) }
    var showSourceEditMaxLineDialog by remember { mutableStateOf(false) }

    val fontScaleSummary = fontScale.toString()
    val sourceEditMaxLineSummary = sourceEditMaxLine.toString()

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("theme_setting"),
            onBack = onBack,
        )
        // 鸿蒙端: 主题列表/自定义主题/底栏/书架布局/搜索布局 stub; 字体缩放/源编辑行数 NumberPicker
        ThemeConfigScreen(
            fontScaleSummary = fontScaleSummary,
            sourceEditMaxLineSummary = sourceEditMaxLineSummary,
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
            onFontScale = { showFontScaleDialog = true },
            onSourceEditMaxLine = { showSourceEditMaxLineDialog = true },
        )
    }

    // fontScale NumberPicker (范围 50..200, 默认 100)
    if (showFontScaleDialog) {
        NumberPickerDialog(
            title = fontScaleLabel,
            value = fontScale,
            range = 50..200,
            onConfirm = {
                fontScale = it
                prefs.putInt(PreferKey.fontScale, it)
                showFontScaleDialog = false
            },
            onDismiss = { showFontScaleDialog = false },
        )
    }
    // sourceEditMaxLine NumberPicker (下限对齐 app min=10, <10 会被 AppConfig 语义视为不限制)
    if (showSourceEditMaxLineDialog) {
        NumberPickerDialog(
            title = sourceEditMaxLineLabel,
            value = sourceEditMaxLine,
            range = 10..20,
            onConfirm = {
                sourceEditMaxLine = it
                prefs.putInt(PreferKey.sourceEditMaxLine, it)
                showSourceEditMaxLineDialog = false
            },
            onDismiss = { showSourceEditMaxLineDialog = false },
        )
    }
}
