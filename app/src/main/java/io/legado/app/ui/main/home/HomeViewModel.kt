package io.legado.app.ui.main.home

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelp
import io.legado.app.model.webBook.WebBook.getBookListAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页外壳 ViewModel：持有所有 tab 的状态，按 tabTitle 分桶。
 * HomeFragment 通过 viewModels() 拿外壳 VM，HomeTabFragment 通过
 * viewModels(ownerProducer = { requireParentFragment() }) 共享同一实例，
 * 订阅 LiveData 时按 tabTitle 过滤即可。
 */
class HomeViewModel(application: Application) : BaseViewModel(application) {

    /** 内层状态：每个 tab 一份，Fragment 销毁不丢分页/缓存 */
    class TabState {
        var initialized: Boolean = false
        val sectionBooksMap = mutableMapOf<String, List<SearchBook>>()
        val loadingSet = mutableSetOf<String>()
        var infiniteSection: HomeSection? = null
        var infinitePage: Int = 1
        var infiniteHasMore: Boolean = true
        var infiniteLoading: Boolean = false
        val infiniteBookSet = linkedSetOf<SearchBook>()
    }

    val tabsLiveData = MutableLiveData<List<HomeTab>>()

    /** 携带 tabTitle：该 tab 的 sections 列表已变更（顺序/增删/样式） */
    val sectionsLiveData = MutableLiveData<String>()

    /** 携带 tabTitle + sectionId：该展示项书籍数据已更新 */
    val sectionUpdated = MutableLiveData<Pair<String, String>>()

    /** 携带 tabTitle + sectionId：该展示项加载状态变化 */
    val sectionLoadingChanged = MutableLiveData<Pair<String, String>>()

    /** 携带 tabTitle + sectionId：该展示项书源失效，UI 需展示错误占位 */
    val sectionErrorChanged = MutableLiveData<Pair<String, String>>()

    private val tabStates = mutableMapOf<String, TabState>()

    fun stateOf(tabTitle: String): TabState = tabStates.getOrPut(tabTitle) { TabState() }

    fun isLoading(tabTitle: String, sectionId: String) =
        stateOf(tabTitle).loadingSet.contains(sectionId)

    fun hasMoreInfinite(tabTitle: String) = stateOf(tabTitle).infiniteHasMore

    fun infiniteSection(tabTitle: String) = stateOf(tabTitle).infiniteSection

    fun sectionBooks(tabTitle: String, sectionId: String): List<SearchBook> =
        stateOf(tabTitle).sectionBooksMap[sectionId] ?: emptyList()

    // ─── 加载入口 ────────────────────────────────────────────────────────

    fun initTabs() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { HomeTabHelp.getTabs() }
            tabsLiveData.value = list
        }
    }

    /** 单个 tab 首次初始化：拉取其下所有展示项。已初始化则跳过（Fragment 重建走这里） */
    fun initTab(tabTitle: String) {
        val state = stateOf(tabTitle)
        if (state.initialized) return
        state.initialized = true
        val sections = HomeTabHelp.getSections(tabTitle)
        state.infiniteSection = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
        sections.forEach { loadSection(tabTitle, it) }
    }

    fun refreshTab(tabTitle: String) {
        HomeTabHelp.getSections(tabTitle).forEach { loadSection(tabTitle, it) }
    }

    private fun loadSection(tabTitle: String, section: HomeSection) {
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            loadInfinite(tabTitle, resetPage = true)
            return
        }
        val state = stateOf(tabTitle)
        state.loadingSet.add(section.id)
        sectionLoadingChanged.postValue(tabTitle to section.id)
        execute {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
            if (source == null) {
                sectionErrorChanged.postValue(tabTitle to section.id)
                return@execute
            }
            val result = getBookListAwait(source, section.exploreUrl, 1, isSearch = false)
            state.sectionBooksMap[section.id] = result.books
            sectionUpdated.postValue(tabTitle to section.id)
        }.onError {
            AppLog.put("主页[$tabTitle]展示项[${section.title}]加载失败", it)
            sectionErrorChanged.postValue(tabTitle to section.id)
        }.onFinally {
            state.loadingSet.remove(section.id)
            sectionLoadingChanged.postValue(tabTitle to section.id)
        }
    }

    fun loadInfinite(tabTitle: String, resetPage: Boolean = false) {
        val state = stateOf(tabTitle)
        val section = state.infiniteSection ?: return
        if (state.infiniteLoading) return
        if (!resetPage && !state.infiniteHasMore) return
        if (resetPage) {
            state.infinitePage = 1
            state.infiniteBookSet.clear()
            state.infiniteHasMore = true
        }
        state.infiniteLoading = true
        state.loadingSet.add(section.id)
        sectionLoadingChanged.postValue(tabTitle to section.id)
        execute {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
                ?: return@execute
            val result = getBookListAwait(
                source, section.exploreUrl, state.infinitePage, isSearch = false
            )
            state.infiniteBookSet.addAll(result.books)
            state.infiniteHasMore = result.hasNextPage && result.books.isNotEmpty()
            state.sectionBooksMap[section.id] = state.infiniteBookSet.toList()
            state.infinitePage++
            sectionUpdated.postValue(tabTitle to section.id)
        }.onError {
            AppLog.put("主页[$tabTitle]无限流[${section.title}]加载失败", it)
        }.onFinally {
            state.infiniteLoading = false
            state.loadingSet.remove(section.id)
            sectionLoadingChanged.postValue(tabTitle to section.id)
        }
    }

    // ─── 增量变更（来自管理对话框）──────────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            state.infiniteSection = section
        }
        sectionsLiveData.postValue(tabTitle)
        loadSection(tabTitle, section)
    }

    fun updateSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        val old = HomeTabHelp.getSections(tabTitle).find { it.id == section.id }
        val sourceChanged = old == null ||
            old.sourceUrl != section.sourceUrl ||
            old.exploreUrl != section.exploreUrl
        when {
            section.style == HomeSection.STYLE_INFINITE_GRID -> state.infiniteSection = section
            old?.style == HomeSection.STYLE_INFINITE_GRID -> {
                state.infiniteSection = null
                state.infiniteBookSet.clear()
            }
        }
        sectionsLiveData.postValue(tabTitle)
        if (sourceChanged) {
            if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                state.infiniteBookSet.clear()
                state.infinitePage = 1
                state.infiniteHasMore = true
            } else {
                state.sectionBooksMap.remove(section.id)
            }
            loadSection(tabTitle, section)
        }
    }

    fun removeSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        state.sectionBooksMap.remove(section.id)
        state.loadingSet.remove(section.id)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            state.infiniteSection = null
            state.infiniteBookSet.clear()
        }
        sectionsLiveData.postValue(tabTitle)
    }

    fun reorderSections(tabTitle: String) {
        sectionsLiveData.postValue(tabTitle)
    }

    // ─── Tab 结构变化 ─────────────────────────────────────────────────────

    /**
     * Tab 结构变更后刷新；可选迁移/移除某 TabState 以保持已加载的分页/缓存。
     */
    fun onTabsChanged(rename: Pair<String, String>? = null, removed: String? = null) {
        rename?.let { (old, new) -> tabStates.remove(old)?.let { tabStates[new] = it } }
        removed?.let { tabStates.remove(it) }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { HomeTabHelp.getTabs() }
            tabsLiveData.value = list
        }
    }
}
