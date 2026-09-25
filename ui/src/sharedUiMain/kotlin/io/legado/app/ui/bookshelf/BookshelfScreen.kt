package io.legado.app.ui.bookshelf

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.pagerPageFocus
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.OnRouteLifecycle
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.bookshelf
import org.jetbrains.compose.resources.stringResource

/**
 * 书架 Screen (KMP 版, commonMain 共享)。
 *
 * 按 `AppConfig.bookGroupStyle` 分流 (对照 app 端 MainActivity.getFragmentId):
 * 1 走 [BookshelfScreen2] (对照 BookshelfFragment2, 单列表 + 分组下钻), 否则走本函数的
 * 样式1 骨架 (对照 BookshelfFragment1, 分组 tab + HorizontalPager):
 *
 * - **顶栏**: 分组切换 ([AppScrollTabRow]) + 搜索图标 + 溢出菜单槽 ([actions] slot)
 * - **内容区**: [HorizontalPager] 左右滑切换分组 (对照 app 端 style1), 每页复用
 *   [ShelfBooksContent] (按 [tier] 选 LIST/GRID, 享受 contentType /
 *   animateItem / timeTick / 滚顶等性能优化; 列表/网格条目由 shared 端 [ShelfListItem] /
 *   [ShelfGridItem] 渲染); 顶部 tab 点击同步 pager, pager 滑动同步 tab 高亮
 * - **空态**: 由 [ShelfBooksContent] 内部居中提示
 *
 * # 简化项 (对照 app 端 ShelfBooksContent)
 *
 * - 下拉刷新: 启用 (refreshEnabled=true), onRefresh 调 [BookshelfViewModel.upToc]
 * - refreshingUrls 由 [BookshelfViewModel] 订阅 UP_BOOKSHELF 事件维护,
 *   coverReloadTick 跟随 configTick (设置变更时可见条目重组重载封面)
 * - 封面由 [coverSlot] 注入: 默认取 [LocalBookCoverSlot] (兜底 [SharedBookCover]);
 *   各端统一默认实现, 宿主端可用 [CompositionLocalProvider] 覆盖注入定制实现
 *
 * # 路由跳转
 *
 * 全部用回调注入, 不依赖 Activity/Intent:
 * - [onBookClick] / [onBookLongClick]: 书籍点击/长按 (打开阅读 / 详情页)
 * - [onSearchClick]: 顶栏搜索图标点击
 * - [onGroupLongClick]: 分组长按 (编辑分组, 桌面端可省略)
 * - [actions]: 顶栏右侧溢出菜单槽 (添加书/书架管理/分组管理等, 由宿主端注入)
 *
 * @param viewModel 书架 VM (持有 groups/books/currentGroupId state)
 * @param isRootTop 主界面是否栈顶 (分组返回拦截只在主界面可见时生效)
 * @param onBookClick 书籍点击回调
 * @param onBookLongClick 书籍长按回调 (默认空)
 * @param onSearchClick 搜索图标点击回调 (默认空)
 * @param onGroupLongClick 分组长按回调 (默认空)
 * @param modifier 外部 Modifier
 * @param tier 布局档位; null = 按 [AppConfigAccessor.bookshelfLayout] 决定 (0=LIST, 其他=GRID)
 * @param coverSlot 封面渲染 slot, 默认取 [LocalBookCoverSlot] (兜底 [SharedBookCover]);
 *   宿主端可通过 [CompositionLocalProvider] 覆盖 [LocalBookCoverSlot] 注入定制实现,
 *   也可直接由此参数显式传入;
 *   第 4 参为封面重载 tick (configTick), 配置变更时可见条目重载封面
 * @param bookshelfActionsCallbacks 顶栏溢出菜单回调集合 (书架管理/添加本地/远程书籍/分组管理/日志等), 默认空实现; 宿主端注入后菜单项生效
 * @param actions 顶栏右侧溢出菜单槽, 默认 [DefaultBookshelfActions] (搜索图标 + 完整溢出菜单)
 * @param scrollState 外部注入的滚动状态; 默认内部 remember 新建。样式2 / 无分组时的滚动位置载体
 * @param gotoTopTick 滚顶信号 (对照 BookshelfTabController.gotoTop), 宿主端每次 tab 双击 +1;
 *   实际滚哪个状态由本函数按"当前分组页 + [ShelfLayoutSpec.tier]"决定
 */
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onBookClick: (Book, String?) -> Unit,
    onBookLongClick: (Book, String?) -> Unit = { _, _ -> },
    onSearchClick: () -> Unit = {},
    onGroupLongClick: (BookGroup) -> Unit = {},
    modifier: Modifier = Modifier,
    tier: BookshelfTier? = null,
    coverSlot: (@Composable (Book, Modifier, Boolean, Int) -> Unit)? = null,
    bookshelfActionsCallbacks: BookshelfActionsCallbacks = BookshelfActionsCallbacks(),
    actions: @Composable RowScope.() -> Unit = {
        DefaultBookshelfActions(
            onSearchClick,
            bookshelfActionsCallbacks
        )
    },
    scrollState: ShelfScrollState = remember { ShelfScrollState() },
    gotoTopTick: Int = 0,
    // 主界面是否栈顶: 分组返回拦截只在主界面可见时生效, 详见 BookshelfScreen2.isRootTop
    isRootTop: Boolean = true,
    /**
     * 本页在当前 tab 内是否处于选中态 (书架 tab 被选中)。
     *
     * 只承担"tab"这一个维度: "前台 + 主界面栈顶"由本页 Lifecycle 提供 (RESUMED),
     * 两者相乘即"书架可见" —— 与 DB 订阅的启停判据同源, 不另立一套手写判定。
     */
    tabSelected: Boolean = true,
) {
    val colors = AppTheme.colors
    /** 各分组滚动状态登记表 (供 tab 双击滚顶取当前分组实例) */
    val pageScrollStates: MutableMap<Long, ShelfScrollState> = remember { mutableStateMapOf() }
    // 订阅启停 = 本页可见 (Lifecycle RESUMED) 且书架 tab 选中。
    // 对照原版: 书架 Fragment 被详情页盖住 / 退到后台 / 切到其它 tab 时 Lifecycle 降到 STARTED,
    // flowWithLifecycleAndDatabaseChangeFirst(RESUMED) 挂起收集 → 不查库; 回到 RESUMED 才重订并重查。
    // 缓存快照 (booksCache) 不随开关清空, 恢复时先用旧快照出帧再刷。
    OnRouteLifecycle(
        minState = Lifecycle.State.RESUMED,
        enabled = tabSelected,
        onEnter = { viewModel.setBookshelfActive(true) },
        onLeave = { viewModel.setBookshelfActive(false) },
    )
    val appConfig = remember { AppConfigProviders.get() }
    // 配置项每次变更后重读 (对照原版: 分组样式变更走 NOTIFY_MAIN 重建 Fragment,
    // 数量开关变更走 BOOKSHELF_REFRESH 重绑 tab)
    var configTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { FlowBus.with(EventBus.NOTIFY_MAIN).collect { configTick++ } }
            launch { FlowBus.with(EventBus.BOOKSHELF_REFRESH).collect { configTick++ } }
        }
    }
    // 稳定化透传到条目层的回调/封面槽: 上层 (MainRoute 等) 重组会新建 lambda 实例,
    // 直接透传时每次上层重组 → 本屏重组 → 各分组页参数变化 → LazyGrid content 重建 →
    // 全部可见条目全量重组 (Compose 对 item 参数按 == 比较, 新 lambda 必不等)。
    // rememberUpdatedState 桥接: 透传引用恒定, 调用时读最新实现, 行为语义不变。
    val currentCoverSlot = rememberUpdatedState(coverSlot ?: LocalBookCoverSlot.current)
    val stableCoverSlot: @Composable (Book, Modifier, Boolean, Int) -> Unit = remember {
        { book, modifier, isVideoCover, tick ->
            currentCoverSlot.value(book, modifier, isVideoCover, tick)
        }
    }
    val currentOnBookClick = rememberUpdatedState(onBookClick)
    val stableOnBookClick: (Book, String?) -> Unit =
        remember { { book, token -> currentOnBookClick.value(book, token) } }
    val currentOnBookLongClick = rememberUpdatedState(onBookLongClick)
    val stableOnBookLongClick: (Book, String?) -> Unit =
        remember { { book, token -> currentOnBookLongClick.value(book, token) } }
    val currentOnGroupLongClick = rememberUpdatedState(onGroupLongClick)
    val stableOnGroupLongClick: (BookGroup) -> Unit =
        remember { { group -> currentOnGroupLongClick.value(group) } }
    val currentOnRefresh = rememberUpdatedState<() -> Unit>({ viewModel.upToc() })
    val stableOnRefresh: () -> Unit = remember { { currentOnRefresh.value() } }
    // 分组样式分流 (对照 MainActivity.getFragmentId: bookGroupStyle==1 走 BookshelfFragment2)
    if (remember(configTick) { appConfig.bookGroupStyle } == 1) {
        BookshelfScreen2(
            viewModel = viewModel,
            onBookClick = stableOnBookClick,
            onBookLongClick = stableOnBookLongClick,
            onGroupLongClick = stableOnGroupLongClick,
            modifier = modifier,
            tier = tier,
            coverSlot = stableCoverSlot,
            scrollState = scrollState,
            actions = actions,
            gotoTopTick = gotoTopTick,
            configTick = configTick,
            isRootTop = isRootTop,
        )
        return
    }
    val eInk = LocalEInk.current
    // 封面 slot 直接透传 (引用已稳定, 不再包新 lambda, 避免所有可见条目一起重组)
    val bookCoverSlot: @Composable (Book, Modifier, Boolean, Int) -> Unit = stableCoverSlot
    val groupCoverSlot: @Composable (BookGroup, Modifier, Boolean, Int) -> Unit =
        DefaultGroupCoverSlot
    val groups by viewModel.bookGroups.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    // 单一数据源: 页数据/顶栏计数均读 VM 缓存切片 (未访问过的分组无条目, 顶栏显示 "..")
    val booksCache by viewModel.booksCache.collectAsState()
    val groupBookCounts = remember(booksCache) { booksCache.mapValues { it.value.size } }
    val scope = rememberCoroutineScope()

    // 顶栏 tab 是否显示分组数量 (对照 app 端 AppConfig.bookshelfShowGroupCount)
    val showGroupCount = remember(configTick) { appConfig.bookshelfShowGroupCount }

    // 布局 spec 各 pager 页共用, 计算一次。
    val layoutSpec = rememberBookshelfLayoutSpec(tier)
    // 各分组页的滚动状态 (对照 BookshelfFragment1.fragmentMap): 滚顶要作用于当前分组页
    // 登记表由宿主持有 (随主界面 entry 存活), 页面离开组合后滚顶仍能拿到实例

    // HorizontalPager (对照 app 端 BookshelfScreen1, pageCount 动态跟随 groups)
    val pagerState = rememberPagerState(
        initialPage = if (groups.isNotEmpty()) {
            groups.indexOfFirst { it.groupId == currentGroupId }.coerceAtLeast(0)
        } else 0,
        pageCount = { groups.size },
    )
    // pager 滑动结束 (settledPage) → 同步 currentGroupId。
    // 不用 currentPage，避免手势过程中频繁改写全局选择状态。
    // 同时持久化 tab 位置 (对照 BookshelfFragment1.onTabSelected: AppConfig.saveTabPosition = position)
    val prefs = PreferenceProviders.get()
    LaunchedEffect(pagerState, groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            groups.getOrNull(page)?.let { group ->
                viewModel.selectGroup(group.groupId)
                prefs.putInt(PreferKey.saveTabPosition, page)
            }
        }
    }
    // 首次拿到分组后恢复上次 tab (对照 BookshelfFragment1.selectLastTab)。
    // 位置在组合期读一次, 避免被上面的 settledPage 持久化覆盖后读到 0;
    // 只改 currentGroupId, 实际滚动交给下面的同步 effect (单一滚动源, 无竞态)。
    val savedTabPosition = remember { appConfig.saveTabPosition }
    var tabRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(groups.size) {
        if (tabRestored || groups.isEmpty()) return@LaunchedEffect
        tabRestored = true
        groups.getOrNull(savedTabPosition)?.let { viewModel.selectGroup(it.groupId) }
    }
    // 外部 currentGroupId 变化 → pager 同步 (仅初始化/外部切换, 用 scrollToPage 不触发动画避免循环)
    LaunchedEffect(currentGroupId, groups.size) {
        if (groups.isEmpty()) return@LaunchedEffect
        val targetIndex = groups.indexOfFirst { it.groupId == currentGroupId }
        if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
            pagerState.scrollToPage(targetIndex)
        }
    }
    // tab 选中位置直接跟随 pagerState.currentPage (无 selectGroup 一帧延迟)
    val displayGroupId = groups.getOrNull(pagerState.currentPage)?.groupId ?: currentGroupId

    // tab 双击滚顶 (对照 BookshelfFragment1.gotoTop → fragmentMap[groupId]?.gotoTop):
    // 取当前分组页的滚动状态, 档位与 layoutSpec 同源
    LaunchedEffect(gotoTopTick) {
        if (gotoTopTick == 0) return@LaunchedEffect
        val target = groups.getOrNull(pagerState.currentPage)
            ?.let { pageScrollStates[it.groupId] } ?: scrollState
        target.gotoTop(layoutSpec.tier, eInk)
    }

    Column(modifier.fillMaxSize()) {
        BookshelfTopBar(
            groups = groups,
            currentGroupId = displayGroupId,
            showGroupCount = showGroupCount,
            groupBookCounts = groupBookCounts,
            onGroupClick = { groupId ->
                // tab 点击 → pager 滚动 → currentPage 变化 → selectGroup (见 LaunchedEffect)
                val targetIndex = groups.indexOfFirst { it.groupId == groupId }
                if (targetIndex >= 0) {
                    scope.launch {
                        if (eInk) pagerState.scrollToPage(targetIndex)
                        else pagerState.animateScrollToPage(targetIndex)
                    }
                }
            },
            onGroupLongClick = stableOnGroupLongClick,
            actions = actions,
        )
        if (groups.isEmpty()) {
            // 对照 BookshelfFragment1.upGroup: 无可见分组时自愈, 启用"全部"分组
            LaunchedEffect(Unit) {
                withContext(IoDispatcher) {
                    AppDbProviders.get().bookGroupDao.enableGroup(BookGroup.IdAll)
                }
            }
            // 无分组时显示空状态 (ShelfBooksContent 内部居中提示 bookshelf_empty)
            ShelfBooksContent(
                items = emptyList(),
                spec = layoutSpec,
                scroll = scrollState,
                refreshEnabled = false,
                onRefresh = {},
                coverReloadTick = 0,
                refreshingUrls = emptySet(),
                onBookClick = stableOnBookClick,
                onBookLongClick = stableOnBookLongClick,
                showLastUpdateTime = true,
                showKindIntro = true,
                bookCoverSlot = bookCoverSlot,
                groupCoverSlot = groupCoverSlot,
                pairBlockId = "shelf-loading",
            )
        } else {
            // 初始分组 groupId: 该分组的页用外部 scrollState (保留宿主 gotoTop 入口), 其他页独立 state
            val initialGroupId = remember { currentGroupId }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1, // 对齐原版 offscreenPageLimit=1，手势开始前相邻页已完成组合
                // (2026-09-15 实测过对照: 改成 0 对启动那 470ms 首绘无帮（429~468ms vs 448~470ms）,
                //  反而会让滑到相邻分组时才开始组合 → 保留 1)。
                key = { index -> groups.getOrNull(index)?.groupId ?: index.toLong() },
            ) { page ->
                val group = groups.getOrNull(page) ?: return@HorizontalPager
                Box(Modifier.fillMaxSize().pagerPageFocus(pagerState, page)) {
                    GroupBooksPage(
                        group = group,
                        spec = layoutSpec,
                        externalScrollState = scrollState,
                        initialGroupId = initialGroupId,
                        scrollStates = pageScrollStates,
                        viewModel = viewModel,
                        configTick = configTick,
                        books = booksCache[group.groupId],
                        onBookClick = stableOnBookClick,
                        onBookLongClick = stableOnBookLongClick,
                        bookCoverSlot = bookCoverSlot,
                        groupCoverSlot = groupCoverSlot,
                        onRefresh = stableOnRefresh,
                    )
                }
            }
        }
    }
}

