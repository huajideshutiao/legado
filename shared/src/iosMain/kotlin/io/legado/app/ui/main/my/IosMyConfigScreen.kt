package io.legado.app.ui.main.my

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端"我的"页入口 (包装 shared/sharedUiMain 的 [MyConfigScreen])。
 *
 * @param onBack 返回回调
 * @param onThemeSetting 跳转主题设置 (由 IosNavHost 注入切到 THEME_CONFIG 路由)
 * @param onWebDavSetting 跳传统份设置 (由 IosNavHost 注入切到 BACKUP_CONFIG 路由)
 * @param onOtherSetting 跳转其它设置 (由 IosNavHost 注入切到 OTHER_CONFIG 路由)
 * @param onBookSourceManage 跳书源管理 (由 IosNavHost 注入切到 BOOK_SOURCE 路由)
 * @param onReplaceManage 跳替换净化 (由 IosNavHost 注入切到 REPLACE_RULE 路由)
 * @param onSourceFilterRuleManage 跳书源过滤规则 (由 IosNavHost 注入切到 SOURCE_FILTER_RULE 路由)
 * @param onTxtTocRuleManage 跳 TXT 目录规则 (由 IosNavHost 注入切到 TXT_TOC_RULE 路由)
 * @param onDictRuleManage 跳字典规则 (由 IosNavHost 注入切到 DICT_RULE 路由)
 * @param onRuleSubManage 跳规则订阅 (由 IosNavHost 注入切到 RULE_SUB 路由)
 * @param onBookmark 跳书签 (由 IosNavHost 注入切到 BOOKMARK 路由)
 * @param onReadRecord 跳阅读记录 (由 IosNavHost 注入切到 READ_RECORD 路由)
 * @param onAbout 跳关于 (由 IosNavHost 注入切到 ABOUT 路由)
 * @param onRssSources 跳 RSS 源列表 (由 IosNavHost 注入切到 RSS_SOURCES 路由, 对照 desktop MyScreen)
 */
@Composable
fun IosMyConfigScreen(
    onBack: () -> Unit,
    onThemeSetting: () -> Unit = {},
    onWebDavSetting: () -> Unit = {},
    onOtherSetting: () -> Unit = {},
    onBookSourceManage: () -> Unit = {},
    onReplaceManage: () -> Unit = {},
    onSourceFilterRuleManage: () -> Unit = {},
    onTxtTocRuleManage: () -> Unit = {},
    onDictRuleManage: () -> Unit = {},
    onRuleSubManage: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onReadRecord: () -> Unit = {},
    onAbout: () -> Unit = {},
    onRssSources: () -> Unit = {},
) {
    // KP-iOS: webService 开关 iOS 端未实现, 恒 false
    val webServiceSummary = rememberString("web_service")
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val themeModeChangeText = rememberString("ios_theme_mode_change_not_implemented")
    val webServiceText = rememberString("ios_web_service_not_implemented")

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("my"),
            onBack = onBack,
        )
        MyConfigScreen(
            webServiceChecked = false,
            webServiceSummary = webServiceSummary,
            onThemeModeChange = {
                Toasters.get().toast(themeModeChangeText)
            },
            onWebServiceChange = { _ ->
                Toasters.get().toast(webServiceText)
            },
            onWebServiceLongClick = {
                Toasters.get().toast(webServiceText)
            },
            onThemeSetting = onThemeSetting,
            onWebDavSetting = onWebDavSetting,
            onOtherSetting = onOtherSetting,
            onBookSourceManage = onBookSourceManage,
            onReplaceManage = onReplaceManage,
            onSourceFilterRuleManage = onSourceFilterRuleManage,
            onTxtTocRuleManage = onTxtTocRuleManage,
            onDictRuleManage = onDictRuleManage,
            onRuleSubManage = onRuleSubManage,
            onBookmark = onBookmark,
            onReadRecord = onReadRecord,
            onAbout = onAbout,
            // RSS 入口: iOS 无底部订阅 tab, 与 desktop 一致走"我的"页 preference 项
            showRssEntry = true,
            onRssSources = onRssSources,
        )
    }
}
