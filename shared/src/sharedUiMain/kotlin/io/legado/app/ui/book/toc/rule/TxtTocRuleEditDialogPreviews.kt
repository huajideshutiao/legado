package io.legado.app.ui.book.toc.rule

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [TxtTocRuleEditDialog] 的 @Preview。
 *
 * 假数据: [TxtTocRule] 纯内存对象, [clipTextProvider] 返回 null,
 * [clipTextSink] 提供空实现 (显示复制规则菜单项)。
 */

private val previewRule = TxtTocRule(
    id = 1L,
    name = "默认规则",
    rule = "^\\s*第[\\d零一二三四五六七八九十百千万]+[章节回卷集部篇].*",
    example = "第一章 测试章节",
    enable = true,
    serialNumber = 0,
)

private val previewRuleEmpty = TxtTocRule(
    id = 2L,
    name = "",
    rule = "",
    example = null,
)

@Preview
@Composable
fun TxtTocRuleEditDialogPreview() = LegadoThemePreview {
    TxtTocRuleEditDialog(
        rule = previewRule,
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
        clipTextSink = {},
    )
}

@Preview
@Composable
fun TxtTocRuleEditDialogNewPreview() = LegadoThemePreview {
    TxtTocRuleEditDialog(
        rule = null,
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
        clipTextSink = {},
    )
}

@Preview
@Composable
fun TxtTocRuleEditDialogNoCopyPreview() = LegadoThemePreview {
    // clipTextSink=null, 不显示复制规则菜单项
    TxtTocRuleEditDialog(
        rule = previewRuleEmpty,
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
        clipTextSink = null,
    )
}

@Preview
@Composable
fun TxtTocRuleEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    TxtTocRuleEditDialog(
        rule = previewRule,
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
        clipTextSink = {},
    )
}
