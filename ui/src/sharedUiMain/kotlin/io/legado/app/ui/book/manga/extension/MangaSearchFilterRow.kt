package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.isVirtualPluginSource
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.component.AppChipRowTitle
import kotlinx.coroutines.CancellationException
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_search_filters
import org.jetbrains.compose.resources.stringResource

/**
 * 搜索页漫画插件源筛选条 (SearchOptionsRow 之下)。
 *
 * 在当前搜索范围内识别插件源 (虚拟 BookSource 行) 中 [BookSourceType.image] 的漫画源
 * (前缀 tachiyomi:// 为漫画/视频插件共用, 视频源不支持 MangaFilterDialog 的筛选契约);
 * 有则逐源渲染一颗 chip; 点开 [MangaFilterDialog] 直接回填 Filter 状态 (与取数委派同一实例),
 * 关闭即触发 onFiltersChanged 重搜。无插件源/服务未注册端渲染为零行。
 * chip 行样式 (行边距/间距/选中态) 收拢在共享 AppChipRow。
 */
@Composable
fun MangaSearchFilterRow(
    searchScope: SearchScope,
    scopeVersion: Int,
    onFiltersChanged: () -> Unit,
) {
    val service = MangaExtensionServiceProviders.getOrNull() ?: return

    var pluginSources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
    LaunchedEffect(searchScope, scopeVersion) {
        val sources = runCatching { searchScope.getBookSources() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        pluginSources = sources.filter {
            it.bookSourceType == BookSourceType.image && it.isVirtualPluginSource()
        }
    }
    if (pluginSources.isEmpty()) return

    var openSource by remember { mutableStateOf<BookSource?>(null) }
    var openFilters by remember { mutableStateOf<FilterList?>(null) }

    AppChipRow {
        AppChipRowTitle(text = stringResource(Res.string.manga_search_filters))
        pluginSources.forEach { source ->
            AppChipRowOption(
                text = source.bookSourceName,
                onClick = { openSource = source },
            )
        }
    }

    // 打开时异步取筛选器 (getFilterList 命中 MangaPluginFilterCache, 通常即时)
    openSource?.let { source ->
        LaunchedEffect(source) {
            openFilters = runCatching { service.getFilterList(source.bookSourceUrl) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
    }
    val source = openSource
    val filters = openFilters
    if (source != null && filters != null) {
        MangaFilterDialog(
            sourceName = source.bookSourceName,
            filterList = filters,
            onDismiss = {
                openSource = null
                openFilters = null
                onFiltersChanged()
            },
        )
    }
}
