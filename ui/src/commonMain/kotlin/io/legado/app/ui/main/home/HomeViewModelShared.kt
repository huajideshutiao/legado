package io.legado.app.ui.main.home

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.webBook.BookListPager
import io.legado.app.model.webBook.ExploreOptionSelectionInvalidatedException
import io.legado.app.model.webBook.ExploreOptionUiState
import io.legado.app.model.webBook.WebBook.getBookListAwait
import io.legado.app.model.webBook.mergeExploreOptionDefinitions
import io.legado.app.model.webBook.pluginFilterSessionOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel 共享核心 (commonMain)。
 *
 * 对照 app 端 `HomeViewModel(app) : BaseViewModel(app)`: 核心编排 (initTabs/initTab/
 * refreshTab/loadSection/loadInfinite/addSection/updateSection/removeSection/
 * reorderSections/onTabsChanged) 不依赖 Android 专属 API, 仅依赖 [AppDbProviders] /
 * [HomeTabHelpShared]/[WebBook], 可下沉多端复用。DAO 走 [AppDbProviders.get];
 * LiveData → 事件信号用 [MutableSharedFlow] (replay=1), 纯状态用 [MutableStateFlow]
 * (postValue → tryEmit: StateFlow 会吞掉相等值, 同一 section 的 loading 开/关投递同一个
 * Pair, 用 StateFlow 转圈就永远停不下来); StateFlow.value 线程安全, 不必切回 Main。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), 注入 [scope];
 * 6 个 LiveData → tabs 用 StateFlow + 5 个事件 SharedFlow; 宿主自管生命周期,
 * 本类不持有 onCleared。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
class HomeViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** 内层状态: 每个 tab 一份, Fragment 销毁不丢分页/缓存。 */
    class TabState(var currentTitle: String) {
        var initialized: Boolean = false

        /** 本 tab 的所有读写都在这里串行 (UI 选择与 IO 结果共用)。 */
        val stateLock = SynchronizedObject()
        val sectionBooksMap = mutableMapOf<String, List<SearchBook>>()
        val loadingSet = mutableSetOf<String>()

        /** 每个展示项的参数定义 + 原始选择快照。 */
        val sectionOptionsMap = mutableMapOf<String, List<ExploreOptionUiState>>()

        /** 本次会话已采用的展示项配置; 编辑事件据此识别真实变化。 */
        val sectionConfigs = mutableMapOf<String, HomeSection>()
        val sectionInvalidated = mutableSetOf<String>()
        var infiniteSection: HomeSection? = null

        /** 每个展示项的在飞任务与世代: 新请求取消旧请求, 旧任务不得发布结果。 */
        val sectionJobs = mutableMapOf<String, Job>()
        val sectionGenerations = mutableMapOf<String, Int>()
        var infiniteJob: Job? = null
        var infiniteGeneration: Int = 0

        /** 无限流翻页游标 (页码 + 上一页解析出的下一页地址)。 */
        val infinitePager = BookListPager()
        var infiniteLoading: Boolean = false
        var infinitePaused: Boolean = false

        val infiniteBookMap = LinkedHashMap<String, SearchBook>()
    }

    // region 状态流: 外部只读 Flow, 适配 Compose 重组 / Android asLiveData 桥接

    /**
     * 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。
     *
     * 不能用 StateFlow: StateFlow 按值去重, 同一 section 的 loading 开/关投递的是相等的 Pair,
     * 第二次会被吞掉, 转圈就永远停不下来。
     */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _tabsFlow = MutableStateFlow<List<HomeTab>?>(null)
    val tabsFlow: StateFlow<List<HomeTab>?> = _tabsFlow.asStateFlow()

    /** 携带 tabTitle: 该 tab 的 sections 列表已变更 (顺序/增删/样式) */
    private val _sectionsFlow = signalFlow<String>()
    val sectionsFlow: SharedFlow<String> = _sectionsFlow.asSharedFlow()

    /** 携带 tabTitle + sectionId: 该展示项书籍数据已更新 */
    private val _sectionUpdatedFlow = signalFlow<Pair<String, String>>()
    val sectionUpdatedFlow: SharedFlow<Pair<String, String>> = _sectionUpdatedFlow.asSharedFlow()

    /** 携带 tabTitle + sectionId: 该展示项加载状态变化 */
    private val _sectionLoadingChangedFlow = signalFlow<Pair<String, String>>()
    val sectionLoadingChangedFlow: SharedFlow<Pair<String, String>> =
        _sectionLoadingChangedFlow.asSharedFlow()

    /** 携带 tabTitle + sectionId: 该展示项书源失效, UI 需展示错误占位 */
    private val _sectionErrorChangedFlow = signalFlow<Pair<String, String>>()
    val sectionErrorChangedFlow: SharedFlow<Pair<String, String>> =
        _sectionErrorChangedFlow.asSharedFlow()

    /** 携带 tabTitle + sectionId: 失效状态本身由 UI 回读当前会话, 不在事件里夹旧 bool。 */
    private val _sectionInvalidatedFlow = signalFlow<Pair<String, String>>()
    val sectionInvalidatedFlow: SharedFlow<Pair<String, String>> =
        _sectionInvalidatedFlow.asSharedFlow()

    /** fresh 第 1 页按有效默认继续时提示一次。 */
    private val _sectionNoticeFlow = signalFlow<Pair<String, String>>()
    val sectionNoticeFlow: SharedFlow<Pair<String, String>> = _sectionNoticeFlow.asSharedFlow()

    /** 携带 tabTitle + sectionId: 该展示项的参数选项已就绪/已变更, UI 需重渲染 chip 行 */
    private val _sectionOptionsChangedFlow = signalFlow<Pair<String, String>>()
    val sectionOptionsChangedFlow: SharedFlow<Pair<String, String>> =
        _sectionOptionsChangedFlow.asSharedFlow()

    // endregion

    /** tabStates 注册表本身的锁: 改名/删除与 UI 读取不能同时 getOrPut 出已删除 tab。 */
    private val registryLock = SynchronizedObject()
    private val tabStates = mutableMapOf<String, TabState>()

    fun stateOf(tabTitle: String): TabState = synchronized(registryLock) {
        tabStates.getOrPut(tabTitle) { TabState(currentTitle = tabTitle) }
    }

    private fun existingState(tabTitle: String): TabState? = synchronized(registryLock) {
        tabStates[tabTitle]
    }

    private fun hasTabState(tabTitle: String): Boolean = synchronized(registryLock) {
        tabStates.containsKey(tabTitle)
    }

    fun isLoading(tabTitle: String, sectionId: String): Boolean {
        val state = existingState(tabTitle) ?: return false
        return synchronized(state.stateLock) { state.loadingSet.contains(sectionId) }
    }

    fun hasMoreInfinite(tabTitle: String): Boolean {
        val state = existingState(tabTitle) ?: return false
        return synchronized(state.stateLock) {
            state.infinitePager.hasNextPage && !state.infinitePaused && !state.infiniteLoading
        }
    }

    fun infiniteSection(tabTitle: String): HomeSection? {
        val state = existingState(tabTitle) ?: return null
        return synchronized(state.stateLock) { state.infiniteSection }
    }

    fun sectionBooks(tabTitle: String, sectionId: String): List<SearchBook> {
        val state = existingState(tabTitle) ?: return emptyList()
        return synchronized(state.stateLock) {
            state.sectionBooksMap[sectionId] ?: emptyList()
        }
    }

    fun sectionOptions(tabTitle: String, sectionId: String): List<ExploreOptionUiState> {
        val state = existingState(tabTitle) ?: return emptyList()
        return synchronized(state.stateLock) {
            state.sectionOptionsMap[sectionId] ?: emptyList()
        }
    }

    fun isSectionInvalidated(tabTitle: String, sectionId: String): Boolean {
        val state = existingState(tabTitle) ?: return false
        return synchronized(state.stateLock) { sectionId in state.sectionInvalidated }
    }

    /** 无限流失败/失效后暂停后续自动翻页; UI/测试据此确认不会再自增。 */
    fun isInfinitePaused(tabTitle: String): Boolean {
        val state = existingState(tabTitle) ?: return true
        return synchronized(state.stateLock) { state.infinitePaused }
    }

    // ─── 加载入口 ────────────────────────────────────────────────────────

    fun initTabs() {
        scope.launch(IoDispatcher) {
            _tabsFlow.value = HomeTabHelpShared.getTabs()
        }
    }

    /** 单个 tab 首次初始化: 拉取其下所有展示项。已初始化则跳过 (Fragment 重建走这里) */
    fun initTab(tabTitle: String) {
        val state = stateOf(tabTitle)
        val sections = synchronized(state.stateLock) {
            if (state.initialized) {
                null
            } else {
                state.initialized = true
                val loaded = HomeTabHelpShared.getSections(tabTitle)
                state.infiniteSection = loaded.find {
                    it.style == HomeSection.STYLE_INFINITE_GRID
                }
                loaded
            }
        } ?: return
        sections.forEach { loadSection(tabTitle, it) }
    }

    fun refreshTab(tabTitle: String) {
        if (!hasTabState(tabTitle)) return
        HomeTabHelpShared.getSections(tabTitle).forEach { loadSection(tabTitle, it) }
    }

    private fun loadSection(tabTitle: String, section: HomeSection) {
        if (!hasTabState(tabTitle)) return
        val state = stateOf(tabTitle)
        if (section.style == HomeSection.STYLE_INFINITE_GRID) {
            synchronized(state.stateLock) {
                state.infiniteSection = section
                state.sectionConfigs[section.id] = section
            }
            loadInfinite(tabTitle, resetPage = true)
            return
        }
        var job: Job? = null
        synchronized(state.stateLock) {
            state.sectionJobs.remove(section.id)?.cancel()
            val generation = (state.sectionGenerations[section.id] ?: 0) + 1
            state.sectionGenerations[section.id] = generation
            state.loadingSet.add(section.id)
            state.sectionConfigs[section.id] = section
            state.sectionInvalidated.remove(section.id)
            // 先建 LAZY 协程并登记，再启动；旧任务被后续加载取消时不会执行 block。
            val launched = scope.launch(IoDispatcher, start = CoroutineStart.LAZY) {
                loadSectionBody(state, section, job, generation)
            }
            job = launched
            state.sectionJobs[section.id] = launched
            _sectionLoadingChangedFlow.tryEmit(state.currentTitle to section.id)
            _sectionInvalidatedFlow.tryEmit(state.currentTitle to section.id)
            _sectionUpdatedFlow.tryEmit(state.currentTitle to section.id)
        }
        job?.start()
    }

    private suspend fun loadSectionBody(
        state: TabState,
        section: HomeSection,
        job: Job?,
        generation: Int,
    ) {
        val id = section.id
        try {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
            if (source == null) {
                synchronized(state.stateLock) {
                    if (state.sectionGenerations[id] == generation) {
                        _sectionErrorChangedFlow.tryEmit(state.currentTitle to id)
                    }
                }
                return
            }
            val options = synchronized(state.stateLock) {
                state.sectionOptionsMap[id].orEmpty()
            }
            var optionsChanged = false
            var freshInvalidated = false
            val result = getBookListAwait(
                source, section.searchKey ?: section.exploreUrl, 1,
                isSearch = section.searchKey != null,
                allowFreshInvalidatedOptions = true,
                optionSelections = options.associate { it.name to it.selectionSnapshot() },
                onUrlResolved = { analyzeUrl ->
                    synchronized(state.stateLock) {
                        if (state.sectionGenerations[id] != generation) return@synchronized
                        val update = mergeExploreOptionDefinitions(
                            options,
                            analyzeUrl.resolvedExploreOptions,
                            replaceAll = true,
                        )
                        freshInvalidated = analyzeUrl.optionSelectionsInvalidated ||
                            update.selectionInvalidated
                        optionsChanged = update.options != options
                        state.sectionOptionsMap[id] = update.options
                        state.sectionInvalidated.remove(id)
                    }
                    if (optionsChanged) {
                        _sectionOptionsChangedFlow.tryEmit(state.currentTitle to id)
                    }
                },
                pluginFilters = pluginFilterSessionOf(source),
            )
            val committed = synchronized(state.stateLock) {
                if (state.sectionGenerations[id] != generation) return@synchronized false
                state.sectionBooksMap[id] = result.books
                state.sectionInvalidated.remove(id)
                true
            }
            if (committed) {
                _sectionUpdatedFlow.tryEmit(state.currentTitle to id)
                _sectionInvalidatedFlow.tryEmit(state.currentTitle to id)
                if (freshInvalidated) _sectionNoticeFlow.tryEmit(state.currentTitle to id)
            }
        } catch (e: ExploreOptionSelectionInvalidatedException) {
            val terminal = synchronized(state.stateLock) {
                if (state.sectionGenerations[id] != generation) return@synchronized false
                state.sectionBooksMap.remove(id)
                state.sectionInvalidated.add(id)
                true
            }
            if (terminal) {
                _sectionUpdatedFlow.tryEmit(state.currentTitle to id)
                _sectionInvalidatedFlow.tryEmit(state.currentTitle to id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            synchronized(state.stateLock) {
                if (state.sectionGenerations[id] == generation) {
                    AppLog.put("主页[${state.currentTitle}]展示项[${section.title}]加载失败", e)
                    _sectionErrorChangedFlow.tryEmit(state.currentTitle to id)
                }
            }
        } finally {
            synchronized(state.stateLock) {
                if (state.sectionGenerations[id] == generation) {
                    state.loadingSet.remove(id)
                    if (state.sectionJobs[id] === job) state.sectionJobs.remove(id)
                    _sectionLoadingChangedFlow.tryEmit(state.currentTitle to id)
                }
            }
        }
    }

    /**
     * 用户在 chip 行切换参数后调用: 清空该展示项旧结果并重载第 1 页。
     * 选项定义与选择已由会话维护, 这里不重新解析。
     */
    fun onSectionOptionSelected(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        synchronized(state.stateLock) {
            state.sectionBooksMap.remove(section.id)
        }
        _sectionUpdatedFlow.tryEmit(state.currentTitle to section.id)
        loadSection(tabTitle, section)
    }

    /** 单选换值; 返回是否实际变化。 */
    fun selectSectionOption(
        tabTitle: String,
        section: HomeSection,
        name: String,
        value: String,
    ): Boolean = updateSectionOption(tabTitle, section, name) { option ->
        if (option.multiSelect || option.items.none { it.second == value }) null
        else if (option.selectedValue == value) null
        else option.copy(selectedValue = value)
    }

    /** 多选整份换值; 返回是否实际变化。 */
    fun selectSectionOptions(
        tabTitle: String,
        section: HomeSection,
        name: String,
        values: Set<String>,
    ): Boolean = updateSectionOption(tabTitle, section, name) { option ->
        if (!option.multiSelect) return@updateSectionOption null
        val allowed = option.items.asSequence()
            .map { it.second }
            .filter { it.isNotBlank() }
            .toHashSet()
        val accepted = values.filterTo(HashSet()) { it in allowed }
        if (accepted == option.selectedValues) null else option.copy(selectedValues = accepted)
    }

    /** 重置展示项参数; 返回是否实际变化。 */
    fun resetSectionOption(
        tabTitle: String,
        section: HomeSection,
        name: String,
    ): Boolean = updateSectionOption(tabTitle, section, name) { option ->
        val updated = if (option.multiSelect) {
            option.copy(selectedValues = emptySet())
        } else {
            option.copy(selectedValue = option.items.firstOrNull()?.second.orEmpty())
        }
        if (updated == option) null else updated
    }

    private fun updateSectionOption(
        tabTitle: String,
        section: HomeSection,
        name: String,
        transform: (ExploreOptionUiState) -> ExploreOptionUiState?,
    ): Boolean {
        val state = stateOf(tabTitle)
        var changed = false
        synchronized(state.stateLock) {
            val options = state.sectionOptionsMap[section.id].orEmpty()
            val index = options.indexOfFirst { it.name == name }
            if (index < 0) return false
            val updated = transform(options[index]) ?: return false
            state.sectionOptionsMap[section.id] =
                options.toMutableList().also { it[index] = updated }
            state.sectionGenerations[section.id] =
                (state.sectionGenerations[section.id] ?: 0) + 1
            state.sectionJobs.remove(section.id)?.cancel()
            if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                state.infiniteJob?.cancel()
                state.infiniteGeneration++
                state.infinitePaused = false
            }
            changed = true
        }
        if (changed) _sectionOptionsChangedFlow.tryEmit(state.currentTitle to section.id)
        return changed
    }

    fun loadInfinite(tabTitle: String, resetPage: Boolean = false) {
        val state = stateOf(tabTitle)
        var generation = 0
        var job: Job? = null
        synchronized(state.stateLock) {
            val section = state.infiniteSection ?: return
            if (!resetPage && (
                    state.infinitePaused ||
                        state.infiniteLoading ||
                        !state.infinitePager.hasNextPage
                    )
            ) {
                return
            }
            state.infiniteJob?.cancel()
            val next = state.infiniteGeneration + 1
            state.infiniteGeneration = next
            state.infiniteJob = null
            if (resetPage) {
                state.infinitePager.reset()
                state.infiniteBookMap.clear()
                state.sectionBooksMap.remove(section.id)
                state.sectionInvalidated.remove(section.id)
                state.infinitePaused = false
            }
            state.infiniteLoading = true
            state.loadingSet.add(section.id)
            state.sectionConfigs[section.id] = section
            generation = next
            val launched = scope.launch(IoDispatcher, start = CoroutineStart.LAZY) {
                loadInfiniteBody(state, section, job, generation)
            }
            job = launched
            state.infiniteJob = launched
            _sectionLoadingChangedFlow.tryEmit(state.currentTitle to section.id)
            _sectionInvalidatedFlow.tryEmit(state.currentTitle to section.id)
            _sectionUpdatedFlow.tryEmit(state.currentTitle to section.id)
        }
        job?.start()
    }

    private suspend fun loadInfiniteBody(
        state: TabState,
        section: HomeSection,
        job: Job?,
        generation: Int,
    ) {
        val id = section.id
        try {
            val source = appDb.bookSourceDao.getBookSource(section.sourceUrl)
            if (source == null) {
                val terminal = synchronized(state.stateLock) {
                    if (state.infiniteGeneration != generation) false
                    else {
                        state.infinitePaused = true
                        state.infiniteLoading = false
                        state.loadingSet.remove(id)
                        true
                    }
                }
                if (terminal) {
                    synchronized(state.stateLock) {
                        _sectionErrorChangedFlow.tryEmit(state.currentTitle to id)
                        _sectionLoadingChangedFlow.tryEmit(state.currentTitle to id)
                    }
                }
                return
            }
            val snapshot = synchronized(state.stateLock) {
                if (state.infiniteGeneration != generation) null
                else Triple(
                    state.infinitePager.page,
                    state.infinitePager.nextPageUrl,
                    state.sectionOptionsMap[id].orEmpty(),
                )
            } ?: return
            val page = snapshot.first
            val nextPageUrl = snapshot.second
            val options = snapshot.third
            val fresh = page == 1 && nextPageUrl == null
            var optionsChanged = false
            var invalidated = false
            var freshInvalidated = false
            val result = getBookListAwait(
                source, section.searchKey ?: section.exploreUrl, page,
                isSearch = section.searchKey != null,
                nextPageUrl = nextPageUrl,
                allowFreshInvalidatedOptions = fresh,
                optionSelections = options.associate { it.name to it.selectionSnapshot() },
                onUrlResolved = { analyzeUrl ->
                    synchronized(state.stateLock) {
                        if (state.infiniteGeneration != generation) return@synchronized
                        val update = mergeExploreOptionDefinitions(
                            options,
                            analyzeUrl.resolvedExploreOptions,
                            replaceAll = nextPageUrl == null,
                        )
                        invalidated = analyzeUrl.optionSelectionsInvalidated ||
                            update.selectionInvalidated
                        if (invalidated && fresh) {
                            freshInvalidated = true
                            invalidated = false
                        }
                        optionsChanged = update.options != options
                        state.sectionOptionsMap[id] = update.options
                    }
                    if (optionsChanged) {
                        _sectionOptionsChangedFlow.tryEmit(state.currentTitle to id)
                    }
                    if (invalidated && !fresh) {
                        throw ExploreOptionSelectionInvalidatedException()
                    }
                },
                pluginFilters = pluginFilterSessionOf(source),
            )
            val committed = synchronized(state.stateLock) {
                if (state.infiniteGeneration != generation) return@synchronized false
                val prevSize = state.infiniteBookMap.size
                result.books.forEach { b -> state.infiniteBookMap.getOrPut(b.bookUrl) { b } }
                state.infinitePager.advance(result, prevSize, state.infiniteBookMap.size)
                state.sectionBooksMap[id] = state.infiniteBookMap.values.toList()
                state.sectionInvalidated.remove(id)
                true
            }
            if (committed) {
                _sectionUpdatedFlow.tryEmit(state.currentTitle to id)
                _sectionInvalidatedFlow.tryEmit(state.currentTitle to id)
                if (freshInvalidated) _sectionNoticeFlow.tryEmit(state.currentTitle to id)
            }
        } catch (e: ExploreOptionSelectionInvalidatedException) {
            val terminal = synchronized(state.stateLock) {
                if (state.infiniteGeneration != generation) return@synchronized false
                state.infiniteBookMap.clear()
                state.infinitePager.reset()
                state.infinitePaused = true
                state.sectionBooksMap.remove(id)
                state.sectionInvalidated.add(id)
                true
            }
            if (terminal) {
                _sectionUpdatedFlow.tryEmit(state.currentTitle to id)
                _sectionInvalidatedFlow.tryEmit(state.currentTitle to id)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val terminal = synchronized(state.stateLock) {
                if (state.infiniteGeneration != generation ||
                    state.infiniteSection?.id != id
                ) {
                    false
                } else {
                    state.infinitePaused = true
                    true
                }
            }
            if (terminal) {
                AppLog.put("主页[${state.currentTitle}]无限流[${section.title}]加载失败", e)
                synchronized(state.stateLock) {
                    _sectionErrorChangedFlow.tryEmit(state.currentTitle to id)
                }
            }
        } finally {
            synchronized(state.stateLock) {
                if (state.infiniteGeneration == generation) {
                    state.infiniteLoading = false
                    state.loadingSet.remove(id)
                    if (state.infiniteJob === job) state.infiniteJob = null
                    _sectionLoadingChangedFlow.tryEmit(state.currentTitle to id)
                }
            }
        }
    }

    // ─── 增量变更 (来自管理对话框) ──────────────────────────────────────

    fun addSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        synchronized(state.stateLock) {
            state.sectionConfigs[section.id] = section
            if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                state.infiniteSection = section
            }
        }
        _sectionsFlow.tryEmit(tabTitle)
        loadSection(tabTitle, section)
    }

    fun updateSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        var reload = false
        var optionsChanged = false
        synchronized(state.stateLock) {
            val old = state.sectionConfigs[section.id]
            val contentChanged = old == null ||
                old.sourceUrl != section.sourceUrl ||
                old.exploreUrl != section.exploreUrl ||
                old.searchKey != section.searchKey
            val styleChanged = old?.style != section.style
            if (contentChanged || styleChanged) {
                state.sectionJobs.remove(section.id)?.cancel()
                state.sectionGenerations[section.id] =
                    (state.sectionGenerations[section.id] ?: 0) + 1
                state.sectionBooksMap.remove(section.id)
                state.loadingSet.remove(section.id)
                if (contentChanged) {
                    state.sectionOptionsMap.remove(section.id)
                    optionsChanged = true
                }
                state.sectionInvalidated.remove(section.id)
                if (old?.style == HomeSection.STYLE_INFINITE_GRID ||
                    section.style == HomeSection.STYLE_INFINITE_GRID
                ) {
                    state.infiniteJob?.cancel()
                    state.infiniteGeneration++
                    state.infiniteJob = null
                    state.infiniteBookMap.clear()
                    state.infinitePager.reset()
                    state.infinitePaused = false
                    state.infiniteLoading = false
                }
                when {
                    section.style == HomeSection.STYLE_INFINITE_GRID ->
                        state.infiniteSection = section

                    old?.style == HomeSection.STYLE_INFINITE_GRID ->
                        state.infiniteSection = null
                }
                state.sectionConfigs[section.id] = section
                reload = true
            } else {
                state.sectionConfigs[section.id] = section
            }
        }
        _sectionsFlow.tryEmit(tabTitle)
        if (optionsChanged) _sectionOptionsChangedFlow.tryEmit(state.currentTitle to section.id)
        if (reload) loadSection(tabTitle, section)
    }

    fun removeSection(tabTitle: String, section: HomeSection) {
        val state = stateOf(tabTitle)
        synchronized(state.stateLock) {
            state.sectionJobs.remove(section.id)?.cancel()
            state.sectionGenerations[section.id] =
                (state.sectionGenerations[section.id] ?: 0) + 1
            state.sectionBooksMap.remove(section.id)
            state.loadingSet.remove(section.id)
            state.sectionOptionsMap.remove(section.id)
            state.sectionConfigs.remove(section.id)
            state.sectionInvalidated.remove(section.id)
            if (state.infiniteSection?.id == section.id ||
                section.style == HomeSection.STYLE_INFINITE_GRID
            ) {
                state.infiniteJob?.cancel()
                state.infiniteGeneration++
                state.infiniteJob = null
                state.infiniteSection = null
                state.infiniteBookMap.clear()
                state.infinitePager.reset()
                state.infinitePaused = true
                state.infiniteLoading = false
            }
        }
        _sectionOptionsChangedFlow.tryEmit(state.currentTitle to section.id)
        _sectionInvalidatedFlow.tryEmit(state.currentTitle to section.id)
        _sectionLoadingChangedFlow.tryEmit(state.currentTitle to section.id)
        _sectionsFlow.tryEmit(state.currentTitle)
    }

    fun reorderSections(tabTitle: String) {
        _sectionsFlow.tryEmit(tabTitle)
    }

    // ─── Tab 结构变化 ─────────────────────────────────────────────────────

    /**
     * Tab 结构变更后刷新; 可选迁移/移除某 TabState 以保持已加载的分页/缓存。
     */
    fun onTabsChanged(rename: Pair<String, String>? = null, removed: String? = null) {
        rename?.let { (old, new) ->
            synchronized(registryLock) { tabStates.remove(old) }?.let { state ->
                synchronized(state.stateLock) {
                    state.currentTitle = new
                    state.sectionJobs.values.forEach { it.cancel() }
                    state.sectionJobs.clear()
                    state.sectionGenerations.keys.toList().forEach { key ->
                        state.sectionGenerations[key] = (state.sectionGenerations[key] ?: 0) + 1
                    }
                    state.infiniteJob?.cancel()
                    state.infiniteJob = null
                    state.infiniteGeneration++
                    state.infiniteLoading = false
                }
                synchronized(registryLock) { tabStates[new] = state }
                scope.launch(IoDispatcher) {
                    if (!hasTabState(new)) return@launch
                    HomeTabHelpShared.getSections(new)
                        .takeIf { hasTabState(new) }
                        ?.forEach { loadSection(new, it) }
                }
            }
        }
        removed?.let { title ->
            synchronized(registryLock) { tabStates.remove(title) }?.let { state ->
                synchronized(state.stateLock) {
                    state.sectionJobs.values.forEach { it.cancel() }
                    state.sectionJobs.clear()
                    state.sectionGenerations.keys.toList().forEach { key ->
                        state.sectionGenerations[key] = (state.sectionGenerations[key] ?: 0) + 1
                    }
                    state.infiniteJob?.cancel()
                    state.infiniteJob = null
                    state.infiniteGeneration++
                    state.infiniteLoading = false
                    state.infinitePaused = true
                    state.loadingSet.clear()
                }
            }
        }
        scope.launch(IoDispatcher) {
            _tabsFlow.value = HomeTabHelpShared.getTabs()
        }
    }
}
