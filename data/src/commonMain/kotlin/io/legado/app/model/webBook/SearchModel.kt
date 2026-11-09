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
import io.legado.app.utils.mapParallelSafe
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
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
    val searchId: Long,
    val generation: Int,
    val requestPage: Int,
    val page: BookListPage,
    /** fresh 第 1 页按有效默认继续: 提交后提示选择已刷新。 */
    val freshInvalidated: Boolean = false,
    val job: Job? = null,
)

/** 单源一页的完整请求快照: 排队期间重搜/换轮/暂停即整份作废。 */
private data class SourcePageRequest(
    val source: BookSource,
    val searchId: Long,
    val generation: Int,
    val page: Int,
    val nextPageUrl: String?,
    val searchKey: String,
    val precision: Boolean,
    val selections: Map<String, ExploreOptionSelection>,
    /** 本次用的是主模板 (replaceAll); 与是否从第 1 页 fresh 是两条轴。 */
    val replaceAllOptions: Boolean,
    /** 本次从第 1 页重新取数; 只有 fresh 才允许按本次有效默认继续。 */
    val allowFreshInvalidated: Boolean,
)

private sealed interface SourcePageOutcome {
    data class Loaded(val result: SourcePageResult) : SourcePageOutcome
    object Skipped : SourcePageOutcome
    data class Invalidated(
        val sourceUrl: String,
        val searchId: Long,
        val generation: Int,
        val job: Job? = null,
    ) : SourcePageOutcome
    data class Failed(
        val sourceUrl: String,
        val searchId: Long,
        val generation: Int,
        val error: Throwable,
        val job: Job? = null,
    ) : SourcePageOutcome
}

/** searchLayout (AppConfig) 的"按源分类"布局标志位; 低 3 位列数 / bit4 视频位仅聚簇布局使用。 */
const val SEARCH_LAYOUT_SOURCE_GROUP = 0x20

/**
 * 搜索编排层。
 *
 * 所有控制状态 (轮次/关键词/世代/游标/源任务/暂停) 在同一把 [stateLock] 下提交；
 * 网络在每源 [Mutex] 外，提交只在短临界区。单源重搜、暂停恢复与主链复用
 * [fetchSourcePage]/[applyOutcome]，不存在第二份 HTTP/错误分支。
 */
