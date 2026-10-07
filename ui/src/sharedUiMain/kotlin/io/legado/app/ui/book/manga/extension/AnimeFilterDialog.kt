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
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.reset
import org.jetbrains.compose.resources.stringResource

/**
 * 视频插件源筛选器对话框 (搜索页/发现页筛选入口点开弹出; 与 [MangaFilterDialog] 同构,
 * AnimeFilter 与 Filter 是上游平行的两份契约)。
 *
 * AnimeFilter 对象非 snapshot state: 回填直接改 [AnimeFilter.state] (Aniyomi 约定, 与取数
 * 委派共享同一实例), UI 刷新由本地 version 计数器驱动; 任一项被改动即置 changed, 关闭时随
 * [onDismiss] 上报 (发现页据此标记筛选已应用 → 热门/最新取数切搜索面)。Group 递归缩进,
 * Select/Sort 选值复用 [AppSelectorDialog], TriState 三态循环 (忽略→含→排除);
 * 重置按钮 (Mihon FilterSheet 同款) 由调用方重建默认实例后经参数回填。
 */
@Composable
fun AnimeFilterDialog(
    sourceName: String,
    filterList: AnimeFilterList,
    onDismiss: (changed: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    // AnimeFilter.state 是普通 var, 改后须手动触发重组 (对照 MangaFilterDialog 的 version)
    var version by remember { mutableIntStateOf(0) }
    // 应用标记: filterList 实例被重置替换时归零
    var changed by remember(filterList) { mutableStateOf(false) }
    // 待选值弹窗: 双槽分别承载 Select(单选索引) 与 Sort(排序项), 关闭即清
    var selectFilter by remember { mutableStateOf<AnimeFilter.Select<*>?>(null) }
    var sortFilter by remember { mutableStateOf<AnimeFilter.Sort?>(null) }

    AppAlertDialog(
        onDismissRequest = { onDismiss(changed) },
        title = sourceName,
        neutralButton = AlertButton(
            text = stringResource(Res.string.reset),
            dismissOnClick = false,
            onClick = onReset,
        ),
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = AppDialogSizes.textAreaMaxHeight()),
        ) {
            itemsIndexed(filterList) { _, filter ->
                AnimeFilterItem(
                    filter = filter,
                    depth = 0,
                    onChanged = {
                        changed = true
                        version++
                    },
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
                changed = true
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
                filter.state = AnimeFilter.Sort.Selection(index, filter.state?.ascending ?: true)
                changed = true
                version++
            },
        )
    }
}

/** 单个 AnimeFilter 条目渲染 (Group 递归缩进; 结构对照 MangaFilterDialog.FilterItem)。 */
@Composable
private fun AnimeFilterItem(
    filter: AnimeFilter<*>,
    depth: Int,
    onChanged: () -> Unit,
    onOpenSelect: (AnimeFilter.Select<*>) -> Unit,
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
            onOpenSelect = onOpenSelect,
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

        is AnimeFilter.Select<*> -> FilterRow(
            name = filter.name,
            valueText = filter.values.getOrNull(filter.state)?.toString().orEmpty(),
            indent = filterIndent(depth),
            onClick = { onOpenSelect(filter) },
        )

        is AnimeFilter.Text -> Column(filterIndent(depth).padding(vertical = DesignTokens.spacingXs)) {
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

/** Group: 标题行点按折叠/展开, 子项逐级缩进 (结构对照 MangaFilterDialog.FilterGroup)。 */
@Composable
private fun AnimeFilterGroup(
    filter: AnimeFilter.Group<*>,
    depth: Int,
    onChanged: () -> Unit,
    onOpenSelect: (AnimeFilter.Select<*>) -> Unit,
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
            children.forEach { child ->
                AnimeFilterItem(
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
