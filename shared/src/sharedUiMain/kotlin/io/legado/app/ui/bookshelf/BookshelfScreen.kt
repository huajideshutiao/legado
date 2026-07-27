package io.legado.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * 书架 Screen (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `BookshelfScreen1` (style1, 分组 tab + HorizontalPager) +
 * `BookshelfScreen2` (style2, 单列表 + 标题) 的共有骨架, 下沉后融合为一个 Screen:
 *
 * - **顶栏**: 分组切换 ([AppScrollTabRow]) + 搜索图标 + 溢出菜单槽 ([actions] slot)
 * - **内容区**: 复用 [ShelfBooksContent] (按 [tier] 选 LIST/GRID, 享受 contentType /
 *   animateItem / timeTick / 滚顶等性能优化; 列表/网格条目由 shared 端 [ShelfListItem] /
 *   [ShelfGridItem] 渲染)
 * - **空态**: 由 [ShelfBooksContent] 内部居中提示
 *
 * # 简化项 (对照 app 端 ShelfBooksContent)
 *
 * - 不接入下拉刷新 (桌面端无下拉手势), refreshEnabled 恒 false
 * - 不接入 HorizontalPager 分组分页 (style1 视觉), 改用顶部 tab 切换 + 单内容区,
 *   对桌面端更适合 (无 ViewPager 习惯, 鼠标点击切换)
 * - refreshingUrls / coverReloadTick 桌面端暂无 state, 传空省略对应功能
 *   (条目仍渲染, 仅无刷新转圈/封面重载动画)
 * - 封面由 [coverSlot] 注入: 默认 [DefaultBookCoverPlaceholder] 渲染书名首字;
 *   桌面端通过 slot 注入 DesktopBookCover (ImageIO + OkHttp 加载)
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
 * @param coverSlot 封面渲染 slot, 默认 [DefaultBookCoverPlaceholder]
 * @param actions 顶栏右侧溢出菜单槽, 默认空 OverflowMenu
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
    coverSlot: @Composable (Book) -> Unit = { DefaultBookCoverPlaceholder(it) },
    actions: @Composable RowScope.() -> Unit = { DefaultBookshelfActions(onSearchClick) },
) {
    val colors = AppTheme.colors
    val appConfig = remember { AppConfigProviders.get() }
    val groups by viewModel.bookGroups.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val books by viewModel.books.collectAsState()

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

    Column(modifier.fillMaxSize().background(colors.background)) {
        BookshelfTopBar(
            groups = groups,
            currentGroupId = currentGroupId,
            showGroupCount = showGroupCount,
            bookCount = books.size,
            onGroupClick = viewModel::selectGroup,
            onGroupLongClick = onGroupLongClick,
            actions = actions,
        )
        // 复用 ShelfBooksContent: 享受 contentType / animateItem / timeTick / 滚顶等性能优化
        // (refreshingUrls / coverReloadTick 桌面端暂无 state, 省略对应功能传空)
        ShelfBooksContent(
            items = books,
            spec = remember(resolvedTier, gridWidthDp) {
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
            },
            scroll = remember { ShelfScrollState() },
            refreshEnabled = false,
            onRefresh = {},
            coverReloadTick = 0,
            refreshingUrls = emptySet(),
            onBookClick = onBookClick,
            onBookLongClick = onBookLongClick,
            showLastUpdateTime = true,
            showKindIntro = true,
            bookCoverSlot = { book, _, _ -> coverSlot(book) },
            groupCoverSlot = { _, modifier, _ -> Box(modifier) },
        )
    }
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
 * 默认顶栏右侧动作: 搜索图标 + 空溢出菜单。
 *
 * 对照 app 端 BookshelfActions: 仅保留搜索图标入口, 溢出菜单项由宿主端通过
 * [BookshelfScreen] 的 `actions` slot 覆盖注入 (app 端 8 项菜单依赖 Activity 跳转,
 * 不下沉)。
 */
@Composable
internal fun DefaultBookshelfActions(onSearchClick: () -> Unit) {
    val colors = AppTheme.colors
    IconButton(onClick = onSearchClick) {
        Icon(
            painter = rememberPainter("ic_search"),
            contentDescription = rememberString("more_menu"),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { }
}

/**
 * 默认封面占位: 渲染书名首字符 (兜底场景, 桌面端应通过 [BookshelfScreen] 的
 * `coverSlot` 参数注入实际封面加载逻辑)。
 *
 * 视觉: 圆角 4dp 矩形 + accent 底 + 白字 (深底黑字), 比例 3:4 (小说封面标准)。
 */
@Composable
fun DefaultBookCoverPlaceholder(book: Book) {
    val colors = AppTheme.colors
    val accent = colors.accent
    val textColor = if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f >= 0.5f) Color(0xDE000000) else Color(0xFFFFFFFF)
    val firstChar = book.name.firstOrNull() ?: '?'
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
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
