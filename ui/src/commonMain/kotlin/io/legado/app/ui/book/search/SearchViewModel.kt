package io.legado.app.ui.book.search

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.incrementalFilter
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.ExploreOptionSelection
import io.legado.app.model.webBook.ExploreOptionUiState
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.model.webBook.SearchModel
import io.legado.app.model.webBook.SourceSearchGroup
import io.legado.app.model.webBook.mergeExploreOptionDefinitions
import io.legado.app.model.webBook.pluginFilterSessionOf
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.throttleLatest
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 搜索 ViewModel (KMP 版)。
 *
 * 下沉自 app 端 `SearchViewModel(application: Application) : BaseViewModel(application)`,
 * 替换 Android 专属依赖:
 * - `androidx.lifecycle.LiveData / MutableLiveData / asLiveData` → 纯状态用 `MutableStateFlow`,
 *   事件 (结果列表 / 搜索结束) 用 `MutableSharedFlow(replay=1)` 对齐 postValue 的"每次都投递"
 * - `viewModelScope` → 自管 [screenModelScope] (带异常兜底),
 * - `BaseViewModel.execute { ... }.onError { ... }` → `scope.launch { ... }` + try/catch
 *   (Coroutine 容器仍可用, 但 StateFlow 已无观察者调度需求, 直接 launch 更直接)
 * - `appDb.searchKeywordDao` (app 端单例) → `AppDbProviders.get().searchKeywordDao`
 * - `appDb.bookDao.flowShelfBookKeys / searchShelfBooks` → 同上经 `AppDbProviders`
 * - `context.toastOnUi(...)` → `Toasters.get().toast(...)` (provider 注入, 各平台 actual 实现)
 * - `Handler(Looper.getMainLooper())` (原代码声明但未使用) → 直接删除
 *
 * SearchModel 自身已下沉 commonMain (走 `AppConfigProviders` / `AppDbProviders`),
 * 本 VM 仅作状态编排与回调适配。
 *
 * 宿主关闭时调用 [close] 释放所有协程。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel {

    private val scope = screenModelScope("搜索")

    /** 搜索范围 (持久化于 AppConfig)。 */
    val searchScope = SearchScope(AppConfigProviders.get().searchScope)

    // ---- 搜索状态 ----

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    /**
     * 搜索结果列表 (对照原 `_searchBookFlow: MutableSharedFlow<List<SearchBook>>`)。
     *
     * 必须是 SharedFlow: SearchBook 只按 bookUrl 比相等, 增量合并后列表常与上次相等,
     * 用 StateFlow 会被吞掉, 新增的书源数/最新章就刷不出来。
     */
    private val _searchBooks = signalFlow<List<SearchBook>>()
    val searchBooks: SharedFlow<List<SearchBook>> = _searchBooks.asSharedFlow()

    /**
     * 搜索结果更新源: 每源完成一次全量发射, 经 [throttleLatest] 节流后再进 [_searchBooks]。
     * 无 replay: 重启节流收集时不会回放旧搜索的列表。
     */
    private val searchBooksSource = MutableSharedFlow<List<SearchBook>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 按源分组的搜索结果 ("按源分类"布局用, 组顺序 = 源完成顺序)。
     * 同 [searchBooks] 的事件语义: 每源每页完成后全量发射一次。
     */
    private val _searchGroups = signalFlow<List<SourceSearchGroup>>()
    val searchGroups: SharedFlow<List<SourceSearchGroup>> = _searchGroups.asSharedFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    /**
     * 搜索结束信号, 值为"结果是否为空" (对照原 `searchFinishLiveData.postValue(isEmpty)`)。
     *
     * 事件语义: UI 收到 true 弹一次"结果为空"对话框, 不是持续渲染的状态。
     */
    private val _searchFinishEmpty = signalFlow<Boolean>()
    val searchFinishEmpty: SharedFlow<Boolean> = _searchFinishEmpty.asSharedFlow()

    /** 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。 */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 按源参数定义 + 原始选择: 页面会话唯一状态, UI 快照直接由它派生。 */
    private val _sourceSearchOptions =
        MutableStateFlow<Map<String, List<ExploreOptionUiState>>>(emptyMap())
    val sourceSearchOptionsFlow: StateFlow<Map<String, List<ExploreOptionUiState>>> =
        _sourceSearchOptions.asStateFlow()

    private fun updateSourceOptions(
        transform: (Map<String, List<ExploreOptionUiState>>) -> Map<String, List<ExploreOptionUiState>>,
    ): Boolean {
        while (true) {
            val old = _sourceSearchOptions.value
            val new = transform(old)
            if (new == old) return false
            if (_sourceSearchOptions.compareAndSet(old, new)) return true
        }
    }

    /** 单选换值; 返回是否实际变化, 调用方据此只重搜变化的源。 */
    fun selectSourceOption(sourceUrl: String, name: String, value: String): Boolean {
        return searchModel.commitSourceSelection(sourceUrl) {
            updateSourceOption(sourceUrl, name) { option ->
                if (option.multiSelect || option.items.none { it.second == value }) null
                else if (option.selectedValue == value) null
                else option.copy(selectedValue = value)
            }
        }
    }

    /** 多选整份换值。 */
    fun selectSourceOptions(sourceUrl: String, name: String, values: Set<String>): Boolean {
        return searchModel.commitSourceSelection(sourceUrl) {
            updateSourceOption(sourceUrl, name) { option ->
                if (!option.multiSelect) return@updateSourceOption null
                val allowed = option.items.asSequence()
                    .map { it.second }
                    .filter { it.isNotBlank() }
                    .toHashSet()
                val accepted = values.filterTo(HashSet()) { it in allowed }
                if (accepted == option.selectedValues) null
                else option.copy(selectedValues = accepted)
            }
        }
    }

    /** 重置指定源参数; 返回是否实际变化。 */
    fun resetSourceOption(sourceUrl: String, name: String): Boolean {
        return searchModel.commitSourceSelection(sourceUrl) {
            updateSourceOption(sourceUrl, name) { option ->
                val updated = if (option.multiSelect) {
                    option.copy(selectedValues = emptySet())
                } else {
                    option.copy(selectedValue = option.items.firstOrNull()?.second.orEmpty())
                }
                if (updated == option) null else updated
            }
        }
    }

    private fun updateSourceOption(
        sourceUrl: String,
        name: String,
        transform: (ExploreOptionUiState) -> ExploreOptionUiState?,
    ): Boolean = updateSourceOptions { map ->
        val options = map[sourceUrl] ?: return@updateSourceOptions map
        val index = options.indexOfFirst { it.name == name }
        if (index < 0) return@updateSourceOptions map
        val updated = transform(options[index]) ?: return@updateSourceOptions map
        map + (sourceUrl to options.toMutableList().also { it[index] = updated })
    }

    /** 重搜指定源 (筛选/参数改动后调用, 其余源结果不动)。 */
    fun restartSourceSearch(source: BookSource) {
        // restartSource 自己会清 paused 并推进世代，不能先 resume 补发旧页。
        searchModel.restartSource(source.bookSourceUrl)
    }

    /** 筛选器编辑开始: 暂停该源分页并作废在飞请求。 */
    fun pauseSourcePagination(sourceUrl: String) {
        searchModel.pauseSource(sourceUrl)
    }

    /** 筛选器编辑结束且未改动: 恢复该源后续分页。 */
    fun resumeSourcePagination(sourceUrl: String) {
        searchModel.resumeSource(sourceUrl)
    }

    /** 重置指定源筛选会话为源默认并重搜该源 (入口行"重置")。 */
    fun resetSourceFilters(source: BookSource) {
        scope.launch {
            resetPluginFilterSession(source)
            searchModel.restartSource(source.bookSourceUrl)
        }
    }

    /**
     * 页面会话的插件源筛选实例 (key=bookSourceUrl): 同一实例同时供筛选 UI 与取数委派使用,
     * 随本 VM (页面) 销毁即丢, 不经任何全局缓存。
     */
    private val pluginFilterSessions = newConcurrentMap<String, PluginFilterSession>()

    /** 同源会话新建互斥: 页面内多个入口同时等待时只建一份。 */
    private val filterSessionMutexes = newConcurrentMap<String, Mutex>()
    private val filterSessionMutexLock = SynchronizedObject()

    private fun filterMutex(url: String): Mutex = synchronized(filterSessionMutexLock) {
        filterSessionMutexes.getOrPut(url) { Mutex() }
    }

    /** 筛选会话建立/替换版本 (面板据此重读实例)。 */
    private val _pluginFiltersVersion = MutableStateFlow(0)
    val pluginFiltersVersion = _pluginFiltersVersion.asStateFlow()

    /** 取该源已建立的筛选会话; 未建立返回 null。 */
    fun getPluginFilterSession(sourceUrl: String): PluginFilterSession? =
        pluginFilterSessions[sourceUrl]

    /**
     * 本轮搜索源列表 (搜索开始时随 [SearchModel] 重建)。
     * 聚簇布局的单源顶部筛选入口与"是否只搜一个源"判定都从这里派生。
     */
    private val _searchSources = MutableStateFlow<List<BookSource>>(emptyList())
    val searchSources = _searchSources.asStateFlow()

    /**
     * 取/建该源筛选会话。空筛选器也是合法会话 (取数委派要求携带实例), 由 UI 按 isEmpty
     * 决定是否显示入口; 本端没有插件实例时返回 null, 不缓存为"没有筛选器"。
     */
    suspend fun ensurePluginFilterSession(source: BookSource): PluginFilterSession? {
        val url = source.bookSourceUrl
        val mutex = filterMutex(url)
        return mutex.withLock {
            pluginFilterSessions[url] ?: pluginFilterSessionOf(source)?.also {
                pluginFilterSessions[url] = it
                _pluginFiltersVersion.update { it + 1 }
            }
        }
    }

    /**
     * 重置该源筛选会话为源默认 (新建实例替换), 返回新会话供 UI 回填。
     * 入口行"重置"调用; 调用方随后重搜该源。
     */
    suspend fun resetPluginFilterSession(source: BookSource): PluginFilterSession? {
        val url = source.bookSourceUrl
        val mutex = filterMutex(url)
        return mutex.withLock {
            pluginFilterSessions.remove(url)
            val session = pluginFilterSessionOf(source)
            if (session != null) pluginFilterSessions[url] = session
            _pluginFiltersVersion.update { it + 1 }
            session
        }
    }

    /** 按源参数定义 (key=bookSourceUrl): 定义与选择变化都经单一状态 CAS 发布。 */
    private fun mergeSourceOptions(
        sourceUrl: String,
        parsed: List<ExploreOption>,
        replaceAll: Boolean,
    ): Boolean {
        while (true) {
            val old = _sourceSearchOptions.value
            val existing = old[sourceUrl].orEmpty()
            val update = mergeExploreOptionDefinitions(existing, parsed, replaceAll)
            val new = if (update.options == existing) old else old + (sourceUrl to update.options)
            if (new == old) return update.selectionInvalidated
            if (_sourceSearchOptions.compareAndSet(old, new)) return update.selectionInvalidated
        }
    }

    /** 当前搜索关键词 (空字符串表示未开始, 触底续搜时沿用)。 */
    var searchKey: String = ""
        private set

    /**
     * 书架书籍 key 集合, 用于判断 SearchBook 是否已在书架。
     *
     * KMP 化: 抽出 [newConcurrentSet] expect/actual,
     * - jvm/android: 仍用 ConcurrentHashMap.newKeySet() 保持线程安全;
     * - iOS/鸿蒙: 用 mutableSetOf() (Kotlin/Native 单线程调度, 编译期兼容)。
     */
    val bookshelf: MutableSet<String> = newConcurrentSet()

    // ---- UI 状态 ----

    /** 搜索框文本。 */
    val query = MutableStateFlow("")

    /** 输入帮助区可见 (true=显示历史/书架命中; false=显示结果列表)。 */
    val inputHelpVisible = MutableStateFlow(true)

    private val _historyKeys = MutableStateFlow<List<SearchKeyword>>(emptyList())
    val historyKeys = _historyKeys.asStateFlow()

    private val _bookshelfBooks = MutableStateFlow<List<Book>>(emptyList())
    val bookshelfBooks = _bookshelfBooks.asStateFlow()

    /** 书架增删时递增, UI 据此重组刷新绿点/徽标。 */
    private val _bookshelfVersion = MutableStateFlow(0)
    val bookshelfVersion = _bookshelfVersion.asStateFlow()

    /** 搜索范围变化版本号, UI 据此重组菜单。 */
    private val _scopeVersion = MutableStateFlow(0)
    val scopeVersion = _scopeVersion.asStateFlow()

    /** 搜索框请求焦点信号 (receiptIntent 无 key 时 +1)。 */
    private val _focusEpoch = MutableStateFlow(0)
    val focusEpoch = _focusEpoch.asStateFlow()

    /** precisionSearch 当前态 (UI 显示用, 切换由宿主写回 PreferenceStore)。 */
    val precisionSearch = MutableStateFlow(AppConfigProviders.get().precisionSearch)

    // ---- 内部 ----

    private var searchID = 0L
    private var searchCommandJob: Job? = null
    private var filteredCount = 0
    private var booksFlowJob: Job? = null
    private var searchBooksThrottleJob: Job? = null
    private val bookshelfSearchKeyFlow = MutableStateFlow("")

    /** 用户手动停止搜索 (对齐原 isManualStopSearch): 停后不触底续搜, 启停按钮也隐藏。 */
    private val _manualStopped = MutableStateFlow(false)
    val manualStopped = _manualStopped.asStateFlow()
    private var isManualStopSearch: Boolean
        get() = _manualStopped.value
        set(value) {
            _manualStopped.value = value
        }

    private val searchModel = SearchModel(scope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope = searchScope

        override fun onSearchStart() {
            filteredCount = 0
            _isSearching.value = true
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            searchBooksSource.tryEmit(searchBooks)
        }

        override fun onSearchGroupsChanged(groups: List<SourceSearchGroup>) {
            _searchGroups.tryEmit(groups)
        }

        override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
            _hasMore.value = hasMore
            _isSearching.value = false
            _searchFinishEmpty.tryEmit(isEmpty)
            if (filteredCount > 0) {
                runCatching {
                    Toasters.get().toast(
                        syncGetString("source_filter_rule_filtered_count", filteredCount)
                    )
                }
            }
        }

        override fun onFiltered(count: Int) {
            filteredCount += count
        }

        override fun onSearchCancel(exception: Throwable?) {
            _isSearching.value = false
            exception?.let {
                runCatching { Toasters.get().toast(it.message ?: "") }
            }
        }

        override fun onSearchOptionsResolved(
            sourceUrl: String,
            options: List<ExploreOption>,
            replaceAll: Boolean,
        ): Boolean = mergeSourceOptions(sourceUrl, options, replaceAll)

        override fun onSearchOptionsInvalidated(sourceUrl: String) {
            runCatching { Toasters.get().toast("参数选择已失效，请重新选择") }
        }

        override fun onSearchOptionsRefreshed(sourceUrl: String) {
            runCatching { Toasters.get().toast("参数选择已失效，已按默认值刷新") }
        }

        override fun onSearchSourceError(sourceUrl: String, error: Throwable) {
            runCatching { Toasters.get().toast(error.message ?: "书源搜索出错") }
        }

        override fun onSearchHasMoreChanged(hasMore: Boolean) {
            _hasMore.value = hasMore
        }

        override fun getSearchOptions(sourceUrl: String): Map<String, ExploreOptionSelection> =
            _sourceSearchOptions.value[sourceUrl].orEmpty()
                .associate { it.name to it.selectionSnapshot() }

        override suspend fun getPluginFilters(source: BookSource): PluginFilterSession? =
            ensurePluginFilterSession(source)

        override fun onSearchSourcesResolved(sources: List<BookSource>) {
            _searchSources.value = sources
        }
    })

    init {
        // 每源完成就全量发射一次: 500ms 一拍进 UI, 避免搜索期间列表高频整体重组
        restartSearchBooksCollector()

        // 订阅书架书籍 key, 用于 isInBookShelf 判断 (对齐原 init { execute { ... } })
        scope.launch {
            AppDbProviders.get().bookDao.flowShelfBookKeys().mapLatest { shelfBooks ->
                val keys = arrayListOf<String>()
                shelfBooks.forEach {
                    keys.add("${it.name}-${it.author}")
                    keys.add(it.name)
                    keys.add(it.bookUrl)
                }
                keys
            }
                // 书架全表 Flow 高频发射: 内容没变就跳过 + 500ms 节流 (对照 ExploreScreenModel), 避免高频整体重组
                .distinctUntilChanged()
                .throttleLatest(500)
                .catch {
                AppLog.put("搜索界面获取书籍列表失败\n${it.message}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                _bookshelfVersion.value++
            }
        }

        // 订阅书架命中 (对齐原 Activity.initData 的 combine + incrementalFilter 链)
        scope.launch {
            val keysFlow = combine(
                inputHelpVisible,
                bookshelfSearchKeyFlow.debounce(300)
            ) { visible, key ->
                if (visible) BookFilter.splitQuery(key)
                else emptyList()
            }.distinctUntilChanged()

            val dbFlow = keysFlow
                .map { it.firstOrNull() ?: "" }
                .distinctUntilChanged()
                .flatMapLatest { firstKey ->
                    if (firstKey.isBlank()) flowOf(emptyList())
                    else AppDbProviders.get().bookDao.searchShelfBooks(firstKey)
                }

            combine(dbFlow, keysFlow) { list, keys -> list to keys }
                .incrementalFilter(skipFirst = true)
                .flowOn(IoDispatcher).conflate().collect { books ->
                    _bookshelfBooks.value = books
                }
        }
    }

    fun isInBookShelf(book: BaseBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    // ---- 搜索框 ----

    /** 搜索框文本变化 (对齐 SearchView.onQueryTextChange)。 */
    fun onQueryChange(text: String) {
        query.value = text
        if (_isSearching.value) {
            stop()
            isManualStopSearch = false
        }
        upHistory(text.trim())
    }

    /** 提交搜索 (对齐 SearchView.onQueryTextSubmit, 空词不提交)。 */
    fun onQuerySubmit() {
        val searchKey = query.value.trim()
        if (searchKey.isEmpty()) return
        isManualStopSearch = false
        saveSearchKey(searchKey)
        this.searchKey = ""
        search(searchKey)
        inputHelpVisible.value = false
    }

    /** 对齐 SearchView.setQuery(key, submit)。 */
    fun setQuery(key: String, submit: Boolean) {
        onQueryChange(key)
        if (submit) onQuerySubmit()
    }

    /** 请求搜索框焦点 (receiptIntent 无 key 时调用)。 */
    fun requestFocus() {
        _focusEpoch.value++
    }

    /** 显示/隐藏输入帮助区。 */
    fun showInputHelp(visible: Boolean) {
        inputHelpVisible.value = visible
        if (visible) {
            upHistory(query.value.trim())
        }
    }

    /** 触底续搜 (对齐原 scrollToBottom)。 */
    fun scrollToBottom() {
        if (isManualStopSearch) return
        if (!_isSearching.value && searchKey.isNotEmpty() && _hasMore.value) {
            search("")
        }
    }

    /** 启停按钮点击。 */
    fun onFabClick() {
        if (_isSearching.value) {
            isManualStopSearch = true
            stop()
        } else {
            search("")
        }
    }

    // ---- 历史 ----

    /** 点击历史关键字, 提交搜索。 */
    fun searchHistory(key: String) {
        setQuery(key, true)
    }

    /** 删除单条历史记录。 */
    fun deleteHistory(searchKeyword: SearchKeyword) {
        scope.launch(IoDispatcher) {
            AppDbProviders.get().searchKeywordDao.delete(searchKeyword)
        }
    }

    /** 清空所有搜索历史。 */
    fun clearHistory() {
        scope.launch(IoDispatcher) {
            AppDbProviders.get().searchKeywordDao.deleteAll()
        }
    }

    /** 保存搜索关键字 (usage +1 / lastUseTime 更新)。 */
    fun saveSearchKey(key: String) {
        scope.launch(IoDispatcher) {
            val dao = AppDbProviders.get().searchKeywordDao
            dao.get(key)?.let {
                it.usage += 1
                it.lastUseTime = systemCurrentTimeMillis()
                dao.update(it)
            } ?: dao.insert(SearchKeyword(key, 1))
        }
    }

    // ---- 搜索 ----

    /**
     * 重启结果节流收集: 新搜索时丢弃上一轮尚未下发的尾部发射, 避免新搜索短暂显示旧结果。
     */
    private fun restartSearchBooksCollector() {
        searchBooksThrottleJob?.cancel()
        searchBooksThrottleJob = scope.launch {
            searchBooksSource.throttleLatest(500).collect { _searchBooks.tryEmit(it) }
        }
    }

    /**
     * 开始搜索。
     * @param key 搜索关键词。空字符串表示触底续搜 (沿用上次的 searchKey)。
     * @param resetOptions 是否重置搜索选项 (单源搜索声明的可选项)。
     */
    fun search(key: String, resetOptions: Boolean = true) {
        if (key.isEmpty() && (_isSearching.value || searchKey.isEmpty())) return
        searchCommandJob?.cancel()
        _isSearching.value = true
        searchCommandJob = scope.launch {
            if ((searchKey == key) || key.isNotEmpty()) {
                searchModel.cancelSearch()
                _isSearching.value = true
                searchID++
                _searchBooks.tryEmit(emptyList())
                _searchGroups.tryEmit(emptyList())
                restartSearchBooksCollector()
                searchKey = key
                _hasMore.value = true
                if (resetOptions && _sourceSearchOptions.value.isNotEmpty()) {
                    _sourceSearchOptions.value = emptyMap()
                }
            }
            if (searchKey.isEmpty()) {
                _isSearching.value = false
                return@launch
            }
            searchModel.search(searchID, searchKey)
        }
    }

    /** 停止搜索。 */
    fun stop() {
        searchCommandJob?.cancel()
        searchModel.cancelSearch()
    }

    /** 暂停搜索 (生命周期 onPause)。 */
    fun pause() = searchModel.pause()

    /** 恢复搜索 (生命周期 onResume)。 */
    fun resume() = searchModel.resume()

    // ---- 范围/精准 ----

    /** SearchScope 确认回调。 */
    fun onSearchScopeOk(newScope: SearchScope) {
        searchScope.update(newScope.toString())
        _scopeVersion.value++
        checkSearch()
    }

    /**
     * 更新搜索范围 (经 Intent 传入, 不触发 checkSearch)。
     *
     * 对齐原 app 端 SearchActivity.receiptIntent 的 searchScope 分支:
     * 仅 update + scopeVersion++, 不 checkSearch (此时 key 可能尚未设置)。
     * 与 [onSearchScopeOk] 区别: 后者由对话框确认, 需 checkSearch 重新搜索。
     */
    fun updateSearchScope(scope: String) {
        searchScope.update(scope, false)
        _scopeVersion.value++
    }

    /** 切换精准搜索 (经 AppConfigProviders.setPrecisionSearch 持久化 + 触发重新搜索)。 */
    fun togglePrecisionSearch() {
        val newValue = !precisionSearch.value
        AppConfigProviders.get().setPrecisionSearch(newValue)
        precisionSearch.value = newValue
        checkSearch()
    }

    /** 选择全部书源 (清空范围)。 */
    fun selectScopeAll() {
        searchScope.update("")
        _scopeVersion.value++
        checkSearch()
    }

    /** 移除某个分组/书源。 */
    fun removeScopeName(name: String) {
        searchScope.remove(name)
        _scopeVersion.value++
        checkSearch()
    }

    /** 搜索结果为空时, 由 UI 触发"切换全部分组"确认。 */
    fun onSearchEmptyConfirmSwitchAll() {
        searchScope.update("")
        _scopeVersion.value++
        checkSearch()
    }

    /** 搜索结果为空时, 由 UI 触发"关闭精准搜索"确认。 */
    fun onSearchEmptyConfirmDisablePrecision() {
        AppConfigProviders.get().setPrecisionSearch(false)
        precisionSearch.value = false
        this.searchKey = ""
        search(query.value)
    }

    // ---- 内部 ----

    /** 若搜索框中有内容且帮助区隐藏, 重新提交搜索。 */
    private fun checkSearch() {
        if (!inputHelpVisible.value) {
            query.value.trim().let {
                if (it.isNotEmpty()) setQuery(it, true)
            }
        }
    }

    /** 更新搜索历史 (按 key 过滤或按时间, 300ms 防抖)。 */
    private fun upHistory(key: String? = null) {
        bookshelfSearchKeyFlow.value = key.orEmpty()
        booksFlowJob?.cancel()
        booksFlowJob = scope.launch {
            delay(300)
            val dao = AppDbProviders.get().searchKeywordDao
            (if (key.isNullOrBlank()) dao.flowByTime()
            else dao.flowSearch(key)).catch {
                AppLog.put("搜索界面获取本地数据失败\n${it.message}", it)
            }.flowOn(IoDispatcher).conflate().collect {
                _historyKeys.value = it
            }
        }
    }

    /** 释放资源 (Activity destroy / Composable onDispose)。 */
    fun close() {
        searchModel.close()
        scope.cancel()
    }
}
