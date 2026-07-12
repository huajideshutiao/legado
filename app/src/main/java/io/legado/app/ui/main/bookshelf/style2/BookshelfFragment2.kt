package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.FragmentRecyclerViewBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.cnCompare
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_recycler_view),
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentRecyclerViewBinding::bind)
    private var booksAdapter: BaseBooksAdapter<*>? = null
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
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
     */
    private fun createAdapter(): BaseBooksAdapter<*> {
        val style = AppConfig.bookshelfLayout
        val isVideo = BookSource.exploreStyleIsVideo(style)
        val cols = getCols()
        val ctx = requireContext()
        return when {
            cols == 0 -> BooksAdapterList(ctx, this, isVideo)
            isVideo -> BooksAdapterVideo(ctx, this)
            cols == 1 -> BooksAdapterList(ctx, this, false)
            else -> BooksAdapterGrid(ctx, this)
        }
    }

    private fun spanCountFor(adapter: BaseBooksAdapter<*>): Int {
        return if (adapter is BooksAdapterGrid || adapter is BooksAdapterVideo) getCols() else 1
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 复用 XML 中的 title_bar(visibility=gone), 与 ExploreFragment 一致
        binding.titleBar.isVisible = true
        binding.titleBar.setTitle(R.string.bookshelf)
        setSupportToolbar(binding.titleBar.toolbar)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
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
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books)
        }
        binding.recyclerView.itemAnimator = null
        val adapter = booksAdapter
        if (adapter == null) {
            val newAdapter = createAdapter()
            val spanCount = spanCountFor(newAdapter)
            binding.recyclerView.layoutManager = if (spanCount <= 1) {
                LinearLayoutManager(context)
            } else {
                GridLayoutManager(context, spanCount)
            }
            binding.recyclerView.adapter = newAdapter
            newAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    val layoutManager = binding.recyclerView.layoutManager
                    if (positionStart == 0 && layoutManager is LinearLayoutManager) {
                        val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                        binding.recyclerView.scrollToPosition(max(0, scrollTo))
                    }
                }

                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    val layoutManager = binding.recyclerView.layoutManager
                    if (toPosition == 0 && layoutManager is LinearLayoutManager) {
                        val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                        binding.recyclerView.scrollToPosition(max(0, scrollTo))
                    }
                }
            })
            booksAdapter = newAdapter
        } else {
            binding.recyclerView.adapter = adapter
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            booksAdapter?.updateItems()
            applyGroupState()
        }
    }

    override fun upSort() {
        initBooksData()
    }

    @OptIn(FlowPreview::class)
    private fun initBooksData() {
        applyGroupState()
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            activityViewModel.observeGroupBooks(
                groupId = groupId,
                lifecycle = viewLifecycleOwner.lifecycle,
                sorter = ::sortBooks,
            )
                .debounce(100)
                .collect { list ->
                books = list
                booksAdapter?.updateItems()
                applyGroupState()
            }
        }
    }

    private fun applyGroupState() {
        if (!isAdded) return
        val group = bookGroups.find { it.groupId == groupId }
        enableRefresh = group?.enableRefresh ?: true
        val baseTitle = group?.groupName ?: getString(R.string.bookshelf)
        binding.titleBar.title = if (AppConfig.bookshelfShowGroupCount) {
            "$baseTitle (${books.size})"
        } else {
            baseTitle
        }
        binding.tvEmptyMsg.isGone = getItemCount() > 0
        binding.refreshLayout.isEnabled = enableRefresh && getItemCount() > 0
    }

    private fun sortBooks(list: List<Book>): List<Book> =
        when (AppConfig.getBookSortByGroupId(groupId)) {
            1 -> list.sortedByDescending { it.latestChapterTime }
            2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
            3 -> list.sortedBy { it.order }
            4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> list.sortedByDescending { it.durChapterTime }
        }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.recyclerView.scrollToPosition(0)
        } else {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item.copy())

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
                IntentData.book = item.copy()
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter?.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter?.notifyDataSetChanged()
            applyGroupState()
        }
    }
}
