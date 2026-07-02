package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentHomeTabBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.HomeTabHelp
import io.legado.app.help.IntentData
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.explore.BaseExploreShowAdapter
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.explore.GridExploreShowAdapter
import io.legado.app.ui.book.explore.VideoExploreShowAdapter
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 单个主页分组（HomeTab）的内容承载 Fragment。
 * ViewModel 共享外壳 HomeFragment 的实例，按 tabTitle 订阅自己的数据。
 */
class HomeTabFragment() : VMBaseFragment<HomeViewModel>(R.layout.fragment_home_tab) {

    constructor(tabTitle: String) : this() {
        arguments = Bundle().apply { putString(ARG_TAB_TITLE, tabTitle) }
    }

    private val tabTitle: String get() = arguments?.getString(ARG_TAB_TITLE).orEmpty()

    private val binding by viewBinding(FragmentHomeTabBinding::bind)

    /** 共享外壳 VM */
    override val viewModel by viewModels<HomeViewModel>(
        ownerProducer = { requireParentFragment() }
    )

    private val glm by lazy { GridLayoutManager(requireContext(), 2) }

    /** header 容器：承载所有非无限流展示项（代码动态创建，无需布局文件） */
    private val headerView by lazy {
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private val sectionViews by lazy {
        HomeSectionAdapter(requireContext(), headerView, sectionCallback)
    }

    private var gridAdapter: BaseExploreShowAdapter<*>? = null
    private var lastInfiniteId: String? = null
    private var lastCoverVideo: Boolean = false

    private val loadMoreView by lazy { LoadMoreView(requireContext()) }

    private val sectionCallback = object : HomeSectionAdapter.Callback {
        override fun onBookClick(book: SearchBook, section: HomeSection, longClick: Boolean) {
            val urlParts = book.bookUrl.split("::", limit = 2)
            if (urlParts.size == 2) {
                IntentData.source = null
                startActivity<ExploreShowActivity> {
                    putExtra("exploreName", urlParts[0])
                    putExtra("exploreUrl", urlParts[1])
                    putExtra("sourceUrl", section.sourceUrl)
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

        override fun onMoreClick(section: HomeSection) {
            IntentData.source = null
            startActivity<ExploreShowActivity> {
                putExtra("exploreName", section.exploreName)
                putExtra("exploreUrl", section.exploreUrl)
                putExtra("sourceUrl", section.sourceUrl)
            }
        }

        override fun isLoading(sectionId: String) = viewModel.isLoading(tabTitle, sectionId)

        override fun sectionOptions(sectionId: String) =
            viewModel.sectionOptions(tabTitle, sectionId)

        override fun onOptionSelected(section: HomeSection) =
            viewModel.onSectionOptionSelected(tabTitle, section)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            viewModel.refreshTab(tabTitle)
        }
        binding.rvHome.layoutManager = glm
        binding.rvHome.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (glm.findLastVisibleItemPosition() >= glm.itemCount - 4) {
                    viewModel.loadInfinite(tabTitle)
                }
            }
        })
        observeData()
        renderSections(HomeTabHelp.getSections(tabTitle))
    }

    override fun onResume() {
        super.onResume()
        viewModel.initTab(tabTitle)
    }

