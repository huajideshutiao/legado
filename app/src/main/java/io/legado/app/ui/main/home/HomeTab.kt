package io.legado.app.ui.main.home

import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelp
import io.legado.app.help.IntentData
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.utils.eventObservable
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch

/**
 * 主页壳层状态（原 HomeFragment 上浮）：snapshot state 由 [HomeViewModel](activity 级)
 * 的 LiveData 推入；UI 见 shared 端 [HomeScreen]。
 *
 * 实现 [HomeUiActions] 将 Android 专属回调（showDialogFragment / vm.refreshTab /
 * vm.loadInfinite / vm.initTab）桥接到 shared 端 HomeScreen。三个 slot
 * (SectionBlock / InfiniteHeader / InfiniteGridCard) 由 [HomeTab] Composable 内闭包
 * 注入, 保留 HomeSectionComposables.kt 的 AndroidView/ShelfCover L3 依赖。
 */
class HomeTabState(
    private val activity: AppCompatActivity,
    val viewModel: HomeViewModel,
) : HomeUiActions {

    var tabs by mutableStateOf<List<HomeTab>>(emptyList())
        private set

    /** Pager 当前页(纯变量, 仅初始化读、翻页由 [onPageChanged] 写; 重建恢复靠 rememberPagerState 自身) */
    var currentPage: Int = 0
        private set

    /** 每个 tab 的展示项列表 (shared 端 HomeUiState 直读) */
    val tabSections = mutableStateMapOf<String, List<HomeSection>>()

    /** sectionKey(tabTitle + " " + sectionId) -> 该展示项的书籍列表 (shared 端 HomeUiState 直读) */
    val booksState = mutableStateMapOf<String, List<SearchBook>>()

    /** 同 key -> 该展示项是否加载中 (shared 端 HomeUiState 直读) */
    val loadingState = mutableStateMapOf<String, Boolean>()

    /** 同 key -> 该展示项是否加载出错 (shared 端 HomeUiState 直读) */
    val errorState = mutableStateMapOf<String, Boolean>()

    private val optionsVersion = mutableStateMapOf<String, Int>()

    /** tabTitle -> 该 tab 的无限流是否还有更多 (shared 端 HomeUiState 直读) */
    val hasMoreState = mutableStateMapOf<String, Boolean>()

    private fun key(tabTitle: String, sectionId: String) = "$tabTitle $sectionId"

    // ---- LiveData/事件接线（HomeTab 注册） ----

    fun onTabs(newTabs: List<HomeTab>) {
        tabs = newTabs
        newTabs.forEach { tab -> tabSections[tab.title] = HomeTabHelp.getSections(tab.title) }
    }

    fun onSectionsChanged(tabTitle: String) {
        tabSections[tabTitle] = HomeTabHelp.getSections(tabTitle)
    }

    fun onSectionUpdated(tabTitle: String, sectionId: String) {
        booksState[key(tabTitle, sectionId)] = viewModel.sectionBooks(tabTitle, sectionId)
        errorState[key(tabTitle, sectionId)] = false
    }

    fun onSectionLoadingChanged(tabTitle: String, sectionId: String) {
        loadingState[key(tabTitle, sectionId)] = viewModel.isLoading(tabTitle, sectionId)
        if (sectionId == viewModel.infiniteSection(tabTitle)?.id) {
            hasMoreState[tabTitle] = viewModel.hasMoreInfinite(tabTitle)
        }
    }

    fun onSectionError(tabTitle: String, sectionId: String) {
        errorState[key(tabTitle, sectionId)] = true
        loadingState[key(tabTitle, sectionId)] = false
    }

    fun onSectionOptionsChanged(tabTitle: String, sectionId: String) {
        val k = key(tabTitle, sectionId)
        optionsVersion[k] = (optionsVersion[k] ?: 0) + 1
    }

    fun onHomeTabEvent(event: HomeTabEvent) {
        viewModel.onTabsChanged(
            rename = if (event.action == HomeTabEvent.RENAME && event.oldTitle != null && event.newTitle != null)
                event.oldTitle to event.newTitle else null,
            removed = if (event.action == HomeTabEvent.REMOVE) event.oldTitle else null
        )
    }

    fun onHomeSectionEvent(event: HomeSectionEvent) {
        val section = event.section
        when (event.action) {
            HomeSectionEvent.ADD -> section?.let { viewModel.addSection(event.tabTitle, it) }
            HomeSectionEvent.UPDATE -> section?.let { viewModel.updateSection(event.tabTitle, it) }
            HomeSectionEvent.REMOVE -> section?.let { viewModel.removeSection(event.tabTitle, it) }
            HomeSectionEvent.REORDER -> viewModel.reorderSections(event.tabTitle)
        }
    }

    // ---- HomeUiActions 桥接实现 ----

    /** 页可见(对照 HomeTabFragment.onResume→initTab)：首次拉取该 tab 全部展示项 */
    override fun onPageVisible(tabTitle: String) {
        tabSections.getOrPut(tabTitle) { HomeTabHelp.getSections(tabTitle) }
        viewModel.initTab(tabTitle)
    }

    /** 页变化(由 shared 端 Pager 派发, 用于持久化当前页) */
    override fun onPageChanged(page: Int) {
        currentPage = page
    }

    override fun refreshTab(tabTitle: String) = viewModel.refreshTab(tabTitle)

    override fun loadInfinite(tabTitle: String) = viewModel.loadInfinite(tabTitle)

    override fun openManageTab() {
        activity.showDialogFragment(HomeTabManageDialog())
    }

    override fun openManageSection() {
        val tabTitle = currentTabTitle ?: return
        activity.showDialogFragment(HomeSectionManageDialog.newInstance(tabTitle))
    }

    // ---- HomeSectionComposables 读取辅助 ----

    fun sectionBooks(tabTitle: String, sectionId: String): List<SearchBook> =
        booksState[key(tabTitle, sectionId)] ?: emptyList()

    fun isSectionLoading(tabTitle: String, sectionId: String): Boolean =
        loadingState[key(tabTitle, sectionId)] ?: false

    fun sectionHasError(tabTitle: String, sectionId: String): Boolean =
        errorState[key(tabTitle, sectionId)] ?: false

    fun infiniteHasMore(tabTitle: String): Boolean = hasMoreState[tabTitle] ?: true

    fun sectionOptions(tabTitle: String, sectionId: String): List<ExploreOption> =
        viewModel.sectionOptions(tabTitle, sectionId)

    fun optionsVersionOf(tabTitle: String, sectionId: String): Int =
        optionsVersion[key(tabTitle, sectionId)] ?: 0

    fun onOptionSelected(tabTitle: String, section: HomeSection) =
        viewModel.onSectionOptionSelected(tabTitle, section)

    fun onMoreClick(section: HomeSection) {
        IntentData.source = null
        activity.startActivity<ExploreShowActivity> {
            putExtra("exploreName", section.exploreName)
            putExtra("exploreUrl", section.exploreUrl)
            putExtra("sourceUrl", section.sourceUrl)
        }
    }

    fun onBookClick(book: SearchBook, section: HomeSection, longClick: Boolean) {
        val urlParts = book.bookUrl.split("::", limit = 2)
        if (urlParts.size == 2) {
            IntentData.source = null
            activity.startActivity<ExploreShowActivity> {
                putExtra("exploreName", urlParts[0])
                putExtra("exploreUrl", urlParts[1])
                putExtra("sourceUrl", section.sourceUrl)
            }
            return
        }
        IntentData.book = book
        when {
            longClick || !AppConfig.devFeat -> activity.startActivity<BookInfoActivity> {
                putExtra("name", book.name)
                putExtra("author", book.author)
            }

            book.isVideo -> activity.startActivity<VideoPlayActivity>()
            book.isRss -> activity.startActivity<ReadRssActivity>()
            else -> activity.startActivity<BookInfoActivity>()
        }
    }

    /** 当前选中 tab 的标题；用于"管理展示项"快捷入口 */
    private val currentTabTitle: String?
        get() = tabs.getOrNull(currentPage)?.title
}

