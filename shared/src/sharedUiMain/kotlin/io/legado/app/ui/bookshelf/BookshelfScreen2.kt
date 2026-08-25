package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.rememberUpdatedState
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
import io.legado.app.model.defaultCoverDisplayPath
import io.legado.app.ui.compose.component.DefaultCoverNineImage
import io.legado.app.ui.compose.component.NinePatchImageOrImage
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.flow.MutableStateFlow
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
    // 主界面是否栈顶 (对照原版: 仅主界面可见收到返回键时才调 BookshelfFragment2.back())。
    // 主界面压栈 (阅读器/详情/WebView 等打开) 时为 false, 分组返回拦截随之失效
    isRootTop: Boolean = true,
) {
    val eInk = LocalEInk.current
    val appConfig = remember { AppConfigProviders.get() }
    val bookCoverSlot = coverSlot ?: LocalBookCoverSlot.current
    // LocalGroupCoverSlot 组合期读取值随宿主重组变化, 稳定化引用避免条目层全量重组合
    val currentGroupCoverSlot = rememberUpdatedState(LocalGroupCoverSlot.current)
    val groupCoverSlot: @Composable (BookGroup, Modifier, Boolean, Int) -> Unit = remember {
        { group, m, isVideoCover, tick ->
            currentGroupCoverSlot.value(group, m, isVideoCover, tick)
        }
    }
    val layoutSpec = rememberBookshelfLayoutSpec(tier)
    // 标题是否拼接书籍数量 (对照 app 端 AppConfig.bookshelfShowGroupCount)
    val showGroupCount = remember(configTick) { appConfig.bookshelfShowGroupCount }
    val groups by viewModel.bookGroups.collectAsState()
    val refreshingUrls by viewModel.refreshingUrls.collectAsState()
    val engineUpTocUrls by viewModel.engineUpTocUrls.collectAsState()
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
    // 稳定化透传回调: 引用恒定, 避免本屏重组 → 条目层全量重组合 (同 BookshelfScreen 策略);
    // onRefresh 内部经 rememberUpdatedState 读最新 books (捕获旧引用会刷旧书)
    val currentBooks = rememberUpdatedState(books)
    val stableOnRefresh: () -> Unit = remember { { viewModel.upToc(currentBooks.value) } }
    // groupId 是 remember 的 delegate 引用, lambda 捕获后恒定, 可一次创建
    val stableOnGroupClick: (BookGroup) -> Unit = remember { { groupId = it.groupId } }

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

    // 对照 BookshelfFragment2.back(): 分组内消费返回事件回根级, 根级不消费 (交给宿主双击退出)。
    // isRootTop 门控: 栈内页面保持同一 Composition, 压栈页面打开时不可见书架页仍注册着本拦截器;
    // 若无门控, 从分组打开的页面 (无自身拦截器的详情/已入架书阅读器等) 第一下返回会被静默消费
    // (分组重置回根, 画面无变化), 第二下才真正退出——表现为"返回键要按两次"
    PlatformBackHandler(enabled = groupId != BookGroup.IdRoot && isRootTop) {
        groupId = BookGroup.IdRoot
    }

    // tab 双击滚顶 (对照 BookshelfFragment2.gotoTop), 档位与 layoutSpec 同源
    LaunchedEffect(gotoTopTick) {
        if (gotoTopTick == 0) return@LaunchedEffect
        scrollState.gotoTop(layoutSpec.tier, eInk)
    }

    Column(modifier.fillMaxSize()) {
        BookshelfTopBarContainer(actions) {
            BookshelfTitleText(title)
        }
        ShelfBooksContent(
            items = items,
            spec = layoutSpec,
            scroll = scrollState,
            refreshEnabled = refreshEnabled,
            // 对照 refreshLayout.setOnRefreshListener: activityViewModel.upToc(books)
            onRefresh = stableOnRefresh,
            coverReloadTick = configTick,
            refreshingUrls = refreshingUrls,
            engineUpdatingUrls = engineUpTocUrls,
            onBookClick = onBookClick,
            onBookLongClick = onBookLongClick,
            showLastUpdateTime = true,
            showKindIntro = true,
            bookCoverSlot = bookCoverSlot,
            groupCoverSlot = groupCoverSlot,
            // 对照 onItemClick(BookGroup): 进入该分组; onItemLongClick(BookGroup): GroupEditDialog
            onGroupClick = stableOnGroupClick,
            onGroupLongClick = onGroupLongClick,
        )
    }
}

