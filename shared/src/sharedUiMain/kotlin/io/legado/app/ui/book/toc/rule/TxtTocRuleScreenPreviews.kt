package io.legado.app.ui.book.toc.rule

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [TxtTocRuleScreen] 的 @Preview。
 *
 * 假数据: [TxtTocRule] 列表用纯内存对象构造, [TxtTocRuleUiActions] 用 no-op object。
 */

private val previewRules = listOf(
    TxtTocRule(
        id = 1L,
        name = "默认规则",
        rule = "^\\s*第[\\d零一二三四五六七八九十百千万]+[章节回卷集部篇].*",
        example = "第一章 测试章节",
        enable = true,
        serialNumber = 0,
    ),
    TxtTocRule(
        id = 2L,
        name = "序号规则",
        rule = "^\\s*\\d+[、.].*",
        example = "1. 测试章节",
        enable = true,
        serialNumber = 1,
    ),
    TxtTocRule(
        id = 3L,
        name = "禁用规则",
        rule = "^\\s*Chapter\\s+\\d+.*",
        example = "Chapter 1 Test",
        enable = false,
        serialNumber = 2,
    ),
)

private val previewState = TxtTocRuleUiState(
    tocRules = previewRules,
    selected = setOf(2L),
)

private val previewStateEmpty = TxtTocRuleUiState()

/** no-op actions */
private object NoOpRuleActions : TxtTocRuleUiActions {
    override fun onBack() {}
    override fun onAddRule() {}
    override fun onEditRule(item: TxtTocRule) {}
    override fun onImportLocal() {}
    override fun onImportOnline() {}
    override fun onImportDefault() {}
    override fun onHelp() {}
    override fun onToggleSelect(item: TxtTocRule, checked: Boolean) {}
    override fun onSelectAll(all: Boolean) {}
    override fun onRevertSelection() {}
    override fun onDelSelection() {}
    override fun onDel(item: TxtTocRule) {}
    override fun onEnableSelection(enabled: Boolean) {}
    override fun onExportSelection() {}
    override fun onMove(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onToTop(item: TxtTocRule) {}
    override fun onToBottom(item: TxtTocRule) {}
    override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {}
}

@Preview
@Composable
fun TxtTocRuleScreenPreview() = LegadoThemePreview {
    TxtTocRuleScreen(state = previewState, actions = NoOpRuleActions)
}

@Preview
@Composable
fun TxtTocRuleScreenEmptyPreview() = LegadoThemePreview {
    TxtTocRuleScreen(state = previewStateEmpty, actions = NoOpRuleActions)
}

@Preview
@Composable
fun TxtTocRuleScreenDarkPreview() = LegadoThemePreview(dark = true) {
    TxtTocRuleScreen(state = previewState, actions = NoOpRuleActions)
}
