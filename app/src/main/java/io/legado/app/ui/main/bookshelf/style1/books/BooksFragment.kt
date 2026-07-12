package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.FragmentRecyclerViewBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.utils.cnCompare
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BooksFragment() : BaseFragment(R.layout.fragment_recycler_view),
    BaseBooksAdapter.CallBack {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentRecyclerViewBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private var booksAdapter: BaseBooksAdapter<*>? = null
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true

    /**
     * 位运算魔数与发现界面共用: 低 4 位为列数, bit 4 (0x10) 为视频布局标志.
     * 固定宽度模式下按屏宽/单元格宽度换算, 与用户配置的列数解耦.
     */
    private fun getCols(): Int {
        if (AppConfig.bookshelfFixedWidthMode) {
            val displayMetrics = resources.displayMetrics
            val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
            val spanCount = (screenWidthDp / AppConfig.bookshelfGridWidth).toInt()
            return maxOf(1, spanCount)
        }
        return BookSource.exploreStyleCols(AppConfig.bookshelfLayout)
    }

    /**
     * 结构对齐发现界面的 initAdapter: isVideo tier 用 [BooksAdapterVideo]
     * 复用 item_explore_video 卡片 (与 VideoExploreShowAdapter 共享 bind).
     * List 类适配器锁 1 列; Grid/Video 走多列.
     */
    private fun createAdapter(): BaseBooksAdapter<*> {
        val style = AppConfig.bookshelfLayout
        val isVideo = BookSource.exploreStyleIsVideo(style)
        val cols = getCols()
        val ctx = requireContext()
        return when {
            cols == 0 -> BooksAdapterList(ctx, this, this, viewLifecycleOwner.lifecycle, isVideo)
            isVideo -> BooksAdapterVideo(ctx, this)
            cols == 1 -> BooksAdapterList(ctx, this, this, viewLifecycleOwner.lifecycle, false)
            else -> BooksAdapterGrid(ctx, this)
        }
    }

    private fun spanCountFor(adapter: BaseBooksAdapter<*>): Int {
        return if (adapter is BooksAdapterGrid || adapter is BooksAdapterVideo) getCols() else 1
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        upRecyclerData()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayoutManager()
    }

    private fun updateLayoutManager() {
        val adapter = booksAdapter ?: return
        val spanCount = spanCountFor(adapter)
        val layoutManager = binding.recyclerView.layoutManager
        if (spanCount <= 1) {
            if (layoutManager !is LinearLayoutManager || layoutManager is GridLayoutManager) {
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
            }
        } else {
            if (layoutManager is GridLayoutManager) {
                layoutManager.spanCount = spanCount
            } else {
                binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
            }
        }
    }

    private fun initRecyclerView() {
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(booksAdapter?.getItems() ?: emptyList())
        }
        val adapter = booksAdapter
        if (adapter == null) {
            val newAdapter = createAdapter()
            val spanCount = spanCountFor(newAdapter)
            binding.recyclerView.layoutManager = if (spanCount <= 1) {
                LinearLayoutManager(context)
            } else {
                GridLayoutManager(context, spanCount)
            }
            // Video 用独立 layout, 与 Grid/List 视图类型冲突, 不共享跨 tab 池.
            when (newAdapter) {
                is BooksAdapterGrid -> binding.recyclerView.setRecycledViewPool(
                    activityViewModel.booksGridRecycledViewPool
                )

                is BooksAdapterVideo -> Unit
                else -> binding.recyclerView.setRecycledViewPool(
                    activityViewModel.booksListRecycledViewPool
                )
            }
            newAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recyclerView.adapter = newAdapter
            newAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    val layoutManager = binding.recyclerView.layoutManager
                    if (positionStart == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                        val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                        binding.recyclerView.scrollToPosition(max(0, scrollTo))
                    }
                }

                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    val layoutManager = binding.recyclerView.layoutManager
                    if (toPosition == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                        val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                        binding.recyclerView.scrollToPosition(max(0, scrollTo))
                    }
                }
            })
            booksAdapter = newAdapter
        } else {
            binding.recyclerView.adapter = adapter
        }
        startLastUpdateTimeJob()
    }

    private fun upFastScrollerBar() {
        binding.recyclerView.setFastScrollEnabled(true)
        binding.recyclerView.scrollBarSize = 0
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    @OptIn(FlowPreview::class)
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            activityViewModel.observeGroupBooks(
                groupId = groupId,
                lifecycle = viewLifecycleOwner.lifecycle,
                sorter = ::sortBooks,
            )
                .debounce(100)
                .collect { list ->
                binding.tvEmptyMsg.isGone = list.isNotEmpty()
                binding.refreshLayout.isEnabled = enableRefresh && list.isNotEmpty()
                booksAdapter?.setItems(list)
                (parentFragment as? BookshelfFragment1)?.updateTabTitle(groupId, list.size)
            }
        }
    }

    private fun sortBooks(list: List<Book>): List<Book> = when (bookSort) {
        1 -> list.sortedByDescending { it.latestChapterTime }
        2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> list.sortedBy { it.order }
        4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> list.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
        else -> list.sortedByDescending { it.durChapterTime }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        // Grid/Video 卡片没有 tvLastUpdateTime 位, 定时刷新纯浪费
        if (!AppConfig.showLastUpdateTime
            || booksAdapter is BooksAdapterGrid
            || booksAdapter is BooksAdapterVideo
        ) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    booksAdapter?.upLastUpdateTime()
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        return booksAdapter?.getItems() ?: emptyList()
    }

    fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.recyclerView.scrollToPosition(0)
        } else {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.setItemViewCacheSize(0)
        binding.recyclerView.adapter = null
    }

    override fun open(book: Book) {
        startActivityForBook(book.copy())
    }

    override fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            IntentData.book = book.copy()
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter?.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter?.notifyDataSetChanged()
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}
