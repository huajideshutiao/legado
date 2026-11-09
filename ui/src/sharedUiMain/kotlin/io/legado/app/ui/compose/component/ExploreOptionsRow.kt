package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import io.legado.app.model.webBook.ExploreOptionUiState

/**
 * 参数筛选行。
 *
 * 只读 [options] 快照渲染, 选择事件交回页面会话: 组件不修改选项实例, 也不保存选中态。
 * 名称不是操作入口; 单选重置在选择弹窗底栏, 多选保持确认/取消/清空的一次提交语义。
 * 单选/多选都可用长按或鼠标右键打开可搜索选择弹窗。
 *
 * @param onSelect 单选换值; 调用方在会话内判断是否实际变化并决定重载
 * @param onMultiSelect 多选整份换值
 * @param onReset 重置该行 (弹窗底栏)
 */
@Composable
fun ExploreOptionsRow(
    options: List<ExploreOptionUiState>,
    /** 页面/源/已提交逻辑目标作用域; 不包含翻页游标与正在编辑的 query。 */
    ownerKey: Any,
    onSelect: (name: String, value: String) -> Unit,
    onMultiSelect: (name: String, values: Set<String>) -> Unit,
    onReset: (name: String) -> Unit,
) {
    if (options.isEmpty()) return
    AppMixedChoiceRowGroup(
        fields = options.map { option ->
            if (option.multiSelect) {
                option.toMultiChoiceField(
                    ownerKey = ownerKey,
                    onSelectionChange = { name, values -> onMultiSelect(name, values) },
                )
            } else {
                option.toChoiceField(
                    ownerKey = ownerKey,
                    onSelect = { name, value -> onSelect(name, value) },
                    onReset = { name -> onReset(name) },
                )
            }
        }
    )
}

private fun ExploreOptionUiState.toChoiceField(
    ownerKey: Any,
    onSelect: (String, String) -> Unit,
    onReset: (String) -> Unit,
) = AppChoiceField(
    fieldKey = ownerKey to name,
    definitionKey = items,
    title = name,
    options = items.map { it.first },
    selectedIndex = items.indexOfFirst { it.second == selectedValue },
    onSelect = { index -> onSelect(name, items[index].second) },
    onReset = { onReset(name) },
    selectedIndexes = selectedIndexes,
)

private fun ExploreOptionUiState.toMultiChoiceField(
    ownerKey: Any,
    onSelectionChange: (String, Set<String>) -> Unit,
): AppMultiChoiceField {
    val aliases = items.withIndex().groupBy { it.value.second }
        .filterKeys { it.isNotBlank() }
        .values
        .flatMap { group ->
            val indexes = group.mapTo(LinkedHashSet()) { it.index }
            group.map { it.index to indexes }
        }
        .toMap()
    return AppMultiChoiceField(
        fieldKey = ownerKey to name,
        definitionKey = items,
        title = name,
        options = items.map { it.first },
        selectedIndexes = selectedIndexes,
        // 弹窗确认时整份写回, 只触发一次重搜
        onSelectionChange = { indexes ->
            val values = indexes.mapTo(mutableSetOf()) { items[it].second }
            if (values != selectedValues) onSelectionChange(name, values)
        },
        headerIndexes = headerIndexes,
        aliasIndexes = aliases,
    )
}
