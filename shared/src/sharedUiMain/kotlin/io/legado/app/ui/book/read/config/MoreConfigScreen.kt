package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 阅读界面更多设置（迁 pref_config_read.xml）。逐条对齐原条目顺序/key/默认值。
 * 底部弹窗深色底，条目均 isBottomBackground=true（showReadTitleAddition 原 XML 未设，保持默认）。
 * 各 key 的事件广播由宿主 Dialog 的 OnSharedPreferenceChangeListener 承接。
 *
 * 下沉 shared/sharedUiMain 后:
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - `stringArrayResource(R.array.xxx)` → `rememberStringArray("xxx")` (key-based, 跨平台)
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
    val screenDirectionEntries = rememberStringArray("screen_direction_title")
    val screenDirectionValues = rememberStringArray("screen_direction_value")
    val screenTimeOutEntries = rememberStringArray("screen_time_out")
    val screenTimeOutValues = rememberStringArray("screen_time_out_value")
    val doublePageEntries = rememberStringArray("double_page_title")
    val doublePageValues = rememberStringArray("double_page_value")
    val progressBarEntries = rememberStringArray("progress_bar_behavior_title")
    val progressBarValues = rememberStringArray("progress_bar_behavior_value")

    val titleScreenDirection = rememberString("screen_direction")
    val titleKeepLight = rememberString("keep_light")
    val titleHideStatusBar = rememberString("pt_hide_status_bar")
    val titleHideNavigationBar = rememberString("pt_hide_navigation_bar")
    val titleDoublePage = rememberString("double_page_horizontal")
    val titleProgressBarBehavior = rememberString("progress_bar_behavior")
    val titleUseZhLayout = rememberString("use_zh_layout")
    val titleTextFullJustify = rememberString("text_full_justify")
    val titleTextBottomJustify = rememberString("text_bottom_justify")
    val titleMouseWheelPage = rememberString("mouse_wheel_page")
    val titleVolumeKeyPageOnPlay = rememberString("volume_key_page_on_play")
    val titlePageTouchSlop = rememberString("page_touch_slop_title")
    val titleAutoChangeSource = rememberString("auto_change_source")
    val titlePreviewImageByClick = rememberString("preview_image_by_click")
    val titleClickRegionalConfig = rememberString("click_regional_config")
    val titleShowReadTitleAddition = rememberString("show_read_title_addition")

    AppTheme {
        PreferenceScreen {
            listPreference(
                prefKey = PreferKey.screenOrientation,
                title = titleScreenDirection,
                entries = screenDirectionEntries,
                values = screenDirectionValues,
                defaultValue = "0",
                isBottomBackground = true,
                onValueChange = { onPrefChange(PreferKey.screenOrientation) },
            )
            listPreference(
                prefKey = PreferKey.keepLight,
                title = titleKeepLight,
                entries = screenTimeOutEntries,
                values = screenTimeOutValues,
                defaultValue = "0",
                isBottomBackground = true,
                onValueChange = { onPrefChange(PreferKey.keepLight) },
            )
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
            switchPreference(
                prefKey = PreferKey.volumeKeyPageOnPlay,
                title = titleVolumeKeyPageOnPlay,
                defaultValue = false,
                isBottomBackground = true,
            )
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
