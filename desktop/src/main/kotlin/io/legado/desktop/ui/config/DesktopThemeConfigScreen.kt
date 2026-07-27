package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.config.ThemeConfigScreen
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 桌面端"主题设置" Screen 入口 (包装 shared/sharedUiMain 的 [ThemeConfigScreen])。
 *
 * # 职责
 *
 * - 在 [ThemeConfigScreen] 之上加 [AppTitleBar] (标题"主题设置" + 返回按钮)
 * - 装配 2 个 summary (fontScaleSummary / sourceEditMaxLineSummary, 从 prefs 读取)
 * - 装配 2 个 NumberPicker 弹窗: fontScale(8..16, 中性"默认"=0 跟随系统) /
 *   sourceEditMaxLine(10..10000, 桌面端 Slider 实用上限, app 端原 max=Int.MAX_VALUE)
 * - 装配 9 个剩余 onClick 回调 (no-op + TODO, 依赖未下沉 Dialog 或平台不适用)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] / [ThemeConfigScreen] 取依赖
 *
 * # 简化项
 *
 * - fontScale: 写 prefs.fontScale 后不调 recreateActivities (桌面端 recreate 概念不适用,
 *   字体缩放由 AppTheme 读取 prefs 触发重组; 中性"默认"=写 0 跟随系统)
 * - sourceEditMaxLine: Slider 范围 10..10000 (app 端 NumberPicker 用 Int.MAX_VALUE,
 *   Slider Float 表示不适合超 1e7, 10000 行已远超实用上限, 不影响实际使用)
 * - 各 onClick (onBookshelfLayout/onCoverConfig/onThemeList 等): 依赖未下沉 Dialog, no-op
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun DesktopThemeConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 PreferenceScreen 通过 LocalPreferenceStoreProvider 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    val prefs = remember { PreferenceProviders.get() }
    val fontScaleLabel = rememberString("font_scale")
    val sourceEditMaxLineLabel = rememberString("source_edit_text_max_line")
    val defaultLabel = rememberString("btn_default_s")
    val followSystemLabel = rememberString("image_style_default") // "默认" 已有 key: 复用 image_style_default (值"默认")

    // fontScale / sourceEditMaxLine 当前值 + 显隐状态
    // fontScale 默认值 0 = 跟随系统 (app 端 AppConfig 默认 10, 但 prefs 未设置时返回 0)
    // sourceEditMaxLine 默认值 Int.MAX_VALUE (app 端 AppConfig 默认), 但 Slider 上限 10000, 用 10000 占位
    var fontScale by remember { mutableIntStateOf(prefs.getInt(PreferKey.fontScale, 0)) }
    var sourceEditMaxLine by remember {
        mutableIntStateOf(
            prefs.getInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE).coerceAtMost(10000),
        )
    }
    var showFontScaleDialog by remember { mutableStateOf(false) }
    var showSourceEditMaxLineDialog by remember { mutableStateOf(false) }
    // 主题列表 / 自定义日间 / 自定义夜间 Dialog 显隐 (接入 desktop 端 ThemeListDialog /
    // ThemeCustomizeDialog, 替代原 TODO 占位)
    var showThemeListDialog by remember { mutableStateOf(false) }
    var showCustomizeDayDialog by remember { mutableStateOf(false) }
    var showCustomizeNightDialog by remember { mutableStateOf(false) }

    // fontScale summary: 0 = 跟随系统, 否则显示数值
    val fontScaleSummary = if (fontScale == 0) followSystemLabel else fontScale.toString()
    val sourceEditMaxLineSummary = sourceEditMaxLine.toString()

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("theme_config"),
                        onBack = onBack,
                    )
                    ThemeConfigScreen(
                        fontScaleSummary = fontScaleSummary,
                        sourceEditMaxLineSummary = sourceEditMaxLineSummary,
                        onBookshelfLayout = { /* TODO: 依赖书架布局选择 Dialog, 桌面端未下沉 */ },
                        onSearchLayout = { /* TODO: 依赖搜索布局选择 Dialog, 桌面端未下沉 */ },
                        onCoverConfig = { /* TODO: 依赖封面配置页, 桌面端未下沉 */ },
                        onWelcomeStyle = { /* TODO: 依赖欢迎页样式选择, 桌面端未下沉 */ },
                        onBottomNavConfig = {
                            // TODO: 桌面端无底部导航栏 (用侧栏), 不适用
                        },
                        onThemeList = { showThemeListDialog = true },
                        onCustomizeDayTheme = { showCustomizeDayDialog = true },
                        onCustomizeNightTheme = { showCustomizeNightDialog = true },
                        onFontScale = { showFontScaleDialog = true },
                        onSourceEditMaxLine = { showSourceEditMaxLineDialog = true },
                    )
                }
            }

            // 2 个 NumberPickerDialog (shared 共享, 替代 app 端 showNumberPicker)
            if (showFontScaleDialog) {
                NumberPickerDialog(
                    title = fontScaleLabel,
                    // fontScale=0 表示跟随系统, NumberPicker 需要非 0 范围, 用 8..16 (app 端范围),
                    // 中性"默认"=写 prefs.fontScale=0 (跟随系统)
                    value = if (fontScale == 0) 10 else fontScale,
                    range = 8..16,
                    onConfirm = {
                        fontScale = it
                        prefs.putInt(PreferKey.fontScale, it)
                        showFontScaleDialog = false
                    },
                    onDismiss = { showFontScaleDialog = false },
                    neutralButtonText = defaultLabel,
                    onNeutral = {
                        fontScale = 0
                        prefs.putInt(PreferKey.fontScale, 0)
                        showFontScaleDialog = false
                    },
                )
            }
            if (showSourceEditMaxLineDialog) {
                NumberPickerDialog(
                    title = sourceEditMaxLineLabel,
                    value = sourceEditMaxLine,
                    // app 端 max=Int.MAX_VALUE, Slider Float 表示不适合超过 1e7 的范围,
                    // 桌面端用 10000 作为上限 (远超源编辑实用行数, 不影响实际使用)
                    range = 10..10000,
                    onConfirm = {
                        sourceEditMaxLine = it
                        prefs.putInt(PreferKey.sourceEditMaxLine, it)
                        showSourceEditMaxLineDialog = false
                    },
                    onDismiss = { showSourceEditMaxLineDialog = false },
                )
            }
            // 主题列表 / 自定义日间 / 自定义夜间 Dialog (desktop 端实现, 替代原 TODO 占位)
            // 在 CompositionLocalProvider + AppTheme 内部, 可访问 LocalThemeStoreProvider /
            // LocalEventBusProvider / LocalPreferenceStoreProvider, ThemeCustomizeDialog /
            // ThemeListDialog 内部通过 applyThemeToStore 应用主题色 + emit recreate
            if (showThemeListDialog) {
                ThemeListDialog(onDismiss = { showThemeListDialog = false })
            }
            if (showCustomizeDayDialog) {
                ThemeCustomizeDialog(
                    mode = ModeEditPrefs,
                    isNight = false,
                    onDismiss = { showCustomizeDayDialog = false },
                )
            }
            if (showCustomizeNightDialog) {
                ThemeCustomizeDialog(
                    mode = ModeEditPrefs,
                    isNight = true,
                    onDismiss = { showCustomizeNightDialog = false },
                )
            }
        }
    }
}
