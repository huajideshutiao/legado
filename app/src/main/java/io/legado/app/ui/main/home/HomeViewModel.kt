package io.legado.app.ui.main.home

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.ExploreOption
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel (Android 壳)。
 *
 * 持有 [HomeViewModelShared] (commonMain 共享核心) 实例, 把 6 个 StateFlow 桥接为
 * 6 个 LiveData, 调用方 observe 用法不变 (对照 [HomeTabState] 注册的 6 个 Observer)。
 *
 * 所有方法直接转发到 [shared], 子类签名/语义不变。桌面端/iOS 端可直接使用
 * [HomeViewModelShared] 无需本壳。
 */
class HomeViewModel(application: Application) : BaseViewModel(application) {

    private val shared = HomeViewModelShared(viewModelScope)

    val tabsLiveData = MutableLiveData<List<HomeTab>>()
    val sectionsLiveData = MutableLiveData<String>()
    val sectionUpdated = MutableLiveData<Pair<String, String>>()
    val sectionLoadingChanged = MutableLiveData<Pair<String, String>>()
    val sectionErrorChanged = MutableLiveData<Pair<String, String>>()
    val sectionOptionsChanged = MutableLiveData<Pair<String, String>>()

    init {
        // StateFlow (可空, 初始 null) → LiveData 桥接, 过滤 null 避免初始假触发
        viewModelScope.launch { shared.tabsFlow.collect { it?.let(tabsLiveData::postValue) } }
        viewModelScope.launch { shared.sectionsFlow.collect { it?.let(sectionsLiveData::postValue) } }
        viewModelScope.launch { shared.sectionUpdatedFlow.collect { it?.let(sectionUpdated::postValue) } }
        viewModelScope.launch { shared.sectionLoadingChangedFlow.collect { it?.let(sectionLoadingChanged::postValue) } }
        viewModelScope.launch { shared.sectionErrorChangedFlow.collect { it?.let(sectionErrorChanged::postValue) } }
        viewModelScope.launch { shared.sectionOptionsChangedFlow.collect { it?.let(sectionOptionsChanged::postValue) } }
    }

    fun stateOf(tabTitle: String) = shared.stateOf(tabTitle)

    fun isLoading(tabTitle: String, sectionId: String) =
        shared.isLoading(tabTitle, sectionId)

    fun hasMoreInfinite(tabTitle: String) = shared.hasMoreInfinite(tabTitle)

    fun infiniteSection(tabTitle: String) = shared.infiniteSection(tabTitle)

    fun sectionBooks(tabTitle: String, sectionId: String): List<SearchBook> =
        shared.sectionBooks(tabTitle, sectionId)

    fun sectionOptions(tabTitle: String, sectionId: String): List<ExploreOption> =
        shared.sectionOptions(tabTitle, sectionId)

    // ─── 加载入口 ────────────────────────────────────────────────────────

    fun initTabs() = shared.initTabs()

    fun initTab(tabTitle: String) = shared.initTab(tabTitle)

    fun refreshTab(tabTitle: String) = shared.refreshTab(tabTitle)

    fun onSectionOptionSelected(tabTitle: String, section: HomeSection) =
        shared.onSectionOptionSelected(tabTitle, section)

    fun loadInfinite(tabTitle: String, resetPage: Boolean = false) =
        shared.loadInfinite(tabTitle, resetPage)

    // ─── 增量变更 (来自管理对话框) ──────────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) =
        shared.addSection(tabTitle, section)

    fun updateSection(tabTitle: String, section: HomeSection) =
        shared.updateSection(tabTitle, section)

    fun removeSection(tabTitle: String, section: HomeSection) =
        shared.removeSection(tabTitle, section)

    fun reorderSections(tabTitle: String) = shared.reorderSections(tabTitle)

    // ─── Tab 结构变化 ─────────────────────────────────────────────────────

    fun onTabsChanged(rename: Pair<String, String>? = null, removed: String? = null) =
        shared.onTabsChanged(rename, removed)
}
