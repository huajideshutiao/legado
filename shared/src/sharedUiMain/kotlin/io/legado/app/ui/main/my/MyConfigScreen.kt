package io.legado.app.ui.main.my

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_bookmark
import legado.shared.generated.resources.ic_cfg_about
import legado.shared.generated.resources.ic_cfg_backup
import legado.shared.generated.resources.ic_cfg_other
import legado.shared.generated.resources.ic_cfg_replace
import legado.shared.generated.resources.ic_cfg_source
import legado.shared.generated.resources.ic_cfg_theme
import legado.shared.generated.resources.ic_cfg_web
import legado.shared.generated.resources.ic_history
import legado.shared.generated.resources.ic_import
import legado.shared.generated.resources.ic_translate
import legado.shared.generated.resources.outline_filter_alt_24
import legado.shared.generated.resources.about
import legado.shared.generated.resources.backup_restore
import legado.shared.generated.resources.book_source
import legado.shared.generated.resources.book_source_manage
import legado.shared.generated.resources.book_source_manage_desc
import legado.shared.generated.resources.bookmark
import legado.shared.generated.resources.dict_rule
import legado.shared.generated.resources.other
import legado.shared.generated.resources.other_setting
import legado.shared.generated.resources.read_record
import legado.shared.generated.resources.replace_purify
import legado.shared.generated.resources.rule_subscription
import legado.shared.generated.resources.source_filter_rule
import legado.shared.generated.resources.theme_mode
import legado.shared.generated.resources.theme_setting
import legado.shared.generated.resources.theme_setting_s
import legado.shared.generated.resources.txt_toc_rule
import legado.shared.generated.resources.web_dav_set_import_old
import legado.shared.generated.resources.web_service
import legado.shared.generated.resources.theme_mode_v
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 我的页设置内容（迁 pref_main.xml）。逐条对齐原条目顺序/key/默认值/图标。
 * themeMode 切换后 applyDayNight、webService 开关/长按/动态 summary 由 [MyTab] 承接。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx)
 * - stringArrayResource(R.array.xxx) → stringArrayResource(Res.array.xxx)
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
) {
    val themeModeEntries = stringArrayResource(Res.array.theme_mode)
    val themeModeValues = stringArrayResource(Res.array.theme_mode_v)

    val titleThemeMode = stringResource(Res.string.theme_mode)
    val titleThemeSetting = stringResource(Res.string.theme_setting)
    val summaryThemeSetting = stringResource(Res.string.theme_setting_s)
    val titleBackupRestore = stringResource(Res.string.backup_restore)
    val summaryWebDav = stringResource(Res.string.web_dav_set_import_old)
    val titleWebService = stringResource(Res.string.web_service)
    val titleOtherSetting = stringResource(Res.string.other_setting)
    val titleBookSource = stringResource(Res.string.book_source)
    val titleBookSourceManage = stringResource(Res.string.book_source_manage)
    val summaryBookSourceManage = stringResource(Res.string.book_source_manage_desc)
    val titleReplacePurify = stringResource(Res.string.replace_purify)
    val titleSourceFilterRule = stringResource(Res.string.source_filter_rule)
    val titleTxtTocRule = stringResource(Res.string.txt_toc_rule)
    val titleDictRule = stringResource(Res.string.dict_rule)
    val titleRuleSub = stringResource(Res.string.rule_subscription)
    val titleOther = stringResource(Res.string.other)
    val titleBookmark = stringResource(Res.string.bookmark)
    val titleReadRecord = stringResource(Res.string.read_record)
    val titleAbout = stringResource(Res.string.about)

    // rememberPainter 是 @Composable，须在此层取值，不能在 LazyListScope 构建 lambda 内调用
    val iconTheme = painterResource(Res.drawable.ic_cfg_theme)
    val iconBackup = painterResource(Res.drawable.ic_cfg_backup)
    val iconWeb = painterResource(Res.drawable.ic_cfg_web)
    val iconOther = painterResource(Res.drawable.ic_cfg_other)
    val iconSource = painterResource(Res.drawable.ic_cfg_source)
    val iconReplace = painterResource(Res.drawable.ic_cfg_replace)
    val iconFilter = painterResource(Res.drawable.outline_filter_alt_24)
    val iconTranslate = painterResource(Res.drawable.ic_translate)
    val iconImport = painterResource(Res.drawable.ic_import)
    val iconBookmark = painterResource(Res.drawable.ic_bookmark)
    val iconHistory = painterResource(Res.drawable.ic_history)
    val iconAbout = painterResource(Res.drawable.ic_cfg_about)

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

// ===== @Preview 合并自 androidMain 的 main/my/MyConfigScreenPreviews.kt =====

/**
 * [MyConfigScreen.kt] 中 [MyConfigScreen] 的 @Preview。
 *
 * MyConfigScreen 内部用 rememberString/rememberStringArray/rememberPainter 取 i18n 资源,
 * jvm Preview 端未识别 key 时 rememberString 返回 key 本身、rememberStringArray 返回空 List、
 * rememberPainter 返回 ic_material_help 占位, 故 Preview 可渲染但部分文案为 key 字符串。
 *
 * MyConfigScreen 自身已用 AppTheme 包裹, 但 LegadoThemePreview 会再包一层 AppTheme
 * (嵌套 AppTheme 仅多套一次 CompositionLocal, 不影响渲染)。
 */

@Preview
@Composable
fun MyConfigScreenPreview() = LegadoThemePreview {
    MyConfigScreen(
        webServiceChecked = false,
        webServiceSummary = "未启用",
        onThemeModeChange = {},
        onWebServiceChange = {},
        onWebServiceLongClick = {},
        onThemeSetting = {},
        onWebDavSetting = {},
        onOtherSetting = {},
        onBookSourceManage = {},
        onReplaceManage = {},
        onSourceFilterRuleManage = {},
        onTxtTocRuleManage = {},
        onDictRuleManage = {},
        onRuleSubManage = {},
        onBookmark = {},
        onReadRecord = {},
        onAbout = {},
    )
}

@Preview
@Composable
fun MyConfigScreenWebServiceOnPreview() = LegadoThemePreview {
    MyConfigScreen(
        webServiceChecked = true,
        webServiceSummary = "已启用 · http://192.168.1.100:1122",
        onThemeModeChange = {},
        onWebServiceChange = {},
        onWebServiceLongClick = {},
        onThemeSetting = {},
        onWebDavSetting = {},
        onOtherSetting = {},
        onBookSourceManage = {},
        onReplaceManage = {},
        onSourceFilterRuleManage = {},
        onTxtTocRuleManage = {},
        onDictRuleManage = {},
        onRuleSubManage = {},
        onBookmark = {},
        onReadRecord = {},
        onAbout = {},
    )
}

@Preview
@Composable
fun MyConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    MyConfigScreen(
        webServiceChecked = false,
        webServiceSummary = "未启用",
        onThemeModeChange = {},
        onWebServiceChange = {},
        onWebServiceLongClick = {},
        onThemeSetting = {},
        onWebDavSetting = {},
        onOtherSetting = {},
        onBookSourceManage = {},
        onReplaceManage = {},
        onSourceFilterRuleManage = {},
        onTxtTocRuleManage = {},
        onDictRuleManage = {},
        onRuleSubManage = {},
        onBookmark = {},
        onReadRecord = {},
        onAbout = {},
    )
}

@Preview
@Composable
fun MyConfigScreenWithRssEntryPreview() = LegadoThemePreview {
    MyConfigScreen(
        webServiceChecked = false,
        webServiceSummary = "未启用",
        onThemeModeChange = {},
        onWebServiceChange = {},
        onWebServiceLongClick = {},
        onThemeSetting = {},
        onWebDavSetting = {},
        onOtherSetting = {},
        onBookSourceManage = {},
        onReplaceManage = {},
        onSourceFilterRuleManage = {},
        onTxtTocRuleManage = {},
        onDictRuleManage = {},
        onRuleSubManage = {},
        onBookmark = {},
        onReadRecord = {},
        onAbout = {},
    )
}
