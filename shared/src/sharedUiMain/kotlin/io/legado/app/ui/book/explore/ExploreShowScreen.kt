package io.legado.app.ui.book.explore

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.bookshelf.KindLabels
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_star            已收藏图标
 *   - ic_star_border     未收藏图标
 *   - ic_layout_list     列表布局图标
 *   - ic_layout_video    视频布局图标
 *   - ic_author          作者图标 (已存在, 复用 BookshelfComposablesShared)
 *   - ic_book_last       最新章节图标 (已存在, 复用 BookshelfComposablesShared)
 *   - ic_bookmark        网格 tier 书架徽标
 *
 * String key (string):
 *   - discovery          发现页标题
 *   - in_favorites       已收藏 contentDescription
 *   - out_favorites      未收藏 contentDescription
 *   - switchLayout       布局切换 contentDescription
 *   - source_filter_rule 源过滤规则菜单
 *   - bottom_line        到底文案
 *   - empty              空文案
 *   - error_load_msg     错误加载文案 (带 %s 格式参数, "点击查看详情")
 *   - intro_show_null    简介为空时回退文案 (替 trimIntro 的 context.getString)
 *
 * L3 不可下沉项 (保留 app 端, 通过 slot 注入):
 *   - ShelfCover (Glide + CoverImageView, 走 AppConfig.loadCoverOnlyWifi)
 *     → coverSlot: @Composable (book, inBookshelf, isVideoStyle, modifier) -> Unit
 *   - AndroidView { LinearLayout + setUpExploreOptions(viewModel.exploreOptions) }
 *     → optionsRowSlot: @Composable () -> Unit (内部读 state.optionsVersion + 调 actions.onExploreOptionChanged)
 *   - AndroidView { ItemExploreVideoBinding } (视频卡 ViewBinding)
 *     → videoItemSlot: @Composable (book, inBookshelf, onClick, onLongClick) -> Unit
 *
 *   注: AppConfig.bookshelfCoverHeight 已在 shared AppConfigAccessor 暴露, 直接读;
 *       AppConfig.loadCoverOnlyWifi 仅 ShelfCover 用 → 由 coverSlot 内部桥接。
 *
 * Color 复用:
 *   - md_green_600 (#43A047, 书架内绿点) → 内联 Color(0xFF43A047), 复刻 R.color.md_green_600
 */

/** 书架内绿点颜色 (复刻 R.color.md_green_600 = #43A047, Material Green 600) */
private val InBookshelfDotColor = Color(0xFF43A047)

/**
 * 发现结果页展示状态 (KMP 共享)。
 *
 * app 端 `ExploreShowActivity` 在 `Content()` 内将自己的 `mutableStateOf` 字段打包为
 * 本 data class 传入; 桌面/iOS 端可同样构造本类复用 [ExploreShowScreen]。
 *
 * 字段语义对照 app 端原 `ExploreShowActivity` 同名字段:
 * - [title] / [books] / [exploreStyle] / [isFavorite] / [footerLoading] / [footerText]:
 *   与 Activity 同名字段一一对应
 * - [bookshelfVersion]: 书架增删时递增驱动重组刷新绿点/徽标
 * - [optionsVersion]: 参数 chip 结构变化时递增驱动 optionsRowSlot 重组重绑
 * - [scrollTopEpoch]: 标题栏点击回顶信号, >0 时触发列表 animateScrollToItem(0)
 */
data class ExploreShowUiState(
    val title: String,
    val books: List<SearchBook>,
    val exploreStyle: Int,
    val isFavorite: Boolean,
    val bookshelfVersion: Int,
    val optionsVersion: Int,
    val scrollTopEpoch: Int,
    val footerLoading: Boolean,
    val footerText: String?,
)

/**
 * 发现结果页用户交互回调 (KMP 共享)。
 *
 * app 端 `ExploreShowActivity` 实现本接口 (已有同名方法直接 `override` 别名桥接);
 * 桌面/iOS 端可自行实现接入各自平台行为。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onBookClick] 跳详情/视频播放、[onShowColumnPicker] 弹 numberPicker)
 * 由 app 端实现内部桥接到 Activity。
 */
interface ExploreShowUiActions {
    /** 返回 (标题栏返回箭头 / 系统返回手势) */
    fun onBack()

    /** 标题栏点击 (对照原 toolbar 点击回顶) */
    fun onTitleClick()

    /** 切换收藏/取消收藏 */
    fun onToggleFavorite()

    /** 切换布局 (list/grid/video) */
    fun onSwitchLayout()

    /** 长按布局图标弹列数选择 */
    fun onShowColumnPicker()

    /** 溢出菜单 - 显示源过滤规则 */
    fun onShowSourceFilterRule()

    /** footer 点击 (错误时弹详情+重试, 否则触发加载下一页) */
    fun onFooterClick()

    /** 触底预加载 (对齐原 findLastVisibleItemPosition >= itemCount - 2) */
    fun onScrollToBottom()

    /** 书籍点击/长按 (补 notShelf type 后进详情, 宿主实现跳转) */
    fun onBookClick(book: SearchBook, longClick: Boolean)

    /** 查询书籍是否在书架 (绿点/徽标渲染用) */
    fun isInBookshelf(book: SearchBook): Boolean

    /** 参数 chip 变化 (宿主清空 books + 重新 explore) */
    fun onExploreOptionChanged()
}

/**
 * 发现结果页 Composable (KMP 版, 下沉自 app 端 ExploreShowScreen.kt)。
 *
 * 标题栏 (收藏/布局切换/溢出) + 参数 chip 行 + 三 tier 结果列表 + 触底加载 footer。
 * 三 tier 条目与 SearchScreen 同构 (对照 item_bookshelf_list/grid、item_explore_video)。
 *
 * @param state 展示状态
 * @param actions 交互回调
 * @param optionsRowSlot 参数 chip 行 (L3: AndroidView + LinearLayout + setUpExploreOptions)
 *   - 内部自行读 state.optionsVersion + 调 actions.onExploreOptionChanged
 * @param videoItemSlot 视频卡 (L3: ItemExploreVideoBinding ViewBinding)
 *   - 调用方传入 (book, inBookshelf, onClick, onLongClick)
 * @param coverSlot 封面 (L3: ShelfCover + Glide + CoverImageView)
 *   - 调用方传入 (book, inBookshelf, isVideoStyle, modifier) 已含尺寸约束
 */
@Composable
fun ExploreShowScreen(
    state: ExploreShowUiState,
    actions: ExploreShowUiActions,
    optionsRowSlot: @Composable () -> Unit,
    videoItemSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
    coverSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        isVideoStyle: Boolean,
        modifier: Modifier,
    ) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = state.title,
            onBack = actions::onBack,
            // 对齐原 toolbar 点击回顶
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { actions.onTitleClick() },
            actions = { ExploreActions(state, actions) },
        )
        optionsRowSlot()
        ResultArea(state, actions, videoItemSlot, coverSlot, Modifier.weight(1f))
    }
}

