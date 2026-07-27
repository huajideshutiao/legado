package io.legado.app.ui.book.searchContent

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.searchResultList
import io.legado.app.help.book.isLocal
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 书内全文搜索(纯 Compose)。intent 契约不变:
 * 入 IntentData.book/searchResultList + searchWord/searchResultIndex extras,
 * 出 setResult 的 key/index extras + IntentData searchResult$key/searchResultList$key。
 *
 * 薄壳模式: 实现 [SearchContentUiActions] 接口供 shared 端 [SearchContentScreen] 回调,
 * 已有同名方法直接 `override` (如 [onQueryChange]), 其余方法以
 * `override fun onXxx() = xxx()` 桥接现有方法, 不改动 Activity 内部其它调用点
 * (参考 BookInfoActivity 薄壳模式)。状态字段由 Activity 托管, [Content] 内打包为
 * [SearchContentUiState] 传入 shared 端 [SearchContentScreen]。
 */
class SearchContentActivity : BaseComposeActivity(), SearchContentUiActions {

    val viewModel by viewModels<SearchContentViewModel>()

    var query by mutableStateOf("")
        private set
    val results = mutableStateListOf<SearchResult>()
    var resultCount by mutableIntStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    var durChapterIndex by mutableIntStateOf(0)
        private set
    var replaceEnabled by mutableStateOf(false)
        private set

    // 搜索框请求焦点信号(无既有结果进入 / 点击底栏计数)
    var focusEpoch by mutableIntStateOf(0)
        private set

    // 恢复上次结果时的初始定位, 消费后置空
    var pendingScrollIndex by mutableStateOf<Int?>(null)

    private var clearFocusHandler: (() -> Unit)? = null

    private var searchJob: Job? = null
    private var initJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        replaceEnabled = viewModel.replaceEnabled
        val searchResultList = IntentData.searchResultList
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        if (noSearchResult) focusEpoch++
        viewModel.initBook {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    @Composable
    override fun Content() {
        val state = SearchContentUiState(
            query = query,
            results = results,
            resultCount = resultCount,
            searching = searching,
            durChapterIndex = durChapterIndex,
            replaceEnabled = replaceEnabled,
            focusEpoch = focusEpoch,
            pendingScrollIndex = pendingScrollIndex,
        )
        SearchContentScreen(state = state, actions = this)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                }
            }
        }
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        results.addAll(list)
        pendingScrollIndex = position
    }

    private fun initBook(submit: Boolean) {
        resultCount = viewModel.searchResultCounts
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                query = searchWord
                if (submit) {
                    startContentSearch(searchWord.trim())
                    clearFocusHandler?.invoke()
                }
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch(IO) {
            viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
        }
    }

    override fun onQueryChange(text: String) {
        query = text
    }

    fun requestFocusSearch() {
        focusEpoch++
    }

    fun toggleReplaceEnabled() {
        viewModel.replaceEnabled = !viewModel.replaceEnabled
        replaceEnabled = viewModel.replaceEnabled
    }

    fun stopSearch() {
        searchJob?.cancel()
    }

    fun startContentSearch(query: String) {
        // 按章节搜索内容
        if (query.isBlank()) return
        searchJob?.cancel()
        results.clear()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        viewModel.lastQuery = query
        searching = true
        searchJob = lifecycleScope.launch(IO) {
            initJob?.join()
            kotlin.runCatching {
                appDb.bookChapterDao.getChapterList(viewModel.bookUrl).forEach { bookChapter ->
                    ensureActive()
                    val searchResults = if (isLocalBook
                        || viewModel.cacheChapterNames.contains(bookChapter.getFileName())
                    ) {
                        viewModel.searchChapter(query, bookChapter)
                    } else {
                        return@forEach
                    }
                    ensureActive()
                    if (searchResults.isNotEmpty()) {
                        viewModel.searchResultList.addAll(searchResults)
                        // 快照写线程安全, 对齐原 post 到主线程的批量追加
                        resultCount = viewModel.searchResultCounts
                        results.addAll(searchResults)
                    }
                }
                if (viewModel.searchResultCounts == 0) {
                    results.add(SearchResult(resultText = getString(R.string.search_content_empty)))
                }
            }.onFailure {
                AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
            }
            searching = false
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    fun openSearchResult(searchResult: SearchResult, index: Int) {
        searchJob?.cancel()
        postEvent(EventBus.SEARCH_RESULT, viewModel.searchResultList as List<SearchResult>)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }

    // ---- SearchContentUiActions 实现 ----
    // onQueryChange 已有同名方法, 直接 override (见上)
    // 其余方法以 override fun onXxx() = xxx() 桥接现有方法, 不改动 Activity 内部调用点

    override fun onBack() = finish()

    override fun onSubmitSearch(query: String) = startContentSearch(query)

    override fun onToggleReplaceEnabled() = toggleReplaceEnabled()

    override fun onStopSearch() = stopSearch()

    override fun onOpenResult(item: SearchResult, index: Int) = openSearchResult(item, index)

    override fun onRequestFocusSearch() = requestFocusSearch()

    override fun setClearFocusHandler(handler: (() -> Unit)?) {
        clearFocusHandler = handler
    }

    override fun clearFocus() {
        clearFocusHandler?.invoke()
    }

    override fun onConsumePendingScrollIndex() {
        pendingScrollIndex = null
    }

}
