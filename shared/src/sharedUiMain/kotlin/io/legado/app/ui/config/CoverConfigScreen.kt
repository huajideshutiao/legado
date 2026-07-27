package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 封面设置页（迁 pref_config_cover.xml）。逐条对齐原条目：
 * key/title/summary/defaultValue/依赖联动/点击行为，读写走 prefs（key 不变）。
 * 点击型（默认封面选择/封面高度）由宿主提供回调（依赖 DialogFragment/NumberPicker）。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → rememberString("xxx") (key-based, 跨平台)
 * - AppConfig.coverShowName/coverShowNameN → LocalPreferenceStoreProvider.current.getBoolean
 *   (替代 app 端 AppConfig 直读, 跨平台通过 PreferenceStoreProvider 注入)
 * - BookCover.upDefaultCover() → onRefreshCover() 回调注入
 *   (BookCover 重 Android 依赖 Glide/Bitmap, 留 app 端; 刷新副作用由宿主承接)
 * - 工具函数 coverCountSummary/coverHeightSummary 保留 app 端 CoverConfigHost
 *   (依赖 Context + BookCover.listDefaultCovers + AppConfig.bookshelfCoverHeight, 无法跨平台)
 * - 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun CoverConfigScreen(
    onDefaultCover: (isNight: Boolean) -> Unit,
    onCoverHeight: () -> Unit,
    // 封面高度变化时（含点击弹窗后）触发 summary 刷新
    coverHeightSummary: String,
    // 默认封面数量变化触发 summary 刷新
    dayCoverSummary: String,
    nightCoverSummary: String,
    // 刷新默认封面缓存 (替代原 BookCover.upDefaultCover() 直接调用)
    onRefreshCover: () -> Unit,
) {
    // coverShowName -> coverShowAuthor 依赖联动（复刻 isEnabled = getPrefBoolean(coverShowName)）
    // LocalPreferenceStoreProvider.current.getBoolean 替代原 AppConfig.coverShowName (跨平台 provider 注入)
    val pref = LocalPreferenceStoreProvider.current
    var showNameDay by remember { mutableStateOf(pref.getBoolean(PreferKey.coverShowName, true)) }
    var showNameNight by remember { mutableStateOf(pref.getBoolean(PreferKey.coverShowNameN, true)) }

    // rememberString 须在 @Composable 上下文取值，LazyListScope 构建 lambda 内不可调用
    val titleCoverHeight = rememberString("bookshelf_cover_height")
    val titleOnlyWifi = rememberString("only_wifi")
    val summaryOnlyWifi = rememberString("only_wifi_summary")
    val titleUseDefault = rememberString("use_default_cover")
    val summaryUseDefault = rememberString("use_default_cover_s")
    val labelDay = rememberString("day")
    val labelNight = rememberString("night")
    val titleDefaultCover = rememberString("default_cover")
    val titleShowName = rememberString("cover_show_name")
    val summaryShowName = rememberString("cover_show_name_summary")
    val titleShowAuthor = rememberString("cover_show_author")
    val summaryShowAuthor = rememberString("cover_show_author_summary")

    AppTheme {
        PreferenceScreen {
            preference(
                title = titleCoverHeight,
                summary = coverHeightSummary,
                onClick = onCoverHeight,
            )
            switchPreference(
                prefKey = PreferKey.loadCoverOnlyWifi,
                title = titleOnlyWifi,
                summary = summaryOnlyWifi,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.useDefaultCover,
                title = titleUseDefault,
                summary = summaryUseDefault,
                defaultValue = false,
            )

            preferenceCategory(labelDay)
            preference(
                title = titleDefaultCover,
                summary = dayCoverSummary,
                onClick = { onDefaultCover(false) },
            )
            switchPreference(
                prefKey = PreferKey.coverShowName,
                title = titleShowName,
                summary = summaryShowName,
                defaultValue = true,
                onCheckedChange = {
                    showNameDay = it
                    onRefreshCover()
                },
            )
            switchPreference(
                prefKey = PreferKey.coverShowAuthor,
                title = titleShowAuthor,
                summary = summaryShowAuthor,
                defaultValue = true,
                enabled = showNameDay,
                onCheckedChange = { onRefreshCover() },
            )

            preferenceCategory(labelNight)
            preference(
                title = titleDefaultCover,
                summary = nightCoverSummary,
                onClick = { onDefaultCover(true) },
            )
            switchPreference(
                prefKey = PreferKey.coverShowNameN,
                title = titleShowName,
                summary = summaryShowName,
                defaultValue = true,
                onCheckedChange = {
                    showNameNight = it
                    onRefreshCover()
                },
            )
            switchPreference(
                prefKey = PreferKey.coverShowAuthorN,
                title = titleShowAuthor,
                summary = summaryShowAuthor,
                defaultValue = true,
                enabled = showNameNight,
                onCheckedChange = { onRefreshCover() },
            )
        }
    }
}
