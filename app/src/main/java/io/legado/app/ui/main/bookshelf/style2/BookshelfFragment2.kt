package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentRecyclerViewBinding
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.cnCompare
import io.legado.app.utils.observeEvent
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
    private var titleBar: TitleBar? = null

    private fun getSpanCount(): Int {
        if (AppConfig.bookshelfFixedWidthMode) {
            val displayMetrics = resources.displayMetrics
            val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
            val spanCount = (screenWidthDp / AppConfig.bookshelfGridWidth).toInt()
            return maxOf(1, spanCount)
        }
        return AppConfig.bookshelfLayout
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        addTitleBar()
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun addTitleBar() {
        val ctx = requireContext()
        titleBar = TitleBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTitle(R.string.bookshelf)
        }
        val root = binding.root as? ViewGroup ?: return
        root.addView(titleBar, 0)
        titleBar?.let { setSupportToolbar(it.toolbar) }

        // Adjust SwipeRefreshLayout constraint to be below TitleBar
        val lp = binding.refreshLayout.layoutParams as? ViewGroup.MarginLayoutParams
        lp?.topMargin = 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayoutManager()
    }

    private fun updateLayoutManager() {
        val spanCount = getSpanCount()
        val layoutManager = binding.recyclerView.layoutManager
        if (spanCount <= 1) {
            if (layoutManager !is LinearLayoutManager) {
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
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books)
        }
        val spanCount = getSpanCount()
        if (spanCount <= 1) {
            binding.recyclerView.layoutManager = LinearLayoutManager(context)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        }
        binding.recyclerView.itemAnimator = null
        val adapter = booksAdapter
        if (adapter == null) {
            val newAdapter = if (spanCount <= 1) {
                BooksAdapterList(requireContext(), this)
            } else {
                BooksAdapterGrid(requireContext(), this)
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
        titleBar?.title = if (AppConfig.bookshelfShowGroupCount) {
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
