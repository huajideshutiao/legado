package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.book.manga.extension.MangaExtensionScreen
import io.legado.app.ui.book.manga.extension.MangaExtensionScreenModel
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 漫画插件管理页路由: 绑定 [MangaExtensionScreenModel] (首入口 init, 幂等) 并渲染
 * [MangaExtensionScreen]; 仓库管理子页 push [AppRoute.MangaExtensionRepos]。
 */
@Composable
fun MangaExtensionRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        MangaExtensionScreenModel()
    }
    val state by screenModel.state.collectAsState()

    MangaExtensionScreen(
        state = state,
        onBack = { navigator.pop() },
        onManageRepos = { navigator.push(AppRoute.MangaExtensionRepos) },
        onInstall = screenModel::install,
        onUpdate = screenModel::update,
        onCancelInstall = screenModel::cancelInstall,
        onUninstall = screenModel::uninstall,
        onTrust = screenModel::trust,
        onRefresh = screenModel::refresh,
        selectedLanguages = screenModel.selectedLanguages,
        onToggleLanguage = screenModel::toggleLanguage,
        onClearLanguages = screenModel::clearLanguages,
    )
}
