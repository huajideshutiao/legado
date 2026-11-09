package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.PluginFilterSession

/**
 * 搜索页单个插件源的筛选入口行 (按源分组布局下嵌在该源区块内, 聚簇布局下单源时在顶部)。
 *
 * 会话实例由搜索页 VM 在请求前建立并持有; 本组件只按 [sessionVersion] 重读, 不在组合期
 * 新建或并发补建。空筛选器是合法会话, 但不出入口行; 本端没有实例的源同样零行。
 * 对话框内改动关闭即 [onFiltersChanged] 重搜该源, [onResetFilters] 重建为源默认。
 */
@Composable
fun PluginSearchFilterRow(
    source: BookSource?,
    sessionVersion: Int,
    getSession: (String) -> PluginFilterSession?,
    onFiltersChanged: (BookSource) -> Unit,
    onResetFilters: (BookSource) -> Unit,
    onEditingChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    if (source == null) return
    val session = remember(source, sessionVersion) { getSession(source.bookSourceUrl) }
    val current = session?.takeIf { !it.isEmpty } ?: return
    PluginFilterEntryRow(
        session = current,
        onApplied = { onFiltersChanged(source) },
        onReset = { onResetFilters(source) },
        onEditingChanged = { onEditingChanged(source.bookSourceUrl, it) },
    )
}