// ===== 菜单 (对齐 explore_bar.xml) =====

@Composable
private fun ExploreActions(state: ExploreShowUiState, actions: ExploreShowUiActions) {
    val colors = AppTheme.colors
    IconButton(onClick = actions::onToggleFavorite) {
        Icon(
            painter = rememberPainter(if (state.isFavorite) "ic_star" else "ic_star_border"),
            contentDescription = rememberString(
                if (state.isFavorite) "in_favorites" else "out_favorites"
            ),
            tint = colors.primaryText,
        )
    }
    // 长按弹列数选择 (对齐原 iconItemOnLongClick)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = actions::onSwitchLayout,
                onLongClick = actions::onShowColumnPicker,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(
                if (BookSource.exploreStyleIsVideo(state.exploreStyle)) {
                    "ic_layout_list"
                } else {
                    "ic_layout_video"
                }
            ),
            contentDescription = rememberString("switchLayout"),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        DropdownMenuItem(
            onClick = { dismiss(); actions.onShowSourceFilterRule() },
        ) {
            Text(rememberString("source_filter_rule"), color = colors.primaryText)
        }
    }
}

// ===== 结果列表: tier 选择对齐原 initAdapter 的 exploreStyle 魔数 =====

@Composable
private fun ResultArea(
    state: ExploreShowUiState,
    actions: ExploreShowUiActions,
    videoItemSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
    coverSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        isVideoStyle: Boolean,
        modifier: Modifier,
    ) -> Unit,
    modifier: Modifier,
) {
    val books = state.books
    @Suppress("UNUSED_EXPRESSION") state.bookshelfVersion // 书架增删时重组刷新绿点/徽标
    val style = state.exploreStyle
    val isVideo = BookSource.exploreStyleIsVideo(style)
    val cols = BookSource.exploreStyleCols(style)
    val spanCount = if (cols <= 1) 1 else cols
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    if (spanCount == 1) {
        val state_ = rememberLazyListState()
        LaunchedEffect(state.scrollTopEpoch) {
            if (state.scrollTopEpoch > 0) state_.animateScrollToItem(0)
        }
        // 触底预加载 (对齐原 findLastVisibleItemPosition >= itemCount - 2)
        LaunchedEffect(state_) {
            snapshotFlow {
                state_.layoutInfo.totalItemsCount to
                    (state_.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)
            }.collect { (total, last) ->
                if (total > 0 && last >= total - 2) actions.onScrollToBottom()
            }
        }
        LazyColumn(state = state_, modifier = modifier.fillMaxWidth(), contentPadding = navPad) {
            items(books, key = { it.bookUrl }) { book ->
                if (cols == 1 && isVideo) {
                    videoItemSlot(
                        book,
                        actions.isInBookshelf(book),
                        { actions.onBookClick(book, false) },
                        { actions.onBookClick(book, true) },
                    )
                } else {
                    ExploreListItem(
                        book = book,
                        isVideoStyle = cols == 0 && isVideo,
                        inBookshelf = actions.isInBookshelf(book),
                        coverSlot = coverSlot,
                        onClick = { actions.onBookClick(book, false) },
                        onLongClick = { actions.onBookClick(book, true) },
                    )
                }
            }
            // footer 不设 key: 首屏仅 footer 可见时, keyed 锚定会让视口跟随 footer 被顶到列表末尾;
            // 无 key 走位置锚定, 数据到达后停在顶部 (对齐 RecyclerView)
            item { LoadMoreFooter(state, actions) }
        }
    } else {
        val state_ = rememberLazyGridState()
        LaunchedEffect(state.scrollTopEpoch) {
            if (state.scrollTopEpoch > 0) state_.animateScrollToItem(0)
        }
        LaunchedEffect(state_) {
            snapshotFlow {
                state_.layoutInfo.totalItemsCount to
                    (state_.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)
            }.collect { (total, last) ->
                if (total > 0 && last >= total - 2) actions.onScrollToBottom()
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(spanCount),
            state = state_,
            modifier = modifier.fillMaxWidth(),
            contentPadding = navPad,
        ) {
            items(books, key = { it.bookUrl }) { book ->
                if (isVideo) {
                    videoItemSlot(
                        book,
                        actions.isInBookshelf(book),
                        { actions.onBookClick(book, false) },
                        { actions.onBookClick(book, true) },
                    )
                } else {
                    ExploreGridItem(
                        book = book,
                        inBookshelf = actions.isInBookshelf(book),
                        coverSlot = coverSlot,
                        onClick = { actions.onBookClick(book, false) },
                        onLongClick = { actions.onBookClick(book, true) },
                    )
                }
            }
            // footer 不设 key: 同上, 防首载后视口跟随 footer 跳底
            item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreFooter(state, actions) }
        }
    }
}

