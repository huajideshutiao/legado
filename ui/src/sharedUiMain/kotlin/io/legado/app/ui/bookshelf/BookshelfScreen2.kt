package io.legado.app.ui.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.root.LocalPlatformCapabilities
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.back
import legado.ui.generated.resources.bookshelf
import legado.ui.generated.resources.ic_arrow_back
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
    onBookClick: (Book, String?) -> Unit,
    onBookLongClick: (Book, String?) -> Unit,
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
    // 无系统返回通道的端 (iOS) 需在分组内常驻可见退出口, 见下方顶栏
    val capabilities = LocalPlatformCapabilities.current
    // 单一数据源: 根级=IdRoot 未分组书, 分组内=该组书 (读 VM 缓存切片, 无独立 Room 流)
    val booksCache by viewModel.booksCache.collectAsState()

    // 当前层级: IdRoot=根级, 其他=已进入的分组 (对照 BookshelfFragment2.groupId)
    // rememberSaveable: 页面脱离组合 (进入详情页) 与重建出栈时保留已进入的分组
    var groupId by rememberSaveable { mutableStateOf(BookGroup.IdRoot) }
    // 各层级独立保存滚动状态: 根级复用外部 scrollState, 各具体分组用独立 rememberSaveable
    val currentScrollState = rememberSaveable(groupId, saver = ShelfScrollState.Saver) {
        if (groupId == BookGroup.IdRoot) scrollState else ShelfScrollState()
    }
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
    AppBackHandler(enabled = groupId != BookGroup.IdRoot && isRootTop) {
        groupId = BookGroup.IdRoot
    }

    // tab 双击滚顶 (对照 BookshelfFragment2.gotoTop), 档位与 layoutSpec 同源
    LaunchedEffect(gotoTopTick) {
        if (gotoTopTick == 0) return@LaunchedEffect
        currentScrollState.gotoTop(layoutSpec.tier, eInk)
    }

    Column(modifier.fillMaxSize()) {
        BookshelfTopBarContainer(actions) {
            // 无系统返回通道的端 (iOS) 没有按键返回可走, 分组内必须给出可见退出口;
            // 有返回通道的端 (Android/鸿蒙系统返回键, 桌面 ESC) 保持原版顶栏形态
            if (!capabilities.supportsSystemBack && groupId != BookGroup.IdRoot) {
                IconButton(onClick = { groupId = BookGroup.IdRoot }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = stringResource(Res.string.back),
                        tint = AppTheme.colors.primaryText,
                    )
                }
            }
            BookshelfTitleText(title)
        }
        ShelfBooksContent(
            items = items,
            spec = layoutSpec,
            scroll = currentScrollState,
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
            // 区块 = 样式2 当前分组 (同一本书可能同时出现在根级与某分组内)
            pairBlockId = "shelf2-group-$groupId",
        )
    }
}

/**
 * 分组封面 (对照 style2 各 GroupViewHolder 的 `ivCover.load(group.cover)`)。
 *
 * 加载与渲染管线与书籍封面同源 ([SharedCoverContent]); 数据差异: 封面取 [BookGroup.cover],
 * 默认封面选图 seed = 组名, 无书源来源 (origin 恒 null), 无共享元素转场端点。
 *
 * 原版分组封面 `ivCover.load(item.cover, inBookshelf = true)` 不传 name/author,
 * `CoverImageView` 的 name/author 为 null, 故默认封面上无竖排书名/作者可画。
 *
 * 比例: 对照书架分组条目, 列表/网格恒 NOVEL 3:4, 视频档 16:9。
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
    SharedCoverContent(
        source = CoverSource(
            coverUrl = group.cover,
            origin = null,
            cacheKey = null,
            defaultCoverSeed = group.groupName,
            shareDecodedCover = false,
            persistentCover = true,
            sharedTransition = false,
            contentDescription = group.groupName,
            // 分组封面不叠字, 叠字内容无消费者
            title = null,
            author = null,
        ),
        modifier = modifier,
        isVideoCover = isVideoCover,
        reloadTick = reloadTick,
        nameAuthorOverlay = false,
    )
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
