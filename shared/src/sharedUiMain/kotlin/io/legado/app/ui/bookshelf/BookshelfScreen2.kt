package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf
import org.jetbrains.compose.resources.stringResource

/**
 * 书架样式2 (KMP 版, 对照 app 端 `style2/BookshelfFragment2`)。
 *
 * 与样式1 (分组 tab + HorizontalPager) 的区别: 单个列表 + 分组下钻。
 * 根级 (groupId = [BookGroup.IdRoot]) 混装"分组条目 + 未分组书籍" (对照 getItems:
 * bookGroups + books), 点分组进入该分组后只显示书籍, 系统返回键回根级。
 *
 * 条目渲染复用 [ShelfBooksContent] 的 BookGroup 分支 (GroupListItem/GroupGridItem/GroupVideoItem),
 * 布局档位与样式1 同源 ([rememberBookshelfLayoutSpec])。
 *
 * @param scrollState 外部注入的滚动状态, 宿主端用于 tab 双击滚顶 (对照 gotoTop)
 * @param gotoTopTick 滚顶信号, 宿主端每次 tab 双击 +1 (对照 BookshelfFragment2.gotoTop)
 * @param configTick 配置变更信号, bump 后重读 bookshelfShowGroupCount (对照 BOOKSHELF_REFRESH)
 */
@OptIn(FlowPreview::class)
@Composable
internal fun BookshelfScreen2(
    viewModel: BookshelfViewModel,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    tier: BookshelfTier? = null,
    coverSlot: (@Composable (Book, Modifier, Boolean) -> Unit)? = null,
    scrollState: ShelfScrollState = remember { ShelfScrollState() },
    gotoTopTick: Int = 0,
    configTick: Int = 0,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val appConfig = remember { AppConfigProviders.get() }
    val bookCoverSlot = coverSlot ?: LocalBookCoverSlot.current
    val groupCoverSlot = LocalGroupCoverSlot.current
    val layoutSpec = rememberBookshelfLayoutSpec(tier)
    // 标题是否拼接书籍数量 (对照 app 端 AppConfig.bookshelfShowGroupCount)
    val showGroupCount = remember(configTick) { appConfig.bookshelfShowGroupCount }
    val groups by viewModel.bookGroups.collectAsState()
    val refreshingUrls by viewModel.refreshingUrls.collectAsState()

    // 当前层级: IdRoot=根级, 其他=已进入的分组 (对照 BookshelfFragment2.groupId)
    var groupId by remember { mutableStateOf(BookGroup.IdRoot) }
    // 排序配置变更 (BOOKSHELF_REFRESH) 时重建 flow 让 sortOf 重读配置 (对照 upSort → initBooksData)
    var sortTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).collect { sortTick++ }
    }
    val booksFlow = remember(groupId, sortTick) { viewModel.booksByGroup(groupId) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    LaunchedEffect(booksFlow) {
        // debounce(100) 对照 BookshelfFragment2.initBooksData
        booksFlow.debounce(100).collect { books = it }
    }

    // 对照 getItems(): 根级 = 分组 + 未分组书籍, 分组内 = 只有书籍
    val items: List<Any> = remember(groupId, groups, books) {
        if (groupId != BookGroup.IdRoot) books else groups + books
    }
    // 对照 applyGroupState: 分组内取分组名, 根级取"书架"; 下拉刷新跟随分组开关且空列表禁用
    val group = groups.find { it.groupId == groupId }
    val rootTitle = stringResource(Res.string.bookshelf)
    val baseTitle = group?.groupName ?: rootTitle
    val title = if (showGroupCount) "$baseTitle (${books.size})" else baseTitle
    val refreshEnabled = (group?.enableRefresh ?: true) && items.isNotEmpty()

    // 对照 BookshelfFragment2.back(): 分组内消费返回事件回根级, 根级不消费 (交给宿主双击退出)
    PlatformBackHandler(enabled = groupId != BookGroup.IdRoot) { groupId = BookGroup.IdRoot }

    // tab 双击滚顶 (对照 BookshelfFragment2.gotoTop), 档位与 layoutSpec 同源
    LaunchedEffect(gotoTopTick) {
        if (gotoTopTick == 0) return@LaunchedEffect
        scrollState.gotoTop(layoutSpec.tier, eInk)
    }

    Column(modifier.fillMaxSize().background(colors.background)) {
        BookshelfTopBarContainer(actions) {
            BookshelfTitleText(title)
        }
        ShelfBooksContent(
            items = items,
            spec = layoutSpec,
            scroll = scrollState,
            refreshEnabled = refreshEnabled,
            // 对照 refreshLayout.setOnRefreshListener: activityViewModel.upToc(books)
            onRefresh = { viewModel.upToc(books) },
            coverReloadTick = 0,
            refreshingUrls = refreshingUrls,
            onBookClick = onBookClick,
            onBookLongClick = onBookLongClick,
            showLastUpdateTime = true,
            showKindIntro = true,
            bookCoverSlot = bookCoverSlot,
            groupCoverSlot = groupCoverSlot,
            // 对照 onItemClick(BookGroup): 进入该分组; onItemLongClick(BookGroup): GroupEditDialog
            onGroupClick = { groupId = it.groupId },
            onGroupLongClick = onGroupLongClick,
        )
    }
}