/** 主页 tab（状态上浮）：装配状态类 + LiveData/事件副作用，再渲染 shared 端 [HomeScreen]。 */
@Composable
fun HomeTab() {
    val activity = LocalActivity.current as AppCompatActivity
    val viewModel = remember(activity) { ViewModelProvider(activity)[HomeViewModel::class.java] }
    val state = remember { HomeTabState(activity, viewModel) }
    HomeEffects(state)
    HomeScreen(
        state = HomeUiState(
            tabs = state.tabs,
            currentPage = state.currentPage,
            tabSections = state.tabSections,
            sectionBooks = state.booksState,
            sectionLoading = state.loadingState,
            sectionError = state.errorState,
            infiniteHasMore = state.hasMoreState,
        ),
        actions = state,
        sectionBlockSlot = { tabTitle, section -> SectionBlock(state, tabTitle, section) },
        infiniteHeaderSlot = { tabTitle, section -> InfiniteHeader(state, tabTitle, section) },
        infiniteGridCardSlot = { tabTitle, section, book -> InfiniteGridCard(state, tabTitle, section, book) },
    )
}

/** 壳层副作用：HomeViewModel LiveData + HOME_TAB/HOME_SECTION 事件（对照原 onViewCreated）。 */
@Composable
private fun HomeEffects(state: HomeTabState) {
    val owner = LocalLifecycleOwner.current
    val vm = state.viewModel
    DisposableEffect(state, owner) {
        val tabsObserver = Observer<List<HomeTab>> { state.onTabs(it) }
        val sectionsObserver = Observer<String> { state.onSectionsChanged(it) }
        val updatedObserver = Observer<Pair<String, String>> { (t, s) -> state.onSectionUpdated(t, s) }
        val loadingObserver = Observer<Pair<String, String>> { (t, s) -> state.onSectionLoadingChanged(t, s) }
        val errorObserver = Observer<Pair<String, String>> { (t, s) -> state.onSectionError(t, s) }
        val optionsObserver = Observer<Pair<String, String>> { (t, s) -> state.onSectionOptionsChanged(t, s) }
        vm.tabsLiveData.observe(owner, tabsObserver)
        vm.sectionsLiveData.observe(owner, sectionsObserver)
        vm.sectionUpdated.observe(owner, updatedObserver)
        vm.sectionLoadingChanged.observe(owner, loadingObserver)
        vm.sectionErrorChanged.observe(owner, errorObserver)
        vm.sectionOptionsChanged.observe(owner, optionsObserver)
        onDispose {
            vm.tabsLiveData.removeObserver(tabsObserver)
            vm.sectionsLiveData.removeObserver(sectionsObserver)
            vm.sectionUpdated.removeObserver(updatedObserver)
            vm.sectionLoadingChanged.removeObserver(loadingObserver)
            vm.sectionErrorChanged.removeObserver(errorObserver)
            vm.sectionOptionsChanged.removeObserver(optionsObserver)
        }
    }
    LaunchedEffect(state) {
        vm.initTabs()
        launch {
            eventObservable(EventBus.HOME_TAB).collect {
                (it as? HomeTabEvent)?.let(state::onHomeTabEvent)
            }
        }
        launch {
            eventObservable(EventBus.HOME_SECTION).collect {
                (it as? HomeSectionEvent)?.let(state::onHomeSectionEvent)
            }
        }
    }
}