/**
 * 布局 spec 决策 (两种分组样式共用)。
 *
 * 未显式传 [tier] 时走 [rememberShelfLayoutSpec] (对照 BooksFragment/BookshelfFragment2 的
 * getCols/createAdapter: bookshelfFixedWidthMode 按屏宽换算列数, 否则读 bookshelfLayout 位段)。
 */
@Composable
internal fun rememberBookshelfLayoutSpec(tier: BookshelfTier?): ShelfLayoutSpec {
    val appConfig = remember { AppConfigProviders.get() }
    // tier 决策: 显式传入优先, 否则按 bookshelfLayout (0=LIST, 其他=GRID)
    val resolvedTier = remember(tier, appConfig.bookshelfLayout) {
        tier ?: if (appConfig.bookshelfLayout == 0) BookshelfTier.LIST else BookshelfTier.GRID
    }
    // 网格列宽 (对照 app 端 bookshelfGridWidth, Adaptive 模式)
    val gridWidthDp = remember(appConfig.bookshelfGridWidth) {
        appConfig.bookshelfGridWidth.coerceIn(60, 240)
    }
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screenWidthDp = remember(containerSize.width, density) {
        with(density) { containerSize.width.toDp().value.toInt() }.coerceAtLeast(1)
    }
    val autoSpec = rememberShelfLayoutSpec(layoutSpecTick = 0, screenWidthDp = screenWidthDp)
    return if (tier == null) autoSpec else remember(resolvedTier, gridWidthDp) {
        when (resolvedTier) {
            BookshelfTier.LIST -> ShelfLayoutSpec(
                tier = ShelfTier.LIST,
                isVideoList = false,
                cols = 1,
                fixedWidth = false,
                gridWidthDp = gridWidthDp,
            )

            BookshelfTier.GRID -> ShelfLayoutSpec(
                tier = ShelfTier.GRID,
                isVideoList = false,
                cols = 1,
                fixedWidth = true,
                gridWidthDp = gridWidthDp,
            )
        }
    }
}

