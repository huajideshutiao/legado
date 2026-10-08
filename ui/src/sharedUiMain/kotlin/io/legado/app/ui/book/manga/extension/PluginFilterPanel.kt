package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.source.model.Filter
import io.legado.app.model.webBook.AnimeFilterSession
import io.legado.app.model.webBook.MangaFilterSession
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 插件源筛选对话框 (发现页/搜索页入口行点开)。
 *
 * 选项多的源 (如 hanime1 的標籤分类) 内联会把结果区挤没, 故收进对话框: 内容走 LazyColumn +
 * 对话框高度上限, 条目按需组合。
 *
 * 会话实例由宿主 VM 持有并经取数委派共用 (同一份 Filter 对象承载筛选状态), 面板直接改该实例;
 * 面板内每条改动即置位, 关闭对话框时经 [onDismiss] 上报一次 (改多项只重取一次)。
 */
@Composable
fun PluginFilterDialog(
    session: PluginFilterSession,
    onDismiss: (changed: Boolean) -> Unit,
) {
    // Filter 对象非 snapshot state: 改后须手动触发重组 (revision 由各条目自行读取)
    var revision by remember(session) { mutableIntStateOf(0) }
    // 应用标记: 会话被重置替换时归零
    var changed by remember(session) { mutableStateOf(false) }
    // Group 展开态 (key=Group 实例, 会话更换即重建)
    val expandedGroups = remember(session) { mutableStateMapOf<Any, Boolean>() }
    var sortFilter by remember(session) { mutableStateOf<Filter.Sort?>(null) }
    var animeSortFilter by remember(session) { mutableStateOf<AnimeFilter.Sort?>(null) }

    val onChanged: () -> Unit = {
        changed = true
        revision++
    }

    AppAlertDialog(onDismissRequest = { onDismiss(changed) }) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = AppDialogSizes.textAreaMaxHeight()),
        ) {
            when (session) {
                is MangaFilterSession -> mangaFilterItems(
                    filters = session.filters.toList(),
                    revision = revision,
                    expandedGroups = expandedGroups,
                    depth = 0,
                    path = "",
                    onChanged = onChanged,
                    onOpenSort = { sortFilter = it },
                )

                is AnimeFilterSession -> animeFilterItems(
                    filters = session.filters.toList(),
                    revision = revision,
                    expandedGroups = expandedGroups,
                    depth = 0,
                    path = "",
                    onChanged = onChanged,
                    onOpenSort = { animeSortFilter = it },
                )
            }
        }
    }

    // Sort 排序弹窗: 选排序键, 升降序由行内按钮切换 (state 可为 null, 默认升序)
    sortFilter?.let { filter ->
        AppSelectorDialog(
            onDismissRequest = { sortFilter = null },
            title = filter.name,
            items = filter.values.toList(),
            onItemSelected = { index ->
                filter.state = Filter.Sort.Selection(index, filter.state?.ascending ?: true)
                onChanged()
            },
        )
    }
    animeSortFilter?.let { filter ->
        AppSelectorDialog(
            onDismissRequest = { animeSortFilter = null },
            title = filter.name,
            items = filter.values.toList(),
            onItemSelected = { index ->
                filter.state = AnimeFilter.Sort.Selection(index, filter.state?.ascending ?: true)
                onChanged()
            },
        )
    }
}

/**
 * 条目缩进: 只按层级递进 (顶层贴对话框内边距, 每级 +[DesignTokens.spacingLg])。
 * 左右基础内边距由对话框内容承担 ([AppAlertDialogContent]), 此处不再叠加。
 */
internal fun filterIndent(depth: Int): Modifier = Modifier.padding(
    start = DesignTokens.spacingLg * depth,
)

/** 通用取值行: 名称 + 当前值 (点按整行触发), 尾部可选附加控件 (漫画/视频筛选面板共用)。 */
@Composable
internal fun FilterRow(
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
