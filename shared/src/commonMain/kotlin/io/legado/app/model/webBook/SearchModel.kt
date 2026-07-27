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
            }.mapParallelSafe(threadCount, bookSources.size) { bookSource ->
                withTimeout(timeLimit) {
                    val page = WebBook.getBookListAwait(
                        bookSource, searchKey, searchPage,
                        filter = { name, author ->
                            !precision || name.contains(searchKey) ||
                                author.contains(searchKey)
                        },
                        onUrlResolved = if (isSingleSource) { analyzeUrl: AnalyzeUrlCore ->
                            val options = parseExploreOptionsFromUrl(analyzeUrl.urlAfterJs)
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
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
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
            copyData.forEach {
                currentCoroutineContext().ensureActive()
                if ((it.name == searchKey) || (it.author == searchKey)) {
                    equalData.add(it)
                } else if (it.name.contains(searchKey) || it.author.contains(searchKey)) {
                    containsData.add(it)
                } else {
                    otherData.add(it)
                }
            }
            newDataS.forEach { nBook ->
                currentCoroutineContext().ensureActive()
                if ((nBook.name == searchKey) || (nBook.author == searchKey)) {
                    var hasSame = false
                    equalData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if ((pBook.name == nBook.name) && (pBook.author == nBook.author)) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        equalData.add(nBook)
                    }
                } else if (nBook.name.contains(searchKey) || nBook.author.contains(searchKey)) {
                    var hasSame = false
                    containsData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if ((pBook.name == nBook.name) && (pBook.author == nBook.author)) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        containsData.add(nBook)
                    }
                } else if (!precision) {
                    var hasSame = false
                    otherData.forEach { pBook ->
                        currentCoroutineContext().ensureActive()
                        if ((pBook.name == nBook.name) && (pBook.author == nBook.author)) {
                            pBook.addOrigin(nBook.origin)
                            hasSame = true
                        }
                    }
                    if (!hasSame) {
                        otherData.add(nBook)
                    }
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
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
        fun onSearchOptionsResolved(options: List<ExploreOption>)
        fun getSearchOptions(): List<ExploreOption>
        fun onFiltered(count: Int) {}
    }

}
