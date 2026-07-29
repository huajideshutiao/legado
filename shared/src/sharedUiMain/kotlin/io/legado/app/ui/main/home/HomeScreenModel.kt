package io.legado.app.ui.main.home

import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * - 6 个 LiveData Observer → 6 个 StateFlow collector (过滤初始 null 避免假触发)
 * - onPageVisible/onPageChanged/refreshTab/loadInfinite 直接转发到 [viewModelShared]
 * - openManageSection/openManageTab 为弹窗类操作, 委托 [PlatformCapabilityProviders]
 *   (app 端 actual 启动 L3 Dialog; 其他端空实现)
 * - HomePageVisible 内 tabSections.getOrPut → StateFlow.update 幂等写入
 *
 * 事件总线 (HOME_TAB/HOME_SECTION) 与 optionsVersion 不在本类处理:
 * - 事件类 (HomeTabEvent/HomeSectionEvent) 仍属 app 端 L3, 未下沉;
 *   管理对话框经 [PlatformCapabilityProviders] 弹出后, 由 app 端自行桥接事件回调
 *   (或后续把事件类下沉到 commonMain 再扩展本类)
 * - sectionOptions 不暴露在 [HomeUiState], slots 占位期间无需重渲染
 *
 * 模式参考 [io.legado.app.ui.main.explore.ExploreScreenModel]。
 */
class HomeScreenModel : ScreenModel, HomeUiActions {

    // 自管 scope (ScreenModelStore 调 onCleared 时取消)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            infiniteHasMore = emptyMap(),
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        observeViewModelShared()
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
            if (tabTitle != null) {
                val sections = HomeTabHelpShared.getSections(tabTitle)
                _state.update { it.copy(tabSections = it.tabSections + (tabTitle to sections)) }
            }
        }
    }

    /** sectionUpdatedFlow: 该展示项书籍已更新, 同时清除 error (对照 HomeTabState.onSectionUpdated) */
    private fun observeSectionUpdated() = scope.launch {
        viewModelShared.sectionUpdatedFlow.collect { pair ->
            if (pair != null) {
                val (tabTitle, sectionId) = pair
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
    }

    /** sectionLoadingChangedFlow: 加载态变化; 若为无限流 section 同步刷新 hasMore (对照 onSectionLoadingChanged) */
    private fun observeSectionLoading() = scope.launch {
        viewModelShared.sectionLoadingChangedFlow.collect { pair ->
            if (pair != null) {
                val (tabTitle, sectionId) = pair
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
    }

    /** sectionErrorChangedFlow: 加载失败, 置 error + 清 loading (对照 HomeTabState.onSectionError) */
    private fun observeSectionError() = scope.launch {
        viewModelShared.sectionErrorChangedFlow.collect { pair ->
            if (pair != null) {
                val (tabTitle, sectionId) = pair
                val key = homeSectionKey(tabTitle, sectionId)
                _state.update {
                    it.copy(
                        sectionError = it.sectionError + (key to true),
                        sectionLoading = it.sectionLoading + (key to false),
                    )
                }
            }
        }
    }

    private fun observeViewModelShared() {
        observeTabs()
        observeSections()
        observeSectionUpdated()
        observeSectionLoading()
        observeSectionError()
        // sectionOptionsChangedFlow: options 不暴露在 HomeUiState, slots 占位期间跳过
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

    /** 打开"管理展示项"对话框: 委托平台能力 (对照 HomeTabState.openManageSection) */
    override fun openManageSection() {
        val tabTitle = currentTabTitle() ?: return
        PlatformCapabilityProviders.getOrNull()?.showHomeSectionManageDialog(tabTitle)
    }

    /** 打开"管理分组"对话框: 委托平台能力 (对照 HomeTabState.openManageTab) */
    override fun openManageTab() {
        PlatformCapabilityProviders.getOrNull()?.showHomeTabManageDialog()
    }

    /** 下拉刷新该 tab (对照 HomeTabState.refreshTab) */
    override fun refreshTab(tabTitle: String) {
        scope.launch { viewModelShared.refreshTab(tabTitle) }
    }

    /** 触底加载更多 (对照 HomeTabState.loadInfinite) */
    override fun loadInfinite(tabTitle: String) {
        scope.launch { viewModelShared.loadInfinite(tabTitle) }
    }

    /** 当前选中 tab 的标题; 用于"管理展示项"快捷入口 (对照 HomeTabState.currentTabTitle) */
    private fun currentTabTitle(): String? {
        val st = _state.value
        return st.tabs.getOrNull(st.currentPage)?.title
    }

    override fun onCleared() {
        scope.cancel()
    }
}