/**
 * 滚顶 (对照 BooksFragment/BookshelfFragment2.gotoTop: E-Ink 直接跳, 否则平滑滚动)。
 * 滚列表还是网格由 [tier] 决定, 与 [ShelfBooksContent] 的取用保持同源。
 */
internal suspend fun ShelfScrollState.gotoTop(tier: ShelfTier, eInk: Boolean) {
    if (tier == ShelfTier.LIST) {
        if (eInk) list.scrollToItem(0) else list.animateScrollToItem(0)
    } else {
        if (eInk) grid.scrollToItem(0) else grid.animateScrollToItem(0)
    }
}

/**
 * 单个分组页 (对照 app 端 BookshelfScreen1 的 GroupBooksPage)。
 *
 * 数据经 [BookshelfViewModel.booksCache] 单一数据源切片 (pager 组合中的分组页
 * 各自持有 Room 流, 由 VM 按页组合/离开维护, 见 [BookshelfViewModel.onGroupPageComposed]),
 * 每页独立 [ShelfScrollState] 保留滚动位置 (初始分组复用外部 scrollState), 并登记到
 * [scrollStates] 供宿主滚顶按当前页取用。
 */
@Composable
private fun GroupBooksPage(
    group: BookGroup,
    spec: ShelfLayoutSpec,
    externalScrollState: ShelfScrollState,
    initialGroupId: Long,
    scrollStates: MutableMap<Long, ShelfScrollState>,
    viewModel: BookshelfViewModel,
    configTick: Int,
    books: List<Book>?,
    onBookClick: (Book, String?) -> Unit,
    onBookLongClick: (Book, String?) -> Unit,
    bookCoverSlot: @Composable (Book, Modifier, Boolean, Int) -> Unit,
    groupCoverSlot: @Composable (BookGroup, Modifier, Boolean, Int) -> Unit,
    onRefresh: () -> Unit,
) {
    // 每分组一份 scrollState; 初始分组用外部 scrollState (与宿主保存的位置连续)
    // rememberSaveable: 页销毁重建后恢复滚动位置 (按 groupId 隔离)
    val pageScrollState = rememberSaveable(group.groupId, saver = ShelfScrollState.Saver) {
        if (group.groupId == initialGroupId) externalScrollState else ShelfScrollState()
    }
    // 页组合即订阅该分组数据流 (对齐原版 fragment 各自订阅, 相邻页数据预加载),
    // 离开组合取消 → 活跃流 = 当前 + 相邻共 ≤3 个
    DisposableEffect(group.groupId) {
        viewModel.onGroupPageComposed(group.groupId)
        onDispose { viewModel.onGroupPageDisposed(group.groupId) }
    }
    DisposableEffect(group.groupId, pageScrollState) {
        scrollStates[group.groupId] = pageScrollState
        onDispose { scrollStates.remove(group.groupId) }
    }
    val refreshingUrls by viewModel.refreshingUrls.collectAsState()
    val engineUpTocUrls by viewModel.engineUpTocUrls.collectAsState()
    // 复用 ShelfBooksContent: 享受 contentType / animateItem / timeTick / 滚顶等性能优化
    ShelfBooksContent(
        items = books.orEmpty(),
        spec = spec,
        scroll = pageScrollState,
        // 对照 BooksFragment: refreshLayout.isEnabled = group.enableRefresh
        refreshEnabled = group.enableRefresh,
        onRefresh = onRefresh,
        coverReloadTick = configTick,
        refreshingUrls = refreshingUrls,
        engineUpdatingUrls = engineUpTocUrls,
        onBookClick = onBookClick,
        onBookLongClick = onBookLongClick,
        showLastUpdateTime = true,
        showKindIntro = true,
        bookCoverSlot = bookCoverSlot,
        groupCoverSlot = groupCoverSlot,
        // 区块 = 分组页: 相邻分组页可能同时展示同一本书, 必须分开配对
        pairBlockId = "shelf-group-${group.groupId}",
    )
}

