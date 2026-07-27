package io.legado.app.ui.main.bookshelf.style1

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CoverRatio
import io.legado.app.ui.book.getRealBookSort
import io.legado.app.ui.bookshelf.BookshelfActions
import io.legado.app.ui.bookshelf.BookshelfTopBar
import io.legado.app.ui.bookshelf.ShelfBooksContent
import io.legado.app.ui.bookshelf.ShelfScrollState
import io.legado.app.ui.bookshelf.rememberShelfLayoutSpec
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.BaseBookshelfState
import io.legado.app.ui.main.bookshelf.BookshelfViewModel
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.ui.main.bookshelf.toBookshelfActionsCallbacks
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.max

/**
 * 书架 style1 壳层状态（原 BookshelfFragment1 上浮）：
 * 分组/每组书籍/滚动状态/当前 tab 位全部托管在此，Pager 页销毁重建后保留。
 */
class BookshelfState1(
    activity: AppCompatActivity,
    mainViewModel: MainViewModel,
    viewModel: BookshelfViewModel,
    scope: CoroutineScope,
    private val scrollStates: MutableMap<Long, ShelfScrollState>,
) : BaseBookshelfState(activity, mainViewModel, viewModel, scope) {

    var bookGroups by mutableStateOf<List<BookGroup>>(emptyList())
        private set

    /** groupId → 排序后的书籍(由各页 collect 写入)，tab 计数与 books 契约同源 */
    val groupBooks = mutableStateMapOf<Long, List<Book>>()

    var currentPage by mutableIntStateOf(AppConfig.saveTabPosition)

    override val groupId: Long get() = bookGroups.getOrNull(currentPage)?.groupId ?: 0

    override val books: List<Book> get() = groupBooks[groupId] ?: emptyList()

    fun scrollState(groupId: Long): ShelfScrollState =
        scrollStates.getOrPut(groupId) { ShelfScrollState() }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            runBlocking { appDb.bookGroupDao.enableGroup(BookGroup.IdAll) }
        } else if (data != bookGroups) {
            bookGroups = data
            // 复刻 selectLastTab: 回到持久化的 tab 位
            currentPage = AppConfig.saveTabPosition.coerceIn(0, data.lastIndex)
        }
    }

    fun sortBooks(list: List<Book>, sort: Int): List<Book> = when (sort) {
        1 -> list.sortedByDescending { it.latestChapterTime }
        2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> list.sortedBy { it.order }
        4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> list.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
        else -> list.sortedByDescending { it.durChapterTime }
    }

    fun tabTitle(group: BookGroup): String {
        if (!AppConfig.bookshelfShowGroupCount) return group.groupName
        val count = groupBooks[group.groupId]?.size
        return "${group.groupName}(${count ?: ".."})"
    }

    override fun gotoTop() {
        val state = scrollStates[groupId] ?: return
        scope.launch {
            if (AppConfig.isEInkMode) {
                state.list.scrollToItem(0)
                state.grid.scrollToItem(0)
            } else {
                launch { state.list.animateScrollToItem(0) }
                launch { state.grid.animateScrollToItem(0) }
            }
        }
    }

    override fun back(): Boolean = false
}

/**
 * 书架 style1 内容: 分组 AppScrollTabRow(accent 指示器/选中色, 长按编辑分组, 重选滚顶)
 * + HorizontalPager(对应 ViewPager2, offscreen 1 页)。状态托管在 [BookshelfState1]。
 */
@Composable
fun BookshelfScreen1(state: BookshelfState1) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val groups = state.bookGroups
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        val pagerState = rememberPagerState(
            initialPage = state.currentPage.coerceAtLeast(0),
            pageCount = { groups.size },
        )
        // 页变化 → 保存 tab 位(对照 onTabSelected 的 saveTabPosition)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                state.currentPage = page
                AppConfig.saveTabPosition = page
            }
        }
        // 外部切页(selectLastTab/分组变化)→ Pager 跟随
        LaunchedEffect(state.currentPage, groups.size) {
            val target = state.currentPage
            if (target in groups.indices && pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
        }
        BookshelfTopBar {
            if (groups.isNotEmpty()) {
                val selected = pagerState.currentPage.coerceIn(0, groups.lastIndex)
                AppScrollTabRow(
                    tabCount = groups.size,
                    selectedIndex = selected,
                    indicatorColor = colors.accent,
                    // 16dp 对照原 Toolbar contentInsetStart(tab 条从内容缩进处起)
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                ) { index ->
                    val group = groups[index]
                    GroupTab(
                        title = state.tabTitle(group),
                        selected = index == selected,
                        onClick = {
                            if (index == selected) {
                                state.gotoTop() // 复刻 onTabReselected
                            } else {
                                scope.launch {
                                    if (eInk) pagerState.scrollToPage(index)
                                    else pagerState.animateScrollToPage(index)
                                }
                            }
                        },
                        onLongClick = { state.showGroupEdit(group) },
                    )
                }
            } else {
                Box(Modifier.weight(1f))
            }
            BookshelfActions(state.toBookshelfActionsCallbacks())
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1, // 对照 offscreenPageLimit = 1
            key = { groups.getOrNull(it)?.groupId ?: it.toLong() },
            userScrollEnabled = !eInk,
        ) { page ->
            groups.getOrNull(page)?.let { group ->
                GroupBooksPage(state, group)
            }
        }
    }
}

/** tab 项: 8dp 水平 padding(tabPaddingStart/End), 56dp 高撑满顶栏(原 TabLayout match_parent 于 Toolbar), 长按编辑分组 */
@Composable
private fun GroupTab(
    title: String,
    selected: Boolean,
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

/** 单个分组页: 订阅 DB flow(排序器取组配置)写回状态类, 内容区共享 ShelfBooksContent */
@OptIn(FlowPreview::class)
@Composable
private fun GroupBooksPage(state: BookshelfState1, group: BookGroup) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // bookSort/sortTick 变化重建 flow, 对照原 upRecyclerData
    LaunchedEffect(group.groupId, group.getRealBookSort(), state.sortTick) {
        state.mainViewModel.observeGroupBooks(
            groupId = group.groupId,
            lifecycle = lifecycle,
            sorter = { state.sortBooks(it, group.getRealBookSort()) },
        ).debounce(100).collect { list ->
            state.groupBooks[group.groupId] = list
        }
    }
    val books = state.groupBooks[group.groupId] ?: emptyList()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    ShelfBooksContent(
        items = books,
        spec = rememberShelfLayoutSpec(state.configTick, screenWidthDp),
        scroll = state.scrollState(group.groupId),
        refreshEnabled = group.enableRefresh && books.isNotEmpty(),
        onRefresh = { state.mainViewModel.upToc(books) },
        configTick = state.configTick,
        refreshingUrls = { state.refreshingUrls },
        onBookClick = state::open,
        onBookLongClick = state::openBookInfo,
        showLastUpdateTime = true,
        showKindIntro = true,
        bookCoverSlot = { book ->
            ShelfCover(
                path = book.getDisplayCover(),
                name = book.name,
                author = book.author,
                origin = book.origin,
                ratio = CoverRatio.NOVEL,
                reloadKey = state.configTick,
            )
        },
        groupCoverSlot = { groupItem ->
            ShelfCover(
                path = groupItem.cover,
                name = null,
                author = null,
                origin = null,
                ratio = CoverRatio.NOVEL,
                reloadKey = state.configTick,
            )
        },
    )
}
