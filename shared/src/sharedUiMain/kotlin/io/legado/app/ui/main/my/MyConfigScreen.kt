package io.legado.app.ui.main.my

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 我的页设置内容（迁 pref_main.xml）。逐条对齐原条目顺序/key/默认值/图标。
 * themeMode 切换后 applyDayNight、webService 开关/长按/动态 summary 由 [MyTab] 承接。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → rememberString("xxx")
 * - stringArrayResource(R.array.xxx) → rememberStringArray("xxx")
 * - painterResource(R.drawable.xxx) → rememberPainter("xxx")
 * - 与 app 端原包名/类名一致, app/desktop 端共用。
 *
 * ## 资源 key 需求清单（需 ResourceProvider actual 命中）
 *
 * ### Painter key (drawable)
 * - `ic_cfg_theme`        主题/外观（listPreference + preference 共用）
 * - `ic_cfg_backup`       备份/恢复
 * - `ic_cfg_web`          web 服务
 * - `ic_cfg_other`        其它设置入口
 * - `ic_cfg_source`       书源/目录规则
 * - `ic_cfg_replace`      替换净化
 * - `outline_filter_alt_24`  书源过滤
 * - `ic_translate`        字典规则
 * - `ic_import`           规则订阅
 * - `ic_bookmark`         书签
 * - `ic_history`          阅读记录（已注册, 复用）
 * - `ic_cfg_about`        关于
 * - `ic_web_outline`      RSS 源 (仅 showRssEntry=true 时渲染, 桌面端独有)
 *
 * ### String key (string)
 * - `theme_mode`                主题模式（标题）
 * - `theme_setting` / `theme_setting_s`
 * - `backup_restore` / `web_dav_set_import_old`
 * - `web_service`
 * - `other_setting`
 * - `book_source`               分类标题
 * - `book_source_manage` / `book_source_manage_desc`
 * - `replace_purify` / `source_filter_rule` / `txt_toc_rule`
 * - `dict_rule` / `rule_subscription`
 * - `rss_sources`               RSS 源 (仅 showRssEntry=true 时渲染, 桌面端独有)
 * - `other`                     分类标题
 * - `bookmark` / `read_record` / `about`
 *
 * ### StringArray key (string-array)
 * - `theme_mode`        主题模式名（系统/亮/暗/E-Ink）
 * - `theme_mode_v`      主题模式值（0/1/2/3）
 *
 * @see io.legado.app.ui.compose.platform.ResourceProvider
 */
