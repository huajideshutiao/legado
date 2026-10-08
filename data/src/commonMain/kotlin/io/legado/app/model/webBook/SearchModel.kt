package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * 按源分组的搜索结果 (搜索界面"按源分类"布局用): 一个书源一个组,
 * 组内为该源已搜到的全部书目, 组顺序 = 源完成顺序。
 */
data class SourceSearchGroup(
    val source: BookSource,
    val books: List<SearchBook>,
)

/** 单源单页结果: 带发出请求时的结果世代, 供丢弃重搜前发出的过期响应。 */
private data class SourcePageResult(
    val source: BookSource,
    val generation: Int,
    val page: BookListPage,
)

/** searchLayout (AppConfig) 的"按源分类"布局标志位; 低 3 位列数 / bit4 视频位仅聚簇布局使用。 */
const val SEARCH_LAYOUT_SOURCE_GROUP = 0x20

/**
 * 搜索编排层。
 *
 * 下沉说明:
 * - `AppConfig.threadCount/precisionSearch` → `AppConfigProviders.get().threadCount/precisionSearch`;
 * - `Executors.newFixedThreadPool(N).asCoroutineDispatcher()` (JVM 专属) →
 *   `Dispatchers.IO.limitedParallelism(N)` (KMP commonMain 可用), 行为等价 (限制并发到 N);
 * - `Coroutine.async` (app 端 WebBook 调试用) 未使用, 无需替换;
 * - 其他依赖 (WebBook/SearchBookFilter/SearchScope/releaseHtmlData/mapParallelSafe 等) 均已下沉。
 */
