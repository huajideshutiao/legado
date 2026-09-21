package io.legado.app.ui.main.home

import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HomeSection
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主页 shared ScreenModel：组合委托 [HomeViewModelShared], 把 6 个 StateFlow 投影为
 * 单个 [HomeUiState] StateFlow, 并实现 [HomeUiActions]。
 *
 * 对照 app 端 [io.legado.app.ui.main.home.HomeTabState] + HomeViewModel 的数据流:
 * - 6 个 LiveData Observer → tabs 用 StateFlow, 5 个事件用 SharedFlow collector
 * - onPageVisible/onPageChanged/refreshTab/loadInfinite 直接转发到 [viewModelShared]
 * - openManageSection/openManageTab 只翻转 [manageSectionOf]/[manageTab] 状态,
 *   由 MainRoute 据此弹 shared Compose 对话框 (HomeSectionManageDialog/HomeTabManageDialog)
 * - HomePageVisible 内 tabSections.getOrPut → StateFlow.update 幂等写入
 *
 * 事件总线 (HOME_TAB/HOME_SECTION) 对照 HomeFragment/HomeTabFragment 的 observeEvent:
 * 管理对话框改完经 [FlowBus] 回流到本类, 转发给 [viewModelShared] 做增量更新。
 *
 * 模式参考 [io.legado.app.ui.main.explore.ExploreScreenModel]。
 */
class HomeScreenModel : ScreenModel, HomeUiActions {

    // 自管 scope (ScreenModelStore 调 onCleared 时取消)
    private val scope = screenModelScope("主页")

    // 共享核心 (commonMain), 内部走 AppDbProviders + HomeTabHelpShared
    private val viewModelShared = HomeViewModelShared(scope)

