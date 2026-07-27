package io.legado.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import androidx.compose.foundation.layout.width
import io.legado.app.constant.BookType
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 书架 Screen (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `BookshelfScreen1` (style1, 分组 tab + HorizontalPager) +
 * `BookshelfScreen2` (style2, 单列表 + 标题) 的共有骨架, 下沉后融合为一个 Screen:
 *
 * - **顶栏**: 分组切换 ([AppScrollTabRow]) + 搜索图标 + 溢出菜单槽 ([actions] slot)
 * - **内容区**: 按 [tier] 选 [LazyColumn] (列表) / [LazyVerticalGrid] (网格)
 *   - 列表项: [ShelfListItem] (封面 + 书名 + 作者 + 最新章节)
 *   - 网格项: [ShelfGridItem] (封面 + 书名)
 * - **空态**: 居中提示
 *
 * # 简化项 (对照 app 端 ShelfBooksContent)
 *
 * - 不接入下拉刷新 (PullToRefresh, 依赖 M3 experimental API 与 MainViewModel.upToc),
 *   桌面端无下拉手势, 后续按需补
 * - 不接入 HorizontalPager 分组分页 (style1 视觉), 改用顶部 tab 切换 + 单内容区,
 *   对桌面端更适合 (无 ViewPager 习惯, 鼠标点击切换)
 * - 不显示未读徽标 (依赖 BaseBook.getUnreadChapterNum 扩展, 该扩展在 app/BookExtensions.kt
 *   未下沉, 桌面端如需可后续下沉)
 * - 不显示分类标签/最新章节行/更新时间等扩展信息 (依赖 toTimeAgo/KindLabels 等
 *   Android-specific 工具), 仅保留书名 + 作者 + 最新章节标题三条核心字段
 * - 封面由 [coverSlot] 注入: commonMain 不依赖 Glide/Coil, 桌面端通过 slot 注入
 *   DesktopImageOps 本地缓存加载; 默认 [DefaultBookCoverPlaceholder] 渲染书名首字
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
    val eInk = LocalEInk.current
    val appConfig = remember { AppConfigProviders.get() }
    val groups by viewModel.bookGroups.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val books by viewModel.books.collectAsState()

    // tier 决策: 显式传入优先, 否则按 bookshelfLayout (0=LIST, 其他=GRID)
    val resolvedTier = remember(tier, appConfig.bookshelfLayout) {
        tier ?: if (appConfig.bookshelfLayout == 0) BookshelfTier.LIST else BookshelfTier.GRID
    }
    // 列表封面高度 (对照 app 端 shelfCoverHeightDp, 桌面端无 video 模式收窄)
    val coverHeightDp = remember(appConfig.bookshelfCoverHeight) {
        appConfig.bookshelfCoverHeight.coerceIn(60, 240)
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
        if (books.isEmpty()) {
            // 空态提示 (对照 app 端 R.string.bookshelf_empty)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = rememberString("empty"),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            BookshelfBooksContent(
                books = books,
                tier = resolvedTier,
                coverHeightDp = coverHeightDp,
                gridWidthDp = gridWidthDp,
                eInk = eInk,
                coverSlot = coverSlot,
                onBookClick = onBookClick,
                onBookLongClick = onBookLongClick,
            )
        }
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
private fun BookshelfTopBar(
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
private fun GroupTab(
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

/**
 * 内容区: 按 tier 选 LazyColumn / LazyVerticalGrid。
 *
 * 对照 app 端 ShelfBooksContent: key=bookUrl 稳定, 列表项 8dp padding,
 * 网格列宽走 [GridCells.Adaptive] (固定宽度模式), 底部 8dp 留白避让导航栏。
 */
@Composable
private fun BookshelfBooksContent(
    books: List<Book>,
    tier: BookshelfTier,
    coverHeightDp: Int,
    gridWidthDp: Int,
    eInk: Boolean,
    coverSlot: @Composable (Book) -> Unit,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    when (tier) {
        BookshelfTier.LIST -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(books, key = { it.bookUrl }) { book ->
                ShelfListItem(
                    book = book,
                    coverHeightDp = coverHeightDp,
                    coverSlot = coverSlot,
                    onClick = { onBookClick(book) },
                    onLongClick = { onBookLongClick(book) },
                )
            }
        }

        BookshelfTier.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(gridWidthDp.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(books, key = { it.bookUrl }) { book ->
                ShelfGridItem(
                    book = book,
                    coverSlot = coverSlot,
                    onClick = { onBookClick(book) },
                    onLongClick = { onBookLongClick(book) },
                )
            }
        }
    }
}

// ---- 条目 ----

/**
 * 列表条目 (对照 app 端 ShelfListItem 简化版)。
 *
 * 结构: 封面 (固定高度 [coverHeightDp]) + 右侧三行
 * (书名 16sp / 作者 13sp / 最新章节 13sp), 整行点击/长按。
 */
@Composable
private fun ShelfListItem(
    book: Book,
    coverHeightDp: Int,
    coverSlot: @Composable (Book) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box(Modifier.height(coverHeightDp.dp)) {
            coverSlot(book)
        }
        Column(
            Modifier
                .padding(start = 8.dp)
                .heightIn(min = coverHeightDp.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 书名行
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
            // 作者行 (对照 app 端 ShelfRowIcon ic_author + getRealAuthor)
            Text(
                text = book.getRealAuthor(),
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 最新章节行 (对照 app 端 ShelfRowIcon ic_book_last + latestChapterTitle)
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Text(
                    text = book.latestChapterTitle.toString(),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 网格条目 (对照 app 端 ShelfGridItem 简化版)。
 *
 * 结构: 封面 (12dp 内边距) + 书名两行居中, 整卡点击/长按。
 */
@Composable
private fun ShelfGridItem(
    book: Book,
    coverSlot: @Composable (Book) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Box(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp),
            ) {
                coverSlot(book)
            }
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
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
private fun DefaultBookshelfActions(onSearchClick: () -> Unit) {
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
            .clip(RoundedCornerShape(4.dp))
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

// ---- @Preview (BookshelfScreen.kt 内 Composable) ----
// 假数据: 纯内存 Book/BookGroup, 不依赖 DB/网络;
// AppConfigProviders 由 LegadoThemePreview 注册 stub。
// BookshelfScreen 自身依赖 BookshelfViewModel (需 DB), 不 Preview, 仅 Preview 其内部组件。

private val screenPreviewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "screenPreview://1",
    tocUrl = "screenPreview://toc",
    origin = BookType.localTag,
    kind = "科幻;小说",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈...",
    coverUrl = "https://preview/cover.jpg",
    durChapterTitle = "黑暗森林",
    latestChapterTitle = "末日之战",
    durChapterIndex = 5,
    totalChapterNum = 10,
    latestChapterTime = 1_700_000_000_000,
    lastCheckCount = 3,
)

private val screenPreviewBooks = listOf(
    screenPreviewBook,
    screenPreviewBook.copy(name = "球状闪电", bookUrl = "screenPreview://2"),
    screenPreviewBook.copy(name = "流浪地球", bookUrl = "screenPreview://3"),
)

private val screenPreviewGroups = listOf(
    BookGroup(groupId = 1, groupName = "全部"),
    BookGroup(groupId = 2, groupName = "科幻"),
    BookGroup(groupId = 3, groupName = "小说"),
)

@Preview
@Composable
fun DefaultBookCoverPlaceholderPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp).width(120.dp)) {
        DefaultBookCoverPlaceholder(screenPreviewBook)
    }
}

@Preview
@Composable
fun DefaultBookCoverPlaceholderDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(16.dp).width(120.dp)) {
        DefaultBookCoverPlaceholder(screenPreviewBook)
    }
}

@Preview
@Composable
fun BookshelfScreenTopBarPreview() = LegadoThemePreview {
    BookshelfTopBar(
        groups = screenPreviewGroups,
        currentGroupId = 1L,
        showGroupCount = true,
        bookCount = 3,
        onGroupClick = {},
        onGroupLongClick = {},
        actions = { DefaultBookshelfActions(onSearchClick = {}) },
    )
}

@Preview
@Composable
fun BookshelfScreenTopBarEmptyPreview() = LegadoThemePreview {
    BookshelfTopBar(
        groups = emptyList(),
        currentGroupId = BookGroup.IdAll,
        showGroupCount = false,
        bookCount = 0,
        onGroupClick = {},
        onGroupLongClick = {},
        actions = { DefaultBookshelfActions(onSearchClick = {}) },
    )
}

@Preview
@Composable
fun BookshelfScreenGroupTabPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp)) {
        GroupTab(title = "选中", selected = true, eInk = false, onClick = {}, onLongClick = {})
        GroupTab(title = "未选中", selected = false, eInk = false, onClick = {}, onLongClick = {})
    }
}

