package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppChoiceField
import io.legado.app.ui.compose.component.AppChoiceRowGroup
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 视频插件源筛选面板 (内联, 不经对话框; 与 [MangaFilterPanel] 同构, AnimeFilter 与 Filter 是上游
 * 平行的两份契约)。
 *
 * 相邻的 Select 聚为一段交给 [AppChoiceRowGroup] 按选项数裁决 (选项数 > 4 出 chip 行, 否则下拉
 * 并排); Group 递归缩进并可折叠; 其余条目沿用取值行/输入框/勾选行。AnimeFilter 对象非 snapshot
 * state: 回填直接改 [AnimeFilter.state] (Aniyomi 约定, 与取数委派共享同一实例), UI 刷新由本地
 * version 计数器驱动; 改动即回调 [onChanged] (宿主随即重取数), 文本框输入期间只刷新显示、失焦或
 * 键盘完成才回调, 免得逐字触发取数。
 */
@Composable
fun AnimeFilterPanel(
    filterList: AnimeFilterList,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var version by remember { mutableIntStateOf(0) }
    var sortFilter by remember { mutableStateOf<AnimeFilter.Sort?>(null) }
    @Suppress("UNUSED_EXPRESSION") version
    Column(modifier.fillMaxWidth()) {
        AnimeFilterPanelItems(
            filters = filterList.toList(),
            depth = 0,
            onChanged = {
                version++
                onChanged()
            },
            onDraft = { version++ },
            onOpenSort = { sortFilter = it },
        )
    }

    // Sort 排序弹窗: 选排序键, 升降序由行内按钮切换 (state 可为 null, 默认升序)
    sortFilter?.let { filter ->
        AppSelectorDialog(
            onDismissRequest = { sortFilter = null },
            title = filter.name,
            items = filter.values.toList(),
            onItemSelected = { index ->
                filter.state = AnimeFilter.Sort.Selection(index, filter.state?.ascending ?: true)
                version++
                onChanged()
            },
        )
    }
}

/** 面板节点: 相邻 Select 合并为一段 (段内由 [AppChoiceRowGroup] 裁决 chip / 下拉), 其余条目逐项。 */
private sealed interface AnimeFilterNode {
    class Selections(val items: List<AnimeFilter.Select<*>>) : AnimeFilterNode
    class Single(val filter: AnimeFilter<*>) : AnimeFilterNode
}

private fun buildAnimeFilterNodes(filters: List<AnimeFilter<*>>): List<AnimeFilterNode> {
    val nodes = ArrayList<AnimeFilterNode>()
    var selections = ArrayList<AnimeFilter.Select<*>>()
    fun flush() {
        if (selections.isNotEmpty()) {
            nodes.add(AnimeFilterNode.Selections(selections))
            selections = ArrayList()
        }
    }
    filters.forEach { filter ->
        if (filter is AnimeFilter.Select<*>) {
            selections.add(filter)
        } else {
            flush()
            nodes.add(AnimeFilterNode.Single(filter))
        }
    }
    flush()
    return nodes
}

@Composable
private fun AnimeFilterPanelItems(
    filters: List<AnimeFilter<*>>,
    depth: Int,
    onChanged: () -> Unit,
    onDraft: () -> Unit,
    onOpenSort: (AnimeFilter.Sort) -> Unit,
) {
    buildAnimeFilterNodes(filters).forEach { node ->
        when (node) {
            is AnimeFilterNode.Selections -> AppChoiceRowGroup(
                modifier = filterIndent(depth),
                fields = node.items.map { filter ->
                    AppChoiceField(
                        title = filter.name,
                        options = filter.values.map { it.toString() },
                        selectedIndex = filter.state,
                        onSelect = { index ->
                            filter.state = index
                            onChanged()
                        },
                    )
                },
            )

            is AnimeFilterNode.Single -> AnimeFilterItem(
                filter = node.filter,
                depth = depth,
                onChanged = onChanged,
                onDraft = onDraft,
                onOpenSort = onOpenSort,
            )
        }
    }
}