/**
 * 分组封面 (对照 style2 各 GroupViewHolder 的 `ivCover.load(group.cover)`)。
 *
 * 走 [BookImageLoaders] 加载 [BookGroup.cover], 行为与书架书籍封面 [SharedBookCover] 对齐
 * (即"以书架封面行为为准"):
 * - 真封面经 [BookImageLoaders.loadCoverOrNull] 落持久区, 与书架书一致;
 *   网络加载期间先铺图集默认封面作占位 (与 SharedBookCover 同款)
 * - 无 cover / 加载失败 / [AppConfigAccessor.useDefaultCover] / 未注册 loader 时,
 *   走默认封面链: 用户图集烘焙图 (seed=组名, 稳定选图) → 内置 `image_cover_default`;
 *   不再渲染组名首字色块 (原版分组封面 name=null, 默认封面上也不叠书名)
 * - 比例: 对照书架分组条目, 列表/网格恒 NOVEL 3:4, 视频档 16:9
 *
 * @param reloadTick 封面重载信号 (configTick): 变化时重启加载, 不变不额外触发
 */
@Composable
fun SharedGroupCover(
    group: BookGroup,
    modifier: Modifier = Modifier,
    isVideoCover: Boolean = false,
    reloadTick: Int = 0,
) {
    val cover = group.cover
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过加载, 直接走默认封面链 (对照原 View 版封面组件 load 行为);
    // 每次组合读 prefs (不 remember): 配置变更后条目重组时读到最新值
    val useDefaultCover = AppConfigProviders.get().useDefaultCover
    // 仅 WiFi 加载封面: 非 WiFi 时 fetcher 层拦网络获取 (缓存命中仍显示, 对齐 SharedBookCover)
    val loadOnlyWifi = AppConfigProviders.get().loadCoverOnlyWifi
    // 位图 + 是否默认封面合成一个 state (对齐 SharedBookCover, 一次加载只引发一次重组)
    var coverState by remember(cover) { mutableStateOf(NoCoverBitmap) }
    // 仅以首次有效布局尺寸降采样；窗口 resize 不重新发起图片请求。
    val displaySize = remember { MutableStateFlow(IntSize.Zero) }
    LaunchedEffect(cover, loader, useDefaultCover, loadOnlyWifi, reloadTick) {
        if (loader == null) return@LaunchedEffect
        val decodeSize = firstValidCoverDecodeSize(displaySize)
        val ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL

        // 默认封面链要读 prefs + 解 JSON (已按 raw 串记忆化), 挪到协程内真用得上时再算
        suspend fun loadDefault() {
            // seed = 组名 (即分组的"书名", 对照书架书 seed=书名 稳定选图), 不回落封面路径;
            // 走 entry 版选图拿 ninePatch 标记 (defaultCoverFilePath 保留给 AudioPlay 等调用)
            val entry = defaultCoverEntry(seed = group.groupName, ratio = ratio)
            if (entry == null) {
                coverState = NoCoverBitmap
                return
            }
            val bmp = loader.loadImageOrNull(
                defaultCoverDisplayPath(entry, ratio), null,
                decodeSize.width, decodeSize.height,
            )
            coverState = if (bmp == null) NoCoverBitmap else CoverBitmap(bmp, true, entry.ninePatch)
        }
        if (useDefaultCover || cover.isNullOrBlank()) {
            loadDefault()
            return@LaunchedEffect
        }
        // 网络加载期间先铺组名选中的图集默认封面作占位 (与 SharedBookCover 同款,
        // 对照原 View 版的 Coil placeholder); 成功后覆盖为真实封面, 失败保持默认封面
        loadDefault()
        // 与书架书同款: 真封面落持久磁盘分区, 清缓存不会把书架/分组清成默认封面
        val bmp = loader.loadCoverOrNull(
            cover, null, decodeSize.width, decodeSize.height, loadOnlyWifi,
        )
        if (bmp != null) {
            coverState = CoverBitmap(bmp, false)
        }
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
    // 默认封面: 用户图集烘焙图 (已按 ratio 裁好; .9 图按九宫格拉伸) 或内置 image_cover_default
    // (jpg, 普通图拉伸); 分组无书名, 不叠竖排文字
    Box(resolvedModifier) {
        if (bmp != null) {
            NinePatchImageOrImage(
                bitmap = bmp,
                isNinePatch = coverState.isNinePatch,
                contentDescription = group.groupName,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            DefaultCoverNineImage(
                modifier = Modifier.matchParentSize(),
                contentDescription = group.groupName,
            )
        }
    }
}

/**
 * 分组封面渲染 slot 的 CompositionLocal: 默认兜底 [SharedGroupCover]。
 *
 * 与 [LocalBookCoverSlot] 对称, 宿主端可用 `CompositionLocalProvider` 覆盖注入平台实现
 * (默认 [SharedGroupCover], 各端统一)。
 */
val LocalGroupCoverSlot =
    staticCompositionLocalOf<@Composable (BookGroup, Modifier, Boolean, Int) -> Unit> {
        @Composable { group, modifier, isVideoCover, tick ->
            SharedGroupCover(group, modifier, isVideoCover, tick)
        }
    }
