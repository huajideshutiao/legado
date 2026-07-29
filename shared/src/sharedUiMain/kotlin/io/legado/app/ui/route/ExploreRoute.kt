package io.legado.app.ui.route

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.main.explore.ExploreScreen
import io.legado.app.ui.main.explore.ExploreScreenModel
import io.legado.app.ui.main.explore.ExploreUiActions
import io.legado.app.ui.main.explore.ExploreUiEvent
import io.legado.app.ui.main.explore.ExploreUiState
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.TextDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppRoute.Explore shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ExploreScreenModel], 渲染 [ExploreScreen]。
 *
 * - dispatch 类回调 (搜索/展开/刷新/置顶/删除/JS) 走 [ExploreScreenModel.dispatch]
 * - 导航类回调 (跳 ExploreShow/BookSourceEdit/Login/Search) 走 [AppNavigator]
 * - 平台专属 (删除确认 / 收藏删除确认 / 分类错误文本框) 由本 Route 持有状态并渲染对话框
 */
@Composable
fun ExploreRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { ExploreScreenModel() }
    val screenState by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // LazyListState 由 Route 持有 (ScreenModel 不持有 Compose 状态)
    val listState = rememberLazyListState()
    val uiState = remember(screenState, listState) {
        ExploreUiState(
            sources = screenState.sources,
            pinned = screenState.pinned,
            groups = screenState.groups,
            searchKey = screenState.searchKey,
            expandedUrl = screenState.expandedUrl,
            expandedKinds = screenState.expandedKinds,
            expandedLoading = screenState.expandedLoading,
            listState = listState,
        )
    }

    // 平台对话框状态
    var pendingDeleteSource by remember { mutableStateOf<BookSourcePart?>(null) }
    var pendingRemovePin by remember { mutableStateOf<PinnedExplore?>(null) }
    var kindError by remember { mutableStateOf<ExploreKind?>(null) }

    val actions = remember(navigator, screenModel, scope) {
        object : ExploreUiActions {
            override fun onSearch(query: String) {
                screenModel.dispatch(ExploreUiEvent.SetSearch(query))
            }

            override fun onGroup(group: String) {
                screenModel.dispatch(ExploreUiEvent.SetGroup(group))
            }

            override fun onToggleExpand(item: BookSourcePart) {
                screenModel.dispatch(ExploreUiEvent.ToggleExpand(item))
            }

            // 对照 ExploreTabState.openPinned: 查 DB 取 source 后跳 ExploreShow; 失败 toast
            override fun onOpenPinned(pin: PinnedExplore) {
                scope.launch {
                    val source = withContext(IoDispatcher) {
                        AppDbProviders.get().bookSourceDao.getBookSource(pin.sourceUrl)
                    }
                    if (source != null) {
                        navigator.push(
                            AppRoute.ExploreShow(
                                source,
                                pin.categoryName,
                                pin.categoryUrl
                            )
                        )
                    } else {
                        Toasters.get().toast("Source not found")
                    }
                }
            }

            override fun onRemovePinned(pin: PinnedExplore) {
                pendingRemovePin = pin
            }

            override fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?) {
                // 对照 ExploreTabState.openExplore: 空白 URL 直接跳过
                if (exploreUrl.isNullOrBlank()) return
                navigator.push(AppRoute.ExploreShow(source, title, exploreUrl))
            }

            override fun onShowKindError(kind: ExploreKind) {
                kindError = kind
            }

            override fun onRunKindJs(source: BookSource, js: String) {
                screenModel.dispatch(ExploreUiEvent.RunKindJs(source, js))
            }

            override fun onEditSource(sourceUrl: String) {
                navigator.push(AppRoute.BookSourceEdit(sourceUrl))
            }

            override fun onToTop(source: BookSourcePart) {
                // 对照 ExploreTabState.toTop: 置顶后滚到顶
                screenModel.dispatch(ExploreUiEvent.ToTop(source))
                scope.launch { listState.animateScrollToItem(0) }
            }

            override fun onLogin(source: BookSourcePart) {
                navigator.push(AppRoute.Login(source.bookSourceUrl))
            }

            override fun onSearchBook(source: BookSourcePart) {
                // SearchScope(source) 暂未下沉到 AppRoute.Search (data object 无参), 走全局搜索
                navigator.push(AppRoute.Search())
            }

            override fun onRefreshSource(source: BookSourcePart) {
                screenModel.dispatch(ExploreUiEvent.RefreshSource(source))
            }

            override fun onDeleteSource(source: BookSourcePart) {
                pendingDeleteSource = source
            }
        }
    }

    // 删除书源确认 (对照 ExploreTabState.deleteSource 的 alert)
    pendingDeleteSource?.let { src ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteSource = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + src.bookSourceName,
            okButton = AlertButton(rememberString("ok")) {
                screenModel.dispatch(ExploreUiEvent.DeleteSource(src))
            },
            cancelButton = AlertButton(rememberString("cancel")),
        )
    }

    // 移除收藏确认 (对照 ExploreTabState.removePinned 的 alert)
    pendingRemovePin?.let { pin ->
        AppAlertDialog(
            onDismissRequest = { pendingRemovePin = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n${pin.sourceName}-${pin.categoryName}",
            okButton = AlertButton(rememberString("ok")) {
                screenModel.dispatch(ExploreUiEvent.RemovePinned(pin))
            },
            cancelButton = AlertButton(rememberString("cancel")),
        )
    }

    // 分类错误详情 (对照 ExploreTabState.showKindError 的 TextDialog)
    kindError?.let { kind ->
        TextDialog(
            title = "ERROR",
            content = kind.url.orEmpty(),
            onConfirm = { kindError = null },
            onDismiss = { kindError = null },
        )
    }

    ExploreScreen(state = uiState, actions = actions, onBack = { navigator.pop() })
}
