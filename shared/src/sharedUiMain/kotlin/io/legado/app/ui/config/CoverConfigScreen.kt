package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.FlowBus
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf_cover_height
import legado.shared.generated.resources.cover_show_author
import legado.shared.generated.resources.cover_show_author_summary
import legado.shared.generated.resources.cover_show_name
import legado.shared.generated.resources.cover_show_name_summary
import legado.shared.generated.resources.day
import legado.shared.generated.resources.default_cover
import legado.shared.generated.resources.night
import legado.shared.generated.resources.only_wifi
import legado.shared.generated.resources.only_wifi_summary
import legado.shared.generated.resources.use_default_cover
import legado.shared.generated.resources.use_default_cover_s
import org.jetbrains.compose.resources.stringResource

/**
 * 封面设置页（迁 pref_config_cover.xml）。逐条对齐原条目：
 * key/title/summary/defaultValue/依赖联动/点击行为，读写走 prefs（key 不变）。
 * 点击型（默认封面选择/封面高度）由宿主提供回调（依赖 DialogFragment/NumberPicker）。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx) (key-based, 跨平台)
 * - AppConfig.coverShowName/coverShowNameN → PreferenceProviders.get().getBoolean
 *   (替代 app 端 AppConfig 直读, 跨平台走 PreferenceProviders 单例)
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
    // PreferenceProviders.get().getBoolean 替代原 AppConfig.coverShowName (跨平台 provider 注入)
    val pref = PreferenceProviders.get()
    var showNameDay by remember { mutableStateOf(pref.getBoolean(PreferKey.coverShowName, true)) }
    var showNameNight by remember { mutableStateOf(pref.getBoolean(PreferKey.coverShowNameN, true)) }

    // rememberString 须在 @Composable 上下文取值，LazyListScope 构建 lambda 内不可调用
    val titleCoverHeight = stringResource(Res.string.bookshelf_cover_height)
    val titleOnlyWifi = stringResource(Res.string.only_wifi)
    val summaryOnlyWifi = stringResource(Res.string.only_wifi_summary)
    val titleUseDefault = stringResource(Res.string.use_default_cover)
    val summaryUseDefault = stringResource(Res.string.use_default_cover_s)
    val labelDay = stringResource(Res.string.day)
    val labelNight = stringResource(Res.string.night)
    val titleDefaultCover = stringResource(Res.string.default_cover)
    val titleShowName = stringResource(Res.string.cover_show_name)
    val summaryShowName = stringResource(Res.string.cover_show_name_summary)
    val titleShowAuthor = stringResource(Res.string.cover_show_author)
    val summaryShowAuthor = stringResource(Res.string.cover_show_author_summary)

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
                // 通知书架重读封面配置 (对照原版 CoverConfigFragment 封面类变更发 BOOKSHELF_REFRESH)
                onCheckedChange = { FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("") },
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
