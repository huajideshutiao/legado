package io.legado.app.ui.main.explore

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.ui.widget.recycler.AccurateScrollRangeLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.databinding.FragmentRecyclerViewBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.help.IntentData
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.space
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.views.onLongClick

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_recycler_view),
    MainFragmentInterface, ExploreAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentRecyclerViewBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { AccurateScrollRangeLayoutManager(context) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var pinnedBinding: ExplorePinnedBinding? = null
    private var pinnedHeaderAdded = false
    private val pinnedHeader: (parent: ViewGroup) -> ViewBinding by lazy {
        { parent ->
            createExplorePinnedBinding(parent).also {
                pinnedBinding = it
                upPinned()
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initTitleBar()
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        initRecyclerView()
        initGroupData()
        upExploreData()
        upPinned()
        observeEvent<String>(EventBus.UP_EXPLORE_PINNED) {
            upPinned(it)
        }
        observeEvent<Boolean>(EventBus.REFRESH_EXPLORE) {
            adapter.refreshCurrentExpanded()
        }
    }

    private fun initTitleBar() {
        binding.titleBar.isVisible = true
        binding.titleBar.setTitle(R.string.discovery)
        // view_search 作为 toolbar 的 content 显示在顶栏同行内部
        // (与原 fragment_explore.xml 的 app:contentLayout 行为一致,
        //  TitleBar 把 contentLayout inflate 到 toolbar, 而非 TitleBar 自身)
        layoutInflater.inflate(R.layout.view_search, binding.titleBar.toolbar, true)
    }

    @SuppressLint("SetTextI18n")
    private fun upPinned(command: String = "") {
        val explores = PinnedExploreHelp.getPinnedExplores()
        if (explores.isEmpty()) {
            if (pinnedHeaderAdded) {
                adapter.removeHeaderView(pinnedHeader)
                pinnedHeaderAdded = false
            }
            return
        } else if (!pinnedHeaderAdded) {
            adapter.addHeaderView(pinnedHeader)
            pinnedHeaderAdded = true
            pinnedBinding?.let { upPinned() }
            return
        }
        val binding = pinnedBinding ?: return
        binding.flexbox.run {
            when {
                command == "add" -> bindPinnedItem(explores.last(), childCount)

                command.startsWith("remove:") -> {
                    val index = command.substring(7).toIntOrNull() ?: return@run
                    if (index < childCount) {
                        removeViewAt(index)
                    }
                }

                else -> {
                    val childCount = childCount
                    val dataSize = explores.size
                    if (childCount > dataSize) {
                        removeViews(dataSize, childCount - dataSize)
                    }
                    explores.forEachIndexed { index, pinned ->
                        bindPinnedItem(pinned, index)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun ViewGroup.bindPinnedItem(pinned: PinnedExplore, index: Int) {
        val itemBinding = if (index < childCount) {
            ItemFilletTextBinding.bind(getChildAt(index))
        } else {
            ItemFilletTextBinding.inflate(layoutInflater, this, true)
        }
        if (itemBinding.root.tag != pinned) {
            itemBinding.root.tag = pinned
            itemBinding.textView.text = "${pinned.sourceName}-${pinned.categoryName}"
            itemBinding.root.setOnClickListener {
                Coroutine.async {
                    appDb.bookSourceDao.getBookSource(pinned.sourceUrl)
                }.onSuccess { source ->
                    if (source != null) {
                        openExplore(source, pinned.categoryName, pinned.categoryUrl)
                    } else {
                        toastOnUi("Source not found")
                    }
                }
            }
            itemBinding.root.onLongClick {
                requireContext().alert(R.string.draw) {
                    setMessage(getString(R.string.sure_del) + "\n${pinned.sourceName}-${pinned.categoryName}")
                    yesButton {
                        PinnedExploreHelp.removePinnedExplore(pinned)
                    }
                    noButton()
                }.show()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search_book_source)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upExploreData(newText)
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.refreshLayout.isEnabled = false
        binding.tvEmptyMsg.setText(R.string.explore_empty)
        val padding = space.md
        binding.recyclerView.setPadding(padding, 0, padding, 0)
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups().flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).conflate().distinctUntilChanged().collect {
                groups.clear()
                groups.addAll(it)
                upGroupsMenu()
                delay(500)
            }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookSourceDao.flowExplore()
                searchKey.startsWith("group:") -> appDb.bookSourceDao.flowGroupExplore(
                    searchKey.substringAfter(
                        "group:"
                    )
                )

                else -> appDb.bookSourceDao.flowExplore(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || searchView.query.isNotEmpty()
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (item.groupId == R.id.menu_group_text) {
            searchView.setQuery("group:${item.title}", true)
        }
    }

    override fun openExplore(source: BookSource, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        IntentData.source = source
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    fun compressExplore() {
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.recyclerView.scrollToPosition(0)
            } else {
                binding.recyclerView.smoothScrollToPosition(0)
            }
        }
    }

    class ExplorePinnedBinding(
        val root: LinearLayout,
        val flexbox: FlexboxLayout
    ) : ViewBinding {
        override fun getRoot(): View = root
    }

    private fun createExplorePinnedBinding(
        parent: ViewGroup
    ): ExplorePinnedBinding {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }
        val tvTitle = TextView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(ctx.space.xs, 0, 0, 0)
            setText(R.string.favorite)
        }
        val flexbox = FlexboxLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            flexWrap = com.google.android.flexbox.FlexWrap.WRAP
        }
        root.addView(tvTitle)
        root.addView(flexbox)
        return ExplorePinnedBinding(root, flexbox)
    }

}