/** 对照 LoadMoreView 三态: 加载转圈 / 到底文案 / 错误点击看详情+重试 */
@Composable
private fun LoadMoreFooter(state: ExploreShowUiState, actions: ExploreShowUiActions) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { actions.onFooterClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (state.footerLoading) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(8.dp)
                    .size(36.dp),
            )
        } else {
            state.footerText?.let {
                Text(
                    // 复刻 XML singleLine 语义: 渲染为空格单行, 而非 maxLines=1 的截断
                    text = it.replace("\n", " "),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

// ===== 条目 (与 SearchScreen 三 tier 同构) =====

/** List tier, 对照 item_bookshelf_list/bindExploreCard:
 *  封面 + 书名行(绿点) + 作者 + 分类 + 最新 + 简介 */
@Composable
private fun ExploreListItem(
    book: SearchBook,
    isVideoStyle: Boolean,
    inBookshelf: Boolean,
    coverSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        isVideoStyle: Boolean,
        modifier: Modifier,
    ) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // 0.75f: 16:9 视频封面按 3/4 高度收窄, 对齐 applyCoverHeight
    val coverHeight = AppConfigProviders.get().bookshelfCoverHeight
        .let { if (isVideoStyle) (it * 0.75f).toInt() else it }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        coverSlot(
            book,
            inBookshelf,
            isVideoStyle,
            Modifier.height(coverHeight.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .heightIn(min = coverHeight.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inBookshelf) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(InBookshelfDotColor, CircleShape),
                    )
                }
                Text(
                    text = book.name,
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListRowIcon("ic_author")
                Text(
                    text = book.getRealAuthor(),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val kinds = book.getKindList()
            if (kinds.isNotEmpty()) KindLabels(kinds)
            if (!book.latestChapterTitle.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ListRowIcon("ic_book_last")
                    Text(
                        text = book.latestChapterTitle.toString(),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // trimIntro 内联: intro?.trim() 非空则展示, 否则回退 intro_show_null 文案
            // 对照 app 端 SearchBook.trimIntro(context): 始终渲染 (空简介显示占位文案)
            val intro = book.intro?.trim()?.takeIf { it.isNotEmpty() }
                ?: rememberString("intro_show_null")
            Text(
                text = intro,
                color = colors.secondaryText,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun ListRowIcon(key: String) {
    Icon(
        painter = rememberPainter(key),
        contentDescription = null,
        tint = AppTheme.colors.secondaryText,
        modifier = Modifier
            .size(18.dp)
            .padding(horizontal = 2.dp),
    )
}

/** Grid tier, 对照 item_bookshelf_grid/bindGridCard: 封面 + 两行书名, 书架书签叠加左上 */
@Composable
private fun ExploreGridItem(
    book: SearchBook,
    inBookshelf: Boolean,
    coverSlot: @Composable (
        book: SearchBook,
        inBookshelf: Boolean,
        isVideoStyle: Boolean,
        modifier: Modifier,
    ) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.fillMaxWidth()) {
            coverSlot(
                book,
                inBookshelf,
                false,
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp),
            )
            Text(
                text = book.name,
                color = AppTheme.colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        if (inBookshelf) {
            Image(
                painter = rememberPainter("ic_bookmark"),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp)
                    .fillMaxWidth(0.22f)
                    .aspectRatio(1f),
            )
        }
    }
}
