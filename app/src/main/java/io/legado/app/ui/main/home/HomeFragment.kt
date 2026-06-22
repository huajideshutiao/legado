package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.FragmentHomeBinding
import io.legado.app.databinding.ViewLoadMoreBinding
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
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HomeFragment() : VMBaseFragment<HomeViewModel>(R.layout.fragment_home),
    MainFragmentInterface, HomeSectionEditDialog.Callback {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentHomeBinding::bind)
    override val viewModel by viewModels<HomeViewModel>()

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

    /** 非无限流展示项的 header 视图构建器 */
    private val sectionViews by lazy {
        HomeSectionAdapter(requireContext(), headerView, sectionCallback)
    }

    /** 当前承载无限流书籍的发现适配器（无无限流时为 null） */
    private var gridAdapter: BaseExploreShowAdapter<*>? = null

    /** 无限流底部加载视图（与发现页一致） */
    private val loadMoreView by lazy { LoadMoreView(requireContext()) }

    private val sectionCallback = object : HomeSectionAdapter.Callback {
        override fun onBookClick(book: SearchBook, section: HomeSection, longClick: Boolean) {
            // 与发现列表 ExploreShowActivity.showBookInfo 保持一致：
            // 长按或非开发者模式 → 进简介；点击按书籍类型分发（视频/RSS/详情）
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

        override fun isLoading(sectionId: String) = viewModel.isLoading(sectionId)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            viewModel.refresh()
        }
        binding.rvHome.layoutManager = glm
        binding.rvHome.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (glm.findLastVisibleItemPosition() >= glm.itemCount - 4) {
                    viewModel.loadInfinite()
                }
            }
        })
        observeData()
        viewModel.init()
    }

    private fun observeData() {
        viewModel.sectionsLiveData.observe(viewLifecycleOwner) { sections ->
            renderSections(sections)
        }
        viewModel.sectionUpdated.observe(viewLifecycleOwner) { sectionId ->
            val books = viewModel.sectionBooksMap[sectionId] ?: return@observe
            if (sectionId == viewModel.infiniteSection?.id) {
                val adapter = gridAdapter ?: return@observe
                val current = adapter.getActualItemCount()
                when {
                    // 翻页：仅追加新增部分，不替换已展示内容
                    books.size > current -> adapter.addItems(books.subList(current, books.size))
                    // 重置（刷新/换源）：整体替换
                    books.size < current -> adapter.setItems(books)
                }
            } else {
                sectionViews.updateSectionBooks(sectionId, books)
            }
        }
        viewModel.sectionLoadingChanged.observe(viewLifecycleOwner) { sectionId ->
            if (sectionId == viewModel.infiniteSection?.id) {
                // 无限流加载状态展示在底部 LoadMoreView，而非展示项标题
                when {
                    viewModel.isLoading(sectionId) -> loadMoreView.startLoad()
                    !viewModel.hasMoreInfinite -> loadMoreView.noMore()
                    else -> loadMoreView.stopLoad()
                }
            } else {
                sectionViews.updateSectionLoading(sectionId)
            }
        }
    }

    /**
     * 重建主页：非无限流展示项渲染进 header；无限流（最多一个、强制最后）书籍由
     * 发现页 Grid/Video 适配器以网格项承载，header 作为其唯一头部随之滚动。
     */
    private fun renderSections(sections: List<HomeSection>) {
        binding.tvEmpty.isVisible = sections.isEmpty()
        binding.rvHome.isVisible = sections.isNotEmpty()
        sectionViews.setSections(sections, viewModel.sectionBooksMap)

        val infinite = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
        val cb = object : BaseExploreShowAdapter.CallBack {
            override fun isInBookshelf(book: BaseBook) = false
            override fun showBookInfo(book: BaseBook, longClick: Boolean) {
                val s = infinite ?: return
                (book as? SearchBook)?.let { sectionCallback.onBookClick(it, s, longClick) }
            }
        }
        // 视频比例用发现页视频适配器，否则用网格适配器；均为 2 列（glm）
        val adapter = if (infinite?.coverVideo == true) {
            VideoExploreShowAdapter(requireContext(), cb)
        } else {
            GridExploreShowAdapter(requireContext(), cb)
        }
        // ViewBinding 只需 getRoot()，匿名包一下代码创建的 headerView 即可
        adapter.addHeaderView { ViewBinding { headerView } }
        val hasInfinite = infinite != null
        if (hasInfinite) {
            adapter.addFooterView { ViewLoadMoreBinding.bind(loadMoreView) }
        }
        gridAdapter = adapter
        binding.rvHome.adapter = adapter
        // RecyclerAdapter 默认 header/footer 跨列为 1，这里覆盖为整行
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
            adapter.setItems(viewModel.sectionBooksMap[infinite.id] ?: emptyList())
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_home, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_manage_section ->
                showDialogFragment(HomeSectionManageDialog())
        }
    }

    /**
     * 展示项变更回调（来自管理对话框转发，或长按编辑/删除直接触发）。
     * 仅对变更的单项做增量处理，已加载的项不重新拉取。
     */
    override fun onHomeSectionChanged(action: String, section: HomeSection?) {
        when (action) {
            "add" -> section?.let { viewModel.addSection(it) }
            "update" -> section?.let { viewModel.updateSection(it) }
            "delete" -> section?.let { viewModel.removeSection(it) }
            "reorder" -> viewModel.reorderSections()
        }
    }
}
