package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.book.tvbox.TvBoxManageScreen
import io.legado.app.ui.book.tvbox.TvBoxScreenModel
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * 影视源 (TVBox) 管理页路由: 绑定 [TvBoxScreenModel] (首入口 init, 幂等) 并渲染
 * [TvBoxManageScreen]。
 */
@Composable
fun TvBoxRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        TvBoxScreenModel()
    }
    val state by screenModel.state.collectAsState()
    // 栈顶订阅: 搜索返回拦截只在影视源页是栈顶时生效 (压栈页面的返回键不被不可见页吞掉)
    val backStack by navigator.backStack.collectAsState()

    TvBoxManageScreen(
        state = state,
        isTopEntry = backStack.lastOrNull()?.id == entry.id,
        onBack = { navigator.pop() },
        onRefresh = screenModel::refresh,
        onAddSource = screenModel::addConfigSource,
        onRemoveSource = screenModel::removeSource,
        onActivateSource = screenModel::activateSource,
        onToggleSite = screenModel::toggleSite,
        onProbeJars = screenModel::probeJars,
        onClearError = screenModel::clearError,
    )
}
