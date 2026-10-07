package io.legado.app.ui.book.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.ui.book.manga.extension.PluginFilterDialog
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.component.AppChipRowTitle
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.manga_search_filters
import org.jetbrains.compose.resources.stringResource

/**
 * 发现页插件源筛选入口 (仅"筛选"分类页渲染, 其余分类/非插件源零行)。
 *
 * 对齐 Mihon BrowseSource 顶栏三 chip (热门/最新/筛选): legado 把三面拆为发现分类列表,
 * "筛选"分类的取数 = 空关键词 + 当前筛选的搜索面。会话实例由宿主 (发现页 VM) 创建持有并经
 * WebBook 透传到委派 (随页面销毁, 不持久化); 本组件只在实例就绪时渲染入口 —— 实例缺失的源
 * 不出入口, 免得点开无对话框。点开 [PluginFilterDialog] 直接回填同一实例, 有改动关闭即
 * [onFiltersApplied] 重载; 重置按钮由宿主换新默认实例后重载, 仍停留筛选面 (原版 Reset 语义)。
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
    var dialogOpen by remember(target) { mutableStateOf(false) }

    AppChipRow {
        AppChipRowTitle(text = stringResource(Res.string.manga_search_filters))
        AppChipRowOption(
            text = target.sourceName,
            onClick = { dialogOpen = true },
        )
    }

    if (dialogOpen) {
        PluginFilterDialog(
            sourceName = target.sourceName,
            session = session,
            onDismiss = { changed ->
                dialogOpen = false
                if (changed) onFiltersApplied()
            },
            onReset = onResetFilters,
        )
    }
}
