package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
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
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSources = emptyList<BookSource>()
    private var searchBooks = arrayListOf<SearchBook>()

    /**
     * 按源分组的原始结果 (key=sourceUrl): 聚合列表 [searchBooks] 之外并行维护,
     * 同一批 SearchBook 对象引用, 无复制开销。
     */
    private val searchGroupBooks = LinkedHashMap<String, MutableList<SearchBook>>()
    private val searchGroupSources = HashMap<String, BookSource>()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    /** 已声明没有下一页的源 url，翻页时直接跳过，避免多发空请求。 */
    private val exhaustedSources = HashSet<String>()

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
            bookSources = callBack.getSearchScope().getBookSources()
            exhaustedSources.clear()
            if (bookSources.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            searchPool = initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = AppConfigProviders.get().precisionSearch
        var hasMore = false
        val pool = searchPool ?: return
        val isSingleSource = bookSources.size == 1
        val selectedOptions = callBack.getSearchOptions().associate { it.name to it.resolvedValue }
        searchJob = scope.launch(pool) {
            flow {
                bookSources.forEach { source ->
                    if (source.bookSourceUrl !in exhaustedSources) {
                        emit(source)
                    }
                    workingState.first { it }
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(min(threadCount, AppConst.MAX_THREAD), bookSources.size) { bookSource ->
                withTimeout(timeLimit) {
                    val page = WebBook.getBookListAwait(
                        bookSource, searchKey, searchPage,
                        filter = { name, author ->
                            !precision || name.contains(searchKey) ||
                                author.contains(searchKey)
                        },
                        onUrlResolved = if (isSingleSource) { analyzeUrl: AnalyzeUrlCore ->
                            val options = parseExploreOptionsFromUrl(analyzeUrl.ruleUrl)
                            if (options.isNotEmpty()) {
                                callBack.onSearchOptionsResolved(options)
                            }
                        } else null,
                        selectedOptions = selectedOptions,
                    )
                    bookSource to page
                }
            }.onEach { (source, page) ->
                val (items, filteredCount) = SearchBookFilter.apply(page.books)
                if (filteredCount > 0) {
                    callBack.onFiltered(filteredCount)
                }
                for (book in items) {
                    book.releaseHtmlData()
                }
                // 该源这页声明没下一页了，下次翻页就不再请求它
                if (!page.hasNextPage) {
                    exhaustedSources.add(source.bookSourceUrl)
                }
                // 多书源聚合：任一家声称还有下一页，整体就还有
                hasMore = hasMore || page.hasNextPage
                mergeItems(items, precision)
                mergeGroup(source, items)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
                callBack.onSearchGroupsChanged(groupSnapshot())
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.message}", it)
                callBack.onSearchCancel(it)
            }.collect()
        }
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean) {
        if (newDataS.isNotEmpty()) {
            val copyData = ArrayList(searchBooks)
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
            copyData.forEach {
                currentCoroutineContext().ensureActive()
                if ((it.name == searchKey) || (it.author == searchKey)) {
                    equalData.add(it)
                    val key = it.name to it.author
                    if (key !in equalIndex) equalIndex[key] = it
                } else if (it.name.contains(searchKey) || it.author.contains(searchKey)) {
                    containsData.add(it)
                    val key = it.name to it.author
                    if (key !in containsIndex) containsIndex[key] = it
                } else {
                    otherData.add(it)
                    val key = it.name to it.author
                    if (key !in otherIndex) otherIndex[key] = it
                }
            }
            newDataS.forEach { nBook ->
                currentCoroutineContext().ensureActive()
                if ((nBook.name == searchKey) || (nBook.author == searchKey)) {
                    addOrMerge(equalData, equalIndex, nBook)
                } else if (nBook.name.contains(searchKey) || nBook.author.contains(searchKey)) {
                    addOrMerge(containsData, containsIndex, nBook)
                } else if (!precision) {
                    addOrMerge(otherData, otherIndex, nBook)
                }
            }
            currentCoroutineContext().ensureActive()
            equalData.sortByDescending { it.origins.size }
            equalData.addAll(containsData.sortedByDescending { it.origins.size })
            if (!precision) {
                equalData.addAll(otherData)
            }
            currentCoroutineContext().ensureActive()
            searchBooks = equalData
        }
    }

    /**
     * 新源首次出现按完成顺序入组, 翻页追加到已有组尾; 零结果源不入组 (与聚簇列表只显有书的语义一致)。
     *
     * 按 bookUrl 去重: 劣质源翻页会重复返回上一页的书, 不去重分组横向行会出现重复封面卡。
     */
    private fun mergeGroup(source: BookSource, items: List<SearchBook>) {
        if (items.isEmpty()) return
        val group = searchGroupBooks.getOrPut(source.bookSourceUrl) { arrayListOf() }
        val seen = group.mapTo(HashSet()) { it.bookUrl }
        items.forEach {
            if (seen.add(it.bookUrl)) group.add(it)
        }
        searchGroupSources[source.bookSourceUrl] = source
    }

    /** 组顺序 = 源完成顺序 (LinkedHashMap 插入序); 发射快照, UI 不持有内部可变结构 */
    private fun groupSnapshot(): List<SourceSearchGroup> =
        searchGroupBooks.map { (url, books) ->
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
        fun onSearchOptionsResolved(options: List<ExploreOption>)
        fun getSearchOptions(): List<ExploreOption>
        fun onFiltered(count: Int) {}
    }

}
