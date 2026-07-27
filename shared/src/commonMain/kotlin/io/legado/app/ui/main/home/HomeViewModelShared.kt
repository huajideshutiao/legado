package io.legado.app.ui.main.home

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.WebBook.getBookListAwait
import io.legado.app.model.webBook.parseExploreOptionsFromUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * 对照 app 端原 `HomeViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心编排 (initTabs / initTab / refreshTab / loadSection / loadInfinite /
 *   addSection / updateSection / removeSection / reorderSections / onTabsChanged)
 *   不依赖 Android 专属 API, 仅依赖 [AppDbProviders] / [HomeTabHelpShared] / [WebBook] /
 *   [Coroutine] / 协程, 可以下沉 commonMain 供多端复用。
 * - 状态用 [MutableStateFlow] 替代 `androidx.lifecycle.MutableLiveData` (LiveData 不可 KMP)。
 *   Android 宿主用 `viewModelScope.launch { collect { postValue } }` 把 StateFlow
 *   转发到 MutableLiveData, 调用方 `observe` 用法不变 (项目未引入 lifecycle-livedata-ktx,
 *   不用 `StateFlow.asLiveData()` 扩展)。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [HomeTabHelpShared] (app 端 HomeTabHelp 下沉版) / [WebBook] / [Coroutine] / [AppLog]
 *   均已 commonMain, 直接复用。
 *
 * # Android 专属依赖替换
 *
 * - `appDb.bookSourceDao.getBookSource(...)` → `AppDbProviders.get().bookSourceDao.getBookSource(...)`
 * - `HomeTabHelp.getTabs()` / `getSections(tabTitle)` → [HomeTabHelpShared.getTabs] /
 *   [HomeTabHelpShared.getSections] (由 app 端下沉, 调用方语义不变)
 * - `BaseViewModel.execute { ... }.onError {}.onFinally {}` → [Coroutine.async] (scope) {}.onError {}.onFinally {}
 *   (BaseViewModel.execute 即 Coroutine.async 包装, 行为一致)
 * - `androidx.lifecycle.MutableLiveData` → [MutableStateFlow]
 * - `LiveData.postValue(x)` → `MutableStateFlow.value = x`
 * - `viewModelScope.launch { withContext(IO) { ... } }` → `scope.launch(Dispatchers.IO) { ... }`
 *   (StateFlow.value 线程安全, 不必切回 Main)
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (参考 [io.legado.app.ui.bookshelf.BookshelfViewModel]
 * / [io.legado.app.ui.explore.ExploreShowViewModelShared]):
 * - app 端 HomeViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - 6 个 LiveData 改为 6 个 StateFlow (可空, 初始 null, app 端桥接时过滤 null 避免初始假触发);
 * - 调用方法直接转发到本类, 子类签名/语义不变。
 *
 * # 状态桥接
 *
 * 6 个原 LiveData 字段对应 6 个 StateFlow (均可空, 初始 null, app 端桥接时过滤 null):
 * - `tabsLiveData` (原 `MutableLiveData<List<HomeTab>>`) → [tabsFlow] (`StateFlow<List<HomeTab>?>`)
 * - `sectionsLiveData` (原 `MutableLiveData<String>`) → [sectionsFlow] (`StateFlow<String?>`)
 * - `sectionUpdated` (原 `MutableLiveData<Pair<String, String>>`) → [sectionUpdatedFlow]
 * - `sectionLoadingChanged` (原 `MutableLiveData<Pair<String, String>>`) → [sectionLoadingChangedFlow]
 * - `sectionErrorChanged` (原 `MutableLiveData<Pair<String, String>>`) → [sectionErrorChangedFlow]
 * - `sectionOptionsChanged` (原 `MutableLiveData<Pair<String, String>>`) → [sectionOptionsChangedFlow]
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope); 宿主自管生命周期,
 *   本类不持有 onCleared (与 ExploreShowViewModelShared 一致)。
 */
class HomeViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** 内层状态: 每个 tab 一份, Fragment 销毁不丢分页/缓存 (对照原 TabState) */
    class TabState {
        var initialized: Boolean = false
        val sectionBooksMap = mutableMapOf<String, List<SearchBook>>()
        val loadingSet = mutableSetOf<String>()

        /** 每个展示项的参数选项 (可变 ExploreOption 实例, VM/Adapter/控件三处共享, 用户点 chip 直接改实例字段) */
        val sectionOptionsMap = mutableMapOf<String, List<ExploreOption>>()
        var infiniteSection: HomeSection? = null
        var infinitePage: Int = 1
        var infiniteHasMore: Boolean = true
        var infiniteLoading: Boolean = false
        val infiniteBookSet = linkedSetOf<SearchBook>()
    }

    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接

    private val _tabsFlow = MutableStateFlow<List<HomeTab>?>(null)
    val tabsFlow: StateFlow<List<HomeTab>?> = _tabsFlow.asStateFlow()

    /** 携带 tabTitle: 该 tab 的 sections 列表已变更 (顺序/增删/样式) */
    private val _sectionsFlow = MutableStateFlow<String?>(null)
    val sectionsFlow: StateFlow<String?> = _sectionsFlow.asStateFlow()

    /** 携带 tabTitle + sectionId: 该展示项书籍数据已更新 */
    private val _sectionUpdatedFlow = MutableStateFlow<Pair<String, String>?>(null)
    val sectionUpdatedFlow: StateFlow<Pair<String, String>?> = _sectionUpdatedFlow.asStateFlow()

    /** 携带 tabTitle + sectionId: 该展示项加载状态变化 */
    private val _sectionLoadingChangedFlow = MutableStateFlow<Pair<String, String>?>(null)
    val sectionLoadingChangedFlow: StateFlow<Pair<String, String>?> = _sectionLoadingChangedFlow.asStateFlow()

    /** 携带 tabTitle + sectionId: 该展示项书源失效, UI 需展示错误占位 */
    private val _sectionErrorChangedFlow = MutableStateFlow<Pair<String, String>?>(null)
    val sectionErrorChangedFlow: StateFlow<Pair<String, String>?> = _sectionErrorChangedFlow.asStateFlow()

    /** 携带 tabTitle + sectionId: 该展示项的参数选项已就绪/已变更, UI 需重渲染 chip 行 */
    private val _sectionOptionsChangedFlow = MutableStateFlow<Pair<String, String>?>(null)
    val sectionOptionsChangedFlow: StateFlow<Pair<String, String>?> = _sectionOptionsChangedFlow.asStateFlow()

    // endregion

    private val tabStates = mutableMapOf<String, TabState>()

    fun stateOf(tabTitle: String): TabState = tabStates.getOrPut(tabTitle) { TabState() }

    fun isLoading(tabTitle: String, sectionId: String) =
        stateOf(tabTitle).loadingSet.contains(sectionId)

    fun hasMoreInfinite(tabTitle: String) = stateOf(tabTitle).infiniteHasMore

    fun infiniteSection(tabTitle: String) = stateOf(tabTitle).infiniteSection

    fun sectionBooks(tabTitle: String, sectionId: String): List<SearchBook> =
        stateOf(tabTitle).sectionBooksMap[sectionId] ?: emptyList()

    fun sectionOptions(tabTitle: String, sectionId: String): List<ExploreOption> =
        stateOf(tabTitle).sectionOptionsMap[sectionId] ?: emptyList()

    // ─── 加载入口 ────────────────────────────────────────────────────────

    fun initTabs() {
        scope.launch(Dispatchers.IO) {
            _tabsFlow.value = HomeTabHelpShared.getTabs()
        }
    }

    /** 单个 tab 首次初始化: 拉取其下所有展示项。已初始化则跳过 (Fragment 重建走这里) */
    fun initTab(tabTitle: String) {
        val state = stateOf(tabTitle)
        if (state.initialized) return
        state.initialized = true
        val sections = HomeTabHelpShared.getSections(tabTitle)
        state.infiniteSection = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
        sections.forEach { loadSection(tabTitle, it) }
    }

    fun refreshTab(tabTitle: String) {
        HomeTabHelpShared.getSections(tabTitle).forEach { loadSection(tabTitle, it) }
    }

    private fun loadSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        // 首次或 url 变更后(map 被清空)才解析; 用户切参数时 map 非空, 跳过以保留选择
        // 无限流也走这里: 解析后存入 map, loadInfinite 读取 selectedOptions
        if (state.sectionOptionsMap[section.id] == null) {
            state.sectionOptionsMap[section.id] = parseExploreOptionsFromUrl(section.exploreUrl)
            _sectionOptionsChangedFlow.value = tabTitle to section.id
        }
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            loadInfinite(tabTitle, resetPage = true)
            return
        }
        state.loadingSet.add(section.id)
        _sectionLoadingChangedFlow.value = tabTitle to section.id
        Coroutine.async(scope) {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
            if (source == null) {
                _sectionErrorChangedFlow.value = tabTitle to section.id
                return@async
            }
            // 与 ExploreShowViewModel.explore 一致: 把 resolvedValue 通过 selectedOptions 传出
            val selectedOptions = state.sectionOptionsMap[section.id]
                ?.takeIf { it.isNotEmpty() }
                ?.associate { it.name to it.resolvedValue }
            val result = getBookListAwait(
                source, section.exploreUrl, 1, isSearch = false,
                selectedOptions = selectedOptions
            )
            state.sectionBooksMap[section.id] = result.books
            _sectionUpdatedFlow.value = tabTitle to section.id
        }.onError {
            AppLog.put("主页[$tabTitle]展示项[${section.title}]加载失败", it)
            _sectionErrorChangedFlow.value = tabTitle to section.id
        }.onFinally {
            state.loadingSet.remove(section.id)
            _sectionLoadingChangedFlow.value = tabTitle to section.id
        }
    }

    /**
     * 用户在 chip 行切换参数后调用。option 实例的 selectedValue 已被 setUpExploreOptions
     * 内部点击监听修改, 这里只负责清空旧 books 并重新加载第 1 页。
     * 不重新解析 options (避免重置用户选择)。
     */
    fun onSectionOptionSelected(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        state.sectionBooksMap.remove(section.id)
        _sectionUpdatedFlow.value = tabTitle to section.id
        loadSection(tabTitle, section)
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
        _sectionLoadingChangedFlow.value = tabTitle to section.id
        Coroutine.async(scope) {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
                ?: return@async
            // 与 ExploreShowViewModel.explore 一致: 把 resolvedValue 通过 selectedOptions 传出
            val selectedOptions = state.sectionOptionsMap[section.id]
                ?.takeIf { it.isNotEmpty() }
                ?.associate { it.name to it.resolvedValue }
            val result = getBookListAwait(
                source, section.exploreUrl, state.infinitePage, isSearch = false,
                selectedOptions = selectedOptions
            )
            state.infiniteBookSet.addAll(result.books)
            state.infiniteHasMore = result.hasNextPage && result.books.isNotEmpty()
            state.sectionBooksMap[section.id] = state.infiniteBookSet.toList()
            state.infinitePage++
            _sectionUpdatedFlow.value = tabTitle to section.id
        }.onError {
            AppLog.put("主页[$tabTitle]无限流[${section.title}]加载失败", it)
        }.onFinally {
            state.infiniteLoading = false
            state.loadingSet.remove(section.id)
            _sectionLoadingChangedFlow.value = tabTitle to section.id
        }
    }

    // ─── 增量变更 (来自管理对话框) ──────────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            state.infiniteSection = section
        }
        _sectionsFlow.value = tabTitle
        loadSection(tabTitle, section)
    }

    fun updateSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        val old = HomeTabHelpShared.getSections(tabTitle).find { it.id == section.id }
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
        _sectionsFlow.value = tabTitle
        if (sourceChanged) {
            if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                state.infiniteBookSet.clear()
                state.infinitePage = 1
                state.infiniteHasMore = true
            } else {
                state.sectionBooksMap.remove(section.id)
                state.sectionOptionsMap.remove(section.id)
            }
            loadSection(tabTitle, section)
        }
    }

    fun removeSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        state.sectionBooksMap.remove(section.id)
        state.loadingSet.remove(section.id)
        state.sectionOptionsMap.remove(section.id)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            state.infiniteSection = null
            state.infiniteBookSet.clear()
        }
        _sectionsFlow.value = tabTitle
    }

    fun reorderSections(tabTitle: String) {
        _sectionsFlow.value = tabTitle
    }

    // ─── Tab 结构变化 ─────────────────────────────────────────────────────

    /**
     * Tab 结构变更后刷新; 可选迁移/移除某 TabState 以保持已加载的分页/缓存。
     */
    fun onTabsChanged(rename: Pair<String, String>? = null, removed: String? = null) {
        rename?.let { (old, new) -> tabStates.remove(old)?.let { tabStates[new] = it } }
        removed?.let { tabStates.remove(it) }
        scope.launch(Dispatchers.IO) {
            _tabsFlow.value = HomeTabHelpShared.getTabs()
        }
    }
}
