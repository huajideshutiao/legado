package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.flow.MutableStateFlow
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf
import legado.shared.generated.resources.image_cover_default
import org.jetbrains.compose.resources.painterResource
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
@Composable
internal fun BookshelfScreen2(
    viewModel: BookshelfViewModel,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    tier: BookshelfTier? = null,
    coverSlot: (@Composable (Book, Modifier, Boolean, Int) -> Unit)? = null,
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
    // 单一数据源: 根级=IdRoot 未分组书, 分组内=该组书 (读 VM 缓存切片, 无独立 Room 流)
    val booksCache by viewModel.booksCache.collectAsState()

    // 当前层级: IdRoot=根级, 其他=已进入的分组 (对照 BookshelfFragment2.groupId)
    var groupId by remember { mutableStateOf(BookGroup.IdRoot) }
    // 层级变化驱动 VM 切换当前分组 (排序配置由 VM.upSort 重启时重读);
    // 样式2 是单页导航: 切换时释放旧分组流 (不持有历史分组, 对齐原版单流重启)
    LaunchedEffect(groupId) { viewModel.selectGroup(groupId) }
    DisposableEffect(groupId) {
        onDispose { viewModel.releaseGroupFlow(groupId) }
    }
    val books = booksCache[groupId].orEmpty()

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
            coverReloadTick = configTick,
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
 * 走 [BookImageLoaders] 加载 [BookGroup.cover], 行为与书架书籍封面 [SharedBookCover] 对齐
 * (即"以书架封面行为为准"):
 * - 真封面经 [BookImageLoaders.loadCoverOrNull] 落持久区, 与书架书一致
 * - 无 cover / 加载失败 / [AppConfigAccessor.useDefaultCover] / 未注册 loader 时,
 *   走默认封面链: 用户图集烘焙图 (seed=组名, 稳定选图) → 内置 `image_cover_default`;
 *   不再渲染组名首字色块 (原版分组封面 name=null, 默认封面上也不叠书名)
 * - 比例: 对照书架分组条目, 列表/网格恒 NOVEL 3:4, 视频档 16:9
 */
@Composable
fun SharedGroupCover(
    group: BookGroup,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
) {
    val cover = group.cover
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过加载, 直接走默认封面链 (对照 app 端 CoverImageView.load 行为);
    // 每次组合读 prefs (不 remember): 配置变更后条目重组时读到最新值
    val useDefaultCover = AppConfigProviders.get().useDefaultCover
    // 位图 + 是否默认封面合成一个 state (对齐 SharedBookCover, 一次加载只引发一次重组)
    var coverState by remember(cover) { mutableStateOf(NoCoverBitmap) }
    // 仅以首次有效布局尺寸降采样；窗口 resize 不重新发起图片请求。
    val displaySize = remember { MutableStateFlow(IntSize.Zero) }
    LaunchedEffect(cover, loader, useDefaultCover) {
        if (loader == null) return@LaunchedEffect
        val decodeSize = firstValidCoverDecodeSize(displaySize)
        val ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL

        // 默认封面链要读 prefs + 解 JSON, 挪到真用得上时才算 (有封面的分组零开销)
        suspend fun loadDefault() {
            // seed = 组名 (即分组的"书名", 对照书架书 seed=书名 稳定选图), 不回落封面路径
            val path = defaultCoverFilePath(
                seed = group.groupName,
                ratio = ratio,
            )
            val bmp = if (path == null) {
                null
            } else {
                loader.loadImageOrNull(path, null, decodeSize.width, decodeSize.height)
            }
            coverState = if (bmp == null) NoCoverBitmap else CoverBitmap(bmp, true)
        }
        if (useDefaultCover || cover.isNullOrBlank()) {
            loadDefault()
            return@LaunchedEffect
        }
        // 与书架书同款: 真封面落持久磁盘分区, 清缓存不会把书架/分组清成默认封面
        val bmp = loader.loadCoverOrNull(cover, null, decodeSize.width, decodeSize.height)
        if (bmp != null) {
            coverState = CoverBitmap(bmp, false)
        } else loadDefault()
    }
    val aspectRatio = if (isVideoCover) VIDEO_COVER_RATIO else NOVEL_COVER_RATIO
    val resolvedModifier = modifier.fillMaxWidth().aspectRatio(aspectRatio)
        .clip(DesignTokens.shapeSm)
        .onSizeChanged { displaySize.value = it }
    val bmp = coverState.bitmap
    if (bmp != null && !coverState.isDefault) {
        Image(
            bitmap = bmp,
            contentDescription = group.groupName,
            modifier = resolvedModifier,
            contentScale = ContentScale.Crop,
        )
        return
    }
    // 默认封面: 用户图集烘焙图 (已按 ratio 裁好) 或内置 image_cover_default
    // (原 .9 图, 这里当普通图拉伸, 对齐 SharedBookCover); 分组无书名, 不叠竖排文字
    Box(resolvedModifier) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = group.groupName,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                painter = painterResource(Res.drawable.image_cover_default),
                contentDescription = group.groupName,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
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
    staticCompositionLocalOf<@Composable (BookGroup, Modifier, Boolean, Int) -> Unit> {
        @Composable { group, modifier, isVideoCover, _ ->
            SharedGroupCover(group, modifier, isVideoCover)
        }
    }
