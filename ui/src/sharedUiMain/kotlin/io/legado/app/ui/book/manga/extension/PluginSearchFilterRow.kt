package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.isTachiyomiPluginSource
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowTitle
import kotlinx.coroutines.CancellationException

/**
 * 搜索页插件源筛选面板 (搜索选项行之下)。
 *
 * 在当前搜索范围内识别 Tachiyomi/Aniyomi 插件源 (虚拟 BookSource 行: 漫画
 * [BookSourceType.image] / 视频 [BookSourceType.video]), 源声明了筛选器才逐源内联渲染筛选面板
 * (对齐 Mihon 仅 filters 非空才显示筛选入口); 每条筛选改动即回调 [onFiltersChanged] 重搜。
 * 会话实例与取数委派是同一份 (VM 持有), 随页面销毁即丢, 不持久化; 无插件源/服务未注册端渲染为零行。
 * 面板样式 (chip / 下拉裁决) 收拢在 [PluginFilterPanel] 与共享 AppChoiceRowGroup。
 */
@Composable
fun PluginSearchFilterRow(
    searchScope: SearchScope,
    scopeVersion: Int,
    ensureFilterSession: suspend (BookSource) -> PluginFilterSession?,
    onFiltersChanged: () -> Unit,
) {
    var filterableSources by remember {
        mutableStateOf<List<Pair<BookSource, PluginFilterSession>>>(emptyList())
    }
    LaunchedEffect(searchScope, scopeVersion) {
        val sources = runCatching { searchScope.getBookSources() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
            .filter {
                (it.bookSourceType == BookSourceType.image ||
                    it.bookSourceType == BookSourceType.video) && it.isTachiyomiPluginSource()
            }
        // 仅保留声明了筛选器的源 (会话实例由宿主建立/复用; 无筛选器源返回 null, 不出面板)
        filterableSources = sources.mapNotNull { source ->
            ensureFilterSession(source)?.let { source to it }
        }
    }
    if (filterableSources.isEmpty()) return

    Column {
        filterableSources.forEach { (source, session) ->
            AppChipRow {
                AppChipRowTitle(text = source.bookSourceName)
            }
            PluginFilterPanel(
                session = session,
                onChanged = onFiltersChanged,
            )
        }
    }
}