/**
 * 顶栏: 分组 tab (左) + 操作区 (右)。
 *
 * 对照 app 端 BookshelfTopBar + BookshelfActions: 分组用 [AppScrollTabRow]
 * 横向滚动 tab, 选中色 accent, 指示条 2dp 贴底; 选中同 tab 再点不触发滚顶
 * (app 端 onTabReselect 调 gotoTop, 此处简化)。
 *
 * @param showGroupCount 是否在 tab 标题后显示 "(n)" 数量
 * @param groupBookCounts 已加载分组各自的书籍数；未加载分组显示 ".."
 */
@Composable
internal fun BookshelfTopBar(
    groups: List<BookGroup>,
    currentGroupId: Long,
    showGroupCount: Boolean,
    groupBookCounts: Map<Long, Int>,
    onGroupClick: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    BookshelfTopBarContainer(actions) {
        if (groups.isNotEmpty()) {
            val selectedIndex = groups.indexOfFirst { it.groupId == currentGroupId }
                .coerceAtLeast(0)
            AppScrollTabRow(
                tabCount = groups.size,
                selectedIndex = selectedIndex,
                indicatorColor = colors.accent,
                modifier = Modifier.weight(1f).padding(start = DesignTokens.spacingLg),
            ) { index ->
                val group = groups[index]
                val title = if (showGroupCount) {
                    "${group.groupName}(${groupBookCounts[group.groupId] ?: ".."})"
                } else {
                    group.groupName
                }
                GroupTab(
                    title = title,
                    selected = index == selectedIndex,
                    eInk = eInk,
                    onClick = { onGroupClick(group.groupId) },
                    onLongClick = { onGroupLongClick(group) },
                )
            }
        } else {
            // 无分组时占位 (CommonMain 无 R.string.bookshelf, 用 key 兜底返回 key 本身)
            BookshelfTitleText(stringResource(Res.string.bookshelf))
        }
    }
}

