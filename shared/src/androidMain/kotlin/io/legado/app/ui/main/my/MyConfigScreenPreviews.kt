package io.legado.app.ui.main.my

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

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
