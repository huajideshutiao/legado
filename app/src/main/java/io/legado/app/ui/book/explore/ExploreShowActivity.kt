package io.legado.app.ui.book.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.IntentData
import io.legado.app.help.book.addType
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.filter.SourceFilterRuleListDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

/**
 * 发现列表 (薄壳模式)。intent 契约 (exploreName/exploreUrl/sourceUrl + IntentData.source) 不变。
 *
 * UI 下沉到 shared/sharedUiMain 的 [ExploreShowScreen]; 本 Activity 实现
 * [ExploreShowUiActions] 接口供 shared 端回调; L3 (Android 专属) Composable
 * ([ExploreShowCover] / [ExploreOptionsRow] / [ExploreVideoItem]) 通过 slot 注入。
 *
 * 状态字段 (books/exploreStyle/isFavorite/...) 由 Activity 托管, 在 [Content]
 * 内打包为 [ExploreShowUiState] 传入 shared 端 [ExploreShowScreen]。
 */
class ExploreShowActivity : BaseComposeActivity(), ExploreShowUiActions {

    val viewModel by viewModels<ExploreShowViewModel>()

    var books by mutableStateOf<List<SearchBook>>(emptyList())
        private set
    var exploreStyle by mutableIntStateOf(0)
        private set
    var isFavorite by mutableStateOf(false)
        private set

    // 版本号驱动的重组信号: 书架集合变化 / 参数 chip 结构变化 / 标题栏点击回顶
    var bookshelfVersion by mutableIntStateOf(0)
        private set
    var optionsVersion by mutableIntStateOf(0)
        private set
    var scrollTopEpoch by mutableIntStateOf(0)
        private set

    // footer 状态, 对照 LoadMoreView(isLoading/hasMore/errorMsg/文案)
    var footerLoading by mutableStateOf(false)
        private set
    var footerText by mutableStateOf<String?>(null)
        private set
    private var footerHasMore = true
    private var errorMsg = ""