/** 单个条目渲染 (Group 递归; Select 由 [AnimeFilterPanelItems] 聚段渲染, 不落到这里)。 */
@Composable
private fun AnimeFilterItem(
    filter: AnimeFilter<*>,
    depth: Int,
    onChanged: () -> Unit,
    onDraft: () -> Unit,
    onOpenSort: (AnimeFilter.Sort) -> Unit,
) {
    when (filter) {
        is AnimeFilter.Header -> Text(
            text = filter.name,
            fontSize = 13.sp,
            color = AppTheme.colors.secondaryText,
            modifier = filterIndent(depth).padding(top = DesignTokens.spacingDefault),
        )

        is AnimeFilter.Separator -> Column(
            modifier = filterIndent(depth).padding(vertical = DesignTokens.spacingDefault),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppTheme.colors.secondaryText.copy(alpha = 0.2f)),
            )
            if (filter.name.isNotBlank()) {
                Text(
                    text = filter.name,
                    fontSize = 12.sp,
                    color = AppTheme.colors.secondaryText,
                    modifier = Modifier.padding(top = DesignTokens.spacingXs),
                )
            }
        }

        is AnimeFilter.Group<*> -> AnimeFilterGroup(
            filter = filter,
            depth = depth,
            onChanged = onChanged,
            onDraft = onDraft,
            onOpenSort = onOpenSort,
        )

        is AnimeFilter.Sort -> FilterRow(
            name = filter.name,
            valueText = filter.state?.let { selection ->
                filter.values.getOrNull(selection.index) +
                    if (selection.ascending) " ↑" else " ↓"
            } ?: filter.values.firstOrNull().orEmpty(),
            indent = filterIndent(depth),
            onClick = { onOpenSort(filter) },
            trailing = {
                Text(
                    text = if (filter.state?.ascending != false) "↑" else "↓",
                    fontSize = 16.sp,
                    color = AppTheme.colors.accent,
                    modifier = Modifier
                        .clickable {
                            val current = filter.state
                            filter.state = if (current == null) {
                                AnimeFilter.Sort.Selection(0, false)
                            } else {
                                current.copy(ascending = !current.ascending)
                            }
                            onChanged()
                        }
                        .padding(DesignTokens.spacingXs),
                )
            },
        )

        is AnimeFilter.Select<*> -> Unit

        is AnimeFilter.Text -> Column(filterIndent(depth).padding(vertical = DesignTokens.spacingXs)) {
            var dirty by remember(filter) { mutableStateOf(false) }
            // 输入期间只刷显示不取数; 失焦/IME 完成/离开组合时提交, 免得改完直接切页丢掉输入
            DisposableEffect(filter) {
                onDispose { if (dirty) onChanged() }
            }
            Text(
                text = filter.name,
                fontSize = 14.sp,
                color = AppTheme.colors.primaryText,
            )
            AppTextField(
                value = filter.state,
                onValueChange = {
                    filter.state = it
                    dirty = true
                    onDraft()
                },
                singleLine = true,
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (dirty) {
                            dirty = false
                            onChanged()
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && dirty) {
                            dirty = false
                            onChanged()
                        }
                    },
            )
        }

        is AnimeFilter.TriState -> FilterRow(
            name = filter.name,
            valueText = when (filter.state) {
                AnimeFilter.TriState.STATE_INCLUDE -> "✓"
                AnimeFilter.TriState.STATE_EXCLUDE -> "✗"
                else -> "—"
            },
            indent = filterIndent(depth),
            valueBold = true,
            onClick = {
                filter.state = (filter.state + 1) % 3
                onChanged()
            },
        )

        is AnimeFilter.CheckBox -> Row(
            modifier = filterIndent(depth)
                .fillMaxWidth()
                .toggleable(
                    value = filter.state,
                    role = Role.Checkbox,
                ) {
                    filter.state = it
                    onChanged()
                }
                .padding(vertical = DesignTokens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filter.name,
                fontSize = 15.sp,
                color = AppTheme.colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            AppCheckbox(
                checked = filter.state,
                onCheckedChange = {
                    filter.state = it
                    onChanged()
                },
            )
        }
    }
}

/** Group: 标题行点按折叠/展开, 子项逐级缩进 (缩进统一由 [filterIndent] 负责, 不叠加)。 */
@Composable
private fun AnimeFilterGroup(
    filter: AnimeFilter.Group<*>,
    depth: Int,
    onChanged: () -> Unit,
    onDraft: () -> Unit,
    onOpenSort: (AnimeFilter.Sort) -> Unit,
) {
    var expanded by remember(filter) { mutableStateOf(true) }
    val children = filter.state
        .filterIsInstance<AnimeFilter<*>>()
        .orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(
            filterIndent(depth)
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filter.name,
                fontSize = 15.sp,
                color = AppTheme.colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 14.sp,
                color = AppTheme.colors.secondaryText,
            )
        }
        if (expanded) {
            AnimeFilterPanelItems(
                filters = children,
                depth = depth + 1,
                onChanged = onChanged,
                onDraft = onDraft,
                onOpenSort = onOpenSort,
            )
        }
    }
}
