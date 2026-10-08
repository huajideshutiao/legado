package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.model.Filter
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppChoiceField
import io.legado.app.ui.compose.component.AppChoiceRowGroup
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 漫画插件源筛选条目 (Filter 契约; 与视频侧 [animeFilterItems] 同构)。
 *
 * 相邻 Select 聚为一段交给 [AppChoiceRowGroup] 按选项数裁决 (选项数 > 4 出 chip 行, 否则下拉
 * 并排); Group 递归缩进并可折叠; 其余条目沿用取值行/输入框/勾选行。
 *
 * [revision] 必须在本函数体内读取: Filter.state 非 snapshot state, 改动只 bump revision,
 * 若父级把 revision 当普通参数往下传而不读, 子项会被 Compose 判为参数未变而整段跳过 (点了不亮)。
 *
 * 每条改动即 [onChanged] (对话框关闭时才据此上报一次)。
 *
 * [path] 为条目在筛选树中的下标路径 (如 `0/2/1`): 作 LazyColumn 条目 key 用 —— 默认的
 * 扁平下标身份在 Group 折叠改变条目数时会整体前移, 使条目内 remember 状态 (下拉展开态等)
 * 挂到别的筛选器上; 树内路径不受折叠影响。
 */
internal fun LazyListScope.mangaFilterItems(
    filters: List<Filter<*>>,
    revision: Int,
    expandedGroups: MutableMap<Any, Boolean>,
    depth: Int,
    path: String,
    onChanged: () -> Unit,
    onOpenSort: (Filter.Sort) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    buildFilterNodes(filters).forEachIndexed { nodeIndex, node ->
        val nodeKey = "$path/$nodeIndex"
        when (node) {
            is FilterNode.Selections -> item(key = nodeKey) {
                AppChoiceRowGroup(
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
            }

            is FilterNode.Single -> mangaFilterItem(
                filter = node.filter,
                revision = revision,
                expandedGroups = expandedGroups,
                depth = depth,
                itemKey = nodeKey,
                onChanged = onChanged,
                onOpenSort = onOpenSort,
            )
        }
    }
}

/** 面板节点: 相邻 Select 合并为一段 (段内由 [AppChoiceRowGroup] 裁决 chip / 下拉), 其余条目逐项。 */
private sealed interface FilterNode {
    class Selections(val items: List<Filter.Select<*>>) : FilterNode
    class Single(val filter: Filter<*>) : FilterNode
}

private fun buildFilterNodes(filters: List<Filter<*>>): List<FilterNode> {
    val nodes = ArrayList<FilterNode>()
    var selections = ArrayList<Filter.Select<*>>()
    fun flush() {
        if (selections.isNotEmpty()) {
            nodes.add(FilterNode.Selections(selections))
            selections = ArrayList()
        }
    }
    filters.forEach { filter ->
        if (filter is Filter.Select<*>) {
            selections.add(filter)
        } else {
            flush()
            nodes.add(FilterNode.Single(filter))
        }
    }
    flush()
    return nodes
}

/** 单个条目渲染 (Group 递归; Select 由 [mangaFilterItems] 聚段渲染, 不落到这里)。 */
private fun LazyListScope.mangaFilterItem(
    filter: Filter<*>,
    revision: Int,
    expandedGroups: MutableMap<Any, Boolean>,
    depth: Int,
    itemKey: String,
    onChanged: () -> Unit,
    onOpenSort: (Filter.Sort) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") revision
    when (filter) {
        is Filter.Header -> item(key = itemKey) {
            Text(
                text = filter.name,
                fontSize = 13.sp,
                color = AppTheme.colors.secondaryText,
                modifier = filterIndent(depth).padding(top = DesignTokens.spacingDefault),
            )
        }

        is Filter.Separator -> item(key = itemKey) {
            Column(modifier = filterIndent(depth).padding(vertical = DesignTokens.spacingDefault)) {
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
        }

        is Filter.Group<*> -> mangaFilterGroup(
            filter = filter,
            revision = revision,
            expandedGroups = expandedGroups,
            depth = depth,
            itemKey = itemKey,
            onChanged = onChanged,
            onOpenSort = onOpenSort,
        )

        is Filter.Sort -> item(key = itemKey) {
            FilterRow(
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
                                    Filter.Sort.Selection(0, false)
                                } else {
                                    current.copy(ascending = !current.ascending)
                                }
                                onChanged()
                            }
                            .padding(DesignTokens.spacingXs),
                    )
                },
            )
        }

        is Filter.Select<*> -> Unit

        is Filter.Text -> item(key = itemKey) {
            Column(filterIndent(depth).padding(vertical = DesignTokens.spacingXs)) {
                Text(
                    text = filter.name,
                    fontSize = 14.sp,
                    color = AppTheme.colors.primaryText,
                )
                AppTextField(
                    value = filter.state,
                    onValueChange = {
                        filter.state = it
                        onChanged()
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is Filter.TriState -> item(key = itemKey) {
            FilterRow(
                name = filter.name,
                valueText = when (filter.state) {
                    Filter.TriState.STATE_INCLUDE -> "✓"
                    Filter.TriState.STATE_EXCLUDE -> "✗"
                    else -> "—"
                },
                indent = filterIndent(depth),
                valueBold = true,
                onClick = {
                    filter.state = (filter.state + 1) % 3
                    onChanged()
                },
            )
        }

        is Filter.CheckBox -> item(key = itemKey) {
            Row(
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
                // 勾选入口只留外层 toggleable, 免得同一次点击改两次 state
                AppCheckbox(checked = filter.state, onCheckedChange = null)
            }
        }
    }
}

/** Group: 标题行点按折叠/展开, 子项逐级缩进 (缩进统一由 [filterIndent] 负责, 不叠加)。 */
private fun LazyListScope.mangaFilterGroup(
    filter: Filter.Group<*>,
    revision: Int,
    expandedGroups: MutableMap<Any, Boolean>,
    depth: Int,
    itemKey: String,
    onChanged: () -> Unit,
    onOpenSort: (Filter.Sort) -> Unit,
) {
    val expanded = expandedGroups[filter] ?: true
    val children = filter.state
        .filterIsInstance<Filter<*>>()
        .orEmpty()
    item(key = itemKey) {
        Row(
            filterIndent(depth)
                .fillMaxWidth()
                .clickable { expandedGroups[filter] = !expanded }
                .padding(vertical = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filter.name,
                fontSize = 15.sp,
                color = AppTheme.colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = rememberPainter(if (expanded) "ic_expand_less" else "ic_expand_more"),
                contentDescription = null,
                tint = AppTheme.colors.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .padding(DesignTokens.spacingXs),
            )
        }
    }
    if (expanded) {
        mangaFilterItems(
            filters = children,
            revision = revision,
            expandedGroups = expandedGroups,
            depth = depth + 1,
            path = itemKey,
            onChanged = onChanged,
            onOpenSort = onOpenSort,
        )
    }
}
