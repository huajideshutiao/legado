package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.auto_change_source
import legado.shared.generated.resources.click_regional_config
import legado.shared.generated.resources.double_page_horizontal
import legado.shared.generated.resources.keep_light
import legado.shared.generated.resources.mouse_wheel_page
import legado.shared.generated.resources.page_touch_slop_title
import legado.shared.generated.resources.preview_image_by_click
import legado.shared.generated.resources.progress_bar_behavior
import legado.shared.generated.resources.pt_hide_navigation_bar
import legado.shared.generated.resources.pt_hide_status_bar
import legado.shared.generated.resources.screen_direction
import legado.shared.generated.resources.show_read_title_addition
import legado.shared.generated.resources.text_bottom_justify
import legado.shared.generated.resources.text_full_justify
import legado.shared.generated.resources.use_zh_layout
import legado.shared.generated.resources.double_page_title
import legado.shared.generated.resources.double_page_value
import legado.shared.generated.resources.progress_bar_behavior_title
import legado.shared.generated.resources.progress_bar_behavior_value
import legado.shared.generated.resources.screen_direction_title
import legado.shared.generated.resources.screen_direction_value
import legado.shared.generated.resources.screen_time_out
import legado.shared.generated.resources.screen_time_out_value
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * 阅读界面更多设置（迁 pref_config_read.xml）。逐条对齐原条目顺序/key/默认值。
 * 底部弹窗深色底，条目均 isBottomBackground=true（showReadTitleAddition 原 XML 未设，保持默认）。
 * 各 key 的事件广播由宿主 Dialog 的 OnSharedPreferenceChangeListener 承接。
 *
 * 下沉 shared/sharedUiMain 后:
 * - `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - `stringArrayResource(R.array.xxx)` → `stringArrayResource(Res.array.xxx)` (key-based, 跨平台)
 * - PreferenceScreen/listPreference/switchPreference/preference 走 shared/sharedUiMain 的
 *   io.legado.app.ui.compose.preference 包, 内部通过 LocalPreferenceStoreProvider 读写 prefs
 */
@Composable
fun MoreConfigScreen(
    pageTouchSlopSummary: String,
    onPageTouchSlop: () -> Unit,
    onClickRegionalConfig: () -> Unit,
    onPrefChange: (String) -> Unit = {},
) {
    val screenDirectionEntries = stringArrayResource(Res.array.screen_direction_title)
    val screenDirectionValues = stringArrayResource(Res.array.screen_direction_value)
    val screenTimeOutEntries = stringArrayResource(Res.array.screen_time_out)
    val screenTimeOutValues = stringArrayResource(Res.array.screen_time_out_value)
    val doublePageEntries = stringArrayResource(Res.array.double_page_title)
    val doublePageValues = stringArrayResource(Res.array.double_page_value)
    val progressBarEntries = stringArrayResource(Res.array.progress_bar_behavior_title)
    val progressBarValues = stringArrayResource(Res.array.progress_bar_behavior_value)

    // 按平台过滤: 桌面无系统栏/无屏幕方向, 这三项拨了没有任何效果; 未注册 capabilities 时(如 @Preview)按显示处理
    val caps = PlatformCapabilityProviders.getOrNull()
    val hasSystemBars = caps?.hasSystemBars() ?: true
    val hasScreenOrientation = caps?.hasScreenOrientation() ?: true

    val titleScreenDirection = stringResource(Res.string.screen_direction)
    val titleKeepLight = stringResource(Res.string.keep_light)
    val titleHideStatusBar = stringResource(Res.string.pt_hide_status_bar)
    val titleHideNavigationBar = stringResource(Res.string.pt_hide_navigation_bar)
    val titleDoublePage = stringResource(Res.string.double_page_horizontal)
    val titleProgressBarBehavior = stringResource(Res.string.progress_bar_behavior)
    val titleUseZhLayout = stringResource(Res.string.use_zh_layout)
    val titleTextFullJustify = stringResource(Res.string.text_full_justify)
    val titleTextBottomJustify = stringResource(Res.string.text_bottom_justify)
    val titleMouseWheelPage = stringResource(Res.string.mouse_wheel_page)
    val titlePageTouchSlop = stringResource(Res.string.page_touch_slop_title)
    val titleAutoChangeSource = stringResource(Res.string.auto_change_source)
    val titlePreviewImageByClick = stringResource(Res.string.preview_image_by_click)
    val titleClickRegionalConfig = stringResource(Res.string.click_regional_config)
    val titleShowReadTitleAddition = stringResource(Res.string.show_read_title_addition)

    AppTheme {
        PreferenceScreen {
            if (hasScreenOrientation) {
                listPreference(
                    prefKey = PreferKey.screenOrientation,
                    title = titleScreenDirection,
                    entries = screenDirectionEntries,
                    values = screenDirectionValues,
                    defaultValue = "0",
                    isBottomBackground = true,
                    onValueChange = { onPrefChange(PreferKey.screenOrientation) },
                )
            }
            listPreference(
                prefKey = PreferKey.keepLight,
                title = titleKeepLight,
                entries = screenTimeOutEntries,
                values = screenTimeOutValues,
                defaultValue = "0",
                isBottomBackground = true,
                onValueChange = { onPrefChange(PreferKey.keepLight) },
            )
            if (hasSystemBars) {
                switchPreference(
                    prefKey = PreferKey.hideStatusBar,
                    title = titleHideStatusBar,
                    defaultValue = false,
                    isBottomBackground = true,
                    onCheckedChange = { onPrefChange(PreferKey.hideStatusBar) },
                )
                switchPreference(
                    prefKey = PreferKey.hideNavigationBar,
                    title = titleHideNavigationBar,
                    defaultValue = false,
                    isBottomBackground = true,
                    onCheckedChange = { onPrefChange(PreferKey.hideNavigationBar) },
                )
            }
            listPreference(
                prefKey = PreferKey.doublePageHorizontal,
                title = titleDoublePage,
                entries = doublePageEntries,
                values = doublePageValues,
                defaultValue = "0",
                isBottomBackground = true,
                onValueChange = { onPrefChange(PreferKey.doublePageHorizontal) },
            )
            listPreference(
                prefKey = PreferKey.progressBarBehavior,
                title = titleProgressBarBehavior,
                entries = progressBarEntries,
                values = progressBarValues,
                defaultValue = "page",
                isBottomBackground = true,
                onValueChange = { onPrefChange(PreferKey.progressBarBehavior) },
            )
            switchPreference(
                prefKey = PreferKey.useZhLayout,
                title = titleUseZhLayout,
                defaultValue = false,
                isBottomBackground = true,
                onCheckedChange = { onPrefChange(PreferKey.useZhLayout) },
            )
            switchPreference(
                prefKey = PreferKey.textFullJustify,
                title = titleTextFullJustify,
                defaultValue = true,
                isBottomBackground = true,
                onCheckedChange = { onPrefChange(PreferKey.textFullJustify) },
            )
            switchPreference(
                prefKey = PreferKey.textBottomJustify,
                title = titleTextBottomJustify,
                defaultValue = true,
                isBottomBackground = true,
                onCheckedChange = { onPrefChange(PreferKey.textBottomJustify) },
            )
            switchPreference(
                prefKey = PreferKey.mouseWheelPage,
                title = titleMouseWheelPage,
                defaultValue = true,
                isBottomBackground = true,
            )
            // 移除 volumeKeyPageOnPlay 开关: 按 project_memory 规则, 音量键始终用于翻页
            preference(
                title = titlePageTouchSlop,
                summary = pageTouchSlopSummary,
                isBottomBackground = true,
                onClick = onPageTouchSlop,
            )
            switchPreference(
                prefKey = PreferKey.autoChangeSource,
                title = titleAutoChangeSource,
                defaultValue = true,
                isBottomBackground = true,
            )
            switchPreference(
                prefKey = PreferKey.previewImageByClick,
                title = titlePreviewImageByClick,
                defaultValue = false,
                isBottomBackground = true,
            )
            preference(
                title = titleClickRegionalConfig,
                isBottomBackground = true,
                onClick = onClickRegionalConfig,
            )
            switchPreference(
                prefKey = PreferKey.showReadTitleAddition,
                title = titleShowReadTitleAddition,
                defaultValue = true,
                onCheckedChange = { onPrefChange(PreferKey.showReadTitleAddition) },
            )
        }
    }
}
