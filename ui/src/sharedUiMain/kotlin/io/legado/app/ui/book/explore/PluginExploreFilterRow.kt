package io.legado.app.ui.book.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.book.manga.extension.PluginFilterPanel
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowTitle
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_search_filters
import org.jetbrains.compose.resources.stringResource

/**
 * 发现页插件源筛选面板 (仅"筛选"分类页渲染, 其余分类/非插件源零行)。
 *
 * 对齐 Mihon BrowseSource 顶栏三 chip (热门/最新/筛选): legado 把三面拆为发现分类列表,
 * "筛选"分类的取数 = 空关键词 + 当前筛选的搜索面。会话实例由宿主 (发现页 VM) 创建持有并经
 * WebBook 透传到委派 (随页面销毁, 不持久化); 本组件只在实例就绪时渲染面板 —— 实例缺失的源
 * 不出筛选行, 免得点开无内容。每条筛选改动即 [onFiltersApplied]。
 */
@Composable
fun PluginExploreFilterRow(
    target: PluginFilterSource?,
    filters: PluginFilterSession?,
    onFiltersApplied: () -> Unit,
) {
    if (target == null) return
    val session = filters ?: return

    Column(Modifier.fillMaxWidth()) {
        AppChipRow {
            AppChipRowTitle(text = stringResource(Res.string.manga_search_filters))
            AppChipRowTitle(text = target.sourceName)
        }
        PluginFilterPanel(
            session = session,
            onChanged = onFiltersApplied,
        )
    }
}
