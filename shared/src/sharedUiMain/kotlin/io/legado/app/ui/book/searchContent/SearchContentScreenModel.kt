package io.legado.app.ui.book.searchContent

import androidx.compose.runtime.mutableStateListOf
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

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

    /** 暴露搜索结果列表，供类型化路由结果完整回传。 */
    val searchResultList: MutableList<SearchResult>
        get() = shared.searchResultList

    // ===== 初始化 (对照 Activity.onActivityCreated) =====

    /**
     * @param searchResultList 路由参数携带的已有搜索结果，用于恢复列表。
     * @param position 当前选中结果索引。
     * @param searchWord 当前搜索词。
     */
    fun init(searchResultList: List<SearchResult>?, position: Int, searchWord: String?) {
        val noSearchResult = searchResultList == null
        if (noSearchResult) requestFocusSearch()
        shared.initBook {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult, searchWord)
        }
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
        shared.searchResultList.addAll(list)
        shared.searchResultCounts = list.size
        results.addAll(list)
        _state.update { it.copy(pendingScrollIndex = position) }
    }

    private fun initBook(submit: Boolean, searchWord: String?) {
        _state.update { it.copy(resultCount = shared.searchResultCounts) }
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
            shared.cacheChapterNames.addAll(BookStorageProviders.get().getChapterFiles(book))
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
        searchJob?.cancel()
        results.clear()
        shared.searchResultList.clear()
        shared.searchResultCounts = 0
        shared.lastQuery = query
        _state.update { it.copy(searching = true) }
        searchJob = scope.launch {
            initJob?.join()
            kotlin.runCatching {
                shared.searchAllChapters(query) { batch ->
                    _state.update { it.copy(resultCount = shared.searchResultCounts) }
                    results.addAll(batch)
                }
                if (shared.searchResultCounts == 0) {
                    results.add(SearchResult(resultText = emptyResultText()))
                }
            }.onFailure {
                AppLog.put("全文搜索出错\n${it.message}", it)
            }
            _state.update { it.copy(searching = false) }
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