/** 顶栏容器 (两种分组样式共用): 背景 + 48dp 高 Row (原版 TabLayout 默认高), 左侧 [content] 右侧 [actions] */
@Composable
internal fun BookshelfTopBarContainer(
    actions: @Composable RowScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalEInk.current
    Box(
        // 不涂背景, 颜色由页面容器/壁纸层统一管; 内容推到状态栏之下, eInk 不避让
        Modifier
            .fillMaxWidth()
            .then(if (eInk) Modifier else Modifier.platformStatusBarPadding())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DesignTokens.viewHeightXl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            actions()
        }
    }
}

/** 顶栏标题 (对照 app 端 TitleBar 的 toolbar title): 样式2 显示分组名/书架, 样式1 无分组时占位 */
@Composable
internal fun RowScope.BookshelfTitleText(title: String) {
    Text(
        text = title,
        color = AppTheme.colors.primaryText,
        fontSize = 20.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = DesignTokens.spacingLg),
    )
}

/** 单个分组 tab 项 (对照 app 端 style1.GroupTab) */
@Composable
internal fun GroupTab(
    title: String,
    selected: Boolean,
    eInk: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .heightIn(min = DesignTokens.viewHeightXl)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = DesignTokens.spacingDefault),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = if (selected) colors.accent else colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

