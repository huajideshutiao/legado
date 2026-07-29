package io.legado.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BottomNavTag
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.bookshelf.BookshelfActionsCallbacks
import io.legado.app.ui.bookshelf.BookshelfScreen
import io.legado.app.ui.bookshelf.BookshelfViewModel
import io.legado.app.ui.bookshelf.ShelfScrollState
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainScreen
import io.legado.app.ui.main.explore.ExploreScreen
import io.legado.app.ui.main.explore.ExploreScreenModel
import io.legado.app.ui.main.explore.ExploreUiActions
import io.legado.app.ui.main.explore.ExploreUiEvent
import io.legado.app.ui.main.explore.ExploreUiState
import io.legado.app.ui.main.home.HomeScreen
import io.legado.app.ui.main.home.HomeScreenModel
import io.legado.app.ui.main.home.homeSectionKey
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 shared 路由入口。
 *
 * 装配 [MainScreen]: 根据 [AppRoute.Main.tab] 定位初始页, 注入 4 个 tab composable
 * (Home/Bookshelf/Explore/MyConfig)。
 *
 * 对照 MainActivity: visibleTags 顺序校验 + initialPage 落点 + pageSelections 跳转流 +
 * onReselect 300ms 双击 (bookshelf gotoTop / explore compress) 均与 Activity 等价。
 * HomeTab 数据流由 [HomeScreenModel] 提供 (下沉 [io.legado.app.ui.main.home.HomeViewModelShared]);
 * slots (SectionBlock/InfiniteHeader/InfiniteGridCard) 原 app 端含 AndroidView/ShelfCover (L3),
 * shared 端用纯 Compose 实现占位 UI (标题 + 占位封面 + 书名/作者)。
 */
@Composable
fun MainRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val appConfig = remember { AppConfigProviders.get() }

    // 对照 MainActivity.computeVisibleTags: 顺序配置校验 + showHome/showDiscovery 过滤
    val visibleTags =
        remember(appConfig.bottomNavItemOrder, appConfig.showHome, appConfig.showDiscovery) {
            computeVisibleTags(appConfig)
        }

    // 对照 MainActivity.homePageIndex: defaultHomePage 落点, 目标 tab 隐藏时回落书架
    val initialPage = remember(visibleTags, appConfig.defaultHomePage) {
        computeHomePageIndex(visibleTags, appConfig.defaultHomePage)
    }

    // 对照 MainActivity.pageSelections: 页面跳转指令流 (index to smooth)
    val pageSelections: MutableSharedFlow<Pair<Int, Boolean>> =
        remember { MutableSharedFlow(extraBufferCapacity = 4) }

    // 对照 MainActivity.currentPage: pager 当前页 (返回键/reselect 判定)
    // rememberSaveable: 配合 LegadoApp 的 SaveableStateHolder, 返回主界面时恢复 tab 位置
    var currentPage by rememberSaveable { mutableStateOf(initialPage) }

    // 对照 MainActivity.bookshelfReselected/exploreReselected: 300ms 双击 reselect
    var bookshelfReselected by remember { mutableLongStateOf(0L) }
    var exploreReselected by remember { mutableLongStateOf(0L) }

    // ScreenModelStore 持有 BookshelfViewModel + ExploreScreenModel, 生命周期随 entry
    val mainScreenModel = screenModelStore.getOrCreateTyped(entry) { MainScreenModel() }
    val exploreListState = rememberLazyListState()
    val bookshelfScrollState = rememberSaveable(saver = ShelfScrollState.Saver) {
        ShelfScrollState()
    }
    val scope = rememberCoroutineScope()

    MainScreen(
        visibleTags = visibleTags,
        initialPage = initialPage,
        pageSelections = pageSelections,
        currentPageSink = { currentPage = it },
        onSelectPage = { index, smooth -> pageSelections.tryEmit(index to smooth) },
        onReselect = { tag ->
            // 对照 MainActivity.onTabReselect: 300ms 内双击触发
            when (tag) {
                BottomNavTag.BOOKSHELF -> {
                    val now = systemCurrentTimeMillis()
                    if (now - bookshelfReselected > 300) {
                        bookshelfReselected = now
                    } else {
                        // 对照 BookshelfTabController.gotoTop: tab 双击滚顶
                        // tier 决策与 BookshelfScreen 一致 (bookshelfLayout==0 → LIST, 否则 GRID)
                        scope.launch {
                            if (appConfig.bookshelfLayout == 0) {
                                if (appConfig.isEInkMode) {
                                    bookshelfScrollState.list.scrollToItem(0)
                                } else {
                                    bookshelfScrollState.list.animateScrollToItem(0)
                                }
                            } else {
                                if (appConfig.isEInkMode) {
                                    bookshelfScrollState.grid.scrollToItem(0)
                                } else {
                                    bookshelfScrollState.grid.animateScrollToItem(0)
                                }
                            }
                        }
                    }
                }

                BottomNavTag.DISCOVERY -> {
                    val now = systemCurrentTimeMillis()
                    if (now - exploreReselected > 300) {
                        exploreReselected = now
                    } else {
                        // 对照 ExploreTabState.compressExplore: 先收起已展开项, 未展开则滚顶
                        if (!mainScreenModel.exploreScreenModel.collapseExpanded()) {
                            scope.launch {
                                if (appConfig.isEInkMode) {
                                    exploreListState.scrollToItem(0)
                                } else {
                                    exploreListState.animateScrollToItem(0)
                                }
                            }
                        }
                    }
                }
            }
        },
        homeTab = { HomeTabContent(mainScreenModel.homeScreenModel, navigator) },
        bookshelfTab = {
            BookshelfTabContent(
                mainScreenModel.bookshelfViewModel,
                navigator,
                bookshelfScrollState,
            )
        },
        exploreTab = {
            ExploreTabContent(
                mainScreenModel.exploreScreenModel,
                exploreListState,
                navigator,
            )
        },
        myTab = { MyTabContent(navigator) },
        bottomBarIconSize = appConfig.bottomBarIconSize,
        bottomBarHeight = appConfig.bottomBarHeight,
        bottomBarLabelMode = appConfig.bottomBarLabelMode,
    )
}

