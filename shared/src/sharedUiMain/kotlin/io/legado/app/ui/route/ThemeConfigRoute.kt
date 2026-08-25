package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.config.ThemeConfigScreen
import io.legado.app.ui.config.ThemeConfigScreenModel
import io.legado.app.ui.config.ThemeConfigUiEvent
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.btn_default_s
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.explore_cols
import legado.shared.generated.resources.explore_item_style
import legado.shared.generated.resources.explore_style
import legado.shared.generated.resources.font_scale
import legado.shared.generated.resources.font_scale_summary
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search_layout
import legado.shared.generated.resources.source_edit_max_line_summary
import legado.shared.generated.resources.source_edit_text_max_line
import legado.shared.generated.resources.theme_setting
import legado.shared.generated.resources.unlimited
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 主题设置 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [ThemeConfigScreenModel], 渲染 [ThemeConfigScreen]。
 *
 * 导航类回调 (onCoverConfig/onWelcomeStyle) 用 [AppNavigator.push];
 * fontScale 用 [NumberPickerDialog] 实现 (对照 app 端 showNumberPicker);
 * onSearchLayout 已在 shared 用 [SearchLayoutConfigDialog] 重建 (对照 ThemeConfigHost.configSearch);
 * onBookshelfLayout/onBottomNavConfig/onThemeList/onCustomizeDayTheme/onCustomizeNightTheme
 * 通过 [PlatformCapabilityProviders] 注入各端实现 (app 端 ThemeConfigHost 已实现, 其他端按需 override)。
 */
