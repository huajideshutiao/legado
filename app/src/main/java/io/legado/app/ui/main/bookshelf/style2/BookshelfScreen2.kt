package io.legado.app.ui.main.bookshelf.style2

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CoverRatio
import io.legado.app.ui.bookshelf.BookshelfActions
import io.legado.app.ui.bookshelf.BookshelfTopBar
import io.legado.app.ui.bookshelf.ShelfBooksContent
import io.legado.app.ui.bookshelf.ShelfScrollState
import io.legado.app.ui.bookshelf.rememberShelfLayoutSpec
import io.legado.app.ui.compose.theme.AppTheme
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
import kotlin.math.max

/**
 * 书架 style2 壳层状态（原 BookshelfFragment2 上浮）：
 * 单列表，分组作为文件夹条目与书籍同列；点分组下钻(切 groupId)，[back] 回根。
 */
class BookshelfState2(
    activity: AppCompatActivity,
    mainViewModel: MainViewModel,
    viewModel: BookshelfViewModel,
    scope: CoroutineScope,
    /** 单列表只需一份滚动状态; gotoTop/下钻共用 */
    val scrollState: ShelfScrollState,
) : BaseBookshelfState(activity, mainViewModel, viewModel, scope) {

    var bookGroups by mutableStateOf<List<BookGroup>>(emptyList())
        private set

    override var groupId by mutableStateOf(BookGroup.IdRoot)

    override var books by mutableStateOf<List<Book>>(emptyList())

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
        }
    }

    fun sortBooks(list: List<Book>, groupId: Long): List<Book> =
        when (AppConfig.getBookSortByGroupId(groupId)) {
            1 -> list.sortedByDescending { it.latestChapterTime }
            2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
            3 -> list.sortedBy { it.order }
            4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> list.sortedByDescending { it.durChapterTime }
        }

    /** MainActivity 返回键调用, 从下钻分组回根分组 */
    override fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            return true
        }
        return false
    }

    override fun gotoTop() {
        scope.launch {
            if (AppConfig.isEInkMode) {
                scrollState.list.scrollToItem(0)
                scrollState.grid.scrollToItem(0)
            } else {
                launch { scrollState.list.animateScrollToItem(0) }
                launch { scrollState.grid.animateScrollToItem(0) }
            }
        }
    }

    fun openGroup(group: BookGroup) {
        groupId = group.groupId
    }
}

/**
 * 书架 style2 内容: 单列表, 分组作为文件夹 item 与书籍同列(根分组 = 组 + 松散书).
 * 点分组切 groupId 下钻, back() 回根; 状态托管在 [BookshelfState2].
 */
@OptIn(FlowPreview::class)
@Composable
fun BookshelfScreen2(state: BookshelfState2) {
    val colors = AppTheme.colors
    val groupId = state.groupId
    val groups = state.bookGroups
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // groupId/upSort 变化重建 flow 重排序, 对照原 initBooksData
    LaunchedEffect(groupId, state.sortTick) {
        state.mainViewModel.observeGroupBooks(
            groupId = groupId,
            lifecycle = lifecycle,
            sorter = { state.sortBooks(it, groupId) },
        ).debounce(100).collect { state.books = it }
    }
    val books = state.books
    val atRoot = groupId == BookGroup.IdRoot
    // 根分组展示 组 + 书, 下钻后仅书, 对照原 getItems
    val items: List<Any> = remember(atRoot, groups, books) {
        if (atRoot) groups + books else books
    }
    val group = remember(groups, groupId) { groups.find { it.groupId == groupId } }
    val enableRefresh = group?.enableRefresh ?: true
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BookshelfTopBar {
            val baseTitle = group?.groupName ?: stringResource(R.string.bookshelf)
            val title = if (AppConfig.bookshelfShowGroupCount) {
                "$baseTitle (${books.size})"
            } else {
                baseTitle
            }
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            BookshelfActions(state.toBookshelfActionsCallbacks())
        }
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        ShelfBooksContent(
            items = items,
            spec = rememberShelfLayoutSpec(state.layoutSpecTick, screenWidthDp),
            scroll = state.scrollState,
            refreshEnabled = enableRefresh && items.isNotEmpty(),
            onRefresh = { state.mainViewModel.upToc(books) },
            coverReloadTick = state.coverReloadTick,
            refreshingUrls = state.refreshingUrls,
            onBookClick = state::open,
            onBookLongClick = state::openBookInfo,
            showLastUpdateTime = true,
            showKindIntro = true,
            bookCoverSlot = { book, modifier, isVideoCover ->
                ShelfCover(
                    path = book.getDisplayCover(),
                    name = book.name,
                    author = book.author,
                    origin = book.origin,
                    ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL,
                    reloadKey = state.coverReloadTick,
                    modifier = modifier,
                    loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                )
            },
            groupCoverSlot = { groupItem, modifier, isVideoCover ->
                ShelfCover(
                    path = groupItem.cover,
                    name = null,
                    author = null,
                    origin = null,
                    ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL,
                    reloadKey = state.coverReloadTick,
                    modifier = modifier,
                )
            },
            onGroupClick = state::openGroup,
            onGroupLongClick = state::showGroupEdit,
        )
    }
}