/**
 * 主界面 ScreenModel: 持有 HomeScreenModel + BookshelfViewModel + ExploreScreenModel,
 * 生命周期随 MainRoute entry (ScreenModelStore 管理 onCleared)。
 */
private class MainScreenModel : ScreenModel {
    val homeScreenModel = HomeScreenModel()
    val bookshelfViewModel = BookshelfViewModel()
    val exploreScreenModel = ExploreScreenModel()

    override fun onCleared() {
        homeScreenModel.onCleared()
        bookshelfViewModel.onCleared()
        exploreScreenModel.onCleared()
    }
}

/** 对照 MainActivity.computeVisibleTags: 顺序配置校验 + showHome/showDiscovery 过滤 */
private fun computeVisibleTags(appConfig: AppConfigAccessor): List<String> {
    val defaultTagOrder = listOf(
        BottomNavTag.HOME,
        BottomNavTag.BOOKSHELF,
        BottomNavTag.DISCOVERY,
        BottomNavTag.MY,
    )
    val savedTagOrder = appConfig.bottomNavItemOrder.split(",").filter { it.isNotEmpty() }
    val orderedTags = savedTagOrder
        .takeIf { it.size == 4 && it.toSet() == defaultTagOrder.toSet() }
        ?: defaultTagOrder
    val tags = orderedTags.filter { tag ->
        when (tag) {
            BottomNavTag.HOME -> appConfig.showHome
            BottomNavTag.DISCOVERY -> appConfig.showDiscovery
            else -> true
        }
    }
    return tags.ifEmpty { listOf(BottomNavTag.BOOKSHELF) }
}

/** 对照 MainActivity.homePageIndex: defaultHomePage 落点, 目标 tab 隐藏时回落书架 */
private fun computeHomePageIndex(visibleTags: List<String>, defaultHomePage: String): Int {
    val bookshelfPos = visibleTags.indexOf(BottomNavTag.BOOKSHELF)
    val pos = when (defaultHomePage) {
        "home" -> visibleTags.indexOf(BottomNavTag.HOME)
        "bookshelf" -> bookshelfPos
        "explore" -> visibleTags.indexOf(BottomNavTag.DISCOVERY)
        "my" -> visibleTags.indexOf(BottomNavTag.MY)
        else -> bookshelfPos
    }
    return if (pos >= 0) pos else bookshelfPos.coerceAtLeast(0)
}

/**
 * 主页 tab: 通过 [HomeScreenModel] 接入数据流。
 *
 * ScreenModel 持有 [io.legado.app.ui.main.home.HomeViewModelShared] (组合委托),
 * 把 6 个 StateFlow 投影为 [HomeUiState], 并实现 [HomeUiActions]。
 *
 * slots (SectionBlock/InfiniteHeader/InfiniteGridCard) 原 app 端含 AndroidView/ShelfCover (L3),
 * 此处用 shared 端纯 Compose 实现占位 UI (标题 + 占位封面 + 书名/作者), 不依赖 L3 组件;
 * 书籍点击对照 ExploreShowRoute.onBookClick: 跳 BookInfo 详情页 (SearchBook.toRouteRef())。
 */
