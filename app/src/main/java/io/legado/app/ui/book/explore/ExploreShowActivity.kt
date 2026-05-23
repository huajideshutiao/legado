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
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.setUpExploreOptions
import io.legado.app.utils.applyNavigationBarPadding
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

    private fun initAdapter(style: Int) {
        when (style) {
            1 -> {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
                adapter = BigExploreShowAdapter(this, this)
            }

            2 -> {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
                adapter = GridExploreShowAdapter(this, this)
            }

            3 -> {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
                adapter = GridExploreShowAdapter(this, this).apply {
                    isVideoStyle = true
                }
            }

            else -> {
                binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
                adapter = ExploreShowAdapter(this, this)
            }
        }
    }

    private fun upAdapterByStyle(style: Int) {
        initAdapter(style)
        bindAdapter()
        viewModel.booksData.value?.let { upData(it) }
        upSwitchLayoutIcon()
    }

    private fun upSwitchLayoutIcon() {
        when (viewModel.exploreStyle) {
            0 -> switchLayoutMenuItem?.setIcon(R.drawable.ic_layout_big_card)
            1 -> switchLayoutMenuItem?.setIcon(R.drawable.ic_layout_grid)
            2 -> switchLayoutMenuItem?.setIcon(R.drawable.ic_layout_video)
            3 -> switchLayoutMenuItem?.setIcon(R.drawable.ic_layout_list)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.explore_bar, menu)
        switchLayoutMenuItem = menu.findItem(R.id.menu_switch_layout)
        starMenuItem = menu.findItem(R.id.menu_star)
        upSwitchLayoutIcon()
        upStarIcon(viewModel.isFavorite())
        return super.onCompatCreateOptionsMenu(menu)
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
        } else if (oldSize >= books.size && viewModel.page > 1) {
            loadMoreView.noMore()
        } else if (oldSize < books.size) {
            val newItems = books.subList(oldSize, books.size)
            adapter.addItems(newItems)
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
