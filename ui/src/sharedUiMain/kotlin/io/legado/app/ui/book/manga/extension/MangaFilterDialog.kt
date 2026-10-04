package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 漫画插件源筛选器对话框 (搜索页筛选条点击弹出), 复用项目标准 alert 样式。
 *
 * Filter 对象非 snapshot state: 回填直接改 [Filter.state] (Mihon 约定, 与取数委派
 * 共享同一实例, 关闭即生效), UI 刷新由本地 version 计数器驱动。Group 递归缩进,
 * Select/Sort 选值复用 [AppSelectorDialog], TriState 三态循环 (忽略→含→排除)。
 */
@Composable
fun MangaFilterDialog(
    sourceName: String,
    filterList: FilterList,
    onDismiss: () -> Unit,
) {
    // Filter.state 是普通 var, 改后须手动触发重组 (对照 SearchOptionsRow 的 localVersion)
    var version by remember { mutableIntStateOf(0) }
    // 待选值弹窗: 双槽分别承载 Select(单选索引) 与 Sort(排序项), 关闭即清
    var selectFilter by remember { mutableStateOf<Filter.Select<*>?>(null) }
    var sortFilter by remember { mutableStateOf<Filter.Sort?>(null) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = sourceName,
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = AppDialogSizes.textAreaMaxHeight()),
        ) {
            itemsIndexed(filterList) { _, filter ->
                FilterItem(
                    filter = filter,
                    depth = 0,
                    onChanged = { version++ },
                    onOpenSelect = { selectFilter = it },
                    onOpenSort = { sortFilter = it },
                )
            }
        }
    }

    // Select 单选弹窗 (values[state] 为当前项, 选中回填索引)
    selectFilter?.let { filter ->
        AppSelectorDialog(
            onDismissRequest = { selectFilter = null },
            title = filter.name,
            items = filter.values.map { it.toString() },
            onItemSelected = { index ->
                filter.state = index
                version++
            },
        )
    }

    // Sort 排序弹窗: 选排序键, 升降序由行内按钮切换 (state 可为 null, 默认升序)
    sortFilter?.let { filter ->
        AppSelectorDialog(
            onDismissRequest = { sortFilter = null },
            title = filter.name,
            items = filter.values.toList(),
            onItemSelected = { index ->
                filter.state = Filter.Sort.Selection(index, filter.state?.ascending ?: true)
                version++
            },
        )
    }
}

/** 单个 Filter 条目渲染 (Group 递归缩进)。 */
@Composable
private fun FilterItem(
    filter: Filter<*>,
    depth: Int,
    onChanged: () -> Unit,
    onOpenSelect: (Filter.Select<*>) -> Unit,
    onOpenSort: (Filter.Sort) -> Unit,
) {
    when (filter) {
        is Filter.Header -> Text(
            text = filter.name,
            fontSize = 13.sp,
            color = AppTheme.colors.secondaryText,
            modifier = filterIndent(depth).padding(top = DesignTokens.spacingDefault),
        )

        is Filter.Separator -> Column(
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

        is Filter.Group<*> -> FilterGroup(
            filter = filter,
            depth = depth,
            onChanged = onChanged,
            onOpenSelect = onOpenSelect,
            onOpenSort = onOpenSort,
        )

        is Filter.Sort -> FilterRow(
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

        is Filter.Select<*> -> FilterRow(
            name = filter.name,
            valueText = filter.values.getOrNull(filter.state)?.toString().orEmpty(),
            indent = filterIndent(depth),
            onClick = { onOpenSelect(filter) },
        )

        is Filter.Text -> Column(filterIndent(depth).padding(vertical = DesignTokens.spacingXs)) {
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

        is Filter.TriState -> FilterRow(
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

        is Filter.CheckBox -> Row(
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
private fun FilterGroup(
    filter: Filter.Group<*>,
    depth: Int,
    onChanged: () -> Unit,
    onOpenSelect: (Filter.Select<*>) -> Unit,
    onOpenSort: (Filter.Sort) -> Unit,
) {
    var expanded by remember(filter) { mutableStateOf(true) }
    val children = filter.state
        .filterIsInstance<Filter<*>>()
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
            children.forEach { child ->
                FilterItem(
                    filter = child,
                    depth = depth + 1,
                    onChanged = onChanged,
                    onOpenSelect = onOpenSelect,
                    onOpenSort = onOpenSort,
                )
            }
        }
    }
}

/** 条目缩进: 每级 [DesignTokens.spacingLg], 两侧对齐 alert 正文行。 */
private fun filterIndent(depth: Int): Modifier = Modifier.padding(
    start = DesignTokens.spacingLg + DesignTokens.spacingLg * depth,
    end = DesignTokens.spacingLg,
)

/** 通用取值行: 名称 + 当前值 (点按整行触发), 尾部可选附加控件。 */
@Composable
private fun FilterRow(
    name: String,
    valueText: String,
    indent: Modifier,
    valueBold: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier = indent
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DesignTokens.spacingDefault),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 15.sp,
            color = colors.primaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            fontSize = 14.sp,
            fontWeight = if (valueBold) FontWeight.Bold else null,
            color = if (valueBold) colors.accent else colors.secondaryText,
        )
        trailing?.invoke()
    }
}