@Composable
private fun HomeTabContent(screenModel: HomeScreenModel, navigator: AppNavigator) {
    val state by screenModel.state.collectAsState()
    HomeScreen(
        state = state,
        actions = screenModel,
        // slots: shared 端纯 Compose 实现 (无 L3 AndroidView/ShelfCover, 用占位封面)
        sectionBlockSlot = { tabTitle, section ->
            HomeSectionBlock(
                section = section,
                books = state.sectionBooks[homeSectionKey(tabTitle, section.id)] ?: emptyList(),
                loading = state.sectionLoading[homeSectionKey(tabTitle, section.id)] == true,
                error = state.sectionError[homeSectionKey(tabTitle, section.id)] == true,
                onBookClick = { book -> navigator.push(AppRoute.BookInfo(book.toRouteRef())) },
            )
        },
        infiniteHeaderSlot = { _, section -> HomeInfiniteHeader(section) },
        infiniteGridCardSlot = { _, _, book ->
            HomeInfiniteGridCard(book) { navigator.push(AppRoute.BookInfo(book.toRouteRef())) }
        },
    )
}

/** 非无限流展示项区块: 标题行 + 内容区 (CoverRow/FourRow 横向封面行 / RankList 排行榜列) */
@Composable
private fun HomeSectionBlock(
    section: HomeSection,
    books: List<SearchBook>,
    loading: Boolean,
    error: Boolean,
    onBookClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitleRow(section.title)
        when {
            error -> Box(
                Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rememberString("error_load_msg"),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                )
            }

            books.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Text(
                        text = rememberString("empty"),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                    )
                }
            }

            else -> when (section.style) {
                HomeSection.STYLE_RANK_LIST -> HomeRankList(books, onBookClick)
                else -> HomeCoverRow(books, onBookClick, section.coverVideo)
            }
        }
    }
}

