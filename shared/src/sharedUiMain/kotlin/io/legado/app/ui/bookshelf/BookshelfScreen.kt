package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.launch

/**
 * 书架 Screen (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `BookshelfScreen1` (style1, 分组 tab + HorizontalPager) +
 * `BookshelfScreen2` (style2, 单列表 + 标题) 的共有骨架, 下沉后融合为一个 Screen:
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
 * - 不接入下拉刷新 (桌面端无下拉手势), refreshEnabled 恒 false
 * - refreshingUrls / coverReloadTick 桌面端暂无 state, 传空省略对应功能
 *   (条目仍渲染, 仅无刷新转圈/封面重载动画)
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
 * @param scrollState 外部注入的滚动状态; 默认内部 remember 新建。宿主端注入后可触发滚顶 (tab reselect)
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
) {
    val colors = AppTheme.colors
    val appConfig = remember { AppConfigProviders.get() }
    val eInk = LocalEInk.current
    // 封面 slot: 显式传入优先, 否则取 CompositionLocal (宿主端可覆盖注入平台实现, 兜底 SharedBookCover)
    val resolvedCoverSlot: @Composable (Book, Modifier, Boolean) -> Unit =
        coverSlot ?: LocalBookCoverSlot.current
    val groups by viewModel.bookGroups.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val books by viewModel.books.collectAsState()
    val scope = rememberCoroutineScope()

    // tier 决策: 显式传入优先, 否则按 bookshelfLayout (0=LIST, 其他=GRID)
    val resolvedTier = remember(tier, appConfig.bookshelfLayout) {
        tier ?: if (appConfig.bookshelfLayout == 0) BookshelfTier.LIST else BookshelfTier.GRID
    }
    // 网格列宽 (对照 app 端 bookshelfGridWidth, Adaptive 模式)
    val gridWidthDp = remember(appConfig.bookshelfGridWidth) {
        appConfig.bookshelfGridWidth.coerceIn(60, 240)
    }
    // 顶栏 tab 是否显示分组数量 (对照 app 端 AppConfig.bookshelfShowGroupCount)
    val showGroupCount = remember { appConfig.bookshelfShowGroupCount }

    // 布局 spec 各 pager 页共用, 计算一次
    val layoutSpec = remember(resolvedTier, gridWidthDp) {
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
    // pager 滑动结束 (settledPage) → selectGroup 同步 currentGroupId 与 _books (供 bookCount)
    // 不用 currentPage: 滑动过程中会频繁触发 selectGroup → DB 查询 → 卡顿
    LaunchedEffect(pagerState, groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }.collect { page ->
            groups.getOrNull(page)?.let { group ->
                viewModel.selectGroup(group.groupId)
            }
        }
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

    Column(modifier.fillMaxSize().background(colors.background)) {
        BookshelfTopBar(
            groups = groups,
            currentGroupId = displayGroupId,
            showGroupCount = showGroupCount,
            bookCount = books.size,
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
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0, // 不预渲染相邻页, 避免滑动时多个 LazyColumn + DB flow 同时活跃导致卡顿
                key = { index -> groups.getOrNull(index)?.groupId ?: index.toLong() },
                userScrollEnabled = !eInk && groups.size > 1,
            ) { page ->
                val group = groups.getOrNull(page) ?: return@HorizontalPager
                GroupBooksPage(
                    group = group,
                    spec = layoutSpec,
                    externalScrollState = scrollState,
                    initialGroupId = initialGroupId,
                    viewModel = viewModel,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    bookCoverSlot = bookCoverSlot,
                    groupCoverSlot = groupCoverSlot,
                )
            }
        }
    }
}

/**
 * 单个分组页 (对照 app 端 BookshelfScreen1 的 GroupBooksPage)。
 *
 * 每页独立订阅 [BookshelfViewModel.booksByGroup] 加载该分组书籍,
 * 每页独立 [ShelfScrollState] 保留滚动位置 (初始分组复用外部 scrollState 保留 gotoTop 入口)。
 */