    private val _state = MutableStateFlow(
        HomeUiState(
            tabs = emptyList(),
            currentPage = 0,
            tabSections = emptyMap(),
            sectionBooks = emptyMap(),
            sectionLoading = emptyMap(),
            sectionError = emptyMap(),
            sectionOptions = emptyMap(),
            sectionOptionsVersion = 0,
            infiniteHasMore = emptyMap(),
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** "管理展示项"对话框的目标 tab (非 null 即显示); 由 MainRoute 订阅后弹 shared 对话框 */
    private val _manageSectionOf = MutableStateFlow<String?>(null)
    val manageSectionOf: StateFlow<String?> = _manageSectionOf.asStateFlow()

    /** "管理分组"对话框是否显示; 由 MainRoute 订阅后弹 shared 对话框 */
    private val _manageTab = MutableStateFlow(false)
    val manageTab: StateFlow<Boolean> = _manageTab.asStateFlow()

    init {
        observeViewModelShared()
        observeHomeEvents()
        viewModelShared.initTabs()
    }

    // ─── StateFlow → HomeUiState 投影 (对照 HomeTabState 的 6 个 Observer) ─────────

    /** tabsFlow: 刷新 tabs + 预填 tabSections (对照 HomeTabState.onTabs) */
    private fun observeTabs() = scope.launch {
        viewModelShared.tabsFlow.collect { tabs ->
            if (tabs != null) {
                val newTabSections =
                    tabs.associate { it.title to HomeTabHelpShared.getSections(it.title) }
                _state.update { it.copy(tabs = tabs, tabSections = newTabSections) }
            }
        }
    }

    /** sectionsFlow: 该 tab 的展示项列表已变更 (对照 HomeTabState.onSectionsChanged) */
    private fun observeSections() = scope.launch {
        viewModelShared.sectionsFlow.collect { tabTitle ->
            val sections = HomeTabHelpShared.getSections(tabTitle)
            _state.update { it.copy(tabSections = it.tabSections + (tabTitle to sections)) }
        }
    }

    /** sectionUpdatedFlow: 该展示项书籍已更新, 同时清除 error (对照 HomeTabState.onSectionUpdated) */
    private fun observeSectionUpdated() = scope.launch {
        viewModelShared.sectionUpdatedFlow.collect { (tabTitle, sectionId) ->
            val key = homeSectionKey(tabTitle, sectionId)
            val books = viewModelShared.sectionBooks(tabTitle, sectionId)
            _state.update {
                it.copy(
                    sectionBooks = it.sectionBooks + (key to books),
                    sectionError = it.sectionError + (key to false),
                )
            }
        }
    }

    /** sectionLoadingChangedFlow: 加载态变化; 若为无限流 section 同步刷新 hasMore (对照 onSectionLoadingChanged) */
    private fun observeSectionLoading() = scope.launch {
        viewModelShared.sectionLoadingChangedFlow.collect { (tabTitle, sectionId) ->
            val key = homeSectionKey(tabTitle, sectionId)
            val loading = viewModelShared.isLoading(tabTitle, sectionId)
            val infiniteSection = viewModelShared.infiniteSection(tabTitle)
            _state.update { st ->
                val newLoading = st.sectionLoading + (key to loading)
                val newHasMore = if (infiniteSection?.id == sectionId) {
                    st.infiniteHasMore + (tabTitle to viewModelShared.hasMoreInfinite(tabTitle))
                } else {
                    st.infiniteHasMore
                }
                st.copy(sectionLoading = newLoading, infiniteHasMore = newHasMore)
            }
        }
    }

    /** sectionErrorChangedFlow: 加载失败, 置 error + 清 loading (对照 HomeTabState.onSectionError) */
    private fun observeSectionError() = scope.launch {
        viewModelShared.sectionErrorChangedFlow.collect { (tabTitle, sectionId) ->
            val key = homeSectionKey(tabTitle, sectionId)
            _state.update {
                it.copy(
                    sectionError = it.sectionError + (key to true),
                    sectionLoading = it.sectionLoading + (key to false),
                )
            }
        }
    }

    /**
     * sectionOptionsChangedFlow: 该展示项的参数选项已就绪/重解析 (对照 HomeSectionAdapter.updateSectionOptions)。
     * ExploreOption 就地可变且相等性忽略选中态, 靠 version 递增保证 StateFlow 一定发射。
     */
    private fun observeSectionOptions() = scope.launch {
        viewModelShared.sectionOptionsChangedFlow.collect { (tabTitle, sectionId) ->
            val key = homeSectionKey(tabTitle, sectionId)
            val options = viewModelShared.sectionOptions(tabTitle, sectionId)
            _state.update {
                it.copy(
                    sectionOptions = it.sectionOptions + (key to options),
                    sectionOptionsVersion = it.sectionOptionsVersion + 1,
                )
            }
        }
    }

    private fun observeViewModelShared() {
        observeTabs()
        observeSections()
        observeSectionUpdated()
        observeSectionLoading()
        observeSectionError()
        observeSectionOptions()
    }

    /**
     * 管理对话框改完后的事件回流 (对照 HomeFragment/HomeTabFragment 的两处 observeEvent)。
     * HOME_TAB 走 onTabsChanged (重命名/删除时迁移 TabState); HOME_SECTION 按 action 做增量更新。
     */
    private fun observeHomeEvents() {
        scope.launch {
            FlowBus.with(EventBus.HOME_TAB).collect { event ->
                if (event !is HomeTabEvent) return@collect
                viewModelShared.onTabsChanged(
                    rename = if (event.action == HomeTabEvent.RENAME &&
                        event.oldTitle != null && event.newTitle != null
                    ) {
                        event.oldTitle to event.newTitle
                    } else {
                        null
                    },
                    removed = if (event.action == HomeTabEvent.REMOVE) event.oldTitle else null,
                )
            }
        }
        scope.launch {
            FlowBus.with(EventBus.HOME_SECTION).collect { event ->
                if (event !is HomeSectionEvent) return@collect
                val section = event.section
                when (event.action) {
                    HomeSectionEvent.ADD ->
                        section?.let { viewModelShared.addSection(event.tabTitle, it) }

                    HomeSectionEvent.UPDATE ->
                        section?.let { viewModelShared.updateSection(event.tabTitle, it) }

                    HomeSectionEvent.REMOVE ->
                        section?.let { viewModelShared.removeSection(event.tabTitle, it) }

                    HomeSectionEvent.REORDER -> viewModelShared.reorderSections(event.tabTitle)
                }
            }
        }
    }

    // ─── HomeUiActions 实现 (对照 HomeTabState 同名方法) ──────────────────────────

    /** 页可见: 首次拉取该 tab 全部展示项 (对照 HomeTabState.onPageVisible) */
    override fun onPageVisible(tabTitle: String) {
        scope.launch {
            if (_state.value.tabSections[tabTitle] == null) {
                val sections = HomeTabHelpShared.getSections(tabTitle)
                _state.update { it.copy(tabSections = it.tabSections + (tabTitle to sections)) }
            }
            viewModelShared.initTab(tabTitle)
        }
    }

    /** 页变化: 仅持久化当前页索引 (对照 HomeTabState.onPageChanged) */
    override fun onPageChanged(page: Int) {
        _state.update { it.copy(currentPage = page) }
    }

    /** 打开"管理展示项"对话框: 置状态由 MainRoute 弹窗 (对照 HomeTabState.openManageSection) */
    override fun openManageSection() {
        _manageSectionOf.value = currentTabTitle() ?: return
    }

    /** 打开"管理分组"对话框: 置状态由 MainRoute 弹窗 (对照 HomeTabState.openManageTab) */
    override fun openManageTab() {
        _manageTab.value = true
    }

    /** 关闭"管理展示项"对话框 */
    fun closeManageSection() {
        _manageSectionOf.value = null
    }

    /** 关闭"管理分组"对话框 */
    fun closeManageTab() {
        _manageTab.value = false
    }

    /** 下拉刷新该 tab (对照 HomeTabState.refreshTab) */
    override fun refreshTab(tabTitle: String) {
        scope.launch { viewModelShared.refreshTab(tabTitle) }
    }

    /** 触底加载更多 (对照 HomeTabState.loadInfinite) */
    override fun loadInfinite(tabTitle: String) {
        scope.launch { viewModelShared.loadInfinite(tabTitle) }
    }

    /** 参数 chip 切换: 清旧 books 并重载第 1 页 (对照 HomeTabFragment.sectionCallback.onOptionSelected) */
    override fun onSectionOptionSelected(tabTitle: String, section: HomeSection) {
        scope.launch { viewModelShared.onSectionOptionSelected(tabTitle, section) }
    }

    /** 当前选中 tab 的标题; 用于"管理展示项"快捷入口 (对照 HomeTabState.currentTabTitle) */
    private fun currentTabTitle(): String? {
        val st = _state.value
        return st.tabs.getOrNull(st.currentPage)?.title
    }

    /** 刷新当前可见 tab (供 F5 等外部刷新入口调用) */
    fun refreshCurrentTab() {
        currentTabTitle()?.let { refreshTab(it) }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
