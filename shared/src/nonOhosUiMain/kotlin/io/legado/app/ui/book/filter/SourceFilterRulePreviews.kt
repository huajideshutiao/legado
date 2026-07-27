package io.legado.app.ui.book.filter

import androidx.compose.runtime.Composable
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewFilterRules

/** [SourceFilterRuleScreen] / [SourceFilterEditDialog] 的 @Preview。 */

private val noOpFilterActions = object : SourceFilterRuleUiActions {
    override fun onBack() {}
    override fun onSearchKeyChange(key: String) {}
    override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {}
    override fun onSelectAll(all: Boolean) {}
    override fun onRevertSelection() {}
    override fun onMoveItem(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onDeleteSelection() {}
    override fun onDeleteRule(rule: SourceFilterRule) {}
    override fun onDeleteAll() {}
    override fun onEnableSelection() {}
    override fun onDisableSelection() {}
    override fun onTopSelect() {}
    override fun onBottomSelect() {}
    override fun onExportSelection() {}
    override fun onEditRule(rule: SourceFilterRule) {}
    override fun onToTop(rule: SourceFilterRule) {}
    override fun onToBottom(rule: SourceFilterRule) {}
    override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {}
    override fun onAddRule() {}
    override fun onImportLocal() {}
    override fun onImportOnline() {}
}

// ---- SourceFilterRuleScreen ----

@AppPreview
@Composable
fun SourceFilterRuleScreenPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(rules = previewFilterRules),
        actions = noOpFilterActions,
    )
}

@AppPreview
@Composable
fun SourceFilterRuleScreenEmptyPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(),
        actions = noOpFilterActions,
    )
}

@AppPreview
@Composable
fun SourceFilterRuleScreenSearchingPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(
            rules = previewFilterRules.take(1),
            searchKey = "同人",
        ),
        actions = noOpFilterActions,
    )
}

@AppPreview
@Composable
fun SourceFilterRuleScreenDarkPreview() = LegadoThemePreview(dark = true) {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(
            rules = previewFilterRules,
            selected = setOf(previewFilterRules[0].id),
        ),
        actions = noOpFilterActions,
    )
}

// ---- SourceFilterEditDialog ----

@AppPreview
@Composable
fun SourceFilterEditDialogPreview() = LegadoThemePreview {
    SourceFilterEditDialog(
        rule = previewFilterRules.first(),
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun SourceFilterEditDialogNewPreview() = LegadoThemePreview {
    SourceFilterEditDialog(
        rule = null,
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun SourceFilterEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SourceFilterEditDialog(
        rule = previewFilterRules.first(),
        onConfirm = {},
        onDismiss = {},
    )
}
