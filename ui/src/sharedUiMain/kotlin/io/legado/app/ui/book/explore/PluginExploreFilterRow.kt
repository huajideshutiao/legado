package io.legado.app.ui.book.explore

import androidx.compose.runtime.Composable
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.book.manga.extension.PluginFilterEntryRow

/**
 * 发现页插件源筛选入口 (仅"筛选"分类页渲染, 其余分类/非插件源零行)。
 *
 * 对齐 Mihon BrowseSource 顶栏三 chip (热门/最新/筛选): legado 把三面拆为发现分类列表,
 * "筛选"分类的取数 = 空关键词 + 当前筛选的搜索面。会话实例由宿主 (发现页 VM) 创建持有并经
 * WebBook 透传到委派 (随页面销毁, 不持久化); 本组件只在实例就绪时渲染入口 —— 实例缺失的源
 * 不出入口, 免得点开无内容。对话框内改动关闭即 [onFiltersApplied] 重载, [onResetFilters]
 * 换新默认会话后重载。
 */
@Composable
fun PluginExploreFilterRow(
    target: PluginFilterSource?,
    filters: PluginFilterSession?,
    onFiltersApplied: () -> Unit,
    onResetFilters: () -> Unit,
) {
    if (target == null) return
    val session = filters ?: return
    PluginFilterEntryRow(
        session = session,
        onApplied = onFiltersApplied,
        onReset = onResetFilters,
    )
}