@Composable
fun MyConfigScreen(
    webServiceChecked: Boolean,
    webServiceSummary: String,
    onThemeModeChange: () -> Unit,
    onWebServiceChange: (Boolean) -> Unit,
    onWebServiceLongClick: () -> Unit,
    onThemeSetting: () -> Unit,
    onWebDavSetting: () -> Unit,
    onOtherSetting: () -> Unit,
    onBookSourceManage: () -> Unit,
    onReplaceManage: () -> Unit,
    onSourceFilterRuleManage: () -> Unit,
    onTxtTocRuleManage: () -> Unit,
    onDictRuleManage: () -> Unit,
    onRuleSubManage: () -> Unit,
    onBookmark: () -> Unit,
    onReadRecord: () -> Unit,
    onAbout: () -> Unit,
    // RSS 源入口 (桌面端独有, app 端不传默认 false 不渲染, 避免 app 端 UI 变更)
    showRssEntry: Boolean = false,
    onRssSources: () -> Unit = {},
) {
    val themeModeEntries = rememberStringArray("theme_mode")
    val themeModeValues = rememberStringArray("theme_mode_v")

    val titleThemeMode = rememberString("theme_mode")
    val titleThemeSetting = rememberString("theme_setting")
    val summaryThemeSetting = rememberString("theme_setting_s")
    val titleBackupRestore = rememberString("backup_restore")
    val summaryWebDav = rememberString("web_dav_set_import_old")
    val titleWebService = rememberString("web_service")
    val titleOtherSetting = rememberString("other_setting")
    val titleBookSource = rememberString("book_source")
    val titleBookSourceManage = rememberString("book_source_manage")
    val summaryBookSourceManage = rememberString("book_source_manage_desc")
    val titleReplacePurify = rememberString("replace_purify")
    val titleSourceFilterRule = rememberString("source_filter_rule")
    val titleTxtTocRule = rememberString("txt_toc_rule")
    val titleDictRule = rememberString("dict_rule")
    val titleRuleSub = rememberString("rule_subscription")
    // RSS 源标题 (仅 showRssEntry=true 时取值; app 端无 rss_sources string 资源,
    // 无条件调用会触发 Resources$NotFoundException, 故用条件组合跳过)
    val titleRssSources = if (showRssEntry) rememberString("rss_sources") else ""
    val titleOther = rememberString("other")
    val titleBookmark = rememberString("bookmark")
    val titleReadRecord = rememberString("read_record")
    val titleAbout = rememberString("about")

    // rememberPainter 是 @Composable，须在此层取值，不能在 LazyListScope 构建 lambda 内调用
    val iconTheme = rememberPainter("ic_cfg_theme")
    val iconBackup = rememberPainter("ic_cfg_backup")
    val iconWeb = rememberPainter("ic_cfg_web")
    val iconOther = rememberPainter("ic_cfg_other")
    val iconSource = rememberPainter("ic_cfg_source")
    val iconReplace = rememberPainter("ic_cfg_replace")
    val iconFilter = rememberPainter("outline_filter_alt_24")
    val iconTranslate = rememberPainter("ic_translate")
    val iconImport = rememberPainter("ic_import")
    val iconBookmark = rememberPainter("ic_bookmark")
    val iconHistory = rememberPainter("ic_history")
    val iconAbout = rememberPainter("ic_cfg_about")
    // RSS 源图标 (复用 ic_web_outline, 仅 showRssEntry=true 时渲染)
    val iconRss = rememberPainter("ic_web_outline")

    AppTheme {
        PreferenceScreen {
            listPreference(
                prefKey = PreferKey.themeMode,
                title = titleThemeMode,
                entries = themeModeEntries,
                values = themeModeValues,
                defaultValue = "0",
                icon = iconTheme,
                onValueChange = { onThemeModeChange() },
            )
            preference(
                title = titleThemeSetting,
                summary = summaryThemeSetting,
                icon = iconTheme,
                onClick = onThemeSetting,
            )
            preference(
                title = titleBackupRestore,
                summary = summaryWebDav,
                icon = iconBackup,
                onClick = onWebDavSetting,
            )
            switchPreference(
                prefKey = PreferKey.webService,
                title = titleWebService,
                summary = webServiceSummary,
                defaultValue = false,
                icon = iconWeb,
                onCheckedChange = onWebServiceChange,
                onLongClick = onWebServiceLongClick,
                checked = webServiceChecked,
            )
            preference(
                title = titleOtherSetting,
                icon = iconOther,
                onClick = onOtherSetting,
            )

            preferenceCategory(titleBookSource)
            preference(
                title = titleBookSourceManage,
                summary = summaryBookSourceManage,
                icon = iconSource,
                onClick = onBookSourceManage,
            )
            preference(
                title = titleReplacePurify,
                icon = iconReplace,
                onClick = onReplaceManage,
            )
            preference(
                title = titleSourceFilterRule,
                icon = iconFilter,
                onClick = onSourceFilterRuleManage,
            )
            preference(
                title = titleTxtTocRule,
                icon = iconSource,
                onClick = onTxtTocRuleManage,
            )
            preference(
                title = titleDictRule,
                icon = iconTranslate,
                onClick = onDictRuleManage,
            )
            preference(
                title = titleRuleSub,
                icon = iconImport,
                onClick = onRuleSubManage,
            )
            // RSS 源入口 (仅桌面端 showRssEntry=true 时渲染; app 端默认 false 跳过, UI 不变)
            if (showRssEntry) {
                preference(
                    title = titleRssSources,
                    icon = iconRss,
                    onClick = onRssSources,
                )
            }

            preferenceCategory(titleOther)
            preference(
                title = titleBookmark,
                icon = iconBookmark,
                onClick = onBookmark,
            )
            preference(
                title = titleReadRecord,
                icon = iconHistory,
                onClick = onReadRecord,
            )
            preference(
                title = titleAbout,
                icon = iconAbout,
                onClick = onAbout,
            )
        }
    }
}
