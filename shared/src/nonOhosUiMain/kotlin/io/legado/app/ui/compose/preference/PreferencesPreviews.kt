package io.legado.app.ui.compose.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [Preferences.kt] 中 Preference DSL 的 @Preview。
 *
 * PreferenceRow/SingleChoiceDialog 是 internal/private 不可直接 Preview,
 * 但可用公开的 [PreferenceScreen] + preference/preferenceCategory/switchPreference/
 * listPreference/editTextPreference DSL 组合一个示例设置页来 Preview。
 *
 * 注: editTextPreference/listPreference 点击会弹 AppAlertDialog, Preview 中点击交互受限,
 * 但可预览行样式 + 行尾 widget。
 */

@AppPreview
@Composable
fun PreferenceScreenPreview() = LegadoThemePreview {
    PreferenceScreen(modifier = Modifier.fillMaxWidth()) {
        preferenceCategory("分组标题")
        preference(
            title = "普通项",
            summary = "副标题描述",
            onClick = {},
        )
        preference(
            title = "仅标题项(无副标题)",
            onClick = {},
        )
        switchPreference(
            prefKey = "preview_switch",
            title = "开关项",
            summary = "副标题描述",
            defaultValue = false,
            checked = true,
        )
        switchPreference(
            prefKey = "preview_switch_disabled",
            title = "禁用开关项",
            summary = "禁用副标题",
            enabled = false,
            checked = false,
        )
        listPreference(
            prefKey = "preview_list",
            title = "单选项",
            entries = listOf("选项一", "选项二", "选项三"),
            values = listOf("1", "2", "3"),
            defaultValue = "1",
        )
        editTextPreference(
            prefKey = "preview_edit",
            title = "输入项",
            summary = "可输入文本",
            defaultValue = "默认值",
        )
    }
}

@AppPreview
@Composable
fun PreferenceScreenDarkPreview() = LegadoThemePreview(dark = true) {
    PreferenceScreen(modifier = Modifier.fillMaxWidth()) {
        preferenceCategory("深色分组")
        preference(title = "深色普通项", summary = "深色副标题", onClick = {})
        switchPreference(
            prefKey = "preview_switch_dark",
            title = "深色开关",
            defaultValue = true,
            checked = true,
        )
    }
}

/**
 * iconListPreference 的 @Preview: 行尾图标预览 + 点击弹带图标的单选。
 *
 * icons 需在 @Composable 上下文中先 rememberPainter 构造好再传入 LazyListScope
 * (LazyListScope 非 @Composable, 不能在 item lambda 外调 rememberPainter)。
 */
@AppPreview
@Composable
fun PreferenceScreenWithIconListPreview() = LegadoThemePreview {
    val icons = listOf(
        rememberPainter("ic_check"),
        rememberPainter("ic_search"),
        rememberPainter("ic_more_vert"),
    )
    PreferenceScreen(modifier = Modifier.fillMaxWidth()) {
        iconListPreference(
            prefKey = "preview_icon_list",
            title = "图标单选项",
            entries = listOf("选项一", "选项二", "选项三"),
            values = listOf("1", "2", "3"),
            icons = icons,
            defaultValue = "1",
        )
    }
}