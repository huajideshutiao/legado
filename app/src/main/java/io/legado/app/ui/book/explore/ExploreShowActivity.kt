package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.filter.SourceFilterRuleListDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.setUpExploreOptions
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.setIconCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    BaseExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()
    private lateinit var adapter: BaseExploreShowAdapter<*>
    private val loadMoreView by lazy { LoadMoreView(this) }
    private var switchLayoutMenuItem: MenuItem? = null
    private var starMenuItem: MenuItem? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initAdapter(viewModel.exploreStyle)

        binding.recyclerView.setFastScrollEnabled(true)
        binding.titleBar.toolbar.setOnClickListener {
            binding.recyclerView.smoothScrollToPosition(0)
        }

        binding.recyclerView.applyNavigationBarPadding()
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                }
            }
        })
        bindAdapter()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, Bundle().apply {
                putString(it, null)
            })
        }
        viewModel.upStarLiveData.observe(this) { upStarIcon(it) }
        viewModel.sourceReadyLiveData.observe(this) {
            upAdapterByStyle(viewModel.exploreStyle)
        }
        viewModel.optionsReadyLiveData.observe(this) {
            initFilterView()
        }
    }

    /**
     * exploreStyle 位运算魔数：
     * - 低 4 位：列数（0/1 单列；2..6 N 列网格）
     * - bit 4 (0x10)：视频布局标志，置位时一律用 [VideoExploreShowAdapter]
     */
    private fun initAdapter(style: Int) {
        val isVideo = BookSource.exploreStyleIsVideo(style)
        val cols = BookSource.exploreStyleCols(style)
        val spanCount = if (cols <= 1) 1 else cols
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        adapter = when {
            isVideo -> VideoExploreShowAdapter(this, this)
            cols <= 1 -> ExploreShowAdapter(this, this)
            else -> GridExploreShowAdapter(this, this)
        }
    }

    private fun upAdapterByStyle(style: Int) {
        initAdapter(style)
        bindAdapter()
        viewModel.booksData.value?.let { upData(it) }
        upSwitchLayoutIcon()
    }

    private fun upSwitchLayoutIcon() {
        val isVideo = BookSource.exploreStyleIsVideo(viewModel.exploreStyle)
        switchLayoutMenuItem?.setIconCompat(
            if (isVideo) R.drawable.ic_layout_list else R.drawable.ic_layout_video
        )
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.explore_bar, menu)
        menu.iconItemOnLongClick(R.id.menu_switch_layout) {
            showColumnPicker()
        }
        switchLayoutMenuItem = menu.findItem(R.id.menu_switch_layout)
        starMenuItem = menu.findItem(R.id.menu_star)
        upSwitchLayoutIcon()
        upStarIcon(viewModel.isFavorite())
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun showColumnPicker() {
        val current = BookSource.exploreStyleCols(viewModel.exploreStyle).coerceIn(1, 6)
        showNumberPicker(
            this,
            titleResId = R.string.explore_cols,
            min = 1,
            max = 6,
            value = current,
        ) { cols ->
            viewModel.setColumnCount(cols)
            upAdapterByStyle(viewModel.exploreStyle)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_switch_layout -> {
                viewModel.switchLayout()
                upAdapterByStyle(viewModel.exploreStyle)
            }

            R.id.menu_star -> {
                viewModel.toggleFavorite()
            }

            R.id.menu_source_filter_rule -> {
                val scope = viewModel.bookSource?.let { SearchScope(it).toString() }
                showDialogFragment(SourceFilterRuleListDialog(scope))
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun upStarIcon(isFavorite: Boolean) {
        if (isFavorite) {
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
        } else {
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
        }
    }

    private fun initFilterView() {
        binding.llFilter.setUpExploreOptions(viewModel.exploreOptions) {
            adapter.clearItems()
            loadMoreView.startLoad()
            viewModel.explore(true)
        }
    }

    private fun bindAdapter() {
        binding.recyclerView.adapter = adapter
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        val oldSize = adapter.getActualItemCount()
        if (books.isEmpty() && oldSize == 0) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (oldSize < books.size) {
            val newItems = books.subList(oldSize, books.size)
            adapter.addItems(newItems)
        }
        // 书源声称没下一页（或退化启发式判定到尽头）就停止上拉
        if (!viewModel.hasNextPage) {
            loadMoreView.noMore()
        }
    }

    override fun isInBookshelf(book: BaseBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: BaseBook, longClick: Boolean) {
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
