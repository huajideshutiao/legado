package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.PluginFilterSession

/**
 * 搜索页单个插件源的筛选入口行 (按源分组布局下嵌在该源区块内, 聚簇布局下单源时在顶部)。
 *
 * 会话实例由搜索页 VM 持有并经取数委派共用 (随页面销毁即丢); 本组件按 [sessionVersion] 重读
 * VM 里的实例, 未建立时经 [ensureFilterSession] 就地建立 —— 非 Tachiyomi/Aniyomi 插件源或未声明
 * 筛选器返回 null, 零行 (对齐 Mihon 仅 filters 非空才显示筛选入口)。对话框内改动关闭即
 * [onFiltersChanged] 重搜该源, [onResetFilters] 重建为源默认。
 */
@Composable
fun PluginSearchFilterRow(
    source: BookSource?,
    sessionVersion: Int,
    getSession: (String) -> PluginFilterSession?,
    ensureFilterSession: suspend (BookSource) -> PluginFilterSession?,
    onFiltersChanged: (BookSource) -> Unit,
    onResetFilters: (BookSource) -> Unit,
) {
    if (source == null) return
    var session by remember(source) { mutableStateOf(getSession(source.bookSourceUrl)) }
    LaunchedEffect(source, sessionVersion) {
        session = getSession(source.bookSourceUrl) ?: ensureFilterSession(source)
    }
    val current = session ?: return
    PluginFilterEntryRow(
        session = current,
        onApplied = { onFiltersChanged(source) },
        onReset = { onResetFilters(source) },
    )
}