    private fun observeData() {
        viewModel.sectionsLiveData.observe(viewLifecycleOwner) { changedTab ->
            if (changedTab != tabTitle) return@observe
            renderSections(HomeTabHelp.getSections(tabTitle))
        }
        viewModel.sectionUpdated.observe(viewLifecycleOwner) { (changedTab, sectionId) ->
            if (changedTab != tabTitle) return@observe
            val books = viewModel.sectionBooks(tabTitle, sectionId)
            if (sectionId == viewModel.infiniteSection(tabTitle)?.id) {
                val adapter = gridAdapter ?: return@observe
                val current = adapter.getActualItemCount()
                when {
                    books.size > current -> adapter.addItems(books.subList(current, books.size))
                    books.size < current -> adapter.setItems(books)
                }
            } else {
                sectionViews.updateSectionBooks(sectionId, books)
            }
        }
        viewModel.sectionOptionsChanged.observe(viewLifecycleOwner) { (changedTab, sectionId) ->
            if (changedTab != tabTitle) return@observe
            sectionViews.updateSectionOptions(
                sectionId,
                viewModel.sectionOptions(tabTitle, sectionId)
            )
        }
        viewModel.sectionLoadingChanged.observe(viewLifecycleOwner) { (changedTab, sectionId) ->
            if (changedTab != tabTitle) return@observe
            if (sectionId == viewModel.infiniteSection(tabTitle)?.id) {
                when {
                    viewModel.isLoading(tabTitle, sectionId) -> loadMoreView.startLoad()
                    !viewModel.hasMoreInfinite(tabTitle) -> loadMoreView.noMore()
                    else -> loadMoreView.stopLoad()
                }
            } else {
                sectionViews.updateSectionLoading(sectionId)
            }
        }
        viewModel.sectionErrorChanged.observe(viewLifecycleOwner) { (changedTab, sectionId) ->
            if (changedTab != tabTitle) return@observe
            if (sectionId == viewModel.infiniteSection(tabTitle)?.id) {
                val msg = requireContext().getString(R.string.home_source_invalid)
                loadMoreView.error(msg, msg)
            } else {
                sectionViews.updateSectionError(sectionId)
            }
        }
        observeEvent<HomeSectionEvent>(EventBus.HOME_SECTION) { event ->
            if (event.tabTitle != tabTitle) return@observeEvent
            val section = event.section
            when (event.action) {
                HomeSectionEvent.ADD -> section?.let { viewModel.addSection(tabTitle, it) }
                HomeSectionEvent.UPDATE -> section?.let { viewModel.updateSection(tabTitle, it) }
                HomeSectionEvent.REMOVE -> section?.let { viewModel.removeSection(tabTitle, it) }
                HomeSectionEvent.REORDER -> viewModel.reorderSections(tabTitle)
            }
        }
    }

    /**
     * 重建该 tab 内容：非无限流展示项渲染进 header；无限流（最多一个、强制最后）
     * 由发现页 Grid/Video 适配器以网格项承载，header 作为其唯一头部随之滚动。
     */
    private fun renderSections(sections: List<HomeSection>) {
        binding.tvEmpty.isVisible = sections.isEmpty()
        binding.rvHome.isVisible = sections.isNotEmpty()
        sectionViews.setSections(sections, viewModel.stateOf(tabTitle).sectionBooksMap)

        val infinite = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
        val coverVideo = infinite?.coverVideo == true
        val canReuse = gridAdapter != null &&
            infinite?.id == lastInfiniteId &&
            coverVideo == lastCoverVideo
        if (canReuse) {
            if (infinite != null) {
                gridAdapter?.setItems(viewModel.sectionBooks(tabTitle, infinite.id))
            }
            return
        }
        lastInfiniteId = infinite?.id
        lastCoverVideo = coverVideo

        val cb = object : BaseExploreShowAdapter.CallBack {
            override fun isInBookshelf(book: BaseBook) = false
            override fun showBookInfo(book: BaseBook, longClick: Boolean) {
                val s = infinite ?: return
                (book as? SearchBook)?.let { sectionCallback.onBookClick(it, s, longClick) }
            }
        }
        val adapter = if (coverVideo) {
            VideoExploreShowAdapter(requireContext(), cb)
        } else {
            GridExploreShowAdapter(requireContext(), cb)
        }
        adapter.addHeaderView { ViewBinding { headerView } }
        val hasInfinite = infinite != null
        if (hasInfinite) {
            adapter.addFooterView { ViewLoadMoreBinding.bind(loadMoreView) }
        }
        gridAdapter = adapter
        binding.rvHome.adapter = adapter
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val a = gridAdapter ?: return 1
                return when {
                    position == 0 -> 2
                    hasInfinite && position == a.itemCount - 1 -> 2
                    else -> 1
                }
            }
        }
        if (infinite != null) {
            loadMoreView.startLoad()
            adapter.setItems(viewModel.sectionBooks(tabTitle, infinite.id))
        }
    }

    companion object {
        private const val ARG_TAB_TITLE = "tabTitle"
    }
}
