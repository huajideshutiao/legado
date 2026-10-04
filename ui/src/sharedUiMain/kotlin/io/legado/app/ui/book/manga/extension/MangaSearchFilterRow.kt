package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_search_filters
import org.jetbrains.compose.resources.stringResource

/**
 * 搜索页漫画插件源筛选条 (SearchOptionsRow 之下)。
 *
 * 在当前搜索范围内识别插件源 (虚拟 BookSource 行), 有则逐源渲染一颗 chip;
 * 点开 [MangaFilterDialog] 直接回填 Filter 状态 (与取数委派同一实例), 关闭即触发
 * onFiltersChanged 重搜。无插件源/服务未注册端渲染为零行。
 * 行内边距与 chip 间距对齐 SearchOptionsRow (spacingDefault/spacingXs + fillet 自带 inset)。
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
            .getOrDefault(emptyList())
        pluginSources = sources.filter { service.isPluginSource(it.bookSourceUrl) }
    }
    if (pluginSources.isEmpty()) return

    var openSource by remember { mutableStateOf<BookSource?>(null) }
    var openFilters by remember { mutableStateOf<FilterList?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题 chip (对齐 SearchOptionChip 标题: 0.8 粗体)
        AppFilletTextButton(
            text = stringResource(Res.string.manga_search_filters),
            alpha = 0.8f,
            bold = true,
        )
        pluginSources.forEach { source ->
            Spacer(Modifier.width(DesignTokens.spacingXs))
            AppFilletTextButton(
                text = source.bookSourceName,
                onClick = { openSource = source },
            )
        }
    }

    // 打开时异步取筛选器 (getFilterList 命中 MangaPluginFilterCache, 通常即时)
    openSource?.let { source ->
        LaunchedEffect(source) {
            openFilters = runCatching { service.getFilterList(source.bookSourceUrl) }
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