@Composable
fun ThemeConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val pref = PreferenceProviders.get()
    val eventBus = LocalEventBusProvider.current
    val appConfig = remember { AppConfigProviders.get() }

    val fontScaleFormat = stringResource(Res.string.font_scale_summary)
    val sourceEditMaxLineFormat = stringResource(Res.string.source_edit_max_line_summary)
    val defaultStr = stringResource(Res.string.btn_default_s)
    // 顶栏标题 (对照 app 端 R.string.theme_setting)
    val titleStr = stringResource(Res.string.theme_setting)

    var showFontScalePicker by remember { mutableStateOf(false) }
    var showSearchLayoutPicker by remember { mutableStateOf(false) }
    var showSourceEditMaxLinePicker by remember { mutableStateOf(false) }

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        ThemeConfigScreenModel(
            onBookshelfLayout = {
                PlatformCapabilityProviders.getOrNull()?.showBookshelfLayoutDialog()
            },
            onSearchLayout = { showSearchLayoutPicker = true },
            onBottomNavConfig = {
                PlatformCapabilityProviders.getOrNull()?.showBottomNavConfigDialog()
            },
            onThemeList = {
                PlatformCapabilityProviders.getOrNull()?.showThemeListDialog()
            },
            onCustomizeDayTheme = {
                PlatformCapabilityProviders.getOrNull()?.showCustomizeDayThemeDialog()
            },
            onCustomizeNightTheme = {
                PlatformCapabilityProviders.getOrNull()?.showCustomizeNightThemeDialog()
            },
            onFontScale = { showFontScalePicker = true },
            onSourceEditMaxLine = { showSourceEditMaxLinePicker = true },
        )
    }
    val state by screenModel.state.collectAsState()

    // 对照 app 端 init: 初始化动态 summary
    LaunchedEffect(Unit) {
        if (state.fontScaleSummary.isEmpty()) {
            // 对照 app 端 fontScaleSummary(): getString(R.string.font_scale_summary, getFontScale(activity))
            val fontScale = PlatformCapabilityProviders.getOrNull()?.getFontScale()
            if (fontScale != null) {
                screenModel.dispatch(
                    ThemeConfigUiEvent.UpdateFontScaleSummary(
                        fontScaleFormat.replace(
                            "%s",
                            fontScale
                        )
                    )
                )
            }
        }
        if (state.sourceEditMaxLineSummary.isEmpty()) {
            // 对照 app 端 upPreferenceSummary(sourceEditMaxLine):
            // getString(R.string.source_edit_max_line_summary, AppConfig.sourceEditMaxLine)
            val maxLine = appConfig.sourceEditMaxLine
            val maxLineStr = if (maxLine == Int.MAX_VALUE) "∞" else maxLine.toString()
            screenModel.dispatch(
                ThemeConfigUiEvent.UpdateSourceEditMaxLineSummary(
                    sourceEditMaxLineFormat.replace("%s", maxLineStr)
                )
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        ThemeConfigScreen(
            fontScaleSummary = state.fontScaleSummary,
            onBookshelfLayout = { screenModel.dispatch(ThemeConfigUiEvent.BookshelfLayout) },
            onSearchLayout = { screenModel.dispatch(ThemeConfigUiEvent.SearchLayout) },
            onCoverConfig = { navigator.push(AppRoute.CoverConfig) },
            onWelcomeStyle = { navigator.push(AppRoute.WelcomeConfig) },
            onBottomNavConfig = { screenModel.dispatch(ThemeConfigUiEvent.BottomNavConfig) },
            onThemeList = { screenModel.dispatch(ThemeConfigUiEvent.ThemeList) },
            onCustomizeDayTheme = { screenModel.dispatch(ThemeConfigUiEvent.CustomizeDayTheme) },
            onCustomizeNightTheme = { screenModel.dispatch(ThemeConfigUiEvent.CustomizeNightTheme) },
            onFontScale = { screenModel.dispatch(ThemeConfigUiEvent.FontScale) },
            sourceEditMaxLineSummary = state.sourceEditMaxLineSummary,
            onSourceEditMaxLine = { screenModel.dispatch(ThemeConfigUiEvent.SourceEditMaxLine) },
            // 换桌面图标: 平台能力注入 (Android setComponentEnabledSetting / iOS
            // setAlternateIconName 实现; 桌面/鸿蒙无平台机制 → 该行隐藏)
            iconChangeSupported =
                PlatformCapabilityProviders.getOrNull()?.launcherIconChangeSupported == true,
            onIconChange = { PlatformCapabilityProviders.getOrNull()?.changeLauncherIcon(it) },
        )
    }

    // 字体缩放 NumberPicker (对照 app 端 onFontScale: 8..16, 默认 10, neutralButton=默认)
    // value 取当前 pref 值: 0 (默认) 显示为 10, 非 0 显示实际值 (对照 master 分支
    // showNumberPicker value=10 是硬编码默认值, 此处改为读当前值以正确回显用户设置)
    if (showFontScalePicker) {
        val currentFontScale = pref.getInt(PreferKey.fontScale, 0).let { if (it == 0) 10 else it }
        NumberPickerDialog(
            title = stringResource(Res.string.font_scale),
            value = currentFontScale,
            range = 8..16,
            onConfirm = {
                pref.putInt(PreferKey.fontScale, it)
                // 对照 app 端 onSharedPreferenceChanged: fontScaleSummary + recreate
                val fontScale = PlatformCapabilityProviders.getOrNull()?.getFontScale()
                if (fontScale != null) {
                    screenModel.dispatch(
                        ThemeConfigUiEvent.UpdateFontScaleSummary(
                            fontScaleFormat.replace(
                                "%s",
                                fontScale
                            )
                        )
                    )
                }
                // 对照 app 端 onSharedPreferenceChanged: fontScale 变更后 recreateActivities()
                eventBus.emitRecreate()
            },
            onDismiss = { showFontScalePicker = false },
            neutralButtonText = defaultStr,
            onNeutral = {
                // 对照 app 端 neutralButton: putPrefInt(PreferKey.fontScale, 0)
                pref.putInt(PreferKey.fontScale, 0)
                val fontScale = PlatformCapabilityProviders.getOrNull()?.getFontScale()
                if (fontScale != null) {
                    screenModel.dispatch(
                        ThemeConfigUiEvent.UpdateFontScaleSummary(
                            fontScaleFormat.replace(
                                "%s",
                                fontScale
                            )
                        )
                    )
                }
                // 对照 app 端 onSharedPreferenceChanged: fontScale 变更后 recreateActivities()
                eventBus.emitRecreate()
            },
        )
    }

    // 源编辑框最大行数 NumberPicker (对照 app 端 onSourceEditMaxLine:
    // min=10, max=Int.MAX_VALUE, value=AppConfig.sourceEditMaxLine)
    // 有意偏离原版: 原版 10..Int.MAX_VALUE 的 Slider 无实用价值, 收窄为 5..30 实用区间,
    // "不限制"由中性按钮直达 (存 0, summary 显 ∞); 存储值不在 5..30 一律视为不限制
    // (兼容旧版写入的 Int.MAX_VALUE 与残留脏值), 各端 accessor 同步收口。
    if (showSourceEditMaxLinePicker) {
        val currentMaxLine = pref.getInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            .let { if (it in 5..30) it else Int.MAX_VALUE }
        NumberPickerDialog(
            title = stringResource(Res.string.source_edit_text_max_line),
            value = currentMaxLine,
            range = 5..30,
            neutralButtonText = stringResource(Res.string.unlimited),
            onNeutral = {
                pref.putInt(PreferKey.sourceEditMaxLine, 0)
                screenModel.dispatch(
                    ThemeConfigUiEvent.UpdateSourceEditMaxLineSummary(
                        sourceEditMaxLineFormat.replace("%s", "∞")
                    )
                )
                showSourceEditMaxLinePicker = false
            },
            onConfirm = {
                pref.putInt(PreferKey.sourceEditMaxLine, it)
                val maxLineStr = if (it == Int.MAX_VALUE) "∞" else it.toString()
                screenModel.dispatch(
                    ThemeConfigUiEvent.UpdateSourceEditMaxLineSummary(
                        sourceEditMaxLineFormat.replace("%s", maxLineStr)
                    )
                )
            },
            onDismiss = { showSourceEditMaxLinePicker = false },
        )
    }

    // 搜索布局配置 (对照 app 端 ThemeConfigHost.configSearch)
    if (showSearchLayoutPicker) {
        SearchLayoutConfigDialog(
            currentLayout = appConfig.searchLayout,
            onConfirm = { newLayout ->
                if (appConfig.searchLayout != newLayout) {
                    pref.putInt(PreferKey.searchLayout, newLayout)
                    // 对照原版 configSearch: 变更后 recreateActivities();
                    // 导航栈恢复由 AppNavigatorSaver 保证, 绕过已无必要
                    eventBus.emitRecreate()
                }
            },
            onDismiss = { showSearchLayoutPicker = false },
        )
    }
}

/**
 * 搜索布局配置对话框 (shared Compose 重建, 对照 app 端 dialog_search_config.xml + ThemeConfigHost.configSearch)。
 *
 * 原 dialog 控件: 1 Spinner(explore_item_style: 普通/视频) + 1 SeekBar(列数 0..6) + ok/cancel。
 * 改用 [AppAlertDialog] + 自绘 DropdownBox + [AppSlider] 替代, 行为逐项等价。
 */
@Composable
private fun SearchLayoutConfigDialog(
    currentLayout: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val styles = stringArrayResource(Res.array.explore_item_style)
    var isVideo by remember {
        mutableStateOf(BookSource.exploreStyleIsVideo(currentLayout))
    }
    var selectedCols by remember {
        mutableIntStateOf(BookSource.exploreStyleCols(currentLayout).coerceIn(0, 6))
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.search_layout),
        okButton = AlertButton(text = stringResource(Res.string.ok)) {
            // 对照 app 端 makeLayoutStyle: 视频置 EXPLORE_STYLE_VIDEO_FLAG, cols 取低 3 位
            val newLayout = (if (isVideo) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0) or
                (selectedCols and BookSource.EXPLORE_STYLE_COLS_MASK)
            onConfirm(newLayout)
        },
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
    ) {
        Column(Modifier.padding(horizontal = DesignTokens.spacingDefault, vertical = 8.dp)) {
            // 第一行: explore_style 标签 + 普通/视频 下拉 (对照原 Spinner sp_item_style)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.explore_style),
                    color = colors.primaryText,
                    modifier = Modifier.padding(end = 8.dp),
                )
                LayoutStyleDropdown(
                    options = styles,
                    selectedIndex = if (isVideo) 1 else 0,
                ) { index ->
                    isVideo = index == 1
                }
            }
            // 第二行: explore_cols 标签 + AppSlider 0..6 + 当前值
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.explore_cols),
                    color = colors.primaryText,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppSlider(
                        value = selectedCols,
                        max = 6,
                        onValueChange = { selectedCols = it },
                    )
                }
                Text(
                    selectedCols.toString(),
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** 复刻 AppCompatSpinner 语义: 当前值 + 下拉箭头, 点开 [AppDropdownMenu] 单选 */
@Composable
private fun LayoutStyleDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selectedIndex) { "" },
                color = colors.primaryText,
                fontSize = 14.sp,
            )
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = colors.secondaryText,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(onClick = { expanded = false; onSelect(i) }) {
                    Text(label, color = colors.primaryText)
                }
            }
        }
    }
}
