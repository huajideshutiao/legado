package io.legado.app.ui.route

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.showSourceLogin
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.bookshelf.BookshelfActionsCallbacks
import io.legado.app.ui.bookshelf.BookshelfAddViewModelShared
import io.legado.app.ui.bookshelf.BookshelfScreen
import io.legado.app.ui.bookshelf.BookshelfViewModel
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.bookshelf.ShelfScrollState
import io.legado.app.ui.bookshelf.ShelfVideoItem
import io.legado.app.ui.bookshelf.toCoverBook
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.ExploreOptionsRow
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.component.horizontalMouseWheel
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.transitionStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.main.MainScreen
import io.legado.app.ui.main.explore.ExploreScreen
import io.legado.app.ui.main.explore.ExploreScreenModel
import io.legado.app.ui.main.explore.ExploreUiActions
import io.legado.app.ui.main.explore.ExploreUiEvent
import io.legado.app.ui.main.explore.ExploreUiState
import io.legado.app.ui.main.home.HomeScreen
import io.legado.app.ui.main.home.HomeScreenModel
import io.legado.app.ui.main.home.HomeSectionManageDialog
import io.legado.app.ui.main.home.HomeTabManageDialog
import io.legado.app.ui.main.home.homeSectionKey
import io.legado.app.ui.main.my.MyConfigScreen
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.LocalPlatformCapabilities
import io.legado.app.ui.root.MainTab
import io.legado.app.ui.root.MainTabSwitcher
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FlowBus
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_book_url
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.error
import legado.shared.generated.resources.help
import legado.shared.generated.resources.home_more
import legado.shared.generated.resources.home_source_invalid
import legado.shared.generated.resources.ic_arrow_right
import legado.shared.generated.resources.ic_help
import legado.shared.generated.resources.import_bookshelf
import legado.shared.generated.resources.my
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.select_file
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.web_service
import legado.shared.generated.resources.web_service_desc
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
    val eventBus = LocalEventBusProvider.current

    // 底栏配置变更即时生效 (修复: 调整底栏设置后需切 tab 才刷新):
    // 各端底栏设置对话框写入后 emitRecreate()/postEvent(EventBus.RECREATE), 本组合
    // 订阅 recreateEvent 递增 configTick 强制重组 (与 AppTheme 观察同一事件流同模式)。
    // 底栏配置 (bottomBarHeight/IconSize/LabelMode/showHome/showDiscovery/bottomNavItemOrder)
    // 均为直读配置的普通 getter (Android 直读 SharedPreferences, 桌面/iOS/鸿蒙经
    // CachedPrefValue 由 pref 变更监听同步刷新), 重组即读到新值, remember 键变化后
    // 底栏立即按新配置刷新, 无需切 tab。
    // 对照原版: BaseActivity 观察 EventBus.RECREATE → recreate() → upHomePage() 整页重建。
    var configTick by remember(eventBus) { mutableIntStateOf(0) }
    LaunchedEffect(eventBus) {
        eventBus.recreateEvent.collect { configTick++ }
    }
    configTick

    // 对照 MainActivity.computeVisibleTags: 顺序配置校验 + showHome/showDiscovery 过滤
    val visibleTags =
        remember(
            configTick,
            appConfig.bottomNavItemOrder,
            appConfig.showHome,
            appConfig.showDiscovery,
        ) {
            computeVisibleTags(appConfig)
        }

    // 对照 MainActivity.homePageIndex: defaultHomePage 落点, 目标 tab 隐藏时回落书架
    val initialPage = remember(configTick, visibleTags, appConfig.defaultHomePage) {
        computeHomePageIndex(visibleTags, appConfig.defaultHomePage)
    }

    // 对照 MainActivity.pageSelections: 页面跳转指令流 (index to smooth)
    val pageSelections: MutableSharedFlow<Pair<Int, Boolean>> =
        remember { MutableSharedFlow(extraBufferCapacity = 4) }

    // 外部切 tab 请求 (桌面端控制栏菜单等): MainTab → 当前可见 tag 列表 index → 平滑滚动
    // (目标 tab 被隐藏时 indexOf 为 -1, 忽略请求; 与 reselect 语义一致)
    LaunchedEffect(visibleTags) {
        MainTabSwitcher.flow.collect { tab ->
            val tag = when (tab) {
                MainTab.HOME -> BottomNavTag.HOME
                MainTab.BOOKSHELF -> BottomNavTag.BOOKSHELF
                MainTab.DISCOVERY -> BottomNavTag.DISCOVERY
                MainTab.MY -> BottomNavTag.MY
            }
            val index = visibleTags.indexOf(tag)
            if (index >= 0) pageSelections.tryEmit(index to true)
        }
    }

    // 对照 MainActivity.currentPage: pager 当前页 (返回键/reselect 判定)
    // rememberSaveable: 配合 LegadoApp 的 SaveableStateHolder, 返回主界面时恢复 tab 位置
    var currentPage by rememberSaveable { mutableStateOf(initialPage) }

    // 对照原版 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT: pager 停稳页 (拖拽回弹/动画中不变),
    // 仅用于门控 home tab 的 section 网络加载——预组合的 tab 不加载, 真正翻到才加载
    var settledPage by rememberSaveable { mutableIntStateOf(initialPage) }

    // tab 集合/顺序变化 (底栏配置变更): 按 tag 定位保持当前 tab (换序/纯外观变更不打断),
    // 仅当前 tab 被隐藏时回落首个可见 tab——不回默认首页 (用户确认回默认页行为多余)。
    var lastVisibleTags by remember { mutableStateOf(visibleTags) }
    LaunchedEffect(visibleTags) {
        if (visibleTags != lastVisibleTags) {
            val old = lastVisibleTags
            lastVisibleTags = visibleTags
            val curTag = old.getOrNull(currentPage)
            val newIndex = curTag?.let { tag -> visibleTags.indexOf(tag) }?.takeIf { it >= 0 } ?: 0
            if (newIndex != currentPage) pageSelections.tryEmit(newIndex to false)
        }
    }

    // 对照 MainActivity.bookshelfReselected/exploreReselected: 300ms 双击 reselect
    var bookshelfReselected by remember { mutableLongStateOf(0L) }
    var exploreReselected by remember { mutableLongStateOf(0L) }
    // 书架滚顶信号 (对照 BookshelfTabController.gotoTop): 具体滚哪个状态由 BookshelfScreen
    // 按"当前分组页 + 布局档位"决定, 此处只发信号
    var bookshelfGotoTopTick by remember { mutableIntStateOf(0) }
    // 书架 tab 激活态: 书架 DB 订阅开关 (tab 切走即取消订阅, 零全表流)
    val bookshelfActive = visibleTags.getOrNull(currentPage) == BottomNavTag.BOOKSHELF
    // home tab 激活态: 用停稳页判定 (对照原版仅当前 Fragment 才 onResume→initTab),
    // 拖拽到一半又滑回不触发; 预组合的 home 页保持组合但不加载
    val homeActive = visibleTags.getOrNull(settledPage) == BottomNavTag.HOME

    // ScreenModelStore 持有 BookshelfViewModel + ExploreScreenModel, 生命周期随 entry
    val mainScreenModel = screenModelStore.getOrCreateTyped(entry) { MainScreenModel() }
    val exploreListState = rememberLazyListState()
    val bookshelfScrollState = rememberSaveable(saver = ShelfScrollState.Saver) {
        ShelfScrollState()
    }
    val scope = rememberCoroutineScope()

    // F5 刷新: 四个 tab 共享同一 entry, 故只在此处唯一注册, 按当前页分发到对应 tab 的刷新入口
    // (书架 upToc / 首页 refreshCurrentTab; 发现、我的无刷新动作)。
    // 当前页用 rememberUpdatedState 透传, 保证 handler 只注册/注销一次。
    val refreshTag = rememberUpdatedState(visibleTags.getOrNull(currentPage))
    DisposableEffect(entry.id) {
        navigator.registerRefreshHandler(entry.id) {
            when (refreshTag.value) {
                BottomNavTag.BOOKSHELF -> mainScreenModel.bookshelfViewModel.upToc()
                BottomNavTag.HOME -> mainScreenModel.homeScreenModel.refreshCurrentTab()
                else -> Unit
            }
        }
        onDispose { navigator.unregisterRefreshHandler(entry.id) }
    }

    // 主界面返回键/ESC: 对照原版 MainActivity.onActivityCreated 的 onBackPressedDispatcher 回调——
    // 非书架 tab → 先切回书架 (不再继续); 书架 tab → 双击退出 (2000ms 窗口, 对齐原版 EXIT_INTERVAL)。
    // 经 AppBackHandler 注册进统一返回链 (覆盖物→Overlay→页面拦截→出栈): 主界面是根页面,
    // 出栈无效, 故启用时恒消费。书架分组页 (BookshelfScreen2) 的返回拦截在本组合之后注册,
    // 栈序优先 (后注册先分发), 分组内返回键先回根分组, 与"书架内部 back() 优先"语义一致。
    // LegadoApp 保持栈内页面同一 Composition (非顶层仅移出可见区), 故仅当主界面是栈顶
    // (backStack.last 为本 entry) 时才拦截——详情页等顶层页面无自身拦截器时返回键正常出栈。
    val backStack by navigator.backStack.collectAsState()
    val overlays by navigator.overlays.collectAsState()
    val bookshelfIndex = visibleTags.indexOf(BottomNavTag.BOOKSHELF)
    var exitTime by remember { mutableLongStateOf(0L) }
    val platformCapabilities = LocalPlatformCapabilities.current
    // enabled 条件: 主界面是栈顶 (详情页等打开时不拦截, 返回键正常出栈) 且无 Overlay
    // (Overlay 由 LegadoApp 根部 BackHandler 先关, 对齐原版"对话框先吃返回键")
    AppBackHandler(enabled = backStack.lastOrNull()?.id == entry.id && overlays.isEmpty()) {
        if (bookshelfIndex >= 0 && currentPage != bookshelfIndex) {
            pageSelections.tryEmit(bookshelfIndex to true)
            return@AppBackHandler
        }
        // 对照原版 exitTime/EXIT_INTERVAL: 第一次提示, 2000ms 内第二次退出
        val now = systemCurrentTimeMillis()
        if (now - exitTime > 2000L) {
            Toasters.get().toast("再按一次退出程序")
            exitTime = now
        } else {
            platformCapabilities.exitApplication()
        }
    }

    MainScreen(
        visibleTags = visibleTags,
        initialPage = initialPage,
        pageSelections = pageSelections,
        currentPageSink = { currentPage = it },
        settledPageSink = { settledPage = it },
        onSelectPage = { index -> pageSelections.tryEmit(index to true) },
        onReselect = { tag ->
            // 对照 MainActivity.onTabReselect: 300ms 内双击触发
            when (tag) {
                BottomNavTag.BOOKSHELF -> {
                    val now = systemCurrentTimeMillis()
                    if (now - bookshelfReselected > 300) {
                        bookshelfReselected = now
                    } else {
                        // 对照 BookshelfFragment1.gotoTop: 滚顶作用于"当前分组页", 且档位判定
                        // 与布局 spec 同源, 故只发信号, 由 BookshelfScreen 决定滚哪个状态
                        bookshelfGotoTopTick++
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
        homeTab = { HomeTabContent(mainScreenModel.homeScreenModel, navigator, homeActive) },
        bookshelfTab = {
            BookshelfTabContent(
                mainScreenModel.bookshelfViewModel,
                navigator,
                bookshelfScrollState,
                bookshelfGotoTopTick,
                bookshelfActive,
                isRootTop = backStack.lastOrNull()?.id == entry.id,
            )
        },
        exploreTab = {
            ExploreTabContent(
                entry = entry,
                screenModel = mainScreenModel.exploreScreenModel,
                listState = exploreListState,
                navigator = navigator,
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
private fun HomeTabContent(
    screenModel: HomeScreenModel,
    navigator: AppNavigator,
    // 主界面停稳页是否为本 tab (对照原版当前 Fragment 才 onResume→initTab)
    active: Boolean,
) {
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    // 顶栏两个管理入口的弹窗态由 ScreenModel 持有, 本 Route 据此渲染 shared 对话框
    val manageSectionOf by screenModel.manageSectionOf.collectAsState()
    val manageTab by screenModel.manageTab.collectAsState()

    // 对照 HomeTabFragment.sectionCallback.onBookClick 的分流
    val onBook: (SearchBook, HomeSection, Boolean) -> Unit = { book, section, longClick ->
        openHomeBook(book, section, longClick, navigator, scope)
    }
    // 对照 sectionCallback.onMoreClick: 标题行整行点击 → ExploreShow (该展示项的发现地址)
    val onMore: (HomeSection) -> Unit = { section ->
        scope.launch { openExploreShow(section, navigator) }
    }
    HomeScreen(
        state = state,
        actions = screenModel,
        active = active,
        // slots: shared 端纯 Compose 实现 (无 L3 AndroidView/ShelfCover, 用占位封面)
        sectionBlockSlot = { tabTitle, section ->
            HomeSectionBlock(
                section = section,
                books = state.sectionBooks[homeSectionKey(tabTitle, section.id)] ?: emptyList(),
                loading = state.sectionLoading[homeSectionKey(tabTitle, section.id)] == true,
                error = state.sectionError[homeSectionKey(tabTitle, section.id)] == true,
                options = state.sectionOptions[homeSectionKey(tabTitle, section.id)] ?: emptyList(),
                optionsVersion = state.sectionOptionsVersion,
                onOptionSelected = { screenModel.onSectionOptionSelected(tabTitle, section) },
                onBookClick = { book -> onBook(book, section, false) },
                onBookLongClick = { book -> onBook(book, section, true) },
                onMoreClick = { onMore(section) },
            )
        },
        infiniteHeaderSlot = { tabTitle, section ->
            HomeInfiniteHeader(
                section = section,
                options = state.sectionOptions[homeSectionKey(tabTitle, section.id)] ?: emptyList(),
                optionsVersion = state.sectionOptionsVersion,
                onOptionSelected = { screenModel.onSectionOptionSelected(tabTitle, section) },
                onMoreClick = { onMore(section) },
            )
        },
        infiniteGridCardSlot = { _, section, book ->
            HomeInfiniteGridCard(
                book = book,
                coverVideo = section.coverVideo,
                onBookClick = { onBook(book, section, false) },
                onBookLongClick = { onBook(book, section, true) },
            )
        },
    )

    // 顶栏"管理展示项" (对照 main_home 菜单 menu_manage_section)
    manageSectionOf?.let { tabTitle ->
        HomeSectionManageDialog(
            tabTitle = tabTitle,
            onDismiss = { screenModel.closeManageSection() },
        )
    }
    // 顶栏"管理分组" (对照 main_home 菜单 menu_manage_tab)
    if (manageTab) {
        HomeTabManageDialog(onDismiss = { screenModel.closeManageTab() })
    }
}

/**
 * 主页书籍点击分流 (对照 HomeTabFragment.sectionCallback.onBookClick)。
 *
 * bookUrl 含 "::" 伪 URL → ExploreShow (分类跳转); 否则长按/未开 devFeat 走 BookInfo,
 * 开了 devFeat 时按书籍类型分流 video/rss/其他。
 */
private fun openHomeBook(
    book: SearchBook,
    section: HomeSection,
    longClick: Boolean,
    navigator: AppNavigator,
    scope: CoroutineScope,
) {
    val urlParts = book.bookUrl.split("::", limit = 2)
    if (urlParts.size == 2) {
        scope.launch {
            val source = withContext(IoDispatcher) {
                AppDbProviders.get().bookSourceDao.getBookSource(section.sourceUrl)
            }
            if (source != null) {
                navigator.push(AppRoute.ExploreShow(source, urlParts[0], urlParts[1]))
            } else {
                Toasters.get().toast("Source not found")
            }
        }
        return
    }
    val ref = book.toRouteRef()
    when {
        longClick || !AppConfigProviders.get().devFeat -> navigator.push(AppRoute.BookInfo(ref))
        book.isVideo -> navigator.push(AppRoute.VideoPlay(ref))
        book.isRss -> navigator.push(AppRoute.ReadRss(ref))
        else -> navigator.push(AppRoute.BookInfo(ref))
    }
}

/** 对照 sectionCallback.onMoreClick: 按 section 的 sourceUrl 取书源后跳 ExploreShow */
private suspend fun openExploreShow(section: HomeSection, navigator: AppNavigator) {
    val source = withContext(IoDispatcher) {
        AppDbProviders.get().bookSourceDao.getBookSource(section.sourceUrl)
    }
    if (source != null) {
        navigator.push(AppRoute.ExploreShow(source, section.exploreName, section.exploreUrl))
    } else {
        Toasters.get().toast("Source not found")
    }
}

/** 非无限流展示项区块: 标题行 + 参数 chip 行 + 内容区 (CoverRow/FourRow 横向封面行 / RankList 排行榜列) */
@Composable
private fun HomeSectionBlock(
    section: HomeSection,
    books: List<SearchBook>,
    loading: Boolean,
    error: Boolean,
    options: List<ExploreOption>,
    optionsVersion: Int,
    onOptionSelected: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    onMoreClick: () -> Unit,
) {
    // 对照 LoadMoreView.showErrorDialog: 错误占位点击弹对话框 (原版无 retry 监听, 无按钮)
    var showErrorDialog by remember { mutableStateOf(false) }
    // 稳定化回调: 上层槽每次调用都新建 lambda 实例, 直接透传时数据未变也会全量重组本区块
    val currentOnOptionSelected = rememberUpdatedState(onOptionSelected)
    val stableOnOptionSelected: () -> Unit = remember { { currentOnOptionSelected.value() } }
    val currentOnBookClick = rememberUpdatedState(onBookClick)
    val stableOnBookClick: (SearchBook) -> Unit =
        remember { { book -> currentOnBookClick.value(book) } }
    val currentOnBookLongClick = rememberUpdatedState(onBookLongClick)
    val stableOnBookLongClick: (SearchBook) -> Unit =
        remember { { book -> currentOnBookLongClick.value(book) } }
    val currentOnMoreClick = rememberUpdatedState(onMoreClick)
    val stableOnMoreClick: () -> Unit = remember { { currentOnMoreClick.value() } }
    // 对照 SectionHolder.root: 每个展示项上下留白 (top default=8 / bottom xs=4)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        HomeSectionTitleRow(section.title, stableOnMoreClick)
        ExploreOptionsRow(options, optionsVersion, stableOnOptionSelected)
        when {
            error -> SectionStateText(
                text = stringResource(Res.string.home_source_invalid),
                onClick = { showErrorDialog = true },
            )

            books.isEmpty() -> if (loading) {
                SectionStateLoading()
            } else {
                SectionStateText(text = stringResource(Res.string.empty))
            }

            else -> when (section.style) {
                // 对照 HomeSectionAdapter.RANK_LIMIT: 排行榜单列前 5 名, 宽屏自适应两列前 10 名
                HomeSection.STYLE_RANK_LIST ->
                    HomeRankList(books, stableOnBookClick, stableOnBookLongClick)

                // 对照 FourColumnAdapter: 每列 4 本, 横向翻列 (宽屏不限数量)
                HomeSection.STYLE_FOUR_ROW -> HomeFourRow(books, stableOnBookClick, stableOnBookLongClick)

                // 对照 HomeSectionAdapter: COVER_ROW 走封面行, 未知样式回落排行榜
                HomeSection.STYLE_COVER_ROW ->
                    HomeCoverRow(books, stableOnBookClick, stableOnBookLongClick, section.coverVideo)

                else -> HomeRankList(books, stableOnBookClick, stableOnBookLongClick)
            }
        }
    }
    // 对照 LoadMoreView.showErrorDialog: 标题"错误" + 书源无效, 无按钮
    if (showErrorDialog) {
        AppAlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = stringResource(Res.string.error),
            message = stringResource(Res.string.home_source_invalid),
        )
    }
}

/** 展示项加载中占位 (对照 LoadMoreView.startLoad: 36dp 转圈 + 8dp 边距) */
@Composable
private fun SectionStateLoading() {
    val colors = AppTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** 展示项空/错误占位 (对照 view_load_more.xml tv_text: 14sp 摘要色 + 12dp 纵向 padding, 单行居中) */
@Composable
private fun SectionStateText(text: String, onClick: (() -> Unit)? = null) {
    val colors = AppTheme.colors
    Text(
        text = text,
        color = colors.secondaryText,
        fontSize = 14.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
    )
}

/** 对照 HomeSectionAdapter.RANK_LIMIT: 排行榜样式仅展示前 N 名 */
private const val HOME_RANK_LIMIT = 5

/**
 * 展示项标题行 (对照 view_home_section_title.xml: 高 36dp, paddingStart 16 / paddingEnd 8,
 * 标题 16sp 加粗 + "更多" 13sp 摘要色 + 16dp 右箭头, 整行可点)。
 */
@Composable
private fun HomeSectionTitleRow(title: String, onMoreClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onMoreClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 对照 view_home_section_title.xml 的 tv_more + iv_arrow (13sp 摘要色 + 16dp 箭头)
        Text(
            text = stringResource(Res.string.home_more),
            color = colors.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_right),
            contentDescription = stringResource(Res.string.home_more),
            tint = colors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 横向封面行 (对照 CoverCardAdapter): 封面 + 书名, 横向滚动。
 *
 * isVideoStyle=true (section.coverVideo) 时对照原版 CoverCardAdapter 的
 * VideoCoverCardVH: 复用 item_explore_video 视频卡 (shared [ShelfVideoItem]),
 * 卡片宽 220dp (原版把 match_parent 根布局改为固定 220dp 才能在横向滚动里排布),
 * 封面按 VIDEO(16:9) 比例由宽度反推高度, 加粗标题 + 分类 + 作者, 无徽标。
 */
@Composable
private fun HomeCoverRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    isVideoStyle: Boolean,
) {
    val scrollState = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .horizontalMouseWheel(scrollState)
            .padding(horizontal = 8.dp),
    ) {
        if (isVideoStyle) {
            // 对照原 VideoCoverCardVH.bind: bindVideoCard(coverRatio=VIDEO, isInBookshelf=false,
            // showBookshelfBadge=false); 封面走 LocalBookCoverSlot (与书架/探索页一致)
            books.forEach { book ->
                ShelfVideoItem(
                    book = book.toCoverBook(),
                    coverReloadTick = 0,
                    onClick = { onBookClick(book) },
                    onLongClick = { onBookLongClick(book) },
                    modifier = Modifier.width(220.dp),
                    coverSlot = { b, m, isVideoCover, tick ->
                        LocalBookCoverSlot.current(b, m, isVideoCover, tick)
                    },
                )
            }
        } else {
            val colors = AppTheme.colors
            // 对照 item_home_cover_card.xml + CoverCardVH.bind: 封面 120×160dp (高 160dp 由
            // 封面组件按 NOVEL 3:4 反推宽 120dp), item 总宽 128 = 120 + 两侧 4dp padding
            books.forEach { book ->
                Column(
                    Modifier
                        .width(128.dp)
                        .padding(4.dp)
                        .combinedClickable(
                            onClick = { onBookClick(book) },
                            onLongClick = { onBookLongClick(book) },
                        ),
                ) {
                    // 封面: 走 LocalBookCoverSlot (与书架/探索页一致)
                    LocalBookCoverSlot.current(
                        book.toCoverBook(),
                        Modifier
                            .width(120.dp)
                            .height(160.dp),
                        false,
                        0,
                    )
                    // 对照 XML tv_name: 12sp 最多 2 行 (minLines=2 保持卡片等高)
                    Text(
                        text = book.name,
                        color = colors.primaryText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    // 对照 XML tv_author: 10sp 摘要色, 最多 1 行, marginTop 2dp
                    Text(
                        text = book.getRealAuthor(),
                        color = colors.secondaryText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 排行榜列 (对照 RankBookAdapter showRank=true): 序号 + 封面 + 书名/作者, 窄屏单列5项 / 宽屏(>=600dp)双列10项 */
@Composable
private fun HomeRankList(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val isWide = maxWidth >= AppTheme.DesignTokens.wideScreenMinWidth
        if (isWide) {
            val displayBooks = books.take(10)
            val leftBooks = displayBooks.take(5)
            val rightBooks = displayBooks.drop(5)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    leftBooks.forEachIndexed { index, book ->
                        HomeRankItem(index + 1, book, true, onBookClick, onBookLongClick)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    rightBooks.forEachIndexed { index, book ->
                        HomeRankItem(index + 6, book, true, onBookClick, onBookLongClick)
                    }
                }
            }
        } else {
            val displayBooks = books.take(HOME_RANK_LIMIT)
            Column(Modifier.fillMaxWidth()) {
                displayBooks.forEachIndexed { index, book ->
                    HomeRankItem(index + 1, book, true, onBookClick, onBookLongClick)
                }
            }
        }
    }
}

/**
 * 四行样式 (对照 FourColumnAdapter): 书籍按 4 本一列切块, 横向翻列,
 * 每列内部走无序号的 [HomeRankItem] (原版 FourColumnVH 内嵌 RankBookAdapter(showRank=false))。
 * 列宽 220dp 对齐原版 FourColumnAdapter.onCreateViewHolder;
 * 滚动用 horizontalScroll + 自定义 [StartSnapFlingBehavior] + [ColumnSnapEffect] 对照原版
 * Horizontal rv + StartSnapHelper(): fling 最多翻 ±1 列 + 90ms/inch 黏滞减速 +
 * 停止时半列规则吸附 (见 StartSnapHelper.findTargetSnapPosition / createScroller / findSnapView)。
 * 鼠标悬停支持滚轮横向平移。
 */
@Composable
private fun HomeFourRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val density = LocalDensity.current
    val itemWidthPx = with(density) { 220.dp.toPx() }
    val columns = books.chunked(4)
    val scrollState = rememberScrollState()
    // fling 限位/黏滞 (对照 StartSnapHelper): 依赖列数, 列数变化时重建
    val flingBehavior = remember(columns.size, itemWidthPx, density) {
        StartSnapFlingBehavior(scrollState, itemWidthPx, columns.size - 1, density)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState, flingBehavior = flingBehavior)
            .horizontalMouseWheel(scrollState)
            .padding(horizontal = 8.dp),
    ) {
        columns.forEach { column ->
            Column(Modifier.width(220.dp)) {
                column.forEach { book ->
                    HomeRankItem(0, book, false, onBookClick, onBookLongClick)
                }
            }
        }
    }
    // 停止吸附: 滚动/惯性滑动停止时吸最近列 (对照原版 StartSnapHelper.findSnapView)
    ColumnSnapEffect(scrollState, itemWidthPx, columns.size - 1, density)
}

/**
 * fling 限位 + 黏滞减速 (对照原版 StartSnapHelper.findTargetSnapPosition + createScroller):
 * - findTargetSnapPosition: 无论 fling 速度多大, 落点最多与当前列相差一列
 *   (velocityX > 0 → firstPos + 1; velocityX < 0 → 已对齐时 firstPos - 1, 否则 firstPos;
 *   0 速度不参与限位, 交给停止吸附);
 * - createScroller: 90ms/inch + DecelerateInterpolator 的黏滞减速 (默认 25ms/inch 偏滑),
 *   这里用相同速率曲线把 fling 动画换成受限平滑滚动。
 */
private class StartSnapFlingBehavior(
    private val scrollState: ScrollState,
    private val itemWidthPx: Float,
    private val maxCol: Int,
    private val density: Density,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (maxCol <= 0) return 0f
        // 第一列被起始边裁掉的宽度; 接近 0 表示当前已对齐到 firstPos (原版 aligned = clipped <= itemWidth/10)
        val current = scrollState.value.toFloat()
        val firstPos = floor(current / itemWidthPx).toInt()
        val clipped = current - firstPos * itemWidthPx
        val aligned = clipped <= itemWidthPx / 10f
        val targetCol = when {
            initialVelocity > 0f -> firstPos + 1
            initialVelocity < 0f -> if (aligned) firstPos - 1 else firstPos
            else -> return 0f
        }.coerceIn(0, maxCol)
        // 目标像素 clamp 到最大滚动位置: 末列可能超出视口右缘 (maxValue = 内容宽 - 视口宽),
        // 超出部分由 scrollBy 内部 clamp, 返回值须与实际滚动量一致
        val target = (targetCol * itemWidthPx).coerceAtMost(scrollState.maxValue.toFloat())
        if (target == current) return 0f
        val dist = target - current
        // 黏滞减速: 每英寸 90ms (原版 calculateSpeedPerPixel = 90 / densityDpi, 1 英寸 = 160dp)
        val durationMs = (abs(dist) / (density.density * 160f) * 90f).toInt().coerceAtLeast(1)
        val startTime = withFrameNanos { it }
        var last = current
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - startTime) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            val eased = STICKY_DECELERATE.transform(t)
            val targetNow = current + dist * eased
            val delta = targetNow - last
            if (delta != 0f) {
                last = targetNow
                // ScrollScope.scrollBy 直接驱动状态 (CMP 1.11 新签名: performFling 带 ScrollScope receiver)
                scrollBy(delta)
            }
            if (t >= 1f) break
        }
        // 限位滚动完全消费了 fling, 无剩余速度 (同 SnapFlingBehavior 的 NoVelocity = 0f)
        return 0f
    }
}

/**
 * 停止吸附 (对照原版 StartSnapHelper.findSnapView + calculateDistanceToFinalSnap):
 * 滚动/惯性滑动停止时, 把最靠近起始边的列吸附对齐到容器起始边。
 *
 * 语义对齐:
 * - round 取最近列 == 原版 findSnapView 的"首列露出不足半列切下一列"
 *   (首列露出 w/2 及以上 → round 吸回当前列, 不足 w/2 → round 切下一列);
 * - 已到末尾 (scrollState.value == maxValue, 末列完全可见) → 吸最后一列, 同原版末尾分支;
 * - 吸附动画用同一黏滞曲线 (90ms/inch DecelerateInterpolator), 同 createScroller。
 *
 * snapshotFlow + distinctUntilChanged 天然防抖: 只在滚动停止 (true→false 下降沿)
 * 时触发一次; 已在对齐位时跳过, 避免动画自触发循环。
 */
@Composable
private fun ColumnSnapEffect(
    scrollState: ScrollState,
    itemWidthPx: Float,
    maxCol: Int,
    density: Density,
) {
    LaunchedEffect(scrollState, itemWidthPx, maxCol, density) {
        snapshotFlow { scrollState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) return@collect
                if (maxCol <= 0 || itemWidthPx <= 0f) return@collect
                val current = scrollState.value.toFloat()
                // 末列完全可见 (滚到底) → 吸最后一列 (滚动被 clamp 到末尾, 同原版末尾分支)
                val atEnd = scrollState.value >= scrollState.maxValue
                val nearest = if (atEnd) {
                    maxCol
                } else {
                    // 滚动位置 = 起始边裁掉宽度, 最近列 = round(位置 / 列宽), round 边界恰在半列处
                    (current / itemWidthPx).roundToInt().coerceIn(0, maxCol)
                }
                val target = (nearest * itemWidthPx).coerceAtMost(scrollState.maxValue.toFloat())
                // 已在吸附位则跳过, 避免空动画自触发循环
                if (abs(current - target) > 0.5f) {
                    // 吸附动画: 同一黏滞曲线 (90ms/inch DecelerateInterpolator)
                    val dist = target - current
                    val durationMs =
                        (abs(dist) / (density.density * 160f) * 90f).toInt().coerceAtLeast(1)
                    val startTime = withFrameNanos { it }
                    var last = current
                    while (true) {
                        val now = withFrameNanos { it }
                        val t = ((now - startTime) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                        val eased = STICKY_DECELERATE.transform(t)
                        val targetNow = current + dist * eased
                        val delta = targetNow - last
                        if (delta != 0f) {
                            last = targetNow
                            scrollState.scroll(scrollPriority = MutatePriority.Default) {
                                scrollBy(delta)
                            }
                        }
                        if (t >= 1f) break
                    }
                }
            }
    }
}

/** 复刻 android.view.animation.DecelerateInterpolator: f(t) = 1 - (1-t)^2 (同 LrcViewShared.DECELERATE) */
private val STICKY_DECELERATE: Easing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction)
}

/** 单条排行/四行条目 (对照 item_home_rank_book.xml: 序号 28dp + 70dp 封面 + 书名/作者) */
@Composable
private fun HomeRankItem(
    rank: Int,
    book: SearchBook,
    showRank: Boolean,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRank) {
            // 对照 RankBookVH: 前 3 名红/橙/黄, 其余摘要色
            Text(
                text = "$rank",
                color = when (rank) {
                    1 -> Color(0xFFE53935)
                    2 -> Color(0xFFF57C00)
                    3 -> Color(0xFFFBC02D)
                    else -> colors.secondaryText
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(28.dp),
            )
        }
        // 封面固定 70dp 高 (对照 XML iv_cover height=70dp), 恒 NOVEL 比例
        Box(Modifier.height(70.dp).padding(start = if (showRank) 8.dp else 0.dp)) {
            LocalBookCoverSlot.current(book.toCoverBook(), Modifier.fillMaxHeight(), false, 0)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.getRealAuthor(),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 无限流头部: 标题行 + 参数 chip 行 (对照 HomeSectionAdapter: 无限流同样显示参数行) */
@Composable
private fun HomeInfiniteHeader(
    section: HomeSection,
    options: List<ExploreOption>,
    optionsVersion: Int,
    onOptionSelected: () -> Unit,
    onMoreClick: () -> Unit,
) {
    // 稳定化回调: 同 HomeSectionBlock, 避免数据未变时全量重组
    val currentOnOptionSelected = rememberUpdatedState(onOptionSelected)
    val stableOnOptionSelected: () -> Unit = remember { { currentOnOptionSelected.value() } }
    val currentOnMoreClick = rememberUpdatedState(onMoreClick)
    val stableOnMoreClick: () -> Unit = remember { { currentOnMoreClick.value() } }
    // 对照 SectionHolder.root: 无限流头部同样有 top 8 / bottom 4 留白
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        HomeSectionTitleRow(section.title, stableOnMoreClick)
        ExploreOptionsRow(options, optionsVersion, stableOnOptionSelected)
    }
}

/** 无限流网格单元 (对照 InfiniteGridCard): 封面 + 书名, 占满单格宽度 */
@Composable
private fun HomeInfiniteGridCard(
    book: SearchBook,
    coverVideo: Boolean,
    onBookClick: () -> Unit,
    onBookLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // 稳定化回调: 同 HomeSectionBlock, 数据未变时卡片整体跳过重组
    val currentOnBookClick = rememberUpdatedState(onBookClick)
    val stableOnBookClick: () -> Unit = remember { { currentOnBookClick.value() } }
    val currentOnBookLongClick = rememberUpdatedState(onBookLongClick)
    val stableOnBookLongClick: () -> Unit = remember { { currentOnBookLongClick.value() } }
    Box(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = stableOnBookClick,
            onLongClick = stableOnBookLongClick,
        )
    ) {
        if (coverVideo) {
            // 对照原版 coverVideo 无限流 → VideoExploreShowAdapter: 视频卡占满格宽
            ShelfVideoItem(
                book = book.toCoverBook(),
                coverReloadTick = 0,
                onClick = stableOnBookClick,
                onLongClick = stableOnBookLongClick,
                modifier = Modifier.fillMaxWidth(),
                coverSlot = { b, m, isVideoCover, tick ->
                    LocalBookCoverSlot.current(b, m, isVideoCover, tick)
                },
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                // 对照 item_bookshelf_grid.xml: 封面四边 12dp margin, 高按 NOVEL 3:4 由宽度
                // 反推 (原版 iv_cover wrap_content + coverRatio=NOVEL), 不读书架封面高度配置
                LocalBookCoverSlot.current(
                    book.toCoverBook(),
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    false,
                    0,
                )
                Text(
                    text = book.name,
                    color = colors.primaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 书架 tab: ViewModel 来自 [MainScreenModel] (ScreenModelStore 生命周期)。
 *
 * onBookClick 对照 app 端 BaseBookshelfState.open (→ startActivityForBook 按书籍类型分流);
 * onBookLongClick 对照 BaseBookshelfState.openBookInfo (→ BookInfoActivity)。
 * onGroupLongClick 对照 app 端 GroupSelectDialog 长按分组 → GroupEditDialog (重命名/排序/删除);
 * 溢出菜单 group_manage → GroupManageDialog (增/改名/删分组)。
 */
@Composable
private fun BookshelfTabContent(
    viewModel: BookshelfViewModel,
    navigator: AppNavigator,
    scrollState: ShelfScrollState,
    gotoTopTick: Int,
    active: Boolean,
    // 主界面是栈顶时分组返回拦截才生效 (否则压栈页面的返回键会被不可见书架页吞掉)
    isRootTop: Boolean,
) {
    var showAppLog by remember { mutableStateOf(false) }
    // 分组长按或管理列表编辑 → GroupEditDialog。
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    // 溢出菜单 "group_manage" → GroupManageDialog (分组列表管理)
    var showGroupManage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val groups by viewModel.bookGroups.collectAsState()
    var manageableGroups by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    // 分组管理对话框数据流, 随 tab 激活启停 (不可见时零订阅)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        AppDbProviders.get().bookGroupDao.flowAll()
            .distinctUntilChanged()
            .conflate()
            .collect { manageableGroups = it }
    }
    // 对照 BookshelfManageRoute: GroupViewModelShared 持久化分组增删改
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    // 溢出菜单 "add_url" / "import_bookshelf" → 输入框对话框 (对照 BaseBookshelfFragment
    // showAddBookByUrlAlert / importBookshelfAlert)
    var showAddByUrl by remember { mutableStateOf(false) }
    var showImportShelf by remember { mutableStateOf(false) }
    val addVm = remember(scope) { BookshelfAddViewModelShared(scope) }
    val currentGroupId by viewModel.currentGroupId.collectAsState()

    val callbacks = remember(navigator, currentGroupId) {
        BookshelfActionsCallbacks(
            onRefresh = { viewModel.upToc() },
            onAddLocalBook = { navigator.push(AppRoute.ImportBook()) },
            onAddRemoteBook = { navigator.push(AppRoute.RemoteBook) },
            onShowAddBookByUrlAlert = { showAddByUrl = true },
            // 对照原版 BaseBookshelfFragment: startActivity<BookshelfManageActivity> {
            //   putExtra("groupId", groupId) } — 管理页只显示书架当前选中分组的书
            onOpenBookshelfManage = { navigator.push(AppRoute.BookshelfManage(currentGroupId)) },
            onShowGroupManage = { showGroupManage = true },
            onImportBookshelf = { showImportShelf = true },
            onShowAppLog = { showAppLog = true },
            onRefreshShelf = { viewModel.refresh() },
        )
    }
    BookshelfScreen(
        viewModel = viewModel,
        // 书架条目是 bookDao.observeAll() flow 实体: 进入路由前拷贝隔离
        // (toRouteRef 不再内部 copy, DB-flow 边界显式 copy 防别名串扰)
        onBookClick = { book -> navigator.push(book.copy().toReadRoute()) },
        onBookLongClick = { book -> navigator.push(AppRoute.BookInfo(book.copy().toRouteRef())) },
        onSearchClick = { navigator.push(AppRoute.Search()) },
        onGroupLongClick = { group -> editingGroup = group },
        bookshelfActionsCallbacks = callbacks,
        scrollState = scrollState,
        gotoTopTick = gotoTopTick,
        isRootTop = isRootTop,
    )
    // 添加网址 / 导入书架进度 (对照 BaseBookshelfFragment.observeLiveBus + ensureWaitDialog:
    // count<0 关闭, 否则 "添加中... (n)"; 取消时 cancel addBookJob)
    val addBookProgress by addVm.addBookProgress.collectAsState(initial = -1)
    if (addBookProgress >= 0) {
        WaitDialog(
            visible = true,
            message = "添加中... ($addBookProgress)",
            onDismissRequest = { addVm.cancelAddBook() },
        )
    }
    // 应用日志对话框 (对照 SearchRoute 同名状态)
    if (showAppLog) {
        AppLogDialog(onDismiss = { showAppLog = false })
    }
    // "添加网址" (对照 BaseBookshelfFragment.showAddBookByUrlAlert: 单行输入 hint=url)
    if (showAddByUrl) {
        TextInputDialog(
            title = stringResource(Res.string.add_book_url),
            hint = "url",
            onConfirm = {
                showAddByUrl = false
                if (it.isNotBlank()) addVm.addBookByUrl(it)
            },
            onDismiss = { showAddByUrl = false },
        )
    }
    // "导入书架" (对照 BaseBookshelfFragment.importBookshelfAlert: 输入 url/json;
    // neutralButton 选文件走平台文件选择器 txt/json, 读文本后走同一 importBookshelf 入口;
    // 取消选择不动作, 对话框保持打开, 与原版 HandleFileContract 取消行为一致)
    if (showImportShelf) {
        TextInputDialog(
            title = stringResource(Res.string.import_bookshelf),
            hint = "url/json",
            neutralButton = AlertButton(
                text = stringResource(Res.string.select_file),
                dismissOnClick = false,
                onClick = {
                    scope.launch {
                        val services = PlatformServiceProviders.getOrNull()
                        val path = services?.let {
                            withContext(IoDispatcher) {
                                it.files.pickFile(FileFilter(extensions = listOf("txt", "json")))
                            }
                        }
                        if (path != null) {
                            val text = withContext(IoDispatcher) { BackupFileOps.readText(path) }
                            showImportShelf = false
                            addVm.importBookshelf(text, currentGroupId)
                        }
                    }
                },
            ),
            onConfirm = {
                showImportShelf = false
                if (it.isNotBlank()) addVm.importBookshelf(it, currentGroupId)
            },
            onDismiss = { showImportShelf = false },
        )
    }
    // 分组管理对话框与原版一致，编辑和新增使用完整 GroupEditDialog。
    if (showGroupManage) {
        GroupManageDialog(
            groups = manageableGroups,
            onAddGroup = { addingGroup = true },
            onEditGroup = { editingGroup = it },
            onUpdateGroup = { groupVm.upGroup(it) },
            onPersistOrder = { ordered -> groupVm.upGroup(*ordered.toTypedArray()) },
            onDismiss = { showGroupManage = false },
            canAddGroup = { AppDbProviders.get().bookGroupDao.canAddGroup() },
        )
    }
    if (addingGroup || editingGroup != null) {
        GroupEditDialog(
            group = editingGroup,
            onConfirm = { updated ->
                if (addingGroup) {
                    groupVm.addGroup(
                        updated.groupName,
                        updated.bookSort,
                        updated.enableRefresh,
                        updated.cover,
                    ) { addingGroup = false }
                } else {
                    groupVm.upGroup(updated) { editingGroup = null }
                }
            },
            onDismiss = {
                addingGroup = false
                editingGroup = null
            },
            onDelete = { del ->
                groupVm.delGroup(del) { editingGroup = null }
            },
        )
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
    entry: RouteEntry,
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
                navigator.push(AppRoute.BookSourceEdit(sourceUrl), RouteResults.BOOK_SOURCE_EDIT)
            }

            override fun onToTop(source: BookSourcePart) {
                screenModel.dispatch(ExploreUiEvent.ToTop(source))
            }

            override fun onLogin(source: BookSourcePart) {
                // 统一登录入口 (对照原 ExploreAdapter: getBookSource()?.showLoginDialog()):
                // 源对象由 showSourceLogin 内部按 url 查库, URL 登录直开全屏 WebView
                showSourceLogin(source.bookSourceUrl)
            }

            override fun onSearchBook(source: BookSourcePart) {
                navigator.push(AppRoute.Search(searchScope = SearchScope(source).toString()))
            }

            override fun onRefreshSource(source: BookSourcePart) {
                screenModel.dispatch(ExploreUiEvent.RefreshSource(source))
            }

            override fun onDeleteSource(source: BookSourcePart) {
                pendingDeleteSource = source
            }
        }
    }

    // 书源编辑返回: 触发发现页刷新当前展开源分类 (sources 列表由 DB flow 自动刷新)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).filter { it.key == RouteResults.BOOK_SOURCE_EDIT }.collect {
            FlowBus.with(EventBus.REFRESH_EXPLORE).tryEmit("")
        }
    }

    // 删除书源确认 (对照 ExploreTabState.deleteSource 的 alert)
    pendingDeleteSource?.let { src ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteSource = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + src.bookSourceName,
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(ExploreUiEvent.DeleteSource(src))
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)),
        )
    }

    // 移除收藏确认 (对照 ExploreTabState.removePinned 的 alert)
    pendingRemovePin?.let { pin ->
        AppAlertDialog(
            onDismissRequest = { pendingRemovePin = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n${pin.sourceName}-${pin.categoryName}",
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(ExploreUiEvent.RemovePinned(pin))
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)),
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
    val caps = LocalPlatformCapabilities.current
    val webServiceDesc = stringResource(Res.string.web_service_desc)
    // 订阅平台 Web 服务运行态 (对照 MyConfigRoute LaunchedEffect 收集 webServiceState);
    // 平台未提供 webServiceState 时回退本地 MutableStateFlow, 供 onWebServiceChange 乐观更新
    val fallbackRunning = remember { MutableStateFlow(caps.isWebServiceRunning()) }
    val webServiceRunning by (caps.webServiceState ?: fallbackRunning).collectAsState()
    val webServiceSummary = if (webServiceRunning) {
        caps.getWebServiceUrl().orEmpty()
    } else {
        webServiceDesc
    }
    var showWebServiceMenu by remember { mutableStateOf(false) }
    // 对照 MyFragment.onCompatOptionsItemSelected: menu_help → showHelp("appHelp")
    var showAppHelp by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏 (对照 fragment_my_config.xml 的 TitleBar title=my + main_my.xml 的 help 图标);
        // tab 页无返回键, 故不用 AppTitleBar (它恒渲染返回箭头), 复刻其视觉容器
        MyTabTitleBar(onHelp = { showAppHelp = true })
        MyConfigScreen(
            webServiceChecked = webServiceRunning,
            webServiceSummary = webServiceSummary,
            onThemeModeChange = {
                PlatformCapabilityProviders.getOrNull()?.applyDayNight()
            },
            onWebServiceChange = {
                // 乐观更新回退态; 平台 webServiceState 非 null 时由流回填校正
                fallbackRunning.value = it
                caps.setWebService(it)
            },
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
    }

    // 帮助对话框 (对照 MyFragment.showHelp("appHelp"))
    if (showAppHelp) {
        HelpDialog("appHelp") { showAppHelp = false }
    }

    // web 服务长按菜单 (对照 app 端 selector: 复制地址 / 浏览器打开)
    if (showWebServiceMenu) {
        val colors = AppTheme.colors
        val url = PlatformCapabilityProviders.getOrNull()?.getWebServiceUrl()
        AlertDialog(
            onDismissRequest = { showWebServiceMenu = false },
            modifier = Modifier.appDialogSize(),
            properties = AppDialogSizes.properties(),
            title = { Text(stringResource(Res.string.web_service), color = colors.primaryText) },
            text = {
                Column {
                    TextButton(onClick = {
                        showWebServiceMenu = false
                        url?.let { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(it) }
                    }) { Text(stringResource(Res.string.copy_url), color = colors.primaryText) }
                    TextButton(onClick = {
                        showWebServiceMenu = false
                        url?.let { PlatformCapabilityProviders.getOrNull()?.openExternalUrl(it) }
                    }) {
                        Text(
                            stringResource(Res.string.open_in_browser),
                            color = colors.primaryText
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showWebServiceMenu = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
            shape = AppTheme.DesignTokens.dialogShape,
            backgroundColor = colors.fillet,
        )
    }
}

/**
 * "我的" tab 顶栏 (对照 fragment_my_config.xml 的 TitleBar + main_my.xml 的 help 图标)。
 *
 * 不复用 [io.legado.app.ui.compose.component.AppTitleBar]: 后者恒渲染返回箭头, tab 页无返回;
 * 背景/insets/E-Ink 分割线取值与其一致。
 */
@Composable
private fun MyTabTitleBar(onHelp: () -> Unit) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    Box(Modifier.fillMaxWidth().then(if (eInk) Modifier else Modifier.transitionStatusBarPadding())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.my),
                color = colors.primaryText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onHelp) {
                Icon(
                    painter = painterResource(Res.drawable.ic_help),
                    contentDescription = stringResource(Res.string.help),
                    tint = colors.primaryText,
                )
            }
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.secondaryText.copy(alpha = 0.4f))
                    .align(Alignment.BottomStart),
            )
        }
    }
}