class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfigProviders.get().threadCount
    private var searchPool: kotlinx.coroutines.CoroutineDispatcher? = null

    private val stateLock = SynchronizedObject()
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

    /** 源出现顺序 (只增不删, 重搜不清): 分组快照按此序输出, 重搜回填后区块不跳位。 */
    private val sourceOrder = ArrayList<String>()

    private var searchJob: Job? = null
    private var searchRunId: Long = 0L
    private val workingState = MutableStateFlow(true)

    /** 每源结果世代 (重搜/暂停 +1): 请求记下发出时的世代, 世代已变即时作废。 */
    private val resultGenerations = HashMap<String, Int>()

    /** 每源下一页页码 (重搜后该源回到第 2 页; 新搜索清空)。 */
    private val nextPages = HashMap<String, Int>()

    /** 每源下一页地址 (上一页解析出时非空, 续页直接请求它; 新搜索清空)。 */
    private val nextPageUrls = HashMap<String, String>()

    /** 本轮精准开关 (startSearch 时快照, 单源重搜沿用同一值)。 */
    private var precision = false

    /** 本轮“任一源还有下一页” (由每源 exhausted/paused 状态派生)。 */
    @Volatile
    private var hasMore = false

    /** 已声明没有下一页的源 url，翻页时直接跳过，避免多发空请求。 */
    private val exhaustedSources = HashSet<String>()

    /** 本轮真实失败、等待用户显式重试的源: 不参与自动触底续搜。 */
    private val failedSources = HashSet<String>()

    /** 正在编辑筛选器的源: 暂停该源分页并把在飞请求作废, 关闭后恢复。 */
    private val pausedSources = HashSet<String>()

    /** 暂停时被取消的那一页: 恢复时补发同一页, 不是无条件全局续搜。 */
    private val pausedRequests = HashMap<String, SourcePageRequest>()

    /** 同源取数互斥: 主链与单源重搜对该源最多一个在飞取数。 */
    private val sourceMutexes = newConcurrentMap<String, Mutex>()
    private val sourceMutexLock = SynchronizedObject()

    /** 每源当前取数协程 (主链子协程或重搜协程), 作废旧请求时精确取消。 */
    private val sourceJobs = HashMap<String, Job>()

    /** 单源重搜/恢复任务 (key=sourceUrl), 在源取数开始前也可被下一次操作取消。 */
    private val sourceCommandJobs = HashMap<String, Job>()

    /** 本轮是否只有一个源 (聚簇布局顶部选项行据此决定是否展示)。 */
    val isSingleSource: Boolean
        get() = synchronized(stateLock) { bookSources.size == 1 }

    private fun initSearchPool(): kotlinx.coroutines.CoroutineDispatcher =
        IoDispatcher.limitedParallelism(min(threadCount, AppConst.MAX_THREAD))

    /**
     * 新一轮搜索。关键词为空仅允许续搜当前轮。
     *
     * 源查询在锁外完成；初始化清表/轮次/关键词在同一锁内提交，旧轮不会在新轮之后清空。
     */
    suspend fun search(searchId: Long, key: String) {
        if (key.isEmpty()) {
            val current = synchronized(stateLock) { searchId == mSearchId }
            if (!current) return
            startSearch(searchId)
            return
        }
        val sources = callBack.getSearchScope().getBookSources()
        var initialized = false
        var emptySources = false
        synchronized(stateLock) {
            if (searchId < mSearchId) return
            if (searchId != mSearchId) {
                closeLocked()
                searchKey = key
                searchBooks = arrayListOf()
                searchGroupBooks.clear()
                searchGroupSources.clear()
                sourceOrder.clear()
                bookSources = sources
                exhaustedSources.clear()
                failedSources.clear()
                pausedSources.clear()
                pausedRequests.clear()
                nextPages.clear()
                nextPageUrls.clear()
                resultGenerations.clear()
                hasMore = sources.isNotEmpty()
                mSearchId = searchId
                searchPool = initSearchPool()
                initialized = true
            }
            emptySources = bookSources.isEmpty()
        }
        if (initialized) {
            val snapshot = synchronized(stateLock) {
                if (mSearchId == searchId) bookSources.toList() else null
            }
            if (snapshot != null) callBack.onSearchSourcesResolved(snapshot)
        }
        if (emptySources && synchronized(stateLock) { mSearchId == searchId }) {
            callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
            return
        }
        startSearch(searchId)
    }

    private fun startSearch(expectedId: Long) {
        var job: Job? = null
        synchronized(stateLock) {
            if (mSearchId != expectedId) return
            precision = AppConfigProviders.get().precisionSearch
            val id = mSearchId
            val runId = ++searchRunId
            val sources = bookSources.toList()
            val pool = searchPool ?: return
            searchJob?.cancel()
            job = scope.launch(pool, start = CoroutineStart.LAZY) {
                try {
                    flow {
                        sources.forEach { source ->
                            val request = synchronized(stateLock) {
                                if (mSearchId != id || searchRunId != runId) null
                                else requestLocked(source, id, nextPageUrls[source.bookSourceUrl])
                            }
                            if (request != null) emit(request)
                            workingState.first { it }
                        }
                    }.onStart {
                        val current = synchronized(stateLock) {
                            mSearchId == id && searchRunId == runId
                        }
                        if (!current) throw CancellationException()
                        callBack.onSearchStart()
                    }.mapParallelSafe(
                        min(threadCount, AppConst.MAX_THREAD),
                        sources.size,
                    ) { request ->
                        fetchSourcePage(request)
                    }.onEach { outcome ->
                        applyOutcome(outcome)
                    }.onCompletion { cause ->
                        if (cause == null) finishRound(id, runId)
                    }.collect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val current = synchronized(stateLock) { mSearchId == id }
                    if (current) {
                        AppLog.put("书源搜索出错: " + e.message, e)
                        callBack.onSearchCancel(e)
                    }
                }
            }
            searchJob = job
        }
        job?.start()
    }

    /**
     * 统一取数入口: 主链、单源重搜、暂停恢复都只走这一个函数。
     * 网络持有该源 [Mutex]；源锁释放后才做短提交。
     */
    private suspend fun fetchSourcePage(request: SourcePageRequest): SourcePageOutcome {
        val url = request.source.bookSourceUrl
        val self = currentCoroutineContext()[Job] ?: return SourcePageOutcome.Skipped
        val mutex = synchronized(sourceMutexLock) {
            sourceMutexes.getOrPut(url) { Mutex() }
        }
        return mutex.withLock {
            val accepted = synchronized(stateLock) {
                if (!isCurrentLocked(request) || url in pausedSources) {
                    false
                } else {
                    sourceJobs[url] = self
                    true
                }
            }
            if (!accepted) return@withLock SourcePageOutcome.Skipped
            try {
                val filters = callBack.getPluginFilters(request.source)
                if (!synchronized(stateLock) { isCurrentLocked(request) }) {
                    clearSourceTask(request, self)
                    return@withLock SourcePageOutcome.Skipped
                }
                var invalidated = false
                var freshInvalidated = false
                val page = withTimeout(timeLimit) {
                    WebBook.getBookListAwait(
                        request.source, request.searchKey, request.page,
                        nextPageUrl = request.nextPageUrl,
                        allowFreshInvalidatedOptions = request.allowFreshInvalidated,
                        filter = { name, author ->
                            !request.precision || name.contains(request.searchKey) ||
                                author.contains(request.searchKey)
                        },
                        onUrlResolved = { analyzeUrl: AnalyzeUrlCore ->
                            val resolved = synchronized(stateLock) {
                                if (!isCurrentLocked(request)) null
                                else {
                                    val changed = callBack.onSearchOptionsResolved(
                                        url,
                                        analyzeUrl.resolvedExploreOptions,
                                        replaceAll = request.replaceAllOptions,
                                    ) || analyzeUrl.optionSelectionsInvalidated
                                    if (changed && !request.allowFreshInvalidated) {
                                        throw ExploreOptionSelectionInvalidatedException()
                                    }
                                    changed
                                }
                            } ?: throw CancellationException()
                            invalidated = resolved
                            if (resolved && request.allowFreshInvalidated) {
                                freshInvalidated = true
                            }
                        },
                        optionSelections = request.selections,
                        pluginFilters = filters,
                    )
                }
                if (!synchronized(stateLock) { isCurrentLocked(request) }) {
                    clearSourceTask(request, self)
                    SourcePageOutcome.Skipped
                } else if (invalidated && !freshInvalidated) {
                    SourcePageOutcome.Invalidated(url, request.searchId, request.generation, self)
                } else {
                    SourcePageOutcome.Loaded(
                        SourcePageResult(
                            request.source,
                            request.searchId,
                            request.generation,
                            request.page,
                            page,
                            freshInvalidated,
                            self,
                        )
                    )
                }
            } catch (e: ExploreOptionSelectionInvalidatedException) {
                if (synchronized(stateLock) { isCurrentLocked(request) }) {
                    SourcePageOutcome.Invalidated(url, request.searchId, request.generation, self)
                } else {
                    clearSourceTask(request, self)
                    SourcePageOutcome.Skipped
                }
            } catch (e: TimeoutCancellationException) {
                if (synchronized(stateLock) { isCurrentLocked(request) }) {
                    SourcePageOutcome.Failed(url, request.searchId, request.generation, e, self)
                } else {
                    clearSourceTask(request, self)
                    SourcePageOutcome.Skipped
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (synchronized(stateLock) { isCurrentLocked(request) }) {
                    SourcePageOutcome.Failed(url, request.searchId, request.generation, e, self)
                } else {
                    clearSourceTask(request, self)
                    SourcePageOutcome.Skipped
                }
            }
        }
    }

    private suspend fun applyOutcome(outcome: SourcePageOutcome) {
        try {
            when (outcome) {
                is SourcePageOutcome.Loaded -> handlePageResult(outcome.result)
                is SourcePageOutcome.Invalidated ->
                    handleInvalidated(outcome.sourceUrl, outcome.searchId, outcome.generation)

                is SourcePageOutcome.Failed -> {
                    synchronized(stateLock) {
                        if (!isCurrentSourceLocked(
                                outcome.sourceUrl,
                                outcome.searchId,
                                outcome.generation,
                            )
                        ) {
                            return@synchronized
                        }
                        failedSources.add(outcome.sourceUrl)
                        recomputeHasMoreLocked()
                        AppLog.put("书源搜索出错: " + outcome.error.message, outcome.error)
                        callBack.onSearchSourceError(outcome.sourceUrl, outcome.error)
                        callBack.onSearchHasMoreChanged(hasMore)
                    }
                }

                SourcePageOutcome.Skipped -> Unit
            }
        } finally {
            when (outcome) {
                is SourcePageOutcome.Loaded -> clearSourceTask(
                    outcome.result.source.bookSourceUrl,
                    outcome.result.job,
                )

                is SourcePageOutcome.Invalidated ->
                    clearSourceTask(outcome.sourceUrl, outcome.job)

                is SourcePageOutcome.Failed ->
                    clearSourceTask(outcome.sourceUrl, outcome.job)

                SourcePageOutcome.Skipped -> Unit
            }
        }
    }

    /** 未提交页仍算在飞; 清理只认本次请求登记的 Job 身份。 */
    private fun clearSourceTask(request: SourcePageRequest, job: Job?) {
        synchronized(stateLock) {
            if (job != null && sourceJobs[request.source.bookSourceUrl] === job) {
                sourceJobs.remove(request.source.bookSourceUrl)
            }
        }
    }

    private fun clearSourceTask(sourceUrl: String, job: Job?) {
        if (job == null) return
        synchronized(stateLock) {
            if (sourceJobs[sourceUrl] === job) sourceJobs.remove(sourceUrl)
        }
    }

    private suspend fun handleInvalidated(sourceUrl: String, searchId: Long, generation: Int) {
        synchronized(stateLock) {
            if (!isCurrentSourceLocked(sourceUrl, searchId, generation)) return
            searchGroupBooks.remove(sourceUrl)
            exhaustedSources.add(sourceUrl)
            failedSources.remove(sourceUrl)
            rebuildAggregate(precision)
            recomputeHasMoreLocked()
            callBack.onSearchSuccess(searchBooks)
            callBack.onSearchGroupsChanged(groupSnapshot())
            callBack.onSearchHasMoreChanged(hasMore)
            callBack.onSearchOptionsInvalidated(sourceUrl)
        }
    }

    /** 每源请求前实时取该源已选原始值 (按源隔离, 供 AnalyzeUrlCore 替换 `<name(...)>` 段)。 */
    private fun sourceSelectedOptions(sourceUrl: String): Map<String, ExploreOptionSelection> =
        callBack.getSearchOptions(sourceUrl)

    /** 该源当前结果世代 (未重搜过 = 0)。调用方必须持有 [stateLock]。 */
    private fun generationOf(sourceUrl: String): Int = resultGenerations[sourceUrl] ?: 0

    /**
     * 单页结果统一处理 (主链与单源重搜共用): 丢弃过期世代 → 过滤/release/exhausted/hasMore/
     * 入组/重建聚合/回调。过滤在锁外, 提交在锁内且重新核身份。
     */
    private suspend fun handlePageResult(result: SourcePageResult) {
        val source = result.source
        val sourceUrl = source.bookSourceUrl
        if (!synchronized(stateLock) { isCurrentSourceLocked(sourceUrl, result.searchId, result.generation) }) {
            return
        }
        val (items, filteredCount) = SearchBookFilter.apply(result.page.books)
        if (filteredCount > 0) {
            callBack.onFiltered(filteredCount)
        }
        for (book in items) {
            book.releaseHtmlData()
        }
        synchronized(stateLock) {
            if (!isCurrentSourceLocked(sourceUrl, result.searchId, result.generation)) {
                null
            } else {
                failedSources.remove(sourceUrl)
                if (!result.page.hasNextPage) {
                    exhaustedSources.add(sourceUrl)
                } else {
                    exhaustedSources.remove(sourceUrl)
                }
                nextPages[sourceUrl] = result.requestPage + 1
                val nextPageUrl = result.page.nextPageUrl
                if (nextPageUrl.isNullOrBlank()) {
                    nextPageUrls.remove(sourceUrl)
                } else {
                    nextPageUrls[sourceUrl] = nextPageUrl
                }
                mergeGroup(source, items)
                rebuildAggregate(precision)
                recomputeHasMoreLocked()
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
                callBack.onSearchGroupsChanged(groupSnapshot())
                callBack.onSearchHasMoreChanged(hasMore)
                if (result.freshInvalidated) {
                    callBack.onSearchOptionsRefreshed(sourceUrl)
                }
            }
        }
    }

    /** 按每源可继续状态重算整体 hasMore, 不用一枚全局 bool 代替源状态。调用方持锁。 */
    private fun recomputeHasMoreLocked() {
        hasMore = bookSources.any { source ->
            val url = source.bookSourceUrl
            url !in exhaustedSources && url !in pausedSources && url !in failedSources
        }
    }

    /**
     * 单源重搜: 在锁内立刻推进世代、取消在飞任务并清该源结果, 然后从第 1 页重取。
     * 不等待旧 HTTP 释放源锁; 旧请求因世代已变即时作废。主链其他源不受影响。
     */
    fun restartSource(sourceUrl: String) {
        var job: Job? = null
        synchronized(stateLock) {
            if (searchKey.isEmpty()) return
            val source = bookSources.firstOrNull { it.bookSourceUrl == sourceUrl } ?: return
            val generation = invalidateSourceLocked(sourceUrl)
            pausedSources.remove(sourceUrl)
            pausedRequests.remove(sourceUrl)
            exhaustedSources.remove(sourceUrl)
            failedSources.remove(sourceUrl)
            nextPages[sourceUrl] = 1
            nextPageUrls.remove(sourceUrl)
            searchGroupBooks.remove(sourceUrl)
            rebuildAggregate(precision)
            recomputeHasMoreLocked()
            callBack.onSearchSuccess(searchBooks)
            callBack.onSearchGroupsChanged(groupSnapshot())
            callBack.onSearchHasMoreChanged(hasMore)
            val fresh = SourcePageRequest(
                source = source,
                searchId = mSearchId,
                generation = generation,
                page = 1,
                nextPageUrl = null,
                searchKey = searchKey,
                precision = precision,
                selections = sourceSelectedOptions(sourceUrl),
                replaceAllOptions = true,
                allowFreshInvalidated = true,
            )
            val pool = searchPool ?: initSearchPool().also { searchPool = it }
            val launched = scope.launch(pool, start = CoroutineStart.LAZY) {
                runSourceCommand(fresh)
            }
            job = launched
            sourceCommandJobs[sourceUrl] = launched
        }
        job?.start()
    }

    /** 单源命令统一收尾: 取数结果仍经同一个 handle 提交, 主链不在飞才发 finish。 */
    private suspend fun runSourceCommand(request: SourcePageRequest) {
        val url = request.source.bookSourceUrl
        try {
            workingState.first { it }
            if (!synchronized(stateLock) { isCurrentLocked(request) }) return
            applyOutcome(fetchSourcePage(request))
        } finally {
            val self = currentCoroutineContext()[Job]
            synchronized(stateLock) {
                if (sourceCommandJobs[url] === self) sourceCommandJobs.remove(url)
            }
            finishIfIdle(request.searchId)
        }
    }

    private fun finishIfIdle(searchId: Long) {
        synchronized(stateLock) {
            if (searchId == 0L || searchId != mSearchId) return
            if (searchJob?.isActive == true || sourceCommandJobs.isNotEmpty()) return
            if (searchBooks.isEmpty()) {
                callBack.onSearchFinish(true, hasMore)
            } else {
                callBack.onSearchFinish(false, hasMore)
            }
        }
    }

    private fun finishRound(searchId: Long, runId: Long) {
        synchronized(stateLock) {
            if (searchId != mSearchId || runId != searchRunId) return
            if (sourceCommandJobs.isNotEmpty()) return
            callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
        }
    }

    private fun isCurrentLocked(request: SourcePageRequest): Boolean =
        request.searchId == mSearchId &&
            request.generation == generationOf(request.source.bookSourceUrl) &&
            request.source.bookSourceUrl !in pausedSources

    private fun isCurrentSourceLocked(sourceUrl: String, searchId: Long, generation: Int): Boolean =
        searchId == mSearchId && generation == generationOf(sourceUrl)

    /** 调用方持锁: 推进该源世代并取消在飞取数/命令。 */
    private fun invalidateSourceLocked(sourceUrl: String): Int {
        val next = generationOf(sourceUrl) + 1
        resultGenerations[sourceUrl] = next
        sourceJobs.remove(sourceUrl)?.cancel()
        sourceCommandJobs.remove(sourceUrl)?.cancel()
        pausedRequests.remove(sourceUrl)
        return next
    }

    /**
     * UI 选择提交与作废旧源请求在同一模型锁域: 旧 onUrlResolved 也在这把锁内回调,
     * 因此不可能在“新选择已写、世代未推进”的窗口把旧选择 merge 回来。
     */
    fun commitSourceSelection(sourceUrl: String, commit: () -> Boolean): Boolean =
        synchronized(stateLock) {
            val changed = commit()
            if (changed && searchKey.isNotEmpty()) invalidateSourceLocked(sourceUrl)
            changed
        }

    /** 主链请求快照构造。调用方持锁。 */
    private fun requestLocked(
        source: BookSource,
        searchId: Long,
        nextPageUrl: String?,
    ): SourcePageRequest? {
        val url = source.bookSourceUrl
        if (url in exhaustedSources || url in pausedSources || url in failedSources) return null
        // 同源已有排队/在飞/未提交请求时, 主链不得再抓一份同页快照。
        if (sourceJobs.containsKey(url) || sourceCommandJobs.containsKey(url)) return null
        val page = nextPages[url] ?: 1
        return SourcePageRequest(
            source = source,
            searchId = searchId,
            generation = generationOf(url),
            page = page,
            nextPageUrl = nextPageUrl,
            searchKey = searchKey,
            precision = precision,
            selections = sourceSelectedOptions(url),
            replaceAllOptions = nextPageUrl == null,
            allowFreshInvalidated = page == 1 && nextPageUrl == null,
        )
    }

    /**
     * 从按源分组数据全量重建聚合列表 (聚合是分组的纯派生视图, 单源变更后重建即自动一致):
     * 分级/聚合/排序语义与原增量版逐字一致。
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
                // 聚合列表持有独立副本; raw 分组里的 SearchBook.origins 不再被聚合写回污染。
                val aggregate = book.copy().also {
                    it.infoHtml = book.infoHtml
                    it.tocHtml = book.tocHtml
                }
                books.add(aggregate)
                index[key] = aggregate
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
     * 新源首次出现按完成顺序入组, 翻页追加到已有组尾; 零结果源不入组。
     * 按 bookUrl 去重: 劣质源翻页会重复返回上一页的书。
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

    /** 组顺序 = 源首次出现顺序; 发射快照, UI 不持有内部可变结构。 */
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

    /** 编辑筛选器期间暂停该源分页, 立即作废在飞页并记住待补发页。 */
    fun pauseSource(sourceUrl: String) {
        synchronized(stateLock) {
            if (searchKey.isEmpty()) return
            val hadActiveTask = sourceJobs.containsKey(sourceUrl) ||
                sourceCommandJobs.containsKey(sourceUrl)
            val atEnd = sourceUrl in exhaustedSources
            pausedSources.add(sourceUrl)
            val generation = invalidateSourceLocked(sourceUrl)
            val source = bookSources.firstOrNull { it.bookSourceUrl == sourceUrl } ?: return
            if (hadActiveTask && !atEnd) {
                val nextUrl = nextPageUrls[sourceUrl]
                val page = nextPages[sourceUrl] ?: 1
                pausedRequests[sourceUrl] = SourcePageRequest(
                    source = source,
                    searchId = mSearchId,
                    generation = generation,
                    page = page,
                    nextPageUrl = nextUrl,
                    searchKey = searchKey,
                    precision = precision,
                    selections = sourceSelectedOptions(sourceUrl),
                    replaceAllOptions = nextUrl == null,
                    allowFreshInvalidated = page == 1 && nextUrl == null,
                )
            } else {
                pausedRequests.remove(sourceUrl)
            }
            recomputeHasMoreLocked()
            callBack.onSearchHasMoreChanged(hasMore)
        }
    }

    /** 结束编辑: 没改动时补发被取消的当前页; 有改动由调用方 [restartSource] 从第 1 页重取。 */
    fun resumeSource(sourceUrl: String) {
        var job: Job? = null
        synchronized(stateLock) {
            pausedSources.remove(sourceUrl)
            recomputeHasMoreLocked()
            callBack.onSearchHasMoreChanged(hasMore)
            val request = pausedRequests.remove(sourceUrl) ?: return
            if (request.source.bookSourceUrl in exhaustedSources) return
            if (!isCurrentLocked(request)) return
            val pool = searchPool ?: initSearchPool().also { searchPool = it }
            val launched = scope.launch(pool, start = CoroutineStart.LAZY) {
                runSourceCommand(request)
            }
            job = launched
            sourceCommandJobs[sourceUrl] = launched
        }
        job?.start()
    }

    fun close() {
        synchronized(stateLock) {
            closeLocked()
        }
    }

    /** 调用方持锁: 结束当前轮并取消所有源任务。 */
    private fun closeLocked() {
        searchJob?.cancel()
        searchJob = null
        sourceJobs.values.forEach { it.cancel() }
        sourceJobs.clear()
        sourceCommandJobs.values.forEach { it.cancel() }
        sourceCommandJobs.clear()
        pausedRequests.clear()
        pausedSources.clear()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>)

        /** 按源分组结果更新 (每源每页完成后全量发射) */
        fun onSearchGroupsChanged(groups: List<SourceSearchGroup>) {}
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)

        /** 单源请求失败: 只影响该源, 不取消其它源。 */
        fun onSearchSourceError(sourceUrl: String, error: Throwable) {}

        /** 整体是否还能继续翻页 (由每源 exhausted/paused 状态派生)。 */
        fun onSearchHasMoreChanged(hasMore: Boolean) {}

        /** 某源搜索 URL 声明的可选项解析完成 (每源每页都会回调, 同结构重复回调由实现方去重)。
         * @return 已有选择是否被本次翻页声明废弃 (true 时该源停止继续翻页并清空已加载结果)
         */
        fun onSearchOptionsResolved(
            sourceUrl: String,
            options: List<ExploreOption>,
            replaceAll: Boolean = true,
        ): Boolean = false

        /** 翻页声明使已选值失效: 调用方提示用户重新选择或刷新。 */
        fun onSearchOptionsInvalidated(sourceUrl: String) {}

        /** fresh 第 1 页按本次有效默认继续: 提示用户选择已刷新。 */
        fun onSearchOptionsRefreshed(sourceUrl: String) {}

        /** 取某源已声明选项的当前原始选择 (默认空: 不支持选项注入的实现方) */
        fun getSearchOptions(sourceUrl: String): Map<String, ExploreOptionSelection> = emptyMap()

        /**
         * 取该源页面会话筛选实例。默认实现 = 无筛选 UI 的调用方 (web API 等): 经唯一入口
         * [pluginFilterSessionOf] 取源默认实例 (注册表就绪后新建, 不缓存)。
         */
        suspend fun getPluginFilters(source: BookSource): PluginFilterSession? =
            pluginFilterSessionOf(source)

        /** 本轮搜索源列表就绪 (在 [search] 里与 [bookSources] 同时赋值)。 */
        fun onSearchSourcesResolved(sources: List<BookSource>) {}

        fun onFiltered(count: Int) {}
    }
}
