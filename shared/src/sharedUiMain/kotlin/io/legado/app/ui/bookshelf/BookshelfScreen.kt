package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.model.BookCoverShared
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf
import legado.shared.generated.resources.image_cover_default
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

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
 *   coverReloadTick 暂无 state 传 0 (条目转圈生效, 封面重载动画省略)
 * - 封面由 [coverSlot] 注入: 默认取 [LocalBookCoverSlot] (兜底 [SharedBookCover]);
 *   宿主端用 [CompositionLocalProvider] 覆盖注入平台实现 (app: ShelfCover / desktop: DesktopBookCover)
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
 * @param onBookClick 书籍点击回调
 * @param onBookLongClick 书籍长按回调 (默认空)
 * @param onSearchClick 搜索图标点击回调 (默认空)
 * @param onGroupLongClick 分组长按回调 (默认空)
 * @param modifier 外部 Modifier
 * @param tier 布局档位; null = 按 [AppConfigAccessor.bookshelfLayout] 决定 (0=LIST, 其他=GRID)
 * @param coverSlot 封面渲染 slot, 默认取 [LocalBookCoverSlot] (兜底 [SharedBookCover]);
 *   宿主端可通过 [CompositionLocalProvider] 覆盖 [LocalBookCoverSlot] 注入平台实现
 *   (app: ShelfCover / desktop: DesktopBookCover), 也可直接由此参数显式传入
 * @param bookshelfActionsCallbacks 顶栏溢出菜单回调集合 (书架管理/添加本地/远程书籍/分组管理/日志等), 默认空实现; 宿主端注入后菜单项生效
 * @param actions 顶栏右侧溢出菜单槽, 默认 [DefaultBookshelfActions] (搜索图标 + 完整溢出菜单)
 * @param scrollState 外部注入的滚动状态; 默认内部 remember 新建。样式2 / 无分组时的滚动位置载体
 * @param gotoTopTick 滚顶信号 (对照 BookshelfTabController.gotoTop), 宿主端每次 tab 双击 +1;
 *   实际滚哪个状态由本函数按"当前分组页 + [ShelfLayoutSpec.tier]"决定
 */
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onGroupLongClick: (BookGroup) -> Unit = {},
    modifier: Modifier = Modifier,
    tier: BookshelfTier? = null,
    coverSlot: (@Composable (Book, Modifier, Boolean) -> Unit)? = null,
    bookshelfActionsCallbacks: BookshelfActionsCallbacks = BookshelfActionsCallbacks(),
    actions: @Composable RowScope.() -> Unit = {
        DefaultBookshelfActions(
            onSearchClick,
            bookshelfActionsCallbacks
        )
    },
    scrollState: ShelfScrollState = remember { ShelfScrollState() },
    gotoTopTick: Int = 0,
) {
    val colors = AppTheme.colors
    val appConfig = remember { AppConfigProviders.get() }
    // 配置项每次变更后重读 (对照原版: 分组样式变更走 NOTIFY_MAIN 重建 Fragment,
    // 数量开关变更走 BOOKSHELF_REFRESH 重绑 tab)
    var configTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.NOTIFY_MAIN).collect { configTick++ }
    }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).collect { configTick++ }
    }
    // 分组样式分流 (对照 MainActivity.getFragmentId: bookGroupStyle==1 走 BookshelfFragment2)
    if (remember(configTick) { appConfig.bookGroupStyle } == 1) {
        BookshelfScreen2(
            viewModel = viewModel,
            onBookClick = onBookClick,
            onBookLongClick = onBookLongClick,
            onGroupLongClick = onGroupLongClick,
            modifier = modifier,
            tier = tier,
            coverSlot = coverSlot,
            scrollState = scrollState,
            actions = actions,
            gotoTopTick = gotoTopTick,
            configTick = configTick,
        )
        return
    }
    val eInk = LocalEInk.current
    // 封面 slot: 显式传入优先, 否则取 CompositionLocal (宿主端可覆盖注入平台实现, 兜底 SharedBookCover)
    val resolvedCoverSlot: @Composable (Book, Modifier, Boolean) -> Unit =
        coverSlot ?: LocalBookCoverSlot.current
    val groups by viewModel.bookGroups.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val groupBookCounts = remember { mutableStateMapOf<Long, Int>() }
    val scope = rememberCoroutineScope()

    // 顶栏 tab 是否显示分组数量 (对照 app 端 AppConfig.bookshelfShowGroupCount)
    val showGroupCount = remember(configTick) { appConfig.bookshelfShowGroupCount }

    // 布局 spec 各 pager 页共用, 计算一次。
    val layoutSpec = rememberBookshelfLayoutSpec(tier)
    // 各分组页的滚动状态 (对照 BookshelfFragment1.fragmentMap): 滚顶要作用于当前分组页
    val pageScrollStates = remember { mutableStateMapOf<Long, ShelfScrollState>() }
    // 封面 slot 包装 (各页共用, 避免每页重复创建 lambda)
    val bookCoverSlot: @Composable (Book, Modifier, Boolean) -> Unit =
        { book, m, isVideoCover -> resolvedCoverSlot(book, m, isVideoCover) }
    val groupCoverSlot: @Composable (BookGroup, Modifier, Boolean) -> Unit =
        { _, m, _ -> Box(m) }

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
    val prefs = LocalPreferenceStoreProvider.current
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

    Column(modifier.fillMaxSize().background(colors.background)) {
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
            onGroupLongClick = onGroupLongClick,
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
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
                showLastUpdateTime = true,
                showKindIntro = true,
                bookCoverSlot = bookCoverSlot,
                groupCoverSlot = groupCoverSlot,
            )
        } else {
            // 初始分组 groupId: 该分组的页用外部 scrollState (保留宿主 gotoTop 入口), 其他页独立 state
            val initialGroupId = remember { currentGroupId }
            // Pager 与鼠标拖动共用同一 flingBehavior, 保证吸附手感一致
            val pagerFling = PagerDefaults.flingBehavior(state = pagerState)
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1, // 对齐原版 offscreenPageLimit=1，手势开始前相邻页已完成组合
                key = { index -> groups.getOrNull(index)?.groupId ?: index.toLong() },
                flingBehavior = pagerFling,
                modifier = Modifier.mouseDragPager(pagerState, scope, pagerFling),
            ) { page ->
                val group = groups.getOrNull(page) ?: return@HorizontalPager
                GroupBooksPage(
                    group = group,
                    spec = layoutSpec,
                    externalScrollState = scrollState,
                    initialGroupId = initialGroupId,
                    scrollStates = pageScrollStates,
                    viewModel = viewModel,
                    onBooksLoaded = { groupId, count -> groupBookCounts[groupId] = count },
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    bookCoverSlot = bookCoverSlot,
                    groupCoverSlot = groupCoverSlot,
                    onRefresh = { viewModel.upToc() },
                )
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
 * 补齐鼠标左键拖动横滑: Compose 的 scrollable 用 CanDragCalculation 把 PointerType.Mouse
 * 排除在拖动之外 (只留滚轮), Pager 自带手势对鼠标不响应。非鼠标事件直接放行, 不影响触摸。
 * 松手后走 Pager 自身的 flingBehavior, 吸附手感与触摸横滑一致。
 */
private fun Modifier.mouseDragPager(
    pagerState: PagerState,
    scope: CoroutineScope,
    flingBehavior: TargetedFlingBehavior,
): Modifier = pointerInput(pagerState, flingBehavior) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        val velocityTracker = VelocityTracker()
        velocityTracker.addPointerInputChange(down)
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            velocityTracker.addPointerInputChange(change)
            if (!dragging &&
                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
            ) {
                dragging = true
            }
            if (dragging) {
                // 受限挂起作用域内只能调非挂起 API, 用 dispatchRawDelta 逐帧推进。
                // Desktop 的 synthetic move 事件 previousPosition 可能是 Offset.Unspecified(NaN),
                // 交给 PagerState.performScroll 会在 roundToLong 抛 "Cannot round NaN value"。
                val delta = change.previousPosition.x - change.position.x
                if (delta.isFinite()) pagerState.dispatchRawDelta(delta)
                change.consume()
            }
        }
        if (dragging) {
            // 手势方向与滚动偏移量反向, 速度取负后交给 Pager 原生 fling 吸附。
            // 速度非有限 (synthetic move 事件的 NaN) 时按 0 吸附, 避免停在半页
            val raw = -velocityTracker.calculateVelocity().x
            val velocity = if (raw.isFinite()) raw else 0f
            scope.launch {
                pagerState.scroll { with(flingBehavior) { performFling(velocity) } }
            }
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
 * 每页独立订阅 [BookshelfViewModel.booksByGroup] 加载该分组书籍,
 * 每页独立 [ShelfScrollState] 保留滚动位置 (初始分组复用外部 scrollState),
 * 并登记到 [scrollStates] 供宿主滚顶按当前页取用。
 */
@Composable
private fun GroupBooksPage(
    group: BookGroup,
    spec: ShelfLayoutSpec,
    externalScrollState: ShelfScrollState,
    initialGroupId: Long,
    scrollStates: MutableMap<Long, ShelfScrollState>,
    viewModel: BookshelfViewModel,
    onBooksLoaded: (Long, Int) -> Unit,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    bookCoverSlot: @Composable (Book, Modifier, Boolean) -> Unit,
    groupCoverSlot: @Composable (BookGroup, Modifier, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    // 每分组一份 scrollState; 初始分组用外部 scrollState (与宿主保存的位置连续)
    // rememberSaveable: 页销毁重建后恢复滚动位置 (按 groupId 隔离)
    val pageScrollState = rememberSaveable(group.groupId, saver = ShelfScrollState.Saver) {
        if (group.groupId == initialGroupId) externalScrollState else ShelfScrollState()
    }
    DisposableEffect(group.groupId, pageScrollState) {
        scrollStates[group.groupId] = pageScrollState
        onDispose { scrollStates.remove(group.groupId) }
    }
    // sortTick: BOOKSHELF_REFRESH (排序配置变更) 时 bump, 重建 flow 让 sortOf 重读配置
    // (对照 BooksFragment.observeLiveBus 的 BOOKSHELF_REFRESH → notifyDataSetChanged)
    var sortTick by remember(group.groupId) { mutableStateOf(0) }
    LaunchedEffect(group.groupId) {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).collect { sortTick++ }
    }
    val booksFlow = remember(group.groupId, sortTick) { viewModel.booksByGroup(group.groupId) }
    var books by remember(group.groupId) { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(group.groupId, booksFlow) {
        booksFlow.collect { loaded ->
            books = loaded
            onBooksLoaded(group.groupId, loaded.size)
        }
    }
    val refreshingUrls by viewModel.refreshingUrls.collectAsState()
    // 复用 ShelfBooksContent: 享受 contentType / animateItem / timeTick / 滚顶等性能优化
    ShelfBooksContent(
        items = books.orEmpty(),
        spec = spec,
        scroll = pageScrollState,
        // 对照 BooksFragment: refreshLayout.isEnabled = group.enableRefresh
        refreshEnabled = group.enableRefresh,
        onRefresh = onRefresh,
        coverReloadTick = 0,
        refreshingUrls = refreshingUrls,
        onBookClick = onBookClick,
        onBookLongClick = onBookLongClick,
        showLastUpdateTime = true,
        showKindIntro = true,
        bookCoverSlot = bookCoverSlot,
        groupCoverSlot = groupCoverSlot,
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
                modifier = Modifier.weight(1f).padding(start = 16.dp),
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

/** 顶栏容器 (两种分组样式共用): 背景 + 56dp 高 Row, 左侧 [content] 右侧 [actions] */
@Composable
internal fun BookshelfTopBarContainer(
    actions: @Composable RowScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
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
        modifier = Modifier.weight(1f).padding(start = 16.dp),
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
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
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
 * 默认封面占位: 渲染书名首字符 (兜底场景, 桌面端应通过 [BookshelfScreen] 的
 * `coverSlot` 参数注入实际封面加载逻辑)。
 *
 * 视觉: 圆角 shapeSm 矩形 + accent 底 + 白字 (深底黑字), 比例 3:4 (小说封面标准)。
 *
 * 高度按宽度 3:4 自动计算 (对齐 CoverImageView.onMeasure 按 coverRatio 自适应),
 * 不再硬编码 160dp。
 *
 * @param modifier 外部尺寸约束; 与 [SharedBookCover] 同法, 高度有界时按比例反推宽度
 */
@Composable
fun DefaultBookCoverPlaceholder(book: Book, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val accent = colors.accent
    val textColor = if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f >= 0.5f) Color(0xDE000000) else Color(0xFFFFFFFF)
    val firstChar = book.name.firstOrNull() ?: '?'
    Box(
        modifier
            .aspectRatio(NOVEL_COVER_RATIO, matchHeightConstraintsFirst = true)
            .clip(DesignTokens.shapeSm)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = firstChar.toString(),
            color = textColor,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 共享封面加载: 走 [BookImageLoaders] (各端注入 coil3 实现) 加载实际封面,
 * 加载中/失败/无 cover URL/未注册 loader/[AppConfigAccessor.useDefaultCover] 时走默认封面。
 *
 * 默认封面链对齐 app 端 `BookCover.newDefaultDrawable`: 用户图集非空时按 seed (书名, 无则封面
 * 路径) 稳定选一张烘焙图, 从 [DataStorageProviders] 的 coversDir 读本地文件; 图集为空回落内置
 * `image_cover_default` (.9 图当普通图拉伸)。竖排书名/作者 overlay 只画在默认封面上,
 * 对照原版 `defaultCover=true` 才 drawNameAuthor。
 *
 * 高度按 [isVideoCover] 选 16:9 / 3:4 由宽度自动算出 (对齐 CoverImageView.onMeasure 按 coverRatio
 * 自适应, 不再硬编码 160dp)。
 *
 * @param book 当前书籍
 * @param modifier 外部尺寸约束; 默认 [Modifier] 时 fillMaxWidth + aspectRatio + shapeSm
 * @param isVideoCover 是否视频封面 (true: 16:9, false: 3:4; 对照 CoverRatio.VIDEO/NOVEL)
 *
 * ohos 未注册 [BookImageLoaders], 恒走内置图 + overlay (与替换前占位语义一致)。
 */
@Composable
fun SharedBookCover(
    book: Book,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
) {
    val cover = book.getDisplayCover()
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过网络加载, 直接走默认封面链 (对照 app 端 CoverImageView 行为)
    val useDefaultCover = remember { AppConfigProviders.get().useDefaultCover }
    val ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL
    // 用户图集选图: seed 同 app 端 defaultCoverSeed() (书名优先, 否则封面路径), 图集为空返回 null
    val defaultCoverPath = remember(book.name, cover, ratio) {
        defaultCoverFilePath(seed = book.name.takeIf { it.isNotBlank() } ?: cover, ratio = ratio)
    }
    var bitmap by remember(cover, book.origin) { mutableStateOf<ImageBitmap?>(null) }
    // 已加载位图是否默认封面 (决定是否画竖排书名/作者)
    var bitmapIsDefault by remember(cover, book.origin) { mutableStateOf(false) }
    LaunchedEffect(cover, book.origin, loader, useDefaultCover, defaultCoverPath) {
        if (loader == null) return@LaunchedEffect
        val loadDefault = {
            if (defaultCoverPath != null) {
                loader.loadImage(
                    url = defaultCoverPath,
                    sourceOrigin = null,
                    onSuccess = { bitmap = it; bitmapIsDefault = true },
                    onError = { bitmap = null },
                )
            }
        }
        if (useDefaultCover || cover.isNullOrBlank()) {
            loadDefault()
            return@LaunchedEffect
        }
        loader.loadImage(
            url = cover,
            sourceOrigin = book.origin,
            onSuccess = { bitmap = it; bitmapIsDefault = false },
            onError = { loadDefault() },
        )
    }
    // 对齐 CoverImageView.onMeasure: 高度有界时按比例反推宽度, 否则按宽度推高度。
    // 不能硬加 fillMaxWidth() —— 列表条目/发现结果页传的是定高 modifier, 撑满宽度会让封面失控放大。
    val aspectRatio = if (isVideoCover) VIDEO_COVER_RATIO else NOVEL_COVER_RATIO
    val resolvedModifier = modifier
        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
        .clip(DesignTokens.shapeSm)
    val bmp = bitmap
    if (bmp != null && !bitmapIsDefault) {
        Image(
            bitmap = bmp,
            contentDescription = book.name,
            modifier = resolvedModifier,
            contentScale = ContentScale.Crop,
        )
        return
    }
    Box(resolvedModifier) {
        if (bmp != null) {
            // 用户图集里的烘焙图 (已按 ratio 裁好)
            Image(
                bitmap = bmp,
                contentDescription = book.name,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 图集为空 / 读盘失败: 内置 image_cover_default (原 .9 图, 这里当普通图拉伸)
            Image(
                painter = painterResource(Res.drawable.image_cover_default),
                contentDescription = book.name,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        CoverNameAuthorOverlay(
            name = book.name,
            author = book.author,
            accent = AppTheme.colors.accent,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * 用户自定义默认封面集选图 (对照 app 端 `BookCover.newDefaultDrawable` 的选图段)。
 *
 * 图集为空或 [DataStorageProviders] 未注册时返回 null, 调用方回落内置图。
 */
private fun defaultCoverFilePath(seed: String?, ratio: CoverRatio): String? {
    val coversDir = DataStorageProviders.getOrNull()?.coversDir ?: return null
    val covers = BookCoverShared.currentDefaultCovers(
        PreferenceProviders.get(),
        AppConfigProviders.get().isNightTheme,
    )
    val index = BookCoverShared.pickDefaultCoverIndex(covers.size, seed)
    if (index < 0) return null
    return BookCoverShared.bakedPath(coversDir, covers[index], ratio)
}

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: NOVEL=3:4 → 0.75 */
internal const val NOVEL_COVER_RATIO = 3f / 4f

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: VIDEO=16:9 → 1.78 */
internal const val VIDEO_COVER_RATIO = 16f / 9f

/**
 * 封面渲染 slot 的 CompositionLocal: 默认兜底 [SharedBookCover]。
 *
 * 宿主端 (app `ShelfCover` / desktop `DesktopBookCover`) 可用 [CompositionLocalProvider]
 * 覆盖注入, 替换 shared 路由 ([BookshelfScreen] / `BookInfoRoute`) 的封面实现,
 * 避免 shared 路由硬编码 fallback 误用平台原生封面组件。
 *
 * 签名 `(Book, Modifier, Boolean) -> Unit` 对齐 [ShelfBooksContent] 的 `bookCoverSlot`
 * (book / modifier / isVideoCover), modifier 与 isVideoCover 不被丢弃。
 */
val LocalBookCoverSlot = staticCompositionLocalOf<@Composable (Book, Modifier, Boolean) -> Unit> {
    @Composable { book, modifier, isVideoCover -> SharedBookCover(book, modifier, isVideoCover) }
}
