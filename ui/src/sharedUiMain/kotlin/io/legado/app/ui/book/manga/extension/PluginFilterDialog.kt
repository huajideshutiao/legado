package io.legado.app.ui.book.manga.extension

import androidx.compose.runtime.Composable
import io.legado.app.model.webBook.AnimeFilterSession
import io.legado.app.model.webBook.MangaFilterSession
import io.legado.app.model.webBook.PluginFilterSession

/**
 * 插件源筛选对话框分派: 按会话实例的契约类型选漫画/视频筛选对话框。
 *
 * 漫画 ([MangaFilterSession]) 与视频 ([AnimeFilterSession]) 是上游平行的两份 Filter 契约,
 * 两份对话框结构同构、仅条目类型不同, 故这里只做分派; 调用方 (搜索页筛选条 / 发现筛选分类页)
 * 不必再自行判源类型。
 */
@Composable
fun PluginFilterDialog(
    sourceName: String,
    session: PluginFilterSession,
    onDismiss: (changed: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    when (session) {
        is MangaFilterSession -> MangaFilterDialog(
            sourceName = sourceName,
            filterList = session.filters,
            onDismiss = onDismiss,
            onReset = onReset,
        )

        is AnimeFilterSession -> AnimeFilterDialog(
            sourceName = sourceName,
            filterList = session.filters,
            onDismiss = onDismiss,
            onReset = onReset,
        )
    }
}
