package io.legado.app.ui.book.search

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.incrementalFilter
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.SearchModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.launch
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 搜索 ViewModel (KMP 版)。
 *
 * 下沉自 app 端 `SearchViewModel(application: Application) : BaseViewModel(application)`,
 * 替换 Android 专属依赖:
 * - `androidx.lifecycle.LiveData / MutableLiveData / asLiveData` → `kotlinx.coroutines.flow.MutableStateFlow / asStateFlow`
 * - `viewModelScope` → 构造参数 [scope] (默认 `SupervisorJob() + Dispatchers.Default`),
 *   宿主可注入生命周期 scope (桌面端窗口 scope / app 端 viewModelScope)
 * - `BaseViewModel.execute { ... }.onError { ... }` → `scope.launch { ... }` + try/catch
 *   (Coroutine 容器仍可用, 但 StateFlow 已无观察者调度需求, 直接 launch 更直接)
 * - `appDb.searchKeywordDao` (app 端单例) → `AppDatabaseProviders.get().appDb.searchKeywordDao`
 * - `appDb.bookDao.flowShelfBookKeys / searchShelfBooks` → 同上经 `AppDatabaseProviders`
 * - `context.toastOnUi(...)` → `Toasters.get().toast(...)` (provider 注入, 各平台 actual 实现)
 * - `Handler(Looper.getMainLooper())` (原代码声明但未使用) → 直接删除
 *
 * SearchModel 自身已下沉 commonMain (走 `AppConfigProviders` / `AppDbProviders`),
 * 本 VM 仅作状态编排与回调适配。
 *
 * @param scope 持有所有协程的 scope, 宿主关闭时调用 [close] 释放。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** 搜索范围 (持久化于 AppConfig)。 */
    val searchScope = SearchScope(AppConfigProviders.get().searchScope)

    // ---- 搜索状态 ----

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchBooks = MutableStateFlow<List<SearchBook>>(emptyList())
    val searchBooks = _searchBooks.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    /** 搜索结束时的空结果标志, null 表示未结束或被重置。UI 据此触发"结果为空"对话框。 */
    private val _searchFinishEmpty = MutableStateFlow<Boolean?>(null)
    val searchFinishEmpty = _searchFinishEmpty.asStateFlow()

    /** searchOptions 结构版本号, UI 监听此变化以重组选项行。 */
    private val _searchOptionsVersion = MutableStateFlow(0)
    val searchOptionsVersion = _searchOptionsVersion.asStateFlow()

    /** 单源搜索 URL 声明的可选项 (ExploreOption), 多源搜索时为空。 */
    val searchOptions = mutableListOf<ExploreOption>()

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
    private var filteredCount = 0
    private var booksFlowJob: Job? = null
    private val bookshelfSearchKeyFlow = MutableStateFlow("")
    private var isManualStopSearch = false

    private val searchModel = SearchModel(scope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope = searchScope

        override fun onSearchStart() {
            filteredCount = 0
            _isSearching.value = true
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            _searchBooks.value = searchBooks
        }

        override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
            _hasMore.value = hasMore
            _isSearching.value = false
            _searchFinishEmpty.value = isEmpty
            if (filteredCount > 0) {
                // 原 R.string.source_filter_rule_filtered_count 文案
                runCatching { Toasters.get().toast("已过滤 $filteredCount 本") }
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

        override fun onSearchOptionsResolved(options: List<ExploreOption>) {
            val structureUnchanged = searchOptions.size == options.size &&
                searchOptions.zip(options).all { (a, b) ->
                    a.name == b.name && a.options == b.options
                }
            if (structureUnchanged) {
                // 保留用户已选中的状态，仅在结构变化时通知 UI
                return
            }
            val merged = options.map { newOpt ->
                searchOptions.find { it.name == newOpt.name }
                    ?.let { existing -> newOpt.apply { copySelectionFrom(existing) } }
                    ?: newOpt
            }
            searchOptions.clear()
            searchOptions.addAll(merged)
            _searchOptionsVersion.value++
        }

        override fun getSearchOptions(): List<ExploreOption> = searchOptions
    })

    init {
        // 订阅书架书籍 key, 用于 isInBookShelf 判断 (对齐原 init { execute { ... } })
        scope.launch {
            AppDatabaseProviders.get().appDb.bookDao.flowShelfBookKeys().mapLatest { shelfBooks ->
                val keys = arrayListOf<String>()
                shelfBooks.forEach {
                    keys.add("${it.name}-${it.author}")
                    keys.add(it.name)
                    keys.add(it.bookUrl)
                }
                keys
            }.catch {
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
                    else AppDatabaseProviders.get().appDb.bookDao.searchShelfBooks(firstKey)
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
            AppDatabaseProviders.get().appDb.searchKeywordDao.delete(searchKeyword)
        }
    }

    /** 清空所有搜索历史。 */
    fun clearHistory() {
        scope.launch(IoDispatcher) {
            AppDatabaseProviders.get().appDb.searchKeywordDao.deleteAll()
        }
    }

    /** 保存搜索关键字 (usage +1 / lastUseTime 更新)。 */
    fun saveSearchKey(key: String) {
        scope.launch(IoDispatcher) {
            val dao = AppDatabaseProviders.get().appDb.searchKeywordDao
            dao.get(key)?.let {
                it.usage += 1
                it.lastUseTime = systemCurrentTimeMillis()
                dao.update(it)
            } ?: dao.insert(SearchKeyword(key, 1))
        }
    }

    // ---- 搜索 ----

    /**
     * 开始搜索。
     * @param key 搜索关键词。空字符串表示触底续搜 (沿用上次的 searchKey)。
     * @param resetOptions 是否重置搜索选项 (单源搜索声明的可选项)。
     */
    fun search(key: String, resetOptions: Boolean = true) {
        scope.launch {
            if ((searchKey == key) || key.isNotEmpty()) {
                searchModel.cancelSearch()
                searchID = systemCurrentTimeMillis()
                _searchBooks.value = emptyList()
                searchKey = key
                _hasMore.value = true
                _searchFinishEmpty.value = null
                if (resetOptions && searchOptions.isNotEmpty()) {
                    searchOptions.clear()
                    _searchOptionsVersion.value++
                }
            }
            if (searchKey.isEmpty()) {
                return@launch
            }
            searchModel.search(searchID, searchKey)
        }
    }

    /** 停止搜索。 */
    fun stop() = searchModel.cancelSearch()

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
            val dao = AppDatabaseProviders.get().appDb.searchKeywordDao
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
    }
}
