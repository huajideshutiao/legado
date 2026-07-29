package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

// WebDavConfig 复用 BackupConfigRoute: WebDav 配置是 BackupConfig 的子集
@Composable
fun WebDavConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    BackupConfigRoute(entry = entry, navigator = navigator, screenModelStore = screenModelStore)
}
