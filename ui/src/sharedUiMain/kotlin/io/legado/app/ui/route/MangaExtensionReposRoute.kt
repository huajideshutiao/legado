package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.manga.extension.MangaReposScreen
import io.legado.app.ui.book.manga.extension.MangaReposScreenModel
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/** 漫画插件仓库管理页路由: 绑定 [MangaReposScreenModel] 并渲染 [MangaReposScreen]。 */
@Composable
fun MangaExtensionReposRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        MangaReposScreenModel(toast = { Toasters.get().toast(it) })
    }
    val state by screenModel.state.collectAsState()

    MangaReposScreen(
        state = state,
        onBack = { navigator.pop() },
        onAddRepo = screenModel::addRepo,
        onRemoveRepo = screenModel::removeRepo,
    )
}
