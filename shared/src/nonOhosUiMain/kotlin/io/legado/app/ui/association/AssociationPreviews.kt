package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * association 模块下 [RuleSubScreen] 与 [ImportListScaffold] 的 @Preview。
 *
 * 假数据: [RuleSub] / 引擎列表用纯内存对象构造, 不依赖 DB / 网络。
 * RuleSubUiActions 用 noop 实现 (Preview 期不触发实际跳转)。
 */

// ===== RuleSubScreen =====

private val previewRuleSubs = listOf(
    RuleSub(
        id = 1L,
        name = "起点书源订阅",
        url = "https://example.com/source1.json",
        type = 0,
        customOrder = 0,
    ),
    RuleSub(
        id = 2L,
        name = "RSS 源订阅",
        url = "https://example.com/rss.json",
        type = 1,
        customOrder = 1,
    ),
    RuleSub(
        id = 3L,
        name = "替换规则订阅",
        url = "https://example.com/replace.json",
        type = 2,
        customOrder = 2,
    ),
)

/** Preview 期 noop [RuleSubUiActions], 所有回调空实现。 */
private object NoopRuleSubActions : RuleSubUiActions {
    override fun onBack() {}
    override fun onAdd() {}
    override fun onEdit(ruleSub: RuleSub) {}
    override fun onOpenSubscription(ruleSub: RuleSub) {}
    override fun onMove(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onToTop(ruleSub: RuleSub) {}
    override fun onToBottom(ruleSub: RuleSub) {}
    override fun onDelete(ruleSub: RuleSub) {}
}

@AppPreview
@Composable
fun RuleSubScreenPreview() = LegadoThemePreview {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = previewRuleSubs),
        actions = NoopRuleSubActions,
    )
}

@AppPreview
@Composable
fun RuleSubScreenEmptyPreview() = LegadoThemePreview {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = emptyList()),
        actions = NoopRuleSubActions,
    )
}

@AppPreview
@Composable
fun RuleSubScreenDarkPreview() = LegadoThemePreview(dark = true) {
    RuleSubScreen(
        state = RuleSubUiState(ruleSubs = previewRuleSubs),
        actions = NoopRuleSubActions,
    )
}

// ===== ImportListScaffold =====

@AppPreview
@Composable
fun ImportListScaffoldPreview() = LegadoThemePreview {
    ImportListScaffold(
        title = "导入书源",
        loading = false,
        errorText = null,
        itemCount = 5,
        selectCount = 2,
        isSelectAll = false,
        itemLabel = { index -> "书源 ${index + 1}" },
        itemState = { index -> if (index % 2 == 0) "已导入" else "可导入" },
        itemChecked = { index -> index % 3 == 0 },
        onItemChecked = { _, _ -> },
        onOpen = {},
        onToggleAll = {},
        onCancel = {},
        onOk = {},
    )
}

@AppPreview
@Composable
fun ImportListScaffoldLoadingPreview() = LegadoThemePreview {
    ImportListScaffold(
        title = "导入书源",
        loading = true,
        errorText = null,
        itemCount = 5,
        selectCount = 0,
        isSelectAll = false,
        itemLabel = { index -> "书源 ${index + 1}" },
        itemState = { _ -> "" },
        itemChecked = { _ -> false },
        onItemChecked = { _, _ -> },
        onOpen = {},
        onToggleAll = {},
        onCancel = {},
        onOk = {},
    )
}

@AppPreview
@Composable
fun ImportListScaffoldErrorPreview() = LegadoThemePreview {
    ImportListScaffold(
        title = "导入书源",
        loading = false,
        errorText = "网络连接失败, 请检查 URL 或稍后重试。",
        itemCount = 0,
        selectCount = 0,
        isSelectAll = false,
        itemLabel = { _ -> "" },
        itemState = { _ -> "" },
        itemChecked = { _ -> false },
        onItemChecked = { _, _ -> },
        onOpen = {},
        onToggleAll = {},
        onCancel = {},
        onOk = {},
    )
}

@AppPreview
@Composable
fun ImportListScaffoldSelectAllPreview() = LegadoThemePreview {
    ImportListScaffold(
        title = "导入替换规则",
        loading = false,
        errorText = null,
        itemCount = 3,
        selectCount = 3,
        isSelectAll = true,
        itemLabel = { index -> "规则 ${index + 1}" },
        itemState = { _ -> "已勾选" },
        itemChecked = { _ -> true },
        onItemChecked = { _, _ -> },
        onOpen = {},
        onToggleAll = {},
        onCancel = {},
        onOk = {},
    )
}