@Preview
@Composable
fun BookshelfScreenBooksContentListPreview() = LegadoThemePreview {
    BookshelfBooksContent(
        books = screenPreviewBooks,
        tier = BookshelfTier.LIST,
        coverHeightDp = 120,
        gridWidthDp = 120,
        eInk = false,
        coverSlot = { DefaultBookCoverPlaceholder(it) },
        onBookClick = {},
        onBookLongClick = {},
    )
}

@Preview
@Composable
fun BookshelfScreenBooksContentGridPreview() = LegadoThemePreview {
    BookshelfBooksContent(
        books = screenPreviewBooks,
        tier = BookshelfTier.GRID,
        coverHeightDp = 120,
        gridWidthDp = 120,
        eInk = false,
        coverSlot = { DefaultBookCoverPlaceholder(it) },
        onBookClick = {},
        onBookLongClick = {},
    )
}

@Preview
@Composable
fun BookshelfScreenListItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        ShelfListItem(
            book = screenPreviewBook,
            coverHeightDp = 120,
            coverSlot = { DefaultBookCoverPlaceholder(it) },
            onClick = {},
            onLongClick = {},
        )
    }
}

@Preview
@Composable
fun BookshelfScreenGridItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(120.dp)) {
        ShelfGridItem(
            book = screenPreviewBook,
            coverSlot = { DefaultBookCoverPlaceholder(it) },
            onClick = {},
            onLongClick = {},
        )
    }
}

@Preview
@Composable
fun BookshelfScreenActionsPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp)) {
        DefaultBookshelfActions(onSearchClick = {})
    }
}