class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfigProviders.get().threadCount
    private var searchPool: kotlinx.coroutines.CoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchKey: String = ""
    private var bookSources = emptyList<BookSource>()
    private var searchBooks = arrayListOf<SearchBook>()

    /**
     * 按源分组的原始结果 (key=sourceUrl): 聚合列表 [searchBooks] 之外并行维护,
     * 同一批 SearchBook 对象引用, 无复制开销。
     */
    private val searchGroupBooks = LinkedHashMap<String, MutableList<SearchBook>>()
    private val searchGroupSources = HashMap<String, BookSource>()

    /**
     * 源出现顺序 (只增不删, 重搜不清): 分组快照按此序输出, 重搜回填后区块不跳位。
     * 仅 [resultMutex] 内访问。
     */
    private val sourceOrder = ArrayList<String>()

    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    /** 单源重搜任务 (key=sourceUrl): 只取消同源旧任务, 不牵连其他源正在进行的重搜 */
    private val restartJobs = newConcurrentMap<String, Job>()

    /** 每源结果世代 (重搜 +1): 请求记下发出时的世代, 世代已变的响应 (重搜前发出的旧页) 丢弃 */
    private val resultGenerations = newConcurrentMap<String, Int>()
    private val resultMutex = Mutex()

    /** 每源下一页页码 (重搜后该源回到第 2 页; 新搜索清空) */
    private val nextPages = newConcurrentMap<String, Int>()

    /** 本轮精准开关 (startSearch 时快照, 单源重搜沿用同一值) */
    private var precision = false

    /** 本轮“任一源还有下一页” (主链与单源重搜共同维护) */
    private var hasMore = false

    /** 已声明没有下一页的源 url，翻页时直接跳过，避免多发空请求 (主链与单源重搜并发访问) */
    private val exhaustedSources = newConcurrentSet<String>()

    /** 本轮是否只有一个源 (聚簇布局顶部选项行据此决定是否展示)。 */
    val isSingleSource: Boolean get() = bookSources.size == 1

    private fun initSearchPool(): kotlinx.coroutines.CoroutineDispatcher {
        // 用 limitedParallelism 替代原 Executors.newFixedThreadPool(N).asCoroutineDispatcher()
        // 行为等价: 限制并发到 N, 复用 Dispatchers.IO 线程池, 无需手动 close
        return IoDispatcher.limitedParallelism(min(threadCount, AppConst.MAX_THREAD))
    }

    suspend fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            searchGroupBooks.clear()
            searchGroupSources.clear()
            sourceOrder.clear()
            bookSources = callBack.getSearchScope().getBookSources()
            callBack.onSearchSourcesResolved(bookSources)
            exhaustedSources.clear()
            nextPages.clear()
            hasMore = false
            restartJobs.values.forEach { it.cancel() }
            restartJobs.clear()
            if (bookSources.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPool = initSearchPool()
        }
        startSearch()
    }

    private fun startSearch() {
        precision = AppConfigProviders.get().precisionSearch
        val pool = searchPool ?: return
        searchJob = scope.launch(pool) {
            flow {
                bookSources.forEach { source ->
                    if (source.bookSourceUrl !in exhaustedSources) {
                        emit(source to takeNextPage(source))
                    }
                    workingState.first { it }
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(min(threadCount, AppConst.MAX_THREAD), bookSources.size) { (bookSource, page) ->
                // 世代在请求发出前记下: 请求期间该源被重搜则本页作废 (不混入新结果)
                val generation = generationOf(bookSource.bookSourceUrl)
                withTimeout(timeLimit) {
                    val bookListPage = WebBook.getBookListAwait(
                        bookSource, searchKey, page,
                        filter = { name, author ->
                            !precision || name.contains(searchKey) ||
                                author.contains(searchKey)
                        },
                        onUrlResolved = { analyzeUrl: AnalyzeUrlCore ->
                            val options = parseExploreOptionsFromUrl(analyzeUrl.ruleUrl)
                            if (options.isNotEmpty()) {
                                callBack.onSearchOptionsResolved(bookSource.bookSourceUrl, options)
                            }
                        },
                        selectedOptions = sourceSelectedOptions(bookSource.bookSourceUrl),
                        pluginFilters = callBack.getPluginFilters(bookSource),
                    )
                    SourcePageResult(bookSource, generation, bookListPage)
                }
            }.onEach { result ->
                handlePageResult(result)
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.message}", it)
                callBack.onSearchCancel(it)
            }.collect()
        }
    }

    /** 每源请求前实时取该源已选值 (按源隔离, 供 AnalyzeUrlCore 替换 `<name(...)>` 段)。 */
    private fun sourceSelectedOptions(sourceUrl: String): Map<String, String> =
        callBack.getSearchOptions(sourceUrl).associate { it.name to it.resolvedValue }

    /** 该源当前结果世代 (未重搜过 = 0)。 */
    private fun generationOf(sourceUrl: String): Int = resultGenerations[sourceUrl] ?: 0

    /** 取该源下一页页码并自增 (主链按书源顺序发射, 单写者无需加锁)。 */
    private fun takeNextPage(source: BookSource): Int {
        val url = source.bookSourceUrl
        val page = nextPages[url] ?: 1
        nextPages[url] = page + 1
        return page
    }

    /**
     * 单页结果统一处理 (主链与单源重搜共用): 丢弃过期世代 → 过滤/release/exhausted/hasMore/
     * 入组/重建聚合/回调。单源重搜协程与主链并发到达, 共享结构访问经 [resultMutex] 互斥。
     */
    private suspend fun handlePageResult(result: SourcePageResult) {
        val source = result.source
        // 该源在本页请求期间被重搜: 本页属旧筛选/旧选项, 整体丢弃
        if (result.generation != generationOf(source.bookSourceUrl)) return
        val (items, filteredCount) = SearchBookFilter.apply(result.page.books)
        if (filteredCount > 0) {
            callBack.onFiltered(filteredCount)
        }
        for (book in items) {
            book.releaseHtmlData()
        }
        resultMutex.withLock {
            // 过滤期间又发生重搜, 二次校验
            if (result.generation != generationOf(source.bookSourceUrl)) return
            // 该源这页声明没下一页了，下次翻页就不再请求它
            if (!result.page.hasNextPage) {
                exhaustedSources.add(source.bookSourceUrl)
            }
            // 多书源聚合：任一家声称还有下一页，整体就还有
            hasMore = hasMore || result.page.hasNextPage
            mergeGroup(source, items)
            rebuildAggregate(precision)
            currentCoroutineContext().ensureActive()
            callBack.onSearchSuccess(searchBooks)
            callBack.onSearchGroupsChanged(groupSnapshot())
        }
    }

    /**
     * 单源重搜: 丢弃该源旧结果与在飞旧请求, 只重发该源第 1 页 (沿用当前关键词与该源最新筛选会话),
     * 其余源的结果与在飞请求不受影响 (任务按源隔离); 主链仍在搜索时不发 onSearchFinish。
     */
    fun restartSource(sourceUrl: String) {
        if (mSearchId == 0L || searchKey.isEmpty()) return
        val source = bookSources.firstOrNull { it.bookSourceUrl == sourceUrl } ?: return
        val searchId = mSearchId
        // 世代 +1: 该源此前发出的请求 (含主链在飞页) 全部作废
        resultGenerations[sourceUrl] = generationOf(sourceUrl) + 1
        restartJobs[sourceUrl]?.cancel()
        val restartJob = scope.launch(searchPool ?: initSearchPool(), start = CoroutineStart.LAZY) {
            workingState.first { it }
            resultMutex.withLock {
                searchGroupBooks.remove(sourceUrl)
                exhaustedSources.remove(sourceUrl)
                // 该源回到第 1 页, 后续续页从第 2 页起 (主链页码按源维护, 不再跳页)
                nextPages[sourceUrl] = 2
                rebuildAggregate(precision)
                callBack.onSearchSuccess(searchBooks)
                callBack.onSearchGroupsChanged(groupSnapshot())
            }
            try {
                val generation = generationOf(sourceUrl)
                val page = withTimeout(timeLimit) {
                    WebBook.getBookListAwait(
                        source, searchKey, 1,
                        filter = { name, author ->
                            !precision || name.contains(searchKey) ||
                                author.contains(searchKey)
                        },
                        selectedOptions = sourceSelectedOptions(sourceUrl),
                        pluginFilters = callBack.getPluginFilters(source),
                    )
                }
                if (searchId != mSearchId) return@launch
                handlePageResult(SourcePageResult(source, generation, page))
                if (searchJob?.isActive != true) {
                    callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                }
            } catch (e: Throwable) {
                if (searchId != mSearchId) return@launch
                if (e is CancellationException) throw e
                AppLog.put("书源搜索出错\n${e.message}", e)
                callBack.onSearchCancel(e)
            } finally {
                // 只清本次任务: 若已被同源新重搜替掉, 不动新任务的登记
                if (restartJobs[sourceUrl] === currentCoroutineContext()[Job]) {
                    restartJobs.remove(sourceUrl)
                }
            }
        }
        restartJobs[sourceUrl] = restartJob
        restartJob.start()
    }

    /**
     * 从按源分组数据全量重建聚合列表 (聚合是分组的纯派生视图, 单源变更后重建即自动一致):
     * 分级/聚合/排序语义与原增量版逐字一致 —— (name,author) 同书聚合 origins、
     * equal 组按 origins 数降序、contains 组次之、other 组仅非精准时追加;
     * 组序=源完成序、组内按到达序, 遍历序即原增量维护下的全局到达序, 输出顺序不变。
     */
    private fun rebuildAggregate(precision: Boolean) {
        val equalData = arrayListOf<SearchBook>()
        val containsData = arrayListOf<SearchBook>()
        val otherData = arrayListOf<SearchBook>()
        val equalIndex = HashMap<Pair<String, String>, SearchBook>()
        val containsIndex = HashMap<Pair<String, String>, SearchBook>()
        val otherIndex = HashMap<Pair<String, String>, SearchBook>()
        fun addOrMerge(
            books: MutableList<SearchBook>,
            index: MutableMap<Pair<String, String>, SearchBook>,
            book: SearchBook,
        ) {
            val key = book.name to book.author
            val existing = index[key]
            if (existing == null) {
                books.add(book)
                index[key] = book
            } else {
                existing.addOrigin(book.origin)
            }
        }
        for (books in searchGroupBooks.values) {
            for (book in books) {
                if ((book.name == searchKey) || (book.author == searchKey)) {
                    addOrMerge(equalData, equalIndex, book)
                } else if (book.name.contains(searchKey) || book.author.contains(searchKey)) {
                    addOrMerge(containsData, containsIndex, book)
                } else if (!precision) {
                    // 精准模式下 other 书与原增量版一致: 不合并 origins, 整组丢弃
                    addOrMerge(otherData, otherIndex, book)
                }
            }
        }
        equalData.sortByDescending { it.origins.size }
        equalData.addAll(containsData.sortedByDescending { it.origins.size })
        if (!precision) {
            equalData.addAll(otherData)
        }
        searchBooks = equalData
    }

    /**
     * 新源首次出现按完成顺序入组, 翻页追加到已有组尾; 零结果源不入组 (与聚簇列表只显有书的语义一致)。
     *
     * 按 bookUrl 去重: 劣质源翻页会重复返回上一页的书, 不去重分组横向行会出现重复封面卡。
     */
    private fun mergeGroup(source: BookSource, items: List<SearchBook>) {
        if (items.isEmpty()) return
        val url = source.bookSourceUrl
        if (url !in searchGroupSources) sourceOrder.add(url)
        val group = searchGroupBooks.getOrPut(url) { arrayListOf() }
        val seen = group.mapTo(HashSet()) { it.bookUrl }
        items.forEach {
            if (seen.add(it.bookUrl)) group.add(it)
        }
        searchGroupSources[url] = source
    }

    /**
     * 组顺序 = 源首次出现顺序 ([sourceOrder] 只增不减); 发射快照, UI 不持有内部可变结构。
     * 重搜期间该书分组为空 (不入快照), 回填后按原位置回来。
     */
    private fun groupSnapshot(): List<SourceSearchGroup> =
        sourceOrder.mapNotNull { url ->
            val books = searchGroupBooks[url] ?: return@mapNotNull null
            if (books.isEmpty()) return@mapNotNull null
            SourceSearchGroup(
                searchGroupSources.getValue(url),
                books.toList(),
            )
        }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        restartJobs.values.forEach { it.cancel() }
        // limitedParallelism 返回的 CoroutineDispatcher 无需 close (复用 Dispatchers.IO)
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>)

        /** 按源分组结果更新 (每源每页完成后全量发射; 默认空实现: Web API 等仅关心聚合列表的实现方不受影响) */
        fun onSearchGroupsChanged(groups: List<SourceSearchGroup>) {}
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)

        /** 某源搜索 URL 声明的可选项解析完成 (每源每页都会回调, 同结构重复回调由实现方去重) */
        fun onSearchOptionsResolved(sourceUrl: String, options: List<ExploreOption>) {}

        /** 取某源已声明选项的当前选择态 (默认空: 不支持选项注入的实现方) */
        fun getSearchOptions(sourceUrl: String): List<ExploreOption> = emptyList()

        /**
         * 取该源页面会话筛选实例 (默认 null: 无筛选 UI 的调用方, 委派按源默认筛选取数)。
         * 实现方持有实例并在会话内复用, 使筛选 UI 与取数共用同一份; 不经全局缓存。
         */
        suspend fun getPluginFilters(source: BookSource): PluginFilterSession? = null

        /** 本轮搜索源列表就绪 (在 [search] 里与 [bookSources] 同时赋值)。 */
        fun onSearchSourcesResolved(sources: List<BookSource>) {}

        fun onFiltered(count: Int) {}
    }

}
