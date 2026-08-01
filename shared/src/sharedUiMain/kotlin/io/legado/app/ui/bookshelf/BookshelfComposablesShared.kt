package io.legado.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getUnreadChapterNum
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.FastScrollLazyVerticalGrid
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.PullToRefreshDefaults
import io.legado.app.ui.compose.component.pullToRefresh
import io.legado.app.ui.compose.component.rememberPullToRefreshState
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.delay
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf_empty
import legado.shared.generated.resources.ic_search
import legado.shared.generated.resources.search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书架共享组件 (KMP 版, 替代 app 端 `BookshelfComposables.kt` 中除 ShelfCover 外的所有组件)。
 *
 * # 下沉改动
 *
 * - **包名**: 保持与 app 端原包名一致 `io.legado.app.ui.bookshelf`
 *   (app 端原 `io.legado.app.ui.main.bookshelf` 包内的 BookshelfScreen1/2 等调用方
 *   仅需改 import 路径为 `io.legado.app.ui.bookshelf` 即可复用)
 * - **资源访问**: `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)`;
 *   `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - **Android API 替换**:
 *   - `LocalContext` → 删除, 改用回调注入
 *   - `LocalConfiguration.current.screenWidthDp` → 由调用方传入 `screenWidthDp: Int` 参数
 *   - `AppConfig.xxx` → `AppConfigProviders.get().xxx` (provider 间接访问)
 *   - `ThemeConfig.curBgImagePath` → `LocalThemeStoreProvider.current.bgImagePath`
 *   - `ColorUtils.isColorLight` → 内联 `isColorLight` 私有函数 (亮度公式与 ColorUtils 一致)
 *   - `AndroidView + CoverImageView` (ShelfCover) → 用 `coverSlot: @Composable (Book, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit`
 *     参数注入; app 端用 ShelfCover 包装, 桌面端用 DesktopBookCover 等自定义实现;
 *     isVideoCover 由条目按 tier 决定 (对照原 adapter coverRatio 赋值), coverReloadTick
 *     为配置变更重载信号 (宿主端封面组件按 tick 判重/重载)
 * - **状态提升**: `BookshelfActions` 改为接受 [BookshelfActionsCallbacks] 而非 BaseBookshelfState;
 *   `ShelfBooksContent` 去掉 `Lifecycle` 参数 (shared 不依赖 androidx.lifecycle,
 *   30s 心跳改用 LaunchedEffect + while(true) + delay, 后台时 Compose 不重组故无副作用)
 * - **保留逻辑**: 视觉/布局/动画/手势/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)
 *
 * # 未下沉组件 (app 端 BookshelfComposables.kt 保留)
 *
 * - `ShelfCover` (AndroidView + CoverImageView + Glide, Android 专属, 其他模块在用)
 *   → app 端调用 shared 版 [ShelfListItem] 等时通过 coverSlot 参数包装 ShelfCover 注入
 * - `rememberShelfLayoutSpec` (依赖 LocalConfiguration.current.screenWidthDp)
 *   → app 端保留原版, 调用 shared 版 [rememberShelfLayoutSpec] 时手动传 screenWidthDp
 *   (或调用方直接用 shared 版, 在调用前先取 LocalConfiguration.current.screenWidthDp 传入)
 */

/** 16:9 视频封面按 3/4 高度收窄, 与 ItemBookshelfListBindingExt 的同名常量语义一致 */
private const val VIDEO_HEIGHT_SCALE = 0.75f

/** 布局三档: 与原 createAdapter 的 List/Grid/Video tier 一一对应 */
enum class ShelfTier { LIST, GRID, VIDEO }

data class ShelfLayoutSpec(
    val tier: ShelfTier,
    val isVideoList: Boolean,
    val cols: Int,
    val fixedWidth: Boolean,
    val gridWidthDp: Int,
)

/** 每分组一份滚动状态, 外置于状态类以便 Pager 页销毁重建后保留 + gotoTop 直达 */
class ShelfScrollState(
    listIndex: Int = 0,
    listOffset: Int = 0,
    gridIndex: Int = 0,
    gridOffset: Int = 0,
) {
    /** LIST 档也走 LazyVerticalGrid (响应式多列列表), 故两档都是网格状态; 分开存以保留各档位置 */
    val list = LazyGridState(listIndex, listOffset)
    val grid = LazyGridState(gridIndex, gridOffset)

    companion object {
        /** rememberSaveable 用: [listIndex, listOffset, gridIndex, gridOffset] */
        val Saver = listSaver<ShelfScrollState, Int>(
            save = {
                listOf(
                    it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset,
                    it.grid.firstVisibleItemIndex, it.grid.firstVisibleItemScrollOffset,
                )
            },
            restore = { ShelfScrollState(it[0], it[1], it[2], it[3]) },
        )
    }
}

/**
 * 复刻 getCols/createAdapter 的 tier 决策: 固定宽度模式按屏宽换算列数,
 * 否则读 bookshelfLayout 位段; cols 0/1 走列表, isVideo 走视频卡片.
 *
 * shared 版本: 不依赖 `LocalConfiguration`, 改为接受 [screenWidthDp] 参数
 * (app 端由调用方从 `LocalConfiguration.current.screenWidthDp` 传入;
 * 桌面端可用 `BoxWithConstraints.maxWidth` 转 dp 后传入)
 */
@Composable
fun rememberShelfLayoutSpec(layoutSpecTick: Int, screenWidthDp: Int): ShelfLayoutSpec {
    return remember(layoutSpecTick, screenWidthDp) {
        val appConfig = AppConfigProviders.get()
        val style = appConfig.bookshelfLayout
        val isVideo = BookSource.exploreStyleIsVideo(style)
        val fixedWidth = appConfig.bookshelfFixedWidthMode
        val gridWidth = appConfig.bookshelfGridWidth
        val cols = if (fixedWidth) {
            maxOf(1, screenWidthDp / gridWidth)
        } else {
            BookSource.exploreStyleCols(style)
        }
        when {
            cols == 0 -> ShelfLayoutSpec(ShelfTier.LIST, isVideo, 1, fixedWidth, gridWidth)
            isVideo -> ShelfLayoutSpec(ShelfTier.VIDEO, false, cols, fixedWidth, gridWidth)
            cols == 1 -> ShelfLayoutSpec(ShelfTier.LIST, false, 1, fixedWidth, gridWidth)
            else -> ShelfLayoutSpec(ShelfTier.GRID, false, cols, fixedWidth, gridWidth)
        }
    }
}

private fun shelfItemKey(item: Any): Any = when (item) {
    is Book -> item.bookUrl
    is BookGroup -> "g:${item.groupId}"
    else -> item
}

private fun shelfItemType(item: Any): Any = if (item is BookGroup) "group" else "book"

/** 网格列数: 固定宽度模式仍走 Adaptive, 否则用户列数按参考宽度响应式加列 */
@Composable
private fun shelfGridCells(spec: ShelfLayoutSpec): GridCells =
    if (spec.fixedWidth) GridCells.Adaptive(spec.gridWidthDp.dp)
    else rememberResponsiveColumns(spec.cols)

/** 计算封面高度 (对照 app 端 shelfCoverHeightDp, 视频模式按 0.75 收窄) */
private fun shelfCoverHeightDp(isVideoStyle: Boolean): Int {
    val base = AppConfigProviders.get().bookshelfCoverHeight
    return if (isVideoStyle) (base * VIDEO_HEIGHT_SCALE).toInt() else base
}

/**
 * 书架 tab 对外控制面 (轻量 interface, 替代 app 端 BookshelfTabController)。
 *
 * app 端 BaseBookshelfState 实现此接口 (MainActivity 经壳桥接调用;
 * back 仅 style2 有意义, style1 恒 false)。
 */
interface BookshelfTabController {
    fun gotoTop()
    fun upSort()
    fun back(): Boolean
}

/**
 * 书架内容区: 下拉刷新 + 空提示 + 按 tier 选行内布局 (三档都是 LazyVerticalGrid).
 * key=bookUrl 稳定; 固定宽度模式网格用 Adaptive(固定宽) 自适应列数.
 *
 * shared 版本改动:
 * - 去掉 `lifecycle: Lifecycle` 参数 (shared 不依赖 androidx.lifecycle),
 *   30s 心跳改用 `LaunchedEffect { while(true) { delay(30s); timeTick++ } }` 替代
 *   `repeatOnLifecycle(RESUMED) { while(true) { delay(30s); timeTick++ } }`
 * - 封面改为 [bookCoverSlot] / [groupCoverSlot] 注入 (替代原 ShelfCover 直接调用)
 *
 * 行为差异: app 在后台 (非 RESUMED) 时, 原 repeatOnLifecycle 取消协程, shared 版本仍跑心跳;
 * 但 Compose 不可见时不重组, timeTick++ 仅触发状态写入不重组 UI, 实际无副作用。
 */
@Composable
fun ShelfBooksContent(
    items: List<Any>,
    spec: ShelfLayoutSpec,
    scroll: ShelfScrollState,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    coverReloadTick: Int,
    refreshingUrls: Set<String>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    showLastUpdateTime: Boolean,
    showKindIntro: Boolean,
    // isVideoCover: 是否用 VIDEO(16:9) 封面比例。对照原版 ShelfCover ratio 选取:
    // Book list 按 isVideoStyle; Group list 恒 NOVEL(原 GroupViewHolder 不设 coverRatio);
    // Grid 恒 NOVEL; Video 恒 VIDEO。
    // 第 4 参 coverReloadTick: 配置变更时重载封面 (宿主端封面组件按 tick 判重/重载)
    bookCoverSlot: @Composable (Book, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
    groupCoverSlot: @Composable (BookGroup, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
    modifier: Modifier = Modifier,
    onGroupClick: ((BookGroup) -> Unit)? = null,
    onGroupLongClick: ((BookGroup) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val pullState = rememberPullToRefreshState()
    // 全表扫描, 别每次重组都跑一遍 (书架上千本时很贵); 无人刷新时直接短路
    val isRefreshing = remember(items, refreshingUrls) {
        refreshingUrls.isNotEmpty() && items.any { item ->
            item is Book && item.bookUrl in refreshingUrls
        }
    }
    // 锁定 refreshingUrls 引用, 避免子项无谓重组
    val refreshingUrlsSet = remember(refreshingUrls) { refreshingUrls }
    val appConfig = remember { AppConfigProviders.get() }
    // 30s 心跳只在列表模式且开了"显示更新时间"时跑, 不依赖任何 tick
    // (原 repeatOnLifecycle(RESUMED) 改为 LaunchedEffect, shared 不依赖 androidx.lifecycle)
    // 心跳 State, 下发给 ShelfLastUpdateText 订阅
    val timeTickState = remember { mutableIntStateOf(0) }
    if (spec.tier == ShelfTier.LIST && showLastUpdateTime && appConfig.showLastUpdateTime) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(30 * 1000)
                timeTickState.intValue++
            }
        }
    }
    // 复刻原 onItemRangeInserted/Moved(0) 的滚顶: 列表模式且停在顶部时, 新的第一项要露出来
    val firstKey = remember(items) { items.firstOrNull()?.let(::shelfItemKey) }
    LaunchedEffect(firstKey) {
        if (firstKey != null && spec.tier == ShelfTier.LIST
            && scroll.list.firstVisibleItemIndex <= 1
        ) {
            scroll.list.scrollToItem(0)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pullState,
                enabled = refreshEnabled,
                onRefresh = onRefresh,
            ),
    ) {
        // 复刻原 RecyclerView 默认 ItemAnimator 的 move 动画(阅读返回重排→条目平移)；E-Ink 关
        val eInk = LocalEInk.current
        when (spec.tier) {
            ShelfTier.LIST -> FastScrollLazyVerticalGrid(
                // 列表档也响应式拆列: 400dp→1 列, 800dp→2 列多列列表 (行内布局不变)
                columns = rememberResponsiveColumns(1),
                state = scroll.list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(items, key = ::shelfItemKey, contentType = ::shelfItemType) { item ->
                    val itemModifier = if (eInk) Modifier else Modifier.animateItem()
                    when (item) {
                        is Book -> ShelfListItem(
                            // 逐项窄化: 非刷新项恒拿 emptySet 单例, 集合变化时可跳过重组
                            item, spec.isVideoList, coverReloadTick,
                            if (item.bookUrl in refreshingUrlsSet) refreshingUrlsSet else emptySet(),
                            showLastUpdateTime, showKindIntro,
                            onClick = { onBookClick(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = itemModifier,
                            coverSlot = bookCoverSlot,
                            lastUpdateTextSlot = { ShelfLastUpdateText(item.latestChapterTime, timeTickState) },
                        )

                        is BookGroup -> GroupListItem(
                            item, spec.isVideoList, coverReloadTick,
                            onClick = { onGroupClick?.invoke(item) },
                            onLongClick = { onGroupLongClick?.invoke(item) },
                            modifier = itemModifier,
                            coverSlot = groupCoverSlot,
                        )
                    }
                }
            }

            ShelfTier.GRID -> FastScrollLazyVerticalGrid(
                columns = shelfGridCells(spec),
                state = scroll.grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(items, key = ::shelfItemKey, contentType = ::shelfItemType) { item ->
                    val itemModifier = if (eInk) Modifier else Modifier.animateItem()
                    when (item) {
                        is Book -> ShelfGridItem(
                            // 逐项窄化: 同 LIST 分支, 避免刷新集合每次变化重组全部可见项
                            item, coverReloadTick,
                            if (item.bookUrl in refreshingUrlsSet) refreshingUrlsSet else emptySet(),
                            onClick = { onBookClick(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = itemModifier,
                            coverSlot = bookCoverSlot,
                        )

                        is BookGroup -> GroupGridItem(
                            item, coverReloadTick,
                            onClick = { onGroupClick?.invoke(item) },
                            onLongClick = { onGroupLongClick?.invoke(item) },
                            modifier = itemModifier,
                            coverSlot = groupCoverSlot,
                        )
                    }
                }
            }

            ShelfTier.VIDEO -> FastScrollLazyVerticalGrid(
                columns = shelfGridCells(spec),
                state = scroll.grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(items, key = ::shelfItemKey, contentType = ::shelfItemType) { item ->
                    val itemModifier = if (eInk) Modifier else Modifier.animateItem()
                    when (item) {
                        is Book -> ShelfVideoItem(
                            item, coverReloadTick,
                            onClick = { onBookClick(item) },
                            onLongClick = { onBookLongClick(item) },
                            modifier = itemModifier,
                            coverSlot = bookCoverSlot,
                        )

                        is BookGroup -> GroupVideoItem(
                            item, coverReloadTick,
                            onClick = { onGroupClick?.invoke(item) },
                            onLongClick = { onGroupLongClick?.invoke(item) },
                            modifier = itemModifier,
                            coverSlot = groupCoverSlot,
                        )
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(Res.string.bookshelf_empty),
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            color = colors.accent,
        )
    }
}

// ---- 顶栏 ----

/** 书架顶栏容器: 背景取值与 AppTitleBar/ExploreTitleBar 同源(EInk 白底分割线/壁纸透明/背景色) */
@Composable
fun BookshelfTopBar(content: @Composable RowScope.() -> Unit) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val themeStore = LocalThemeStoreProvider.current
    val hasBgImage = remember(themeStore.bgImagePath) {
        !themeStore.bgImagePath.isNullOrBlank()
    }
    val bg = when {
        eInk -> Color.White
        hasBgImage -> Color.Transparent
        else -> colors.background
    }
    val insetsModifier = if (eInk) {
        Modifier.windowInsetsPadding(WindowInsets(0))
    } else {
        Modifier.statusBarsPadding()
    }
    Box(Modifier.fillMaxWidth().background(bg).then(insetsModifier)) {
        Row(
            // 56dp 对照原 TitleBar/Toolbar minHeight=actionBarSize
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
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

/**
 * 顶栏动作区: 搜索图标(常显) + 溢出菜单(对照原 main_bookshelf.xml 项序)
 *
 * shared 版本: 不依赖 BaseBookshelfState, 改为接受 [BookshelfActionsCallbacks]
 * (app 端由 BookshelfState 实现 callbacks, 桌面端由调用方注入)
 */
@Composable
fun BookshelfActions(callbacks: BookshelfActionsCallbacks) {
    val colors = AppTheme.colors
    IconButton(onClick = callbacks.onOpenSearch) {
        Icon(
            painter = painterResource(Res.drawable.ic_search),
            contentDescription = stringResource(Res.string.search),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        // 刷新: 等价下拉刷新 (onRefresh), 无下拉手势的端 (如桌面) 走此入口
        ShelfMenuItem("refresh") { dismiss(); callbacks.onRefresh() }
        ShelfMenuItem("force_refresh_book") { dismiss(); callbacks.onRefreshShelf() }
        ShelfMenuItem("book_local") { dismiss(); callbacks.onAddLocalBook() }
        ShelfMenuItem("add_remote_book") { dismiss(); callbacks.onAddRemoteBook() }
        ShelfMenuItem("add_url") { dismiss(); callbacks.onShowAddBookByUrlAlert() }
        ShelfMenuItem("bookshelf_management") { dismiss(); callbacks.onOpenBookshelfManage() }
        ShelfMenuItem("group_manage") { dismiss(); callbacks.onShowGroupManage() }
        ShelfMenuItem("import_bookshelf") { dismiss(); callbacks.onImportBookshelf() }
        ShelfMenuItem("log") { dismiss(); callbacks.onShowAppLog() }
    }
}

/** 书架顶栏动作回调集合 (替代 app 端 BaseBookshelfState 的菜单动作方法) */
data class BookshelfActionsCallbacks(
    val onOpenSearch: () -> Unit = {},
    /** 刷新 (等价下拉刷新 onRefresh, 无下拉手势的端走菜单入口) */
    val onRefresh: () -> Unit = {},
    val onRefreshShelf: () -> Unit = {},
    val onAddLocalBook: () -> Unit = {},
    val onAddRemoteBook: () -> Unit = {},
    val onShowAddBookByUrlAlert: () -> Unit = {},
    val onOpenBookshelfManage: () -> Unit = {},
    val onShowGroupManage: () -> Unit = {},
    val onImportBookshelf: () -> Unit = {},
    val onShowAppLog: () -> Unit = {},
)

@Composable
private fun ShelfMenuItem(textKey: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(rememberString(textKey), color = AppTheme.colors.primaryText)
    }
}

// ---- 条目 ----

/** 复刻 BadgeView: 11sp 白字/最小 16dp/8dp 圆角, 高亮 accent 否则 darker_gray, 0 隐藏 */
@Composable
fun UnreadBadge(count: Int, highlight: Boolean, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = AppTheme.colors
    val bg = if (highlight) colors.accent else Color(0xAAAAAAAA)
    Box(
        modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(bg, DesignTokens.shapeDefault)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = Color.White, fontSize = 11.sp)
    }
}

/** 复刻 LabelsBar/AccentBgTextView: accent 底 2dp 圆角标签 */
@Composable
fun KindLabels(kinds: List<String>) {
    val colors = AppTheme.colors
    val textColor = if (isColorLight(colors.accent.toArgb())) Color.Black else Color.White
    Row {
        kinds.forEach { kind ->
            Text(
                text = kind,
                color = textColor,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .background(colors.accent, KindLabelShape)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/** 标签圆角: 预建一份, 免得每个标签每次重组都 new 一个 shape */
private val KindLabelShape = RoundedCornerShape(2.dp)

@Composable
fun ShelfRowIcon(painterKey: String) {
    Icon(
        painter = rememberPainter(painterKey),
        contentDescription = null,
        tint = AppTheme.colors.secondaryText,
        modifier = Modifier.size(18.dp).padding(horizontal = 2.dp),
    )
}

/**
 * 列表条目, 对照 item_bookshelf_list: 书名行(徽标/转圈)+作者行(更新时间)+分类+进度+最新+简介
 *
 * shared 版本: 封面改为 [coverSlot] 注入 (app 端用 ShelfCover AndroidView, 桌面端自定义);
 * 封面尺寸由 [coverSlot] 接收的 Modifier 决定 (列表档传 fillMaxHeight 触发 CoverImageView
 * 按高度+比例反算宽度, 对照原 XML iv_cover height=120dp + wrap_content width)
 *
 * 封面比例: 对照原 [ItemBookshelfListBinding.bindExploreCard] 的 `ivCover.coverRatio =
 * if (isVideoStyle) VIDEO else NOVEL`, 此处把 [isVideoStyle] 作为 isVideoCover 透传给 [coverSlot].
 */
@Composable
fun ShelfListItem(
    book: Book,
    isVideoStyle: Boolean,
    coverReloadTick: Int,
    refreshingUrls: Set<String>,
    showLastUpdateTime: Boolean,
    showKindIntro: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 搜索页书架命中区复用本条目时按原版 BookAdapter 覆盖书架配置门:
    // kind/intro/更新时间恒显, flHasNew.gone() 不画未读徽标
    forceShowKind: Boolean = false,
    forceShowIntro: Boolean = false,
    forceShowUpdateTime: Boolean = false,
    hideUnread: Boolean = false,
    coverSlot: @Composable (Book, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
    // 更新时间 Text 注入: 父项不感知 timeTick 心跳
    lastUpdateTextSlot: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val appConfig = remember { AppConfigProviders.get() }
    val coverHeight = remember(coverReloadTick, isVideoStyle) { shelfCoverHeightDp(isVideoStyle) }
    val refreshing = book.bookUrl in refreshingUrls
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box(Modifier.height(coverHeight.dp)) {
            coverSlot(book, Modifier.fillMaxHeight(), isVideoStyle, coverReloadTick)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .heightIn(min = coverHeight.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.name,
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (refreshing && !book.isLocal) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp),
                    )
                } else if (!hideUnread && appConfig.showUnread) {
                    val unread = remember(
                        book.durChapterIndex,
                        book.totalChapterNum
                    ) { book.getUnreadChapterNum() }
                    UnreadBadge(unread, book.lastCheckCount > 0)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShelfRowIcon("ic_author")
                Text(
                    text = remember(book.author) { book.getRealAuthor() },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                if (forceShowUpdateTime ||
                    (showLastUpdateTime && appConfig.showLastUpdateTime && !book.isLocal)
                ) {
                    lastUpdateTextSlot()
                }
            }
            if (forceShowKind || (showKindIntro && appConfig.bookshelfListShowKind)) {
                // 正则切分 + 净化, 别每次重组重算
                val kinds = remember(book.kind, book.wordCount) { book.getKindList() }
                if (kinds.isNotEmpty()) KindLabels(kinds)
            }
            if (!book.durChapterTitle.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShelfRowIcon("ic_history")
                    Text(
                        text = book.durChapterTitle.toString(),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShelfRowIcon("ic_book_last")
                    Text(
                        text = book.latestChapterTitle.toString(),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (forceShowIntro || (showKindIntro && appConfig.bookshelfListShowIntro)) {
                val intro = remember(book.intro, book.author) { book.getDisplayIntro() }
                if (!intro.isNullOrBlank()) {
                    Text(
                        text = intro,
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = appConfig.bookshelfListIntroLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/** 更新时间 Text: derivedStateOf 订阅心跳, 仅本 Composable 在 timeTick 变化时重组 */
@Composable
fun ShelfLastUpdateText(time: Long, timeTick: State<Int>) {
    val colors = AppTheme.colors
    val timeAgo by remember(time) {
        derivedStateOf {
            timeTick.value
            time.toTimeAgo()
        }
    }
    Text(timeAgo, color = colors.secondaryText, fontSize = 13.sp, maxLines = 1)
}

/** 网格条目, 对照 item_bookshelf_grid: 封面(12dp 边距)+两行书名, 徽标/转圈叠加右上 */
@Composable
fun ShelfGridItem(
    book: Book,
    coverReloadTick: Int,
    refreshingUrls: Set<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSlot: @Composable (Book, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
) {
    val colors = AppTheme.colors
    val appConfig = remember { AppConfigProviders.get() }
    val refreshing = book.bookUrl in refreshingUrls
    Box(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.fillMaxWidth()) {
            // 封面 Box: 宽度填满 (减 12dp 左右内边距), 对照原 XML iv_cover match_parent + 12dp margin
            // 无 cover URL 时仍渲染封面 Box (走占位), 对齐 app 端 CoverImageView 无 path 也显示默认封面
            Box(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                // 对照原 bindGridCard: ivCover.coverRatio = NOVEL (恒)
                coverSlot(book, Modifier.fillMaxWidth(), false, coverReloadTick)
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
        // 徽标/转圈对照原 XML: 约束到 parent 右上角 (bv_unread/rl_loading 均 layout_constraintRight_toRightOf=parent + Top_toTopOf=parent),
        // 不放进封面 Box (那有 12dp padding 会把徽标推到 16dp 处); bv_unread marginTop/End=4dp, rl_loading 无 margin
        if (refreshing && !book.isLocal) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
            )
        } else if (appConfig.showUnread) {
            val unread =
                remember(book.durChapterIndex, book.totalChapterNum) { book.getUnreadChapterNum() }
            UnreadBadge(
                count = unread,
                highlight = book.lastCheckCount > 0,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
            )
        }
    }
}

/** 视频卡片, 对照 item_explore_video/bindVideoCard: 16:9 封面+粗体标题+分类+作者, 无徽标 */
@Composable
fun ShelfVideoItem(
    book: Book,
    coverReloadTick: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSlot: @Composable (Book, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        // 无 cover URL 时仍渲染封面 Box (走占位), 对齐 app 端 CoverImageView 无 path 也显示默认封面
        Box(Modifier.fillMaxWidth()) {
            // 对照原 bindVideoCard: ivCover.coverRatio = VIDEO (恒)
            coverSlot(book, Modifier.fillMaxWidth(), true, coverReloadTick)
        }
        Text(
            text = book.name,
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        // 正则切分 + 净化 / 作者拆分, 别每次重组重算
        val kinds = remember(book.kind, book.wordCount) { book.getKindList() }
        if (kinds.isNotEmpty()) {
            Box(Modifier.padding(top = 2.dp)) { KindLabels(kinds) }
        }
        val author = remember(book.author) { book.getRealAuthor() }
        if (author.isNotBlank()) {
            Text(
                text = author,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ---- style2 分组(文件夹)条目: 单图封面 + 组名, 对照各 tier 的 GroupViewHolder ----

@Composable
fun GroupListItem(
    group: BookGroup,
    isVideoStyle: Boolean,
    coverReloadTick: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSlot: @Composable (BookGroup, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
) {
    val colors = AppTheme.colors
    val coverHeight = remember(coverReloadTick, isVideoStyle) { shelfCoverHeightDp(isVideoStyle) }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(coverHeight.dp)) {
            // 对照原 style2 BooksAdapterList.GroupViewHolder: applyCoverHeight(isVideoStyle) 收窄高度,
            // 但不设 coverRatio (保持默认 NOVEL); 故 isVideoCover 恒 false
            coverSlot(group, Modifier.fillMaxHeight(), false, coverReloadTick)
        }
        Text(
            text = group.groupName,
            color = colors.primaryText,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
fun GroupGridItem(
    group: BookGroup,
    coverReloadTick: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSlot: @Composable (BookGroup, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
        ) {
            // 对照原 style2 BooksAdapterGrid.GroupViewHolder: 不设 coverRatio (保持默认 NOVEL)
            coverSlot(group, Modifier.fillMaxWidth(), false, coverReloadTick)
        }
        Text(
            text = group.groupName,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
fun GroupVideoItem(
    group: BookGroup,
    coverReloadTick: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSlot: @Composable (BookGroup, Modifier, isVideoCover: Boolean, coverReloadTick: Int) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        // 无 cover URL 时仍渲染封面 Box (走占位), 对齐 app 端无 path 也显示默认封面
        Box(Modifier.fillMaxWidth()) {
            // 对照原 style2 BooksAdapterVideo.GroupViewHolder: ivCover.coverRatio = VIDEO
            coverSlot(group, Modifier.fillMaxWidth(), true, coverReloadTick)
        }
        Text(
            text = group.groupName,
            color = colors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---- 辅助: 亮度计算 (替代 app 端 ColorUtils.isColorLight) ----

/**
 * 简化版亮度判断: 与 `io.legado.app.utils.ColorUtils.isColorLight` 行为一致。
 *
 * 公式: `0.299R + 0.587G + 0.114B >= 0.5` (NTSC luminance, 与 ColorUtils 一致)
 */
private fun isColorLight(color: Int): Boolean {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return (0.299f * r + 0.587f * g + 0.114f * b) >= 0.5f
}