    val exploreTitle: String
        get() = intent.getStringExtra("exploreName") ?: getString(R.string.discovery)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        startLoad()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) { upError(it) }
        viewModel.upAdapterLiveData.observe(this) { bookshelfVersion++ }
        viewModel.upStarLiveData.observe(this) { isFavorite = it }
        viewModel.sourceReadyLiveData.observe(this) { exploreStyle = viewModel.exploreStyle }
        viewModel.optionsReadyLiveData.observe(this) { optionsVersion++ }
    }

    @Composable
    override fun Content() {
        val state = ExploreShowUiState(
            title = exploreTitle,
            books = books,
            exploreStyle = exploreStyle,
            isFavorite = isFavorite,
            bookshelfVersion = bookshelfVersion,
            optionsVersion = optionsVersion,
            scrollTopEpoch = scrollTopEpoch,
            footerLoading = footerLoading,
            footerText = footerText,
        )
        ExploreShowScreen(
            state = state,
            actions = this,
            optionsRowSlot = { ExploreOptionsRow(this) },
            videoItemSlot = { book, inBookshelf, onClick, onLongClick ->
                ExploreVideoItem(book, inBookshelf, onClick, onLongClick)
            },
            coverSlot = { book, inBookshelf, isVideoStyle, modifier ->
                ExploreShowCover(book, inBookshelf, isVideoStyle, modifier)
            },
        )
    }

    // ===== ExploreShowUiActions 实现 (别名桥接到已有方法) =====

    override fun onBack() = onBackPressedDispatcher.onBackPressed()

    override fun onTitleClick() = scrollToTop()

    override fun onToggleFavorite() {
        viewModel.toggleFavorite()
    }

    override fun onSwitchLayout() = switchLayout()

    override fun onShowColumnPicker() = showColumnPicker()

    override fun onShowSourceFilterRule() = showSourceFilterRule()

    override fun onFooterClick() = onFooterClickImpl()

    override fun onScrollToBottom() = scrollToBottom()

    override fun onBookClick(book: SearchBook, longClick: Boolean) {
        onBookClickImpl(book, longClick)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun onExploreOptionChanged() = onExploreOptionChangedImpl()

    // ===== 内部实现 (原 Activity 行为保留) =====

    fun scrollToTop() {
        scrollTopEpoch++
    }

    // ===== footer 状态机 (对照 LoadMoreView) =====

    private fun startLoad() {
        footerLoading = true
    }

    private fun stopLoad() {
        footerLoading = false
    }

    private fun hasMoreLoad() {
        errorMsg = ""
        footerHasMore = true
        startLoad()
    }

    private fun noMore(msg: String? = null) {
        stopLoad()
        errorMsg = ""
        footerHasMore = false
        footerText = msg ?: getString(R.string.bottom_line)
    }

    private fun upError(msg: String) {
        stopLoad()
        footerHasMore = false
        errorMsg = msg
        footerText = getString(R.string.error_load_msg, "点击查看详情")
    }

    private fun onFooterClickImpl() {
        if (errorMsg.isNotBlank()) {
            alert(R.string.error) {
                setMessage(errorMsg)
                neutralButton(R.string.retry) {
                    if (!footerLoading) scrollToBottom(true)
                }
            }
            return
        }
        if (!footerLoading) scrollToBottom(true)
    }

    fun scrollToBottom(forceLoad: Boolean = false) {
        if ((footerHasMore && !footerLoading) || forceLoad) {
            hasMoreLoad()
            viewModel.explore()
        }
    }

    private fun upData(newBooks: List<SearchBook>) {
        stopLoad()
        if (newBooks.isEmpty() && books.isEmpty()) {
            noMore(getString(R.string.empty))
        } else if (books.size < newBooks.size) {
            books = newBooks
        }
        // 书源声称没下一页 (或退化启发式判定到尽头) 就停止上拉
        if (!viewModel.hasNextPage) {
            noMore()
        }
    }

    // ===== 菜单 / chip 动作 =====

    fun switchLayout() {
        viewModel.switchLayout()
        exploreStyle = viewModel.exploreStyle
    }

    fun showColumnPicker() {
        val current = BookSource.exploreStyleCols(viewModel.exploreStyle).coerceIn(0, 6)
        showNumberPicker(
            this,
            titleResId = R.string.explore_cols,
            min = 0,
            max = 6,
            value = current,
        ) { cols ->
            viewModel.setColumnCount(cols)
            exploreStyle = viewModel.exploreStyle
        }
    }

    fun showSourceFilterRule() {
        val scope = viewModel.bookSource?.let { SearchScope(it).toString() }
        showDialogFragment(SourceFilterRuleListDialog(scope))
    }

    private fun onExploreOptionChangedImpl() {
        books = emptyList()
        // 原版仅 startLoad; 此处一并复位 hasMore/error, 否则先前到底后换参数会永久停止翻页
        hasMoreLoad()
        viewModel.explore(true)
    }

    // ===== 书籍点击 =====

    fun isInBookshelf(book: BaseBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /** 点击书籍: 补 notShelf type (对齐原 registerListener) 后进详情 */
    fun onBookClickImpl(book: BaseBook, longClick: Boolean = false) {
        if (!viewModel.isInBookShelf(book)) {
            book.addType(BookType.notShelf)
        }
        showBookInfo(book, longClick)
    }

    private fun showBookInfo(book: BaseBook, longClick: Boolean) {
        val urlParts = book.bookUrl.split("::", limit = 2)
        if (urlParts.size == 2) {
            IntentData.source = viewModel.bookSource
            startActivity<ExploreShowActivity> {
                putExtra("exploreName", urlParts[0])
                putExtra("exploreUrl", urlParts[1])
            }
            return
        }
        IntentData.book = book
        when {
            longClick || !AppConfig.devFeat -> startActivity<BookInfoActivity> {
                putExtra("name", book.name)
                putExtra("author", book.author)
            }

            book.isVideo -> startActivity<VideoPlayActivity>()
            book.isRss -> startActivity<ReadRssActivity>()
            else -> startActivity<BookInfoActivity>()
        }
    }
}
