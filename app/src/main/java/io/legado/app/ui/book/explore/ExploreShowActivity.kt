package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.GlobalVars
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        adapter = ExploreShowAdapter(this, this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
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
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.explore_bar, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_switch_layout -> {
                when (adapter) {
                    is ExploreShowAdapter -> {
                        adapter = BigExploreShowAdapter(this, this)
                        item.setIcon(R.drawable.ic_layout_grid)
                    }

                    is BigExploreShowAdapter -> {
                        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
                        adapter = GridExploreShowAdapter(this, this)
                        item.setIcon(R.drawable.ic_layout_list)
                    }

                    is GridExploreShowAdapter -> {
                        binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
                        adapter = ExploreShowAdapter(this, this)
                        item.setIcon(R.drawable.ic_layout_big_card)
                    }
                }
                bindAdapter()
                viewModel.booksData.value?.let { upData(it) }
            }
        }
        return super.onCompatOptionsItemSelected(item)
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
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
        }
    }

    override fun isInBookshelf(book: BaseBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: Book, action: Boolean) {
        if (book.bookUrl.contains("::")) {
            val tmp = book.bookUrl.split("::")
            startActivity<ExploreShowActivity> {
                putExtra("exploreName", tmp[0])
                putExtra("sourceUrl", intent.getStringExtra("sourceUrl"))
                putExtra("exploreUrl", tmp[1])
            }
        } else {
            GlobalVars.nowBook = book
            if (action || !book.isVideo || !AppConfig.showVideoUi) {
                startActivity<BookInfoActivity> {
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                }
            } else {
                startActivity<VideoPlayActivity>()
            }
        }
    }
}
