package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.showSourceLogin
import io.legado.app.ui.book.explore.ExploreShowScreen
import io.legado.app.ui.book.explore.ExploreShowScreenModel
import io.legado.app.ui.book.explore.ExploreShowUiActions
import io.legado.app.ui.book.explore.ExploreShowUiEvent
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.bookshelf.ShelfVideoItem
import io.legado.app.ui.bookshelf.toCoverBook
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.ExploreOptionsRow
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.explore.ExploreShowViewModelShared
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.screenModelScope
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.cancel
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bottom_line
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.error
import legado.shared.generated.resources.error_load_msg
import legado.shared.generated.resources.explore_cols
import legado.shared.generated.resources.retry
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.ExploreShow / AppRoute.ExploreShowByUrl shared 路由入口。
 *
 * 复用 shared [ExploreShowScreen] + [ExploreShowScreenModel] + [ExploreShowViewModelShared];
 * 路由参数取自 [entry]。三 slot 均用 shared 默认实现:
 * optionsRow 复用公共 [ExploreOptionsRow] (与主页展示项同一份 ExploreOptionView 复刻),
 * videoItem 复用 [ShelfVideoItem], cover 复用 [LocalBookCoverSlot] (宿主端可覆盖注入平台封面组件)。
 *
 * 双变体 (对照 master ExploreShowActivity):
 * - [AppRoute.ExploreShow]: 应用内跳转, 已有 [BookSource] 对象直传, 不查源
 *   (vm.initData(source, …) 重载, 用户要求: 发现→show 应用内跳转不做源查找)。
 * - [AppRoute.ExploreShowByUrl]: 外部入口 (SearchActivity alias 同款), 只带
 *   sourceUrl/exploreUrl/exploreName, 查源下沉到路由内
 *   (vm.initData(exploreName, exploreUrl, sourceUrl), 对照 master initData(intent):
 *   IntentData.source ?: DAO 查 sourceUrl), 查源期间页面直接渲染加载态, 书架不进导航栈。
 *
 * VM StateFlow → ScreenModel 事件桥接对照 app 端 Activity.observe(ViewModel LiveData)。
 */