// ---- 默认实现 (供宿主端未注入 slot 时使用) ----

/**
 * 默认顶栏右侧动作: 搜索图标 + 完整溢出菜单。
 *
 * 复用 shared [BookshelfActions] (对照 app 端 main_bookshelf.xml 项序):
 * 搜索图标 + 强制刷新/添加本地/远程书籍/添加网址/书架管理/分组管理/导入书架/日志。
 * 宿主端通过 [BookshelfScreen] 的 `bookshelfActionsCallbacks` 参数注入回调,
 * 或用 `actions` slot 完全覆盖。
 */
@Composable
internal fun DefaultBookshelfActions(
    onSearchClick: () -> Unit,
    callbacks: BookshelfActionsCallbacks = BookshelfActionsCallbacks(),
) {
    BookshelfActions(callbacks.copy(onOpenSearch = onSearchClick))
}

/**
 * 书籍封面 (加载与渲染管线见 [SharedCoverContent])。
 *
 * 书籍特有的输入: 默认封面选图 seed = 书名 (无书名回落封面路径), 与详情/搜索/发现共用
 * [DecodedBitmapCache] 的已解码封面小表, 默认封面上叠竖排书名/作者, 挂共享元素转场端点。
 *
 * 尺寸按 [isVideoCover] 选 16:9 / 3:4, 由调用方给出的那一个维度反算另一个维度 (对齐原 View 版
 * `CoverImageView.onMeasure` 按 coverRatio 自适应)。
 *
 * @param book 当前书籍
 * @param modifier 外部尺寸约束 (调用方给宽/高/内边距等); 本组件只追加比例与圆角
 * @param isVideoCover 是否视频封面 (true: 16:9, false: 3:4; 对照 CoverRatio.VIDEO/NOVEL)
 * @param reloadTick 封面重载信号 (configTick): 变化时重启加载, 不变不额外触发
 *
 * ohos 未注册 BookImageLoader, 恒走内置图 + overlay (与替换前占位语义一致)。
 */
