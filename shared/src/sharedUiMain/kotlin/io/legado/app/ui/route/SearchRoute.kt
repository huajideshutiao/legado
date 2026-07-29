package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchNavCallbacks
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.ui.book.search.SearchScreen
import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.launch

/**
 * AppRoute.Search shared 路由入口。
 *
 * SearchViewModel 未实现 ScreenModel, 无法走 screenModelStore.getOrCreateTyped,
 * 改用 remember 复用 + DisposableEffect 在离开组合时 close 释放协程;
 * 平台专属回调 (搜索范围/过滤规则/日志/清空历史) 通过 shared 对话框组件实现。
 */
@Composable
fun SearchRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val viewModel = remember { SearchViewModel() }
    val scope = rememberCoroutineScope()
    // 对照 Activity.repeatOnLifecycle(RESUMED) { resume(); ... finally { pause() } }
    DisposableEffect(viewModel) {
        viewModel.resume()
        onDispose {
            viewModel.pause()
            viewModel.close()
        }
    }

    // 书源分组列表 (供 SearchScopeDialog 使用)
    var bookGroups by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { bookGroups = it }
    }

    // 对话框状态
    var showSearchScope by remember { mutableStateOf(false) }
    var showAppLog by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    val navCallbacks = remember(navigator, scope) {
        object : SearchNavCallbacks {
            override fun onBack() {
                navigator.pop()
            }

            // 对照 SearchActivity.onBookClick + showBookInfo:
            // - 不在书架补 notShelf type
            // - bookUrl 含 "::" 是探索结果, 按 book.origin 查 BookSource 后跳 ExploreShow
            // - longClick || !devFeat 直接进详情; 否则按 isVideo/isRss/默认 分流
            override fun onBookClick(book: BaseBook, longClick: Boolean) {
                if (!viewModel.isInBookShelf(book)) {
                    book.addType(BookType.notShelf)
                }
                val urlParts = book.bookUrl.split("::", limit = 2)
                if (urlParts.size == 2) {
                    // 对照 Activity.showBookInfo: ExploreShow 需 BookSource, 按 book.origin 查 DAO
                    scope.launch {
                        val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                        source?.let {
                            navigator.push(
                                AppRoute.ExploreShow(
                                    it,
                                    urlParts[0],
                                    urlParts[1]
                                )
                            )
                        }
                    }
                    return
                }
                val ref = when (book) {
                    is Book -> book.toRouteRef()
                    is SearchBook -> book.toRouteRef()
                    else -> return
                }
                // 对照 Activity: longClick || !devFeat 进 BookInfo, 否则按类型分流
                if (longClick || !AppConfigProviders.get().devFeat) {
                    navigator.push(AppRoute.BookInfo(ref))
                    return
                }
                when {
                    book.isVideo -> navigator.push(AppRoute.VideoPlay(ref))
                    book.isRss -> navigator.push(AppRoute.ReadRss(ref))
                    else -> navigator.push(AppRoute.BookInfo(ref))
                }
            }

            override fun onManageBookSources() {
                navigator.push(AppRoute.BookSourceManage)
            }

            override fun onAlertSearchScope() {
                showSearchScope = true
            }

            // 对照 Activity: 弹 scoped 列表对话框 (非跳转管理页)
            override fun onShowSourceFilterRule() {
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceFilterRuleList",
                        payload = viewModel.searchScope.toString()
                    )
                )
            }

            override fun onShowAppLog() {
                showAppLog = true
            }

            override fun onClearHistory() {
                showClearHistoryConfirm = true
            }
        }
    }

    SearchScreen(viewModel = viewModel, navCallbacks = navCallbacks)

    // 搜索范围对话框
    if (showSearchScope) {
        // 当前选中的分组名 → 映射为分组 ID
        val scopeNames = viewModel.searchScope.displayNames
        val selectedIds =
            bookGroups.filter { it.groupName in scopeNames }.map { it.groupId }.toSet()
        SearchScopeDialog(
            groups = bookGroups,
            selectedGroupIds = selectedIds,
            onConfirm = { ids ->
                val names = bookGroups.filter { it.groupId in ids }.map { it.groupName }
                viewModel.onSearchScopeOk(SearchScope(names))
                showSearchScope = false
            },
            onDismiss = { showSearchScope = false },
        )
    }

    // 应用日志对话框
    if (showAppLog) {
        AppLogDialog(onDismiss = { showAppLog = false })
    }

    // 清空搜索历史确认对话框
    if (showClearHistoryConfirm) {
        AppAlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = rememberString("draw"),
            message = rememberString("sure_clear_search_history"),
            okButton = AlertButton(rememberString("ok")) {
                viewModel.clearHistory()
            },
            cancelButton = AlertButton(rememberString("cancel")),
        )
    }
}