@Composable
fun ExploreShowRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route
    val screenModel = screenModelStore.getOrCreateTyped(entry) { ExploreShowScreenModel() }

    // VM 创建 (组合委托, 对照 app 端 ExploreShowViewModel)
    val vmScope = remember { screenModelScope("发现列表") }
    val vm = remember(vmScope) { ExploreShowViewModelShared(vmScope) }
    DisposableEffect(vmScope) {
        onDispose { vmScope.cancel() }
    }

    // footer 文案 (对照 Activity getString(R.string.empty / bottom_line / error_load_msg))
    val emptyText = stringResource(Res.string.empty)
    val bottomLineText = stringResource(Res.string.bottom_line)
    val errorLoadText = stringResource(Res.string.error_load_msg, "点击查看详情")
    val errorTitle = stringResource(Res.string.error)
    val retryText = stringResource(Res.string.retry)

    // 标题初始化 (对照原 Activity intent exploreName / discovery; ByUrl 变体 extra 可为空)
    // route 由 RouteContent 保证只分派 ExploreShow 两变体, else 分支不可达 (穷尽性占位)
    val title = when (route) {
        is AppRoute.ExploreShow -> route.title
        is AppRoute.ExploreShowByUrl -> route.title.orEmpty()
        else -> ""
    }
    LaunchedEffect(title) {
        screenModel.dispatch(ExploreShowUiEvent.TitleChanged(title))
    }

    // 首次加载 + 初始化数据 (对照 Activity.onActivityCreated: startLoad + initData)
    LaunchedEffect(vm, route) {
        screenModel.dispatch(ExploreShowUiEvent.StartLoad)
        when (route) {
            // 应用内跳转: 已有 BookSource 直传, 不查源 (用户要求)
            is AppRoute.ExploreShow -> vm.initData(route.source, route.title, route.exploreUrl)
            // 外部入口: 查源下沉到路由内 (IntentData.source ?: DAO 查 sourceUrl)
            is AppRoute.ExploreShowByUrl -> vm.initData(
                route.title,
                route.exploreUrl,
                route.sourceUrl
            )
            // 不可达 (RouteContent 仅分派两变体); 占位保证 when 穷尽
            else -> Unit
        }
    }

    // VM 事件流 → ScreenModel 事件桥接 (对照 Activity.observe LiveData)
    LaunchedEffect(vm) {
        vm.booksFlow.collect { books ->
            screenModel.dispatch(
                ExploreShowUiEvent.BooksLoaded(
                    books = books,
                    hasNextPage = vm.hasNextPage,
                    emptyText = emptyText,
                    bottomLineText = bottomLineText,
                )
            )
        }
    }
    LaunchedEffect(vm) {
        vm.errorFlow.collect { msg ->
            screenModel.dispatch(ExploreShowUiEvent.ErrorLoaded(msg, errorLoadText))
        }
    }
    LaunchedEffect(vm) {
        vm.upAdapterFlow.collect {
            screenModel.dispatch(ExploreShowUiEvent.BookshelfVersionBump)
        }
    }
    LaunchedEffect(vm) {
        vm.upStarFlow.collect { isFavorite ->
            if (isFavorite != null) screenModel.dispatch(
                ExploreShowUiEvent.IsFavoriteChanged(
                    isFavorite
                )
            )
        }
    }
    LaunchedEffect(vm) {
        vm.sourceReadyFlow.collect {
            screenModel.dispatch(ExploreShowUiEvent.ExploreStyleChanged(vm.exploreStyle))
            // 菜单"书源登录"显隐: 书源带登录入口 (loginUrl/loginUi 非空, 对照原 hasLoginUrl)
            screenModel.dispatch(
                ExploreShowUiEvent.LoginAvailabilityChanged(vm.bookSource?.hasLogin() == true)
            )
        }
    }
    LaunchedEffect(vm) {
        vm.optionsReadyFlow.collect {
            screenModel.dispatch(ExploreShowUiEvent.OptionsVersionBump)
        }
    }

    val state by screenModel.state.collectAsState()

    // 错误详情对话框状态 (对照 Activity.onFooterClickImpl 的 alert)
    var showErrorDialog by remember { mutableStateOf(false) }
    // 列数选择器 (原走 PlatformCapabilities.showColumnPicker, 仅 Android 有实现; 改用 shared 对话框)
    var showColumnPicker by remember { mutableStateOf(false) }

    // 刷新当前列表: 清空 books + 重拉第一页 (对照注册的 refreshHandler / onExploreOptionChanged)
    val refresh = {
        screenModel.dispatch(ExploreShowUiEvent.ClearBooks)
        vm.explore(true)
    }

    val actions = remember(navigator, screenModel, vm) {
        object : ExploreShowUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 对照 Activity.scrollToTop: scrollTopEpoch++
            override fun onTitleClick() {
                screenModel.dispatch(ExploreShowUiEvent.ScrollTopBump)
            }

            // 对照 Activity.onToggleFavorite: viewModel.toggleFavorite()
            override fun onToggleFavorite() {
                vm.toggleFavorite()
            }

            // 菜单 - 刷新: 重拉第一页 (对齐注册的 refreshHandler 语义)
            override fun onRefresh() {
                refresh()
            }

            // 菜单 - 书源登录: 统一登录入口, URL 登录桌面端直开登录窗口 (2026-08-07)
            override fun onLogin() {
                val source = vm.bookSource ?: return
                showSourceLogin(source.bookSourceUrl, source)
            }

            // 对照 Activity.switchLayout: viewModel.switchLayout() + exploreStyle 更新
            override fun onSwitchLayout() {
                vm.switchLayout()
                screenModel.dispatch(ExploreShowUiEvent.ExploreStyleChanged(vm.exploreStyle))
            }

            // 对照 Activity.showColumnPicker: showNumberPicker + setColumnCount
            override fun onShowColumnPicker() {
                showColumnPicker = true
            }

            // 对照 Activity.showSourceFilterRule: 弹 scoped 列表对话框 (非跳转管理页)
            override fun onShowSourceFilterRule() {
                val scope = vm.bookSource?.let { SearchScope(it).toString() }
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceFilterRuleList",
                        payload = scope
                    )
                )
            }

            // 对照 Activity.onFooterClickImpl: errorMsg 非空弹详情+重试, 否则触发加载
            override fun onFooterClick() {
                if (screenModel.errorMsg.isNotBlank()) {
                    showErrorDialog = true
                    return
                }
                if (!screenModel.state.value.footerLoading) {
                    screenModel.dispatch(ExploreShowUiEvent.LoadMore)
                    vm.explore()
                }
            }

            // 对照 Activity.scrollToBottom: guard + hasMoreLoad + viewModel.explore()
            override fun onScrollToBottom() {
                if (screenModel.footerHasMore && !screenModel.state.value.footerLoading) {
                    screenModel.dispatch(ExploreShowUiEvent.LoadMore)
                    vm.explore()
                }
            }

            // 对照 Activity.onBookClickImpl + showBookInfo
            override fun onBookClick(book: SearchBook, longClick: Boolean) {
                // 补 notShelf type (对齐原 registerListener)
                if (!vm.isInBookShelf(book)) {
                    book.addType(BookType.notShelf)
                }
                // bookUrl 含 "::" 自跳转 ExploreShow (对照 showBookInfo 的 urlParts 分支)
                val urlParts = book.bookUrl.split("::", limit = 2)
                if (urlParts.size == 2) {
                    val source = vm.bookSource ?: return
                    navigator.push(AppRoute.ExploreShow(source, urlParts[0], urlParts[1]))
                    return
                }
                // longClick || !devFeat → BookInfo; 否则按类型分流
                if (longClick || !AppConfigProviders.get().devFeat) {
                    navigator.push(AppRoute.BookInfo(book.toRouteRef()))
                    return
                }
                when {
                    book.isVideo -> navigator.push(AppRoute.VideoPlay(book.toRouteRef()))
                    book.isRss -> navigator.push(AppRoute.ReadRss(book.toRouteRef()))
                    else -> navigator.push(AppRoute.BookInfo(book.toRouteRef()))
                }
            }

            // 对照 Activity.isInBookshelf: viewModel.isInBookShelf(book)
            override fun isInBookshelf(book: SearchBook): Boolean {
                return vm.isInBookShelf(book)
            }

            // 对照 Activity.onExploreOptionChangedImpl: 清空 books + hasMoreLoad + explore(true)
            override fun onExploreOptionChanged() {
                screenModel.dispatch(ExploreShowUiEvent.ClearBooks)
                vm.explore(true)
            }
        }
    }

    DisposableEffect(entry.id, vm) {
        navigator.registerRefreshHandler(entry.id) {
            refresh()
        }
        onDispose { navigator.unregisterRefreshHandler(entry.id) }
    }

    // 列数选择器 (对照 Activity.showColumnPicker → showNumberPicker(min=0, max=6))
    if (showColumnPicker) {
        NumberPickerDialog(
            title = stringResource(Res.string.explore_cols),
            value = BookSource.exploreStyleCols(vm.exploreStyle).coerceIn(0, 6),
            range = 0..6,
            onConfirm = { cols ->
                vm.setColumnCount(cols)
                screenModel.dispatch(ExploreShowUiEvent.ExploreStyleChanged(vm.exploreStyle))
                showColumnPicker = false
            },
            onDismiss = { showColumnPicker = false },
        )
    }

    // 错误详情对话框 (对照 Activity.onFooterClickImpl 的 alert)
    if (showErrorDialog) {
        AppAlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = errorTitle,
            message = screenModel.errorMsg,
            neutralButton = AlertButton(retryText) {
                showErrorDialog = false
                if (!screenModel.state.value.footerLoading) {
                    screenModel.dispatch(ExploreShowUiEvent.LoadMore)
                    vm.explore()
                }
            },
        )
    }

    ExploreShowScreen(
        state = state,
        actions = actions,
        optionsRowSlot = {
            // 参数 chip 行: 读 vm.exploreOptions, 选中变化调 onExploreOptionChanged 重载
            ExploreOptionsRow(
                options = vm.exploreOptions,
                optionsVersion = state.optionsVersion,
                onOptionSelected = actions::onExploreOptionChanged,
            )
        },
        // 视频卡: 复用 ShelfVideoItem (item_explore_video 的 shared Compose 版), 封面走 LocalBookCoverSlot
        videoItemSlot = { book, inBookshelf, onClick, onLongClick ->
            ShelfVideoItem(
                book = book.toCoverBook(inBookshelf),
                coverReloadTick = state.bookshelfVersion,
                onClick = onClick,
                onLongClick = onLongClick,
                coverSlot = { b, modifier, isVideoCover, coverReloadTick ->
                    LocalBookCoverSlot.current(b, modifier, isVideoCover, coverReloadTick)
                },
            )
        },
        // 封面: 复用 LocalBookCoverSlot (宿主端注入 ShelfCover, 兜底 SharedBookCover)
        coverSlot = { book, inBookshelf, isVideoStyle, modifier ->
            // toCoverBook 结果缓存一次, 避免每次重组新建 Book (对照 SearchScreen coverSlot 写法)
            LocalBookCoverSlot.current(
                remember(book, inBookshelf) { book.toCoverBook(inBookshelf) },
                modifier,
                isVideoStyle,
                0,
            )
        },
    )
}