@Composable
fun SharedBookCover(
    book: Book,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
    reloadTick: Int = 0,
) {
    val cover = book.getDisplayCover()
    SharedCoverContent(
        source = CoverSource(
            coverUrl = cover,
            origin = book.origin,
            // 同 URL 不同来源是不同资源, origin 必须进 key (对照原版 ImageLoader 的 sourceOrigin)
            cacheKey = book.origin,
            defaultCoverSeed = book.name.takeIf { it.isNotBlank() } ?: cover,
            shareDecodedCover = true,
            // 非书架书 (搜索/发现/主页结果) 的封面只落临时缓存区, 不占书架持久区
            persistentCover = !book.isNotShelf,
            sharedTransition = true,
            contentDescription = book.name,
            title = book.name,
            author = book.author,
        ),
        modifier = modifier,
        isVideoCover = isVideoCover,
        reloadTick = reloadTick,
        nameAuthorOverlay = true,
    )
}


/**
 * 默认分组封面 slot: 与书架同源 (转 [LocalGroupCoverSlot] → [SharedGroupCover]),
 * 分组封面渲染与书架 style2 条目/编辑分组对话框一致, 不再空白占位。
 */
private val DefaultGroupCoverSlot: @Composable (BookGroup, Modifier, Boolean, Int) -> Unit =
    { group, m, isVideoCover, tick -> LocalGroupCoverSlot.current(group, m, isVideoCover, tick) }

/**
 * 封面渲染 slot 的 CompositionLocal: 默认兜底 [SharedBookCover]。
 *
 * 宿主端可用 [CompositionLocalProvider] 覆盖注入, 替换 shared 路由
 * ([BookshelfScreen] / `BookInfoRoute`) 的封面实现; 各端默认统一走 [SharedBookCover]。
 *
 * 签名 `(Book, Modifier, Boolean) -> Unit` 对齐 [ShelfBooksContent] 的 `bookCoverSlot`
 * (book / modifier / isVideoCover), modifier 与 isVideoCover 不被丢弃。
 */
val LocalBookCoverSlot =
    staticCompositionLocalOf<@Composable (Book, Modifier, Boolean, Int) -> Unit> {
        @Composable { book, modifier, isVideoCover, tick ->
            SharedBookCover(book, modifier, isVideoCover, tick)
        }
}

/**
 * SearchBook → Book 适配 [LocalBookCoverSlot]: 非书架书补 notShelf 标记, 宿主端据此把封面
 * 落临时缓存区而非书架持久区 (对照原版 `BaseExploreShowAdapter.registerListener` 在 bind 时
 * `addType(notShelf)` + `ImageLoader.load(.., inBookshelf)` 的分流)。
 */
fun SearchBook.toCoverBook(inBookshelf: Boolean = false): Book = toBook().apply {
    if (!inBookshelf) addType(BookType.notShelf)
}