/**
 * 分组封面 (对照 style2 各 GroupViewHolder 的 `ivCover.load(group.cover)`)。
 *
 * 走 [BookImageLoaders] 加载 [BookGroup.cover], 无 cover / 加载失败 / 未注册 loader /
 * [AppConfigAccessor.useDefaultCover] 时回退组名首字占位 (对齐 [SharedBookCover])。
 */
@Composable
fun SharedGroupCover(
    group: BookGroup,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
) {
    val cover = group.cover
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过加载, 直接走占位 (对照 app 端 CoverImageView.load 行为)
    val useDefaultCover = remember { AppConfigProviders.get().useDefaultCover }
    var bitmap by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    // 显示尺寸走 StateFlow (量尺寸不触发重组), 按尺寸降采样解码而非解原图
    val displaySize = remember { MutableStateFlow(IntSize.Zero) }
    LaunchedEffect(cover, loader, useDefaultCover) {
        if (useDefaultCover || cover.isNullOrBlank() || loader == null) return@LaunchedEffect
        displaySize.collect { size ->
            if (size.width <= 0 || size.height <= 0) return@collect
            bitmap = loader.loadImageOrNull(cover, null, size.width, size.height)
        }
    }
    val aspectRatio = if (isVideoCover) VIDEO_COVER_RATIO else NOVEL_COVER_RATIO
    val resolvedModifier = modifier.fillMaxWidth().aspectRatio(aspectRatio)
        .clip(DesignTokens.shapeSm)
        .onSizeChanged { displaySize.value = it }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = group.groupName,
            modifier = resolvedModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        val accent = AppTheme.colors.accent
        val textColor =
            if (accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f >= 0.5f) {
                Color(0xDE000000)
            } else {
                Color(0xFFFFFFFF)
            }
        Box(
            resolvedModifier.background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (group.groupName.firstOrNull() ?: '?').toString(),
                color = textColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 分组封面渲染 slot 的 CompositionLocal: 默认兜底 [SharedGroupCover]。
 *
 * 与 [LocalBookCoverSlot] 对称, 宿主端可用 `CompositionLocalProvider` 覆盖注入平台实现
 * (如 app 端 ShelfCover 走 CoverImageView)。
 */
val LocalGroupCoverSlot =
    staticCompositionLocalOf<@Composable (BookGroup, Modifier, Boolean) -> Unit> {
        @Composable { group, modifier, isVideoCover ->
            SharedGroupCover(group, modifier, isVideoCover)
        }
    }