@Composable
private fun GroupBooksPage(
    group: BookGroup,
    spec: ShelfLayoutSpec,
    externalScrollState: ShelfScrollState,
    initialGroupId: Long,
    viewModel: BookshelfViewModel,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    bookCoverSlot: @Composable (Book, Modifier, Boolean) -> Unit,
    groupCoverSlot: @Composable (BookGroup, Modifier, Boolean) -> Unit,
) {
    // 每分组一份 scrollState; 初始分组用外部 scrollState (保留宿主 gotoTop 入口)
    val pageScrollState = remember(group.groupId) {
        if (group.groupId == initialGroupId) externalScrollState else ShelfScrollState()
    }
    // 每分组独立订阅 booksByGroup 流 (互不干扰, 页销毁时自动取消)
    val pageBooksFlow = remember(group.groupId) { viewModel.booksByGroup(group.groupId) }
    val pageBooks by pageBooksFlow.collectAsState(initial = emptyList())
    // 复用 ShelfBooksContent: 享受 contentType / animateItem / timeTick / 滚顶等性能优化
    ShelfBooksContent(
        items = pageBooks,
        spec = spec,
        scroll = pageScrollState,
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
}

/**
 * 顶栏: 分组 tab (左) + 操作区 (右)。
 *
 * 对照 app 端 BookshelfTopBar + BookshelfActions: 分组用 [AppScrollTabRow]
 * 横向滚动 tab, 选中色 accent, 指示条 2dp 贴底; 选中同 tab 再点不触发滚顶
 * (app 端 onTabReselect 调 gotoTop, 此处简化)。
 *
 * @param showGroupCount 是否在 tab 标题后显示 "(n)" 数量
 * @param bookCount 当前分组书籍数 (showGroupCount=true 时附加到当前 tab 标题)
 */
@Composable
internal fun BookshelfTopBar(
    groups: List<BookGroup>,
    currentGroupId: Long,
    showGroupCount: Boolean,
    bookCount: Int,
    onGroupClick: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
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
                    val title = if (showGroupCount && index == selectedIndex) {
                        "${group.groupName}($bookCount)"
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
                Text(
                    text = rememberString("bookshelf"),
                    color = colors.primaryText,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            actions()
        }
    }
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
 * @param modifier 外部尺寸约束; 默认 [Modifier] 时 fillMaxWidth + aspectRatio(3:4)
 */
@Composable
fun DefaultBookCoverPlaceholder(book: Book, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val accent = colors.accent
    val textColor = if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f >= 0.5f) Color(0xDE000000) else Color(0xFFFFFFFF)
    val firstChar = book.name.firstOrNull() ?: '?'
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(NOVEL_COVER_RATIO)
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
 * 加载中/失败/无 cover URL/未注册 loader/[AppConfigAccessor.useDefaultCover] 时回退到占位。
 *
 * 视觉与 [DefaultBookCoverPlaceholder] 一致 (fillMaxWidth + shapeSm 圆角 + 按 coverRatio 自适应高度),
 * 加载成功时以 [ContentScale.Crop] 填满同尺寸区域, 不改变占位 footprint。
 *
 * 高度按 [isVideoCover] 选 16:9 / 3:4 由宽度自动算出 (对齐 CoverImageView.onMeasure 按 coverRatio
 * 自适应, 不再硬编码 160dp)。
 *
 * @param book 当前书籍
 * @param modifier 外部尺寸约束; 默认 [Modifier] 时 fillMaxWidth + aspectRatio + shapeSm
 * @param isVideoCover 是否视频封面 (true: 16:9, false: 3:4; 对照 CoverRatio.VIDEO/NOVEL)
 *
 * ohos 未注册 [BookImageLoaders], 恒走占位 (与替换前行为一致)。
 */
@Composable
fun SharedBookCover(
    book: Book,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
) {
    val cover = book.getDisplayCover()
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过加载, 直接走占位 (对照 app 端 CoverImageView 行为)
    val useDefaultCover = remember { AppConfigProviders.get().useDefaultCover }
    var bitmap by remember(cover, book.origin) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover, book.origin, loader, useDefaultCover) {
        if (useDefaultCover || cover.isNullOrBlank() || loader == null) return@LaunchedEffect
        loader.loadImage(
            url = cover,
            sourceOrigin = book.origin,
            onSuccess = { bitmap = it },
            onError = { bitmap = null },
        )
    }
    // aspectRatio 按 coverRatio 自适应高度 (对齐 CoverImageView.onMeasure), 不再硬编码 160dp
    val aspectRatio = if (isVideoCover) VIDEO_COVER_RATIO else NOVEL_COVER_RATIO
    val resolvedModifier =
        modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(DesignTokens.shapeSm)
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = book.name,
            modifier = resolvedModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            resolvedModifier.background(AppTheme.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            val accent = AppTheme.colors.accent
            val textColor =
                if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f >= 0.5f) Color(
                    0xDE000000
                ) else Color(0xFFFFFFFF)
            val firstChar = book.name.firstOrNull() ?: '?'
            Text(
                text = firstChar.toString(),
                color = textColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: NOVEL=3:4 → 0.75 */
private const val NOVEL_COVER_RATIO = 3f / 4f

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: VIDEO=16:9 → 1.78 */
private const val VIDEO_COVER_RATIO = 16f / 9f

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
