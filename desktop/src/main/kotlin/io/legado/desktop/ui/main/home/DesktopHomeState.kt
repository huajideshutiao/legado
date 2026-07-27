package io.legado.desktop.ui.main.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.main.home.HomeUiActions
import io.legado.app.ui.main.home.HomeViewModelShared

/**
 * 桌面端主页壳层状态 (对照 app 端 HomeTabState)。
 *
 * 持有 [HomeViewModelShared] (commonMain 下沉), 用 Compose mutableState 镜像 VM 的
 * StateFlow, 实现 [HomeUiActions] 把 shared 端 HomeScreen 的回调桥接到 VM。
 *
 * 与 app 端 [io.legado.app.ui.main.home.HomeTabState] 区别:
 * - 不依赖 Activity/Fragment, [openManageSection]/[openManageTab] 暂 no-op (管理对话框后续补)
 * - VM 用 [HomeViewModelShared] (commonMain), 不用 app 端 HomeViewModel (AndroidViewModel)
 * - 状态收集由 [DesktopHomeScreen] 的 LaunchedEffect 桥接 StateFlow → 本类 onXxx 方法
 *   (对照 desktop ExploreShowStateHolder + ExploreShowViewModelShared 模式), 而非 LiveData observe
 *
 * 字段语义对照 HomeTabState 同名字段, 供 [DesktopHomeScreen] 组装
 * [io.legado.app.ui.main.home.HomeUiState]。
 */
class DesktopHomeState(
    val viewModel: HomeViewModelShared,
) : HomeUiActions {

    var tabs by mutableStateOf<List<HomeTab>>(emptyList())
        private set

    /** Pager 当前页 (仅初始化读; 翻页由 [onPageChanged] 写) */
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

    /** tabTitle -> 该 tab 的无限流是否还有更多 (shared 端 HomeUiState 直读) */
    val hasMoreState = mutableStateMapOf<String, Boolean>()

    /** 管理分组对话框显隐 (由 [openManageTab] 触发, DesktopHomeScreen 末尾渲染) */
    var showManageTabDialog by mutableStateOf(false)
        private set

    /** 管理展示项对话框显隐 (由 [openManageSection] 触发) */
    var showManageSectionDialog by mutableStateOf(false)
        private set

    private fun key(tabTitle: String, sectionId: String) = "$tabTitle $sectionId"

    // ---- StateFlow 桥接回调 (由 DesktopHomeScreen 的 LaunchedEffect 调用) ----

    fun onTabs(newTabs: List<HomeTab>) {
        tabs = newTabs
        newTabs.forEach { tab -> tabSections[tab.title] = HomeTabHelpShared.getSections(tab.title) }
    }

    fun onSectionsChanged(tabTitle: String) {
        tabSections[tabTitle] = HomeTabHelpShared.getSections(tabTitle)
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

    // ---- HomeUiActions 桥接实现 ----

    /** 页可见 (对照 HomeTabFragment.onResume→initTab): 首次拉取该 tab 全部展示项 */
    override fun onPageVisible(tabTitle: String) {
        tabSections.getOrPut(tabTitle) { HomeTabHelpShared.getSections(tabTitle) }
        viewModel.initTab(tabTitle)
    }

    /** 页变化 (由 shared 端 Pager 派发, 用于持久化当前页) */
    override fun onPageChanged(page: Int) {
        currentPage = page
    }

    override fun refreshTab(tabTitle: String) = viewModel.refreshTab(tabTitle)

    override fun loadInfinite(tabTitle: String) = viewModel.loadInfinite(tabTitle)

    // ---- 管理对话框 (对照 app 端 HomeTabManageDialog / HomeSectionManageDialog) ----
    // app 端用 BaseComposeDialogFragment (L3), 桌面端用纯 @Composable Dialog (见 DesktopHomeScreen),
    // 持久化走 HomeTabHelpShared, 变更后刷新 VM (对照 app 端 EventBus HOME_TAB/HOME_SECTION 桥接)

    /** 当前选中 tab 的标题; "管理展示项"快捷入口用 (对照 app 端 HomeTabState.currentTabTitle) */
    val currentTabTitle: String? get() = tabs.getOrNull(currentPage)?.title

    override fun openManageTab() {
        showManageTabDialog = true
    }

    override fun openManageSection() {
        // 仅当前 tab 存在时弹管理展示项对话框 (对照 app 端 currentTabTitle ?: return)
        if (currentTabTitle != null) showManageSectionDialog = true
    }

    fun dismissManageTabDialog() {
        showManageTabDialog = false
    }

    fun dismissManageSectionDialog() {
        showManageSectionDialog = false
    }

    /** 新建分组 (对话框调用), 持久化后刷新 VM tab 列表 (对照 HomeTabEvent.ADD → onTabsChanged) */
    fun addTab(title: String) {
        HomeTabHelpShared.addTab(title)
        viewModel.onTabsChanged()
    }

    /** 重命名分组, 迁移 TabState 后刷新 VM (对照 HomeTabEvent.RENAME → onTabsChanged(rename)) */
    fun renameTab(oldTitle: String, newTitle: String) {
        if (HomeTabHelpShared.renameTab(oldTitle, newTitle)) {
            viewModel.onTabsChanged(rename = oldTitle to newTitle)
        }
    }

    /** 删除分组, 移除 TabState 后刷新 VM (对照 HomeTabEvent.REMOVE → onTabsChanged(removed)) */
    fun deleteTab(title: String) {
        HomeTabHelpShared.removeTab(title)
        viewModel.onTabsChanged(removed = title)
    }

    /** 删除展示项, 清 VM 缓存 + 刷新该 tab 的 sections (对照 HomeSectionEvent.REMOVE) */
    fun deleteSection(tabTitle: String, section: HomeSection) {
        HomeTabHelpShared.removeSection(tabTitle, section.id)
        viewModel.removeSection(tabTitle, section)
        onSectionsChanged(tabTitle)
    }
}