/** 展示项标题行 (对照 SectionTitleRow: 粗体标题 + 左侧色块) */
@Composable
private fun HomeSectionTitleRow(title: String) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(colors.accent),
        )
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 横向封面行 (对照 CoverRow/FourRow): 占位封面 + 书名, 横向滚动 */
@Composable
private fun HomeCoverRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    isVideoStyle: Boolean,
) {
    val colors = AppTheme.colors
    val coverHeight = AppConfigProviders.get().bookshelfCoverHeight
        .let { if (isVideoStyle) (it * 0.75f).toInt() else it }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        books.forEach { book ->
            Column(
                Modifier
                    .width(90.dp)
                    .padding(horizontal = 4.dp)
                    .clickable { onBookClick(book) },
            ) {
                // 占位封面 (无 L3 ShelfCover): 纯色 Box + 书名首字
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(coverHeight.dp)
                        .background(colors.bottomBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!book.coverUrl.isNullOrBlank()) {
                        Text(
                            text = book.name.take(2),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = book.name,
                    color = colors.primaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** 排行榜列 (对照 RankColumn): 序号 + 书名 + 作者, 垂直列表 */
@Composable
private fun HomeRankList(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        books.forEachIndexed { index, book ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable { onBookClick(book) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    color = if (index < 3) colors.accent else colors.secondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(
                        text = book.name,
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            text = book.getRealAuthor(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 无限流头部: 标题行 (无 AndroidView 参数 chip 行, 纯 Compose) */
@Composable
private fun HomeInfiniteHeader(section: HomeSection) {
    HomeSectionTitleRow(section.title)
}

/** 无限流网格单元 (对照 InfiniteGridCard): 占位封面 + 书名, 占满单格宽度 */
@Composable
private fun HomeInfiniteGridCard(
    book: SearchBook,
    onBookClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val coverHeight = AppConfigProviders.get().bookshelfCoverHeight
    Box(Modifier.fillMaxWidth().combinedClickable(onClick = onBookClick)) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp)
                    .height(coverHeight.dp)
                    .background(colors.bottomBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (!book.coverUrl.isNullOrBlank()) {
                    Text(
                        text = book.name.take(2),
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 书架 tab: ViewModel 来自 [MainScreenModel] (ScreenModelStore 生命周期)。
 *
 * onBookClick 对照 app 端 BaseBookshelfState.open (→ startActivityForBook 按书籍类型分流);
 * onBookLongClick 对照 BaseBookshelfState.openBookInfo (→ BookInfoActivity)。
 */
@Composable
private fun BookshelfTabContent(
    viewModel: BookshelfViewModel,
    navigator: AppNavigator,
    scrollState: ShelfScrollState,
) {
    var showAppLog by remember { mutableStateOf(false) }
    // 对照 app 端 BookshelfActions 菜单项: 路由类用 navigator.push, 日志用对话框
    val callbacks = remember(navigator) {
        BookshelfActionsCallbacks(
            onAddLocalBook = { navigator.push(AppRoute.ImportBook) },
            onAddRemoteBook = { navigator.push(AppRoute.RemoteBook) },
            onOpenBookshelfManage = { navigator.push(AppRoute.BookshelfManage) },
            onShowAppLog = { showAppLog = true },
        )
    }
    BookshelfScreen(
        viewModel = viewModel,
        onBookClick = { book -> navigator.push(book.toReadRoute()) },
        onBookLongClick = { book -> navigator.push(AppRoute.BookInfo(book.toRouteRef())) },
        onSearchClick = { navigator.push(AppRoute.Search()) },
        bookshelfActionsCallbacks = callbacks,
        scrollState = scrollState,
    )
    // 应用日志对话框 (对照 SearchRoute / LoginRoute 同名状态)
    if (showAppLog) {
        AppLogDialog(onDismiss = { showAppLog = false })
    }
}

/**
 * 发现 tab: 通过 [ExploreScreenModel] 接入 sources/pinned/groups/kinds 数据流,
 * 导航类 actions 走 [AppNavigator], 平台对话框 (删除确认/收藏删除/分类错误) 本 Route 持有。
 *
 * 对照 ExploreRoute (独立发现页): 共用同一 ScreenModel + actions 模式, 差异仅无 onBack。
 */
@Composable
private fun ExploreTabContent(
    screenModel: ExploreScreenModel,
    listState: LazyListState,
    navigator: AppNavigator,
) {
    val screenState by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

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

    // 平台对话框状态 (对照 ExploreRoute 同名状态)
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
                screenModel.dispatch(ExploreUiEvent.ToTop(source))
            }

            override fun onLogin(source: BookSourcePart) {
                navigator.push(AppRoute.Login(source.bookSourceUrl))
            }

            override fun onSearchBook(source: BookSourcePart) {
                // AppRoute.Search 暂无 SearchScope 参数, 走全局搜索
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

    ExploreScreen(state = uiState, actions = actions)
}

/**
 * 我的 tab: 配置项回调用 navigator 跳转, 主题切换走 PlatformCapabilityProviders。
 * webService 开关态/长按菜单对照 MyConfigRoute (shared 端统一模式)。
 */
@Composable
private fun MyTabContent(navigator: AppNavigator) {
    var webServiceChecked by remember { mutableStateOf(false) }
    var webServiceSummary by remember { mutableStateOf("") }
    var showWebServiceMenu by remember { mutableStateOf(false) }

    MyConfigScreen(
        webServiceChecked = webServiceChecked,
        webServiceSummary = webServiceSummary,
        onThemeModeChange = {
            PlatformCapabilityProviders.getOrNull()?.applyDayNight()
        },
        onWebServiceChange = { webServiceChecked = it },
        onWebServiceLongClick = { showWebServiceMenu = true },
        onThemeSetting = { navigator.push(AppRoute.ThemeConfig) },
        onWebDavSetting = { navigator.push(AppRoute.BackupConfig) },
        onOtherSetting = { navigator.push(AppRoute.OtherConfig) },
        onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
        onReplaceManage = { navigator.push(AppRoute.ReplaceRule) },
        onSourceFilterRuleManage = { navigator.push(AppRoute.SourceFilterRule) },
        onTxtTocRuleManage = { navigator.push(AppRoute.TxtTocRule) },
        onDictRuleManage = { navigator.push(AppRoute.DictRule) },
        onRuleSubManage = { navigator.push(AppRoute.RuleSub) },
        onBookmark = { navigator.push(AppRoute.Bookmark()) },
        onReadRecord = { navigator.push(AppRoute.ReadRecord) },
        onAbout = { navigator.push(AppRoute.About) },
    )

    // web 服务长按菜单 (对照 app 端 selector: 复制地址 / 浏览器打开)
    if (showWebServiceMenu) {
        val colors = AppTheme.colors
        val url = PlatformCapabilityProviders.getOrNull()?.getWebServiceUrl()
        AlertDialog(
            onDismissRequest = { showWebServiceMenu = false },
            title = { Text(rememberString("web_service"), color = colors.primaryText) },
            text = {
                Column {
                    TextButton(onClick = {
                        showWebServiceMenu = false
                        url?.let { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(it) }
                    }) { Text(rememberString("copy_url"), color = colors.primaryText) }
                    TextButton(onClick = {
                        showWebServiceMenu = false
                        url?.let { PlatformCapabilityProviders.getOrNull()?.openExternalUrl(it) }
                    }) { Text(rememberString("open_in_browser"), color = colors.primaryText) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showWebServiceMenu = false }) {
                    Text(rememberString("cancel"))
                }
            },
            shape = AppTheme.DesignTokens.dialogShape,
            backgroundColor = MaterialTheme.colors.surface,
        )
    }
}
