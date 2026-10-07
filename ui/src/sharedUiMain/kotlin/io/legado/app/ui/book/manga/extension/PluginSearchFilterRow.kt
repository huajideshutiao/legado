package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.isTachiyomiPluginSource
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.component.AppChipRowTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_search_filters
import org.jetbrains.compose.resources.stringResource

/**
 * 搜索页插件源筛选条 (SearchOptionsRow 之下)。
 *
 * 在当前搜索范围内识别 Tachiyomi/Aniyomi 插件源 (虚拟 BookSource 行: 漫画
 * [BookSourceType.image] / 视频 [BookSourceType.video]), 源声明了筛选器才逐源渲染一颗 chip
 * (对齐 Mihon 仅 filters 非空才显示筛选入口); 点开 [PluginFilterDialog] 直接回填会话实例
 * (与取数委派同一份), 有改动关闭即触发 [onFiltersChanged] 重搜, 重置按钮换新默认实例并重搜。
 *
 * 会话实例由宿主 (搜索页 VM) 持有, 随页面销毁即丢, 不持久化; 无插件源/服务未注册端渲染为零行。
 * chip 行样式 (行边距/间距/选中态) 收拢在共享 AppChipRow。
 */
@Composable
fun PluginSearchFilterRow(
    searchScope: SearchScope,
    scopeVersion: Int,
    ensureFilterSession: suspend (BookSource) -> PluginFilterSession?,
    resetFilterSession: suspend (BookSource) -> PluginFilterSession?,
    onFiltersChanged: () -> Unit,
) {
    var filterableSources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
    LaunchedEffect(searchScope, scopeVersion) {
        val sources = runCatching { searchScope.getBookSources() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
            .filter {
                (it.bookSourceType == BookSourceType.image ||
                    it.bookSourceType == BookSourceType.video) && it.isTachiyomiPluginSource()
            }
        // 仅保留声明了筛选器的源 (会话实例由宿主建立/复用; 无筛选器源返回 null, 不出入口)
        filterableSources = sources.filter { ensureFilterSession(it) != null }
    }
    if (filterableSources.isEmpty()) return

    var openSource by remember { mutableStateOf<BookSource?>(null) }
    var openFilters by remember { mutableStateOf<PluginFilterSession?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AppChipRow {
        AppChipRowTitle(text = stringResource(Res.string.manga_search_filters))
        filterableSources.forEach { source ->
            AppChipRowOption(
                text = source.bookSourceName,
                onClick = {
                    // 取会话实例后再开对话框 (取不到则不开, 入口保持可重试)
                    coroutineScope.launch {
                        openFilters = ensureFilterSession(source)
                        openSource = source.takeIf { openFilters != null }
                    }
                },
            )
        }
    }

    val source = openSource
    val session = openFilters
    if (source != null && session != null) {
        PluginFilterDialog(
            sourceName = source.bookSourceName,
            session = session,
            onDismiss = { changed ->
                openSource = null
                openFilters = null
                if (changed) onFiltersChanged()
            },
            onReset = {
                coroutineScope.launch {
                    resetFilterSession(source)?.let { openFilters = it }
                    onFiltersChanged()
                }
            },
        )
    }
}
