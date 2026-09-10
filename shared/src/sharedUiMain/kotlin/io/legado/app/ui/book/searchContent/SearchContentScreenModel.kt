package io.legado.app.ui.book.searchContent

import androidx.compose.runtime.mutableStateListOf
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书内全文搜索 ScreenModel (shared sharedUiMain)。
 *
 * 状态机与 mutableStateOf 状态从 app 端 [SearchContentActivity] 迁入本类,
 * 持有 [SearchContentUiState] 的 [MutableStateFlow], results 透传 SnapshotStateList 引用;
 * 搜索编排委托 [SearchContentViewModelShared] (已下沉 commonMain)。
 *
 * 平台专属逻辑 (Intent/setResult/finish/postEvent/observeEvent/getString) 留 app 端 Activity,
 * 通过 [onOpenResult] 回调与 [SearchContentUiActions] 接口回调触发;
 * 简繁转换 lambda 与空结果文案 lambda 经构造函数注入。
 */
class SearchContentScreenModel(
    private val chineseConverter: (type: Int, text: String) -> String,
    private val emptyResultText: () -> String,
) : ScreenModel {

    private val scope = screenModelScope("搜索内容", IoDispatcher)

    private val shared = SearchContentViewModelShared(
        scope = scope,
        chineseConverter = chineseConverter,
    )

    private val results = mutableStateListOf<SearchResult>()

    private var clearFocusHandler: (() -> Unit)? = null

    private var searchJob: Job? = null
    private var initJob: Job? = null

    /** 打开搜索结果回调, 由 Activity 注入平台路由 (setResult/finish/postEvent) */
    var onOpenResult: ((item: SearchResult, index: Int) -> Unit)? = null

    private val _state = MutableStateFlow(
        SearchContentUiState(
            query = "",
            results = results,
            resultCount = 0,
            searching = false,
            durChapterIndex = 0,
            replaceEnabled = shared.replaceEnabled,
            focusEpoch = 0,
            pendingScrollIndex = null,
        )
    )
    val state: StateFlow<SearchContentUiState> = _state.asStateFlow()

    /**
     * 快照形式暴露搜索结果列表，供类型化路由结果完整回传。
     * [results] 只在主线程更新，是搜索结果的唯一 UI 真源。
     */
    val searchResultList: List<SearchResult>
        get() = results.toList()

    // ===== 初始化 (对照 Activity.onActivityCreated) =====

    /**
     * @param searchResultList 路由参数携带的已有搜索结果，用于恢复列表。
     * @param position 当前选中结果索引。
     * @param searchWord 当前搜索词。
     * @param book 当前书籍 (阅读页全文搜索入口经 BookRef 传入; null 回落 IntentData.book)。
     */
    fun init(
        searchResultList: List<SearchResult>?,
        position: Int,
        searchWord: String?,
        book: Book? = null,
    ) {
        val noSearchResult = searchResultList == null
        if (noSearchResult) requestFocusSearch()
        shared.initBook(
            success = {
                initSearchResultList(searchResultList, position)
                initBook(noSearchResult, searchWord)
            },
            book = book,
        )
    }

    /** SAVE_CONTENT 事件处理: 更新 cacheChapterNames (observeEvent 留 Activity) */
    fun onSaveContent(book: Book, chapter: BookChapter) {
        shared.book?.bookUrl?.let { bookUrl ->
            if (book.bookUrl == bookUrl) {
                shared.cacheChapterNames.add(chapter.getFileName())
            }
        }
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        results.addAll(list)
        _state.update { it.copy(resultCount = list.size, pendingScrollIndex = position) }
    }

    private fun initBook(submit: Boolean, searchWord: String?) {
        shared.book?.let { book ->
            initCacheFileNames(book)
            _state.update { it.copy(durChapterIndex = book.durChapterIndex) }
            searchWord?.let { word ->
                _state.update { it.copy(query = word) }
                if (submit) {
                    startContentSearch(word.trim())
                    clearFocusHandler?.invoke()
                }
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = scope.launch {
            val files = BookStorageProviders.get().getChapterFiles(book)
            // 列目录在 IO、写集合回主线程: cacheChapterNames 只允许主线程写
            withContext(mainDispatcher) {
                shared.cacheChapterNames.addAll(files)
            }
        }
    }

    // ===== 搜索逻辑 =====

    fun requestFocusSearch() {
        _state.update { it.copy(focusEpoch = it.focusEpoch + 1) }
    }

    fun toggleReplaceEnabled() {
        shared.replaceEnabled = !shared.replaceEnabled
        _state.update { it.copy(replaceEnabled = shared.replaceEnabled) }
    }

    fun stopSearch() {
        searchJob?.cancel()
    }

    fun startContentSearch(query: String) {
        if (query.isBlank()) return
        shared.lastQuery = query
        // searching 的置位与清位必须同一执行路径: 置位留在协程外时, 协程体没跑到 (作用域已取消)
        // 或在 initJob.join() 处被取消, 都会让进度条永久转。
        // 旧任务先 cancelAndJoin 再清列表: 它的 finally 才不会擦掉本轮的 searching,
        // 它最后一批结果也不会落进新一轮
        val previous = searchJob
        searchJob = scope.launch {
            previous?.cancelAndJoin()
            // [results] 直喂 LazyColumn 的 items，写入必须在主线程 (对照原版
            // `tvCurrentSearchInfo.post { adapter.addItems(...) }`)；在 IO 线程改它会让
            // LazyList 测量期按下标取项时列表已被改小 → IndexOutOfBoundsException
            withContext(mainDispatcher) { results.clear() }
            _state.update { it.copy(searching = true, resultCount = 0) }
            try {
                initJob?.join()
                // 已缓存章名集合在主线程拍只读快照交给 IO 循环查 (裸 HashSet 跨线程读写不安全)
                val cachedChapterNames = withContext(mainDispatcher) { shared.cacheChapterNames.toSet() }
                val allResults =
                    shared.searchAllChapters(query, cachedChapterNames) { batch, totalCount ->
                    // 本回调已由 searchAllChapters 投到主线程
                    results.addAll(batch)
                        _state.update { it.copy(resultCount = totalCount) }
                }
                if (allResults.isEmpty()) {
                    withContext(mainDispatcher) {
                        results.add(SearchResult(resultText = emptyResultText()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("全文搜索出错\n${e.message}", e)
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun setClearFocusHandler(handler: (() -> Unit)?) {
        clearFocusHandler = handler
    }

    fun clearFocus() {
        clearFocusHandler?.invoke()
    }

    fun consumePendingScrollIndex() {
        _state.update { it.copy(pendingScrollIndex = null) }
    }

    // ===== 事件分发 =====

    fun dispatch(event: SearchContentUiEvent) {
        when (event) {
            is SearchContentUiEvent.QueryChange ->
                _state.update { it.copy(query = event.text) }

            is SearchContentUiEvent.SubmitSearch ->
                startContentSearch(event.query)

            SearchContentUiEvent.ToggleReplaceEnabled ->
                toggleReplaceEnabled()

            SearchContentUiEvent.StopSearch ->
                stopSearch()

            SearchContentUiEvent.RequestFocusSearch ->
                requestFocusSearch()

            SearchContentUiEvent.ConsumePendingScrollIndex ->
                consumePendingScrollIndex()

            is SearchContentUiEvent.SetClearFocusHandler ->
                setClearFocusHandler(event.handler)

            SearchContentUiEvent.ClearFocus ->
                clearFocus()

            is SearchContentUiEvent.OpenResult -> {
                stopSearch()
                onOpenResult?.invoke(event.item, event.index)
            }
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        initJob?.cancel()
        scope.cancel()
    }
}

sealed interface SearchContentUiEvent {
    data class QueryChange(val text: String) : SearchContentUiEvent
    data class SubmitSearch(val query: String) : SearchContentUiEvent
    object ToggleReplaceEnabled : SearchContentUiEvent
    object StopSearch : SearchContentUiEvent
    data class OpenResult(val item: SearchResult, val index: Int) : SearchContentUiEvent
    object RequestFocusSearch : SearchContentUiEvent
    data class SetClearFocusHandler(val handler: (() -> Unit)?) : SearchContentUiEvent
    object ClearFocus : SearchContentUiEvent
    object ConsumePendingScrollIndex : SearchContentUiEvent
}
