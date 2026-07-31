package io.legado.app.ui.dict.rule

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewDictRules

/** [DictRuleScreen] / [DictRuleEditDialog] 的 @Preview (列表态/空态/多选态/编辑态)。 */

private val noOpDictActions = object : DictRuleUiActions {
    override fun onBack() {}
    override fun onAddRule() {}
    override fun onEditRule(name: String) {}
    override fun onImportLocal() {}
    override fun onImportOnline() {}
    override fun onImportDefault() {}
    override fun onHelp() {}
    override fun onToggleSelected(item: DictRule, checked: Boolean) {}
    override fun onSelectAll(all: Boolean) {}
    override fun onRevertSelection() {}
    override fun onMoveItem(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean) {}
    override fun onDeleteRule(rule: DictRule) {}
    override fun onDeleteSelection() {}
    override fun onEnableSelection() {}
    override fun onDisableSelection() {}
    override fun onExportSelection() {}
}

// ---- DictRuleScreen ----

@Preview
@Composable
fun DictRuleScreenPreview() = LegadoThemePreview {
    DictRuleScreen(
        state = DictRuleUiState(dictRules = previewDictRules),
        actions = noOpDictActions,
    )
}

@Preview
@Composable
fun DictRuleScreenEmptyPreview() = LegadoThemePreview {
    DictRuleScreen(
        state = DictRuleUiState(),
        actions = noOpDictActions,
    )
}

@Preview
@Composable
fun DictRuleScreenSelectionPreview() = LegadoThemePreview {
    DictRuleScreen(
        state = DictRuleUiState(
            dictRules = previewDictRules,
            selected = setOf(previewDictRules[0].name, previewDictRules[2].name),
        ),
        actions = noOpDictActions,
    )
}

@Preview
@Composable
fun DictRuleScreenDarkPreview() = LegadoThemePreview(dark = true) {
    DictRuleScreen(
        state = DictRuleUiState(dictRules = previewDictRules),
        actions = noOpDictActions,
    )
}

// ---- DictRuleEditDialog ----

@Preview
@Composable
fun DictRuleEditDialogPreview() = LegadoThemePreview {
    DictRuleEditDialog(
        rule = previewDictRules.first(),
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
    )
}

@Preview
@Composable
fun DictRuleEditDialogNewPreview() = LegadoThemePreview {
    DictRuleEditDialog(
        rule = null,
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
    )
}

@Preview
@Composable
fun DictRuleEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    DictRuleEditDialog(
        rule = previewDictRules.first(),
        onConfirm = {},
        onDismiss = {},
        clipTextProvider = { null },
        clipTextSink = {},
    )
}
