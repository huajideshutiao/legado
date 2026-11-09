package io.legado.app.ui.explore

import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.PluginExploreKindUrl
import io.legado.app.data.entities.isTachiyomiPluginSource
import io.legado.app.help.IntentData
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.SingleJobSlot
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.webBook.BookListPager
import io.legado.app.model.webBook.ExploreOptionSelection
import io.legado.app.model.webBook.ExploreOptionUiState
import io.legado.app.model.webBook.ExploreOptionSelectionInvalidatedException
import io.legado.app.model.webBook.PluginFilterSession
import io.legado.app.model.webBook.WebBook.getBookListAwait
import io.legado.app.model.webBook.mergeExploreOptionDefinitions
import io.legado.app.model.webBook.pluginFilterSessionOf
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.throttleLatest
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 发现页单一页面状态: 请求 id 与 books/loading/error/notice 一次提交。 */
data class ExplorePageState(
    val requestId: Long = 0L,
    val books: List<SearchBook> = emptyList(),
    val hasNextPage: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 发现页展示 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ExploreShowViewModel(app) : BaseViewModel(app)`: 核心业务编排
 * (initData/收藏/分页加载/书架 key 维护/列数与样式切换) 不依赖 Android 专属 API,
 * 仅依赖 [AppDbProviders]/[IntentData]/[PinnedExploreHelp]/[WebBook]/[SearchBookFilter],
 * 可下沉多端复用。
 *
 * Android 专属依赖替换: toast 文案 (R.string.source_filter_rule_filtered_count) →
 * `Toasters.get().toast("已过滤 $n 本")`; BuildConfig.DEBUG → false 兜底 (桌面无 BuildConfig);
 * ConcurrentHashMap.newKeySet() → [newConcurrentSet] expect/actual (iOS: mutableSetOf());
 * LiveData → 信号类 [MutableSharedFlow] (replay=1, 保留 postValue 每次都投递语义——
 * StateFlow 会吞掉相等值, 重试后错误串相同就收不到事件, 加载态永远悬空), 纯状态用 StateFlow。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承); app 端 VM 持本类实例,
 * 7 个 LiveData 改为 7 个 Flow 后经 viewModelScope.launch { collect { postValue } } 桥接;
 * initData(intent) 在 app 端解析 Intent 后转发到本类 (exploreName/exploreUrl/sourceUrl)。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** 书架书籍 key 集合, 用于 [isInBookShelf] 判断 (原 `bookshelf: MutableSet<String>`)。 */
    val bookshelf: MutableSet<String> = newConcurrentSet()

    // region 状态流: 外部只读 Flow, 适配 Compose 重组 / Android asLiveData 桥接

    /**
     * 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。
     *
     * 不能用 StateFlow: StateFlow 按值去重, 重复投递相同值 (同一个错误串重试后再次失败 /
     * 上拉后结果列表未变) 不会触发下游, 加载态就永远停在转圈。
     */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 书架变化信号 (对照原 `upAdapterLiveData: MutableLiveData<String>`)。
     *
     * 值固定为 `"isInBookshelf"` (与原 `upAdapterLiveData.postValue("isInBookshelf")` 一致),
     * app 端 observe 后 `bookshelfVersion++` 驱动列表重组。
     */
    private val _upAdapterFlow = signalFlow<String>()
    val upAdapterFlow: SharedFlow<String> = _upAdapterFlow.asSharedFlow()

    /** 书源就绪信号 (对照原 `sourceReadyLiveData: MutableLiveData<Unit>`)。 */
    private val _sourceReadyFlow = signalFlow<Unit>()
    val sourceReadyFlow: SharedFlow<Unit> = _sourceReadyFlow.asSharedFlow()

    /** 当前请求的完整页面状态; 旧 requestId 的迟到结果不会写入。 */
    private val _pageState = MutableStateFlow(ExplorePageState())
    val pageState: StateFlow<ExplorePageState> = _pageState.asStateFlow()

    /** 参数行只读快照: UI 只读它渲染, 不直接改模型实例。 */
    private val _optionsFlow = MutableStateFlow<List<ExploreOptionUiState>>(emptyList())
    val optionsFlow: StateFlow<List<ExploreOptionUiState>> = _optionsFlow.asStateFlow()

    /** 收藏状态变化信号 (对照原 `upStarLiveData: MutableLiveData<Boolean>`)。 */
    private val _upStarFlow = MutableStateFlow<Boolean?>(null)
    val upStarFlow: StateFlow<Boolean?> = _upStarFlow.asStateFlow()
    // endregion

    // region 字段 (对照原 app 端 ViewModel 同名字段, 行为一致)

    /** 当前发现目标书源 (initData 时由 IntentData.source 或 sourceUrl 查 DAO 设置)。 */
    var bookSource: BookSource? = null
        private set

    /** 当前发现样式 (bookSource?.exploreStyle ?: 0)。 */
    val exploreStyle: Int get() = bookSource?.exploreStyle ?: 0

    /**
     * 当前发现分类页是否为插件源的"筛选"分类 (筛选入口据此判定; 其余分类/非插件源 false)。
     * TVBox 站点不属 Tachiyomi/Aniyomi 插件源 (无筛选契约)。
     */
    val isPluginFilterKind: Boolean
        get() = rawExploreUrl == PluginExploreKindUrl.FILTER &&
            bookSource?.isTachiyomiPluginSource() == true

    /**
     * 本页取数用的会话筛选实例 (漫画 [MangaFilterSession] / 视频 [AnimeFilterSession]; 仅本页会话内
     * 有效, VM 随页面销毁即丢, 不持久化)。由 [createPluginExploreFilters] 经唯一入口
     * [pluginFilterSessionOf] 建 (平台侧等注册表就绪后才作答), 经 [explore] 透传取数委派;
     * 入口行"重置"或退出重进页面时新建会话。
     */
    var pluginExploreFilters: PluginFilterSession? = null
        private set

    /** 筛选会话版本 (每次替换实例 +1): 面板据此重组 (结构相等的两份实例否则会被 Compose 跳过)。 */
    private val _pluginFiltersVersion = MutableStateFlow(0)
    val pluginFiltersVersion: StateFlow<Int> = _pluginFiltersVersion.asStateFlow()

    /** 原始发现 URL (含参数 chip, 解析后填入 [exploreOptions])。 */
    private var rawExploreUrl: String? = null

    /** 发现分类名 (PinnedExplore.categoryName 用)。 */
    var exploreName: String? = null
        private set

    /**
     * 搜索模式 (AppRoute.SourceSearchShow 入口): 数据走书源 searchUrl 搜索规则
     * (isSearch=true, key=[searchKeyword]), 与发现模式的 explore 规则区分;
     * 布局/翻页/收藏等其余机制与发现模式同源。
     */
    private var isSearchMode = false

    /** 搜索模式的搜索词 (WebBook isSearch=true 时作为 key 传入)。 */
    private var searchKeyword: String? = null

    /** 页面会话唯一状态: 定义 + 原始选择 (快照即 UI 数据), 所有提交经 [stateLock] 串行。 */
    private val stateLock = SynchronizedObject()

    /** 翻页游标: 页码 + 上一页解析出的下一页地址 (与主页无限流共用同一份裁决)。 */
    private val pager = BookListPager()

    /** 单请求槽: 新请求取消旧请求, 旧请求不得再发布定义/结果/提示; 网络/JS 在 IO。 */
    private val exploreSlot = SingleJobSlot(
        CoroutineScope(scope.coroutineContext + IoDispatcher)
    )

    /** 初始化目标世代: DAO/筛选会话等挂起完成后必须仍属于本会话。 */
    private var sessionId = 0L

    /** 请求序号: 所有提交在 [stateLock] 内核对。 */
    private var requestId = 0L

    /** 筛选器编辑期间暂停翻页; 编辑不改动时恢复, 改动时从第 1 页重取。 */
    private var paginationPaused = false

    /** 暂停时是否取消了一个在飞页: 恢复时要重发该页, 否则 footer 会停在加载态。 */
    private var paginationInterrupted = false

    /** 参数选择弹窗身份: 页面/源/已提交目标作用域, 不含翻页游标。 */
    val optionsOwnerKey: Any
        get() = synchronized(stateLock) {
            Triple(
                bookSource?.bookSourceUrl.orEmpty(),
                isSearchMode,
                if (isSearchMode) searchKeyword.orEmpty() else rawExploreUrl.orEmpty(),
            )
        }

    /** 当前分页页码 (1-based, 成功加载一页后 +1)。 */
    val page: Int get() = synchronized(stateLock) { pager.page }

    /** 最近一次拉取后, 是否还有下一页。 */
    val hasNextPage: Boolean get() = synchronized(stateLock) { pager.hasNextPage }

    /** 已加载的发现结果 (按 bookUrl 去重, values 进入 [pageState])。
     * 用 Map 而非 LinkedHashSet: SearchBook 已改结构相等, 而 time 是构造参数 (当前毫秒),
     * Set 去重恒不命中会让下游 items(key = bookUrl) 撞重复 key 崩溃, 且触底兜底失效。 */
    private val books = LinkedHashMap<String, SearchBook>()
    // endregion

    init {
        // 订阅书架书籍 key 集合 (对照原 init { execute { appDb.bookDao.flowAll().mapLatest { ... } } })
        scope.launch {
            appDb.bookDao.flowAll().mapLatest { bookList ->
                val keys = arrayListOf<String>()
                bookList.filterNot { it.isNotShelf }.forEach {
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
                AppLog.put("发现列表界面获取书籍数据失败\n${it.message}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                // 原 upAdapterLiveData.postValue("isInBookshelf")
                _upAdapterFlow.tryEmit("isInBookshelf")
            }
        }
    }

    /**
     * 初始化数据 (对照原 ExploreShowViewModel.initData(intent: Intent))。
     *
     * 原 app 端从 `intent.getStringExtra("exploreUrl" / "exploreName" / "sourceUrl")` 取值,
     * 下沉后由 app 端 ViewModel.initData(intent) 解析 Intent 后转发到本方法。
     *
     * 行为:
     * 1. 设置 [rawExploreUrl] / [exploreName]
     * 2. 若 [bookSource] 未初始化, 优先取 [IntentData.source], 否则按 sourceUrl 查 DAO
     * 3. 解析 [exploreOptions]
     * 4. 推送 sourceReady / upStar 信号; 参数定义由首次真实请求发布
     * 5. 触发首次 [explore]
     *
     * @param exploreName 发现分类名 (intent.getStringExtra("exploreName"))
     * @param exploreUrl 发现 URL (intent.getStringExtra("exploreUrl"))
     * @param sourceUrl 书源 URL (intent.getStringExtra("sourceUrl")), 当 IntentData.source
     *   为 null 时用此查 DAO; null 时直接返回 (与原 `return@execute` 一致)
     */
    fun initData(exploreName: String?, exploreUrl: String?, sourceUrl: String?) {
        val session = beginSession()
        scope.launch(IoDispatcher) {
            val source = (IntentData.source as? BookSource)
                ?: sourceUrl?.let { appDb.bookSourceDao.getBookSource(it) }
            if (!isSessionCurrent(session)) return@launch
            if (source == null) {
                publishSessionError(session, "未找到书源")
                return@launch
            }
            applySession(session, source, exploreName, exploreUrl, false, null)
        }
    }

    /**
     * 初始化数据 (重载, 直接传入已知的 [BookSource] 对象)。
     *
     * 供桌面端等已有 source 对象的场景使用, 避免再走 [IntentData.source] / DAO 查询。
     * 行为与 [initData] (exploreName, exploreUrl, sourceUrl) 一致, 仅 bookSource 来源不同。
     */
    fun initData(source: BookSource, exploreName: String?, exploreUrl: String?) {
        val session = beginSession()
        scope.launch(IoDispatcher) {
            if (!isSessionCurrent(session)) return@launch
            applySession(session, source, exploreName, exploreUrl, false, null)
        }
    }

    /**
     * 初始化数据 (搜索模式重载, AppRoute.SourceSearchShow 入口)。
     *
     * 与发现模式差异: [explore] 走搜索规则 (isSearch=true); [rawExploreUrl] 存书源
     * searchUrl; [exploreName] 存搜索词。
     */
    fun initDataSearch(source: BookSource, keyword: String) {
        val session = beginSession()
        scope.launch(IoDispatcher) {
            if (!isSessionCurrent(session)) return@launch
            applySession(session, source, keyword, source.searchUrl, true, keyword)
        }
    }

    /** 建立新页面会话: 旧请求/旧初始化在锁内立即作废, 新目标准备完成前不启动请求。 */
    private fun beginSession(): Long {
        var session = 0L
        synchronized(stateLock) {
            sessionId++
            session = sessionId
            requestId++
            _optionsFlow.value = emptyList()
            pager.reset()
            books.clear()
            paginationPaused = false
            paginationInterrupted = false
            pluginExploreFilters = null
            bookSource = null
            rawExploreUrl = null
            exploreName = null
            isSearchMode = false
            searchKeyword = null
            exploreSlot.cancel()
            _pageState.value = ExplorePageState(requestId = requestId, loading = true)
        }
        _pluginFiltersVersion.update { it + 1 }
        return session
    }

    private fun isSessionCurrent(session: Long): Boolean = synchronized(stateLock) {
        sessionId == session
    }

    private suspend fun applySession(
        session: Long,
        source: BookSource,
        name: String?,
        url: String?,
        searchMode: Boolean,
        keyword: String?,
    ) {
        synchronized(stateLock) {
            if (sessionId != session) return
            bookSource = source
            exploreName = name
            rawExploreUrl = url
            isSearchMode = searchMode
            searchKeyword = keyword
        }
        _sourceReadyFlow.tryEmit(Unit)
        _upStarFlow.value = isFavorite()
        createPluginExploreFilters(session)
        if (isSessionCurrent(session)) explore()
    }

    private fun publishSessionError(session: Long, message: String) {
        synchronized(stateLock) {
            if (sessionId != session) return
            _pageState.value = ExplorePageState(
                requestId = requestId,
                loading = false,
                error = message,
                hasNextPage = false,
            )
        }
    }

    fun isFavorite(): Boolean {
        val sourceUrl = bookSource?.bookSourceUrl ?: return false
        val url = rawExploreUrl ?: return false
        // searchKey 维度参与匹配: 搜索类收藏与发现类收藏的 categoryUrl 同源时不互混
        val searchKey = if (isSearchMode) searchKeyword else null
        val favorites = PinnedExploreHelp.getPinnedExplores()
        return favorites.any { it.sourceUrl == sourceUrl && it.categoryUrl == url && it.searchKey == searchKey }
    }

    /**
     * 切换收藏状态 (对照原 toggleFavorite)。
     *
     * 已收藏则取消, 未收藏则添加。变更后推送 [upStarFlow] 信号。
     */
    fun toggleFavorite() {
        val sourceUrl = bookSource?.bookSourceUrl ?: return
        val sourceName = bookSource?.bookSourceName ?: return
        val categoryName = exploreName ?: return
        val categoryUrl = rawExploreUrl ?: return
        val searchKey = if (isSearchMode) searchKeyword else null

        val favorites = PinnedExploreHelp.getPinnedExplores()
        val existing = favorites.find {
            it.sourceUrl == sourceUrl && it.categoryUrl == categoryUrl && it.searchKey == searchKey
        }
        if (existing != null) {
            PinnedExploreHelp.removePinnedExplore(existing)
        } else {
            PinnedExploreHelp.addPinnedExplore(
                PinnedExplore(
                    sourceUrl,
                    sourceName,
                    categoryName,
                    categoryUrl,
                    searchKey
                )
            )
        }
        _upStarFlow.value = isFavorite()
    }

    /**
     * 建本页取数用的会话筛选实例: 插件源"筛选"分类页与单源搜索模式各建一次。
     *
     * 筛选不持久化: 实例为源默认筛选的新建件 (源 getFilterList 每次全新实例),
     * 挂本 VM (页面会话) 随页面销毁即丢; 每次进入均为全新默认。
     */
    private suspend fun createPluginExploreFilters(session: Long) {
        val source = synchronized(stateLock) { bookSource } ?: return
        if (!isPluginFilterKind && !isSearchMode) return
        val filter = pluginFilterSessionOf(source)
        synchronized(stateLock) {
            if (sessionId != session) return
            pluginExploreFilters = filter
        }
        _pluginFiltersVersion.update { it + 1 }
    }

    /**
     * 重置到源默认筛选: 换新会话实例并返回 (拿不到新实例返回 null, 保留原实例)。
     * 入口行"重置"调用, 调用方换实例后重载第 1 页。
     */
    suspend fun resetPluginExploreFilters(): PluginFilterSession? {
        var session = 0L
        var source: BookSource? = null
        synchronized(stateLock) {
            session = sessionId
            source = bookSource
            if (source == null) return null
            requestId++
            paginationPaused = false
            paginationInterrupted = false
            exploreSlot.cancel()
        }
        val filter = pluginFilterSessionOf(source!!) ?: return null
        synchronized(stateLock) {
            if (sessionId != session) return null
            pluginExploreFilters = filter
        }
        _pluginFiltersVersion.update { it + 1 }
        return filter
    }

    /** 单选换值; 返回是否实际变化 (调用方据此决定是否重载)。 */
    fun selectOption(name: String, value: String): Boolean {
        var changed = false
        synchronized(stateLock) {
            val options = _optionsFlow.value
            val index = options.indexOfFirst { it.name == name && !it.multiSelect }
            if (index < 0) return false
            val old = options[index]
            if (old.selectedValue == value || old.items.none { it.second == value }) return false
            _optionsFlow.value = options.toMutableList().also {
                it[index] = old.copy(selectedValue = value)
            }
            requestId++
            exploreSlot.cancel()
            changed = true
        }
        return changed
    }

    /** 多选整份换值; 返回是否实际变化。 */
    fun selectOptions(name: String, values: Set<String>): Boolean {
        var changed = false
        synchronized(stateLock) {
            val options = _optionsFlow.value
            val index = options.indexOfFirst { it.name == name && it.multiSelect }
            if (index < 0) return false
            val old = options[index]
            val allowed = old.items.asSequence()
                .map { it.second }
                .filter { it.isNotBlank() }
                .toHashSet()
            val accepted = values.filterTo(HashSet()) { it in allowed }
            if (accepted == old.selectedValues) return false
            _optionsFlow.value = options.toMutableList().also {
                it[index] = old.copy(selectedValues = accepted)
            }
            requestId++
            exploreSlot.cancel()
            changed = true
        }
        return changed
    }

    /** 参数行重置; 返回是否实际变化。 */
    fun resetOption(name: String): Boolean {
        var changed = false
        synchronized(stateLock) {
            val options = _optionsFlow.value
            val index = options.indexOfFirst { it.name == name }
            if (index < 0) return false
            val old = options[index]
            val updated = if (old.multiSelect) {
                old.copy(selectedValues = emptySet())
            } else {
                old.copy(selectedValue = old.items.firstOrNull()?.second.orEmpty())
            }
            if (updated == old) return false
            _optionsFlow.value = options.toMutableList().also { it[index] = updated }
            requestId++
            exploreSlot.cancel()
            changed = true
        }
        return changed
    }

    /**
     * 筛选器编辑开始: 暂停翻页并作废在飞页, 避免旧筛选条件的结果混入。
     */
    fun pausePagination() {
        synchronized(stateLock) {
            val wasActive = exploreSlot.isActive
            paginationInterrupted = wasActive
            paginationPaused = true
            requestId++
            if (wasActive) _pageState.value = _pageState.value.copy(loading = false)
            exploreSlot.cancel()
        }
    }

    /** 筛选器编辑结束且未改动: 恢复后续翻页并补发被取消的那页。 */
    fun resumePagination() {
        val interrupted = synchronized(stateLock) {
            paginationPaused = false
            paginationInterrupted.also { paginationInterrupted = false }
        }
        if (interrupted) explore()
    }

    /**
     * 加载一页发现结果。
     *
     * - `resetPage=true`: 重置 page=1 + 清 books (参数 chip 变化用)
     * - `resetPage=false`: 加载下一页 (page 不变, 成功后 page++)
     *
     * 请求发出前固定源/选择/页码/下一页地址/筛选会话快照; 新请求取消旧请求,
     * 只有当前请求能发布定义/结果/错误。
     *
     * 成功时: 过滤命中源过滤规则的结果 (toast 提示过滤条数) + 合并去重 + 发布 [pageState] + page++。
     * 失败时: 在同一 [pageState] 里发布 error, 不再另开错误流。
     *
     * @param resetPage true=重置 page + 清 books (参数 chip 变化 / 错误重试用)
     */
    fun explore(resetPage: Boolean = false) {
        synchronized(stateLock) {
            if (paginationPaused && !resetPage) return
            if (resetPage) {
                paginationPaused = false
                paginationInterrupted = false
                pager.reset()
                books.clear()
            }
            val source = bookSource
            if (source == null) {
                publishErrorLocked("未找到书源")
                return
            }
            val key = if (isSearchMode) {
                searchKeyword ?: run {
                    publishErrorLocked("搜索词为空")
                    return
                }
            } else {
                rawExploreUrl ?: run {
                    publishErrorLocked("发现地址为空")
                    return
                }
            }
            val request = ExploreRequest(
                id = ++requestId,
                source = source,
                key = key,
                page = pager.page,
                nextPageUrl = pager.nextPageUrl,
                replaceAll = pager.nextPageUrl == null,
                fresh = pager.page == 1 && pager.nextPageUrl == null,
                isSearch = isSearchMode,
                filters = pluginExploreFilters,
                selections = _optionsFlow.value.associate {
                    it.name to it.selectionSnapshot()
                },
            )
            _pageState.value = ExplorePageState(
                requestId = request.id,
                books = books.values.toList(),
                hasNextPage = pager.hasNextPage,
                loading = true,
            )
            // 请求登记与清屏/选择提交在同一锁域; 旧请求的 restart 无法在它之后复活。
            exploreSlot.restart { runExplore(request) }
        }
    }

    private fun publishErrorLocked(message: String) {
        _pageState.value = ExplorePageState(
            requestId = requestId,
            books = books.values.toList(),
            hasNextPage = pager.hasNextPage,
            loading = false,
            error = message,
        )
    }

    private suspend fun runExplore(request: ExploreRequest) {
        var freshInvalidated = false
        try {
            val pageResult = withTimeout(timeLimit) {
                getBookListAwait(
                    request.source, request.key, request.page,
                    isSearch = request.isSearch,
                    pluginFilters = request.filters,
                    nextPageUrl = request.nextPageUrl,
                    allowFreshInvalidatedOptions = request.fresh,
                    onUrlResolved = { analyzeUrl: AnalyzeUrlCore ->
                        synchronized(stateLock) {
                            if (requestId != request.id) return@synchronized
                            val update = mergeExploreOptionDefinitions(
                                _optionsFlow.value,
                                analyzeUrl.resolvedExploreOptions,
                                request.replaceAll,
                            )
                            _optionsFlow.value = update.options
                            val invalidated = analyzeUrl.optionSelectionsInvalidated ||
                                update.selectionInvalidated
                            if (invalidated && !request.fresh) {
                                throw ExploreOptionSelectionInvalidatedException()
                            }
                            if (invalidated) freshInvalidated = true
                        }
                    },
                    optionSelections = request.selections,
                )
            }
            val (items, filteredCount) = SearchBookFilter.apply(pageResult.books)
            synchronized(stateLock) {
                if (requestId != request.id) return
                val prevSize = books.size
                items.forEach { b -> books.getOrPut(b.bookUrl) { b } }
                pager.advance(pageResult, prevSize, books.size)
                _pageState.value = ExplorePageState(
                    requestId = request.id,
                    books = books.values.toList(),
                    hasNextPage = pager.hasNextPage,
                    loading = false,
                    error = null,
                    notice = if (freshInvalidated) {
                        "参数选择已失效，已按默认值刷新"
                    } else {
                        null
                    },
                )
            }
            if (filteredCount > 0) {
                runCatching {
                    Toasters.get().toast(
                        syncGetString("source_filter_rule_filtered_count", filteredCount)
                    )
                }
            }
        } catch (e: ExploreOptionSelectionInvalidatedException) {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (requestId != request.id) return
                val message = "参数选择已失效，请刷新或重新选择"
                books.clear()
                pager.reset()
                _pageState.value = ExplorePageState(
                    requestId = request.id,
                    books = emptyList(),
                    hasNextPage = false,
                    loading = false,
                    error = message,
                    notice = null,
                )
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (requestId != request.id) return
                _pageState.value = _pageState.value.copy(
                    requestId = request.id,
                    books = books.values.toList(),
                    hasNextPage = pager.hasNextPage,
                    loading = false,
                    error = "请求超时",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (requestId != request.id) return
                e.printStackTraceOnDebug()
                _pageState.value = _pageState.value.copy(
                    requestId = request.id,
                    books = books.values.toList(),
                    hasNextPage = pager.hasNextPage,
                    loading = false,
                    error = e.stackTraceStr,
                )
            }
        }
    }

    private data class ExploreRequest(
        val id: Long,
        val source: BookSource,
        val key: String,
        val page: Int,
        val nextPageUrl: String?,
        /** 本次用主模板, 定义可完整替换 (按页码的 page2 也是主模板)。 */
        val replaceAll: Boolean,
        /** 本次是从第 1 页重新取数; 只有 fresh 允许旧选择失效后按默认继续。 */
        val fresh: Boolean,
        val isSearch: Boolean,
        val filters: PluginFilterSession?,
        val selections: Map<String, ExploreOptionSelection>,
    )

    /**
     * 判断书籍是否在书架 (对照原 isInBookShelf)。
     *
     * bookUrl 含 "::" 视为子分类, 不在书架;
     * 否则按 name-author / name / bookUrl 查 [bookshelf]。
     */
    fun isInBookShelf(book: BaseBook): Boolean {
        if (book.bookUrl.contains("::")) return false
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelf.contains(key) || bookshelf.contains(book.bookUrl)
    }

    /**
     * 切换视频/非视频两类样式 (对照原 switchLayout)。
     *
     * 切到非视频写 0 (列表), 切到视频写 [BookSource.EXPLORE_STYLE_VIDEO_FLAG]。
     * 变更后异步更新 DB。
     */
    fun switchLayout() {
        bookSource?.let {
            it.exploreStyle = if (BookSource.exploreStyleIsVideo(it.exploreStyle)) 0
            else BookSource.EXPLORE_STYLE_VIDEO_FLAG
            scope.launch(IoDispatcher) {
                appDb.bookSourceDao.update(it)
            }
        }
    }

    /**
     * 设置列数 (对照原 setColumnCount, 保留视频布局标志位)。
     *
     * exploreStyle 解码: 低 3 bit = 列数, 第 5 bit (0x10) = 视频标志位;
     * 列数变更需保留视频标志位, 仅替换低 3 bit。范围 0..6。
     *
     * @param cols 列数 (coerceIn 0..6)
     */
    fun setColumnCount(cols: Int) {
        bookSource?.let {
            val flag = it.exploreStyle and BookSource.EXPLORE_STYLE_VIDEO_FLAG
            it.exploreStyle = flag or cols.coerceIn(0, 6)
            scope.launch(IoDispatcher) {
                appDb.bookSourceDao.update(it)
            }
        }
    }
}
