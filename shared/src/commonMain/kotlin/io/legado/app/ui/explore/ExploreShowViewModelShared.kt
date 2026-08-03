package io.legado.app.ui.explore

import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.IntentData
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.help.toast.Toasters
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.model.webBook.WebBook.getBookListAwait
import io.legado.app.model.webBook.parseExploreOptionsFromUrl
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

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

    /**
     * 当前发现结果列表 (对照原 `booksData: MutableLiveData<List<SearchBook>>`)。
     *
     * 每次成功加载都投递一次 (即便列表内容与上次相同), 与 [errorFlow] 构成
     * "一次请求必出一个结果" 的配对, 保证 footer 转圈总能被关掉。
     */
    private val _booksFlow = signalFlow<List<SearchBook>>()
    val booksFlow: SharedFlow<List<SearchBook>> = _booksFlow.asSharedFlow()

    /** 加载错误信息 (对照原 `errorLiveData: MutableLiveData<String>`)。 */
    private val _errorFlow = signalFlow<String>()
    val errorFlow: SharedFlow<String> = _errorFlow.asSharedFlow()

    /** 书源就绪信号 (对照原 `sourceReadyLiveData: MutableLiveData<Unit>`)。 */
    private val _sourceReadyFlow = signalFlow<Unit>()
    val sourceReadyFlow: SharedFlow<Unit> = _sourceReadyFlow.asSharedFlow()

    /** 参数 chip 就绪信号 (对照原 `optionsReadyLiveData: MutableLiveData<Unit>`)。 */
    private val _optionsReadyFlow = signalFlow<Unit>()
    val optionsReadyFlow: SharedFlow<Unit> = _optionsReadyFlow.asSharedFlow()

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

    /** 原始发现 URL (含参数 chip, 解析后填入 [exploreOptions])。 */
    private var rawExploreUrl: String? = null

    /** 发现分类名 (PinnedExplore.categoryName 用)。 */
    var exploreName: String? = null
        private set

    /** URL 解析出的参数 chip 列表 (可被 onUrlResolved 回调追加)。 */
    val exploreOptions = mutableListOf<ExploreOption>()

    /** 当前分页页码 (1-based, 成功加载一页后 +1)。 */
    var page = 1
        private set

    /** 最近一次拉取后, 是否还有下一页。书源配了 hasMoreRule 走 JS, 否则按"本页非空就当还有"。 */
    var hasNextPage: Boolean = true
        private set

    /** 已加载的发现结果 (LinkedHashSet 去重, toList 后暴露给 [booksFlow])。 */
    private var books = linkedSetOf<SearchBook>()
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
     * 4. 推送 sourceReady / optionsReady / upStar 信号
     * 5. 触发首次 [explore]
     *
     * @param exploreName 发现分类名 (intent.getStringExtra("exploreName"))
     * @param exploreUrl 发现 URL (intent.getStringExtra("exploreUrl"))
     * @param sourceUrl 书源 URL (intent.getStringExtra("sourceUrl")), 当 IntentData.source
     *   为 null 时用此查 DAO; null 时直接返回 (与原 `return@execute` 一致)
     */
    fun initData(exploreName: String?, exploreUrl: String?, sourceUrl: String?) {
        scope.launch(IoDispatcher) {
            rawExploreUrl = exploreUrl
            this@ExploreShowViewModelShared.exploreName = exploreName
            if (bookSource == null) {
                bookSource = (IntentData.source as? BookSource)
                    ?: sourceUrl?.let { appDb.bookSourceDao.getBookSource(it) }
                    ?: return@launch
            }
            parseExploreOptions()
            _sourceReadyFlow.tryEmit(Unit)
            if (exploreOptions.isNotEmpty()) {
                _optionsReadyFlow.tryEmit(Unit)
            }
            _upStarFlow.value = isFavorite()
            explore()
        }
    }

    /**
     * 初始化数据 (重载, 直接传入已知的 [BookSource] 对象)。
     *
     * 供桌面端等已有 source 对象的场景使用, 避免再走 [IntentData.source] / DAO 查询。
     * 行为与 [initData] (exploreName, exploreUrl, sourceUrl) 一致, 仅 bookSource 来源不同。
     *
     * @param source 发现目标书源 (直接赋值给 [bookSource], 不走 IntentData / DAO)
     * @param exploreName 发现分类名
     * @param exploreUrl 发现 URL
     */
    fun initData(source: BookSource, exploreName: String?, exploreUrl: String?) {
        scope.launch(IoDispatcher) {
            rawExploreUrl = exploreUrl
            this@ExploreShowViewModelShared.exploreName = exploreName
            bookSource = source
            parseExploreOptions()
            _sourceReadyFlow.tryEmit(Unit)
            if (exploreOptions.isNotEmpty()) {
                _optionsReadyFlow.tryEmit(Unit)
            }
            _upStarFlow.value = isFavorite()
            explore()
        }
    }

    /**
     * 当前发现是否已收藏 (对照原 isFavorite)。
     *
     * 按 sourceUrl + rawExploreUrl 在 [PinnedExploreHelp.getPinnedExplores] 中查找。
     */
    fun isFavorite(): Boolean {
        val sourceUrl = bookSource?.bookSourceUrl ?: return false
        val url = rawExploreUrl ?: return false
        val favorites = PinnedExploreHelp.getPinnedExplores()
        return favorites.any { it.sourceUrl == sourceUrl && it.categoryUrl == url }
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

        val favorites = PinnedExploreHelp.getPinnedExplores()
        val existing = favorites.find { it.sourceUrl == sourceUrl && it.categoryUrl == categoryUrl }
        if (existing != null) {
            PinnedExploreHelp.removePinnedExplore(existing)
        } else {
            PinnedExploreHelp.addPinnedExplore(
                PinnedExplore(
                    sourceUrl,
                    sourceName,
                    categoryName,
                    categoryUrl
                )
            )
        }
        _upStarFlow.value = isFavorite()
    }

    /**
     * 解析 rawExploreUrl 中的参数 chip 填入 [exploreOptions] (对照原 parseExploreOptions)。
     */
    private fun parseExploreOptions() {
        val url = rawExploreUrl ?: return
        exploreOptions.clear()
        mergeOptions(parseExploreOptionsFromUrl(url))
    }

    /**
     * 合并新参数 chip (按 name 去重, 对照原 mergeOptions)。
     */
    private fun mergeOptions(newOptions: List<ExploreOption>) {
        newOptions.forEach { newOpt ->
            if (exploreOptions.any { it.name == newOpt.name }) return@forEach
            exploreOptions.add(newOpt)
        }
    }

    /**
     * 加载一页发现结果 (对照原 explore)。
     *
     * - `resetPage=true`: 重置 page=1 + 清 books (参数 chip 变化用)
     * - `resetPage=false`: 加载下一页 (page 不变, 成功后 page++)
     *
     * 原 `Coroutine.async(viewModelScope) { getBookListAwait(...) }
     *        .timeout(if (BuildConfig.DEBUG) 0L else timeLimit)
     *        .onSuccess(IO) { pageResult -> ... }
     *        .onError { ... }`
     * 下沉后 [Coroutine] 已 commonMain, 直接复用;
     * `BuildConfig.DEBUG` 用 `false` 兜底 (桌面端无 BuildConfig), 故 timeout 永远走 timeLimit 分支,
     * 简化为 `.timeout(timeLimit)` (与 `if (false) 0L else timeLimit` 等价)。
     *
     * 成功时: 过滤命中源过滤规则的结果 (toast 提示过滤条数) + 合并去重 + 推送 [booksFlow] + page++。
     * 失败时: 推送 [errorFlow] (stackTraceStr)。
     *
     * @param resetPage true=重置 page + 清 books (参数 chip 变化 / 错误重试用)
     */
    fun explore(resetPage: Boolean = false) {
        val source = bookSource ?: return
        val url = rawExploreUrl ?: return
        if (resetPage) {
            page = 1
            books.clear()
        }
        val selectedOptions = exploreOptions.associate { it.name to it.resolvedValue }
        Coroutine.async(scope) {
            getBookListAwait(
                source, url, page, isSearch = false,
                onUrlResolved = { analyzeUrl: AnalyzeUrlCore ->
                    val oldSize = exploreOptions.size
                    mergeOptions(parseExploreOptionsFromUrl(analyzeUrl.urlAfterJs))
                    if (exploreOptions.size > oldSize) {
                        _optionsReadyFlow.tryEmit(Unit)
                    }
                },
                selectedOptions = selectedOptions,
            )
        }.timeout(timeLimit).onSuccess(IoDispatcher) { pageResult ->
            val prevSize = books.size
            val (items, filteredCount) = SearchBookFilter.apply(pageResult.books)
            if (filteredCount > 0) {
                // 原 context.toastOnUi(context.getString(R.string.source_filter_rule_filtered_count, n))
                // commonMain 无 R.string, 用硬编码中文 (与 SearchViewModel "已过滤 $n 本" 一致)
                runCatching { Toasters.get().toast("已过滤 $filteredCount 本") }
            }
            books.addAll(items)
            // 兜底: 翻到第二页起, 去重后整体未增长则视为到底; 防止 hasMoreRule 缺失或配错时无限触底
            hasNextPage = pageResult.hasNextPage && (page == 1 || books.size > prevSize)
            _booksFlow.tryEmit(books.toList())
            page++
        }.onError {
            it.printStackTraceOnDebug()
            _errorFlow.tryEmit(it.stackTraceStr)
        }
    }

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
