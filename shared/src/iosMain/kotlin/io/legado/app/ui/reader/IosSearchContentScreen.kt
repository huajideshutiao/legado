package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.ui.book.searchContent.SearchContentScreen
import io.legado.app.ui.book.searchContent.SearchContentUiActions
import io.legado.app.ui.book.searchContent.SearchContentUiState
import io.legado.app.ui.book.searchContent.SearchContentViewModelShared
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.formatNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端书内全文搜索 Screen (包装 shared/sharedUiMain 的 [SearchContentScreen])。
 *
 * 对照 desktop `desktop/.../search/SearchContentScreen.kt`, iOS 端作为全屏覆盖层
 * 由 [IosReaderScreen] 的 clickSearch 触发显示 (不走路由切换, 复用外层 Provider)。
 *
 * 持有 [SearchContentUiState] + 构造 [SearchContentUiActions] 实现, 位置传参调用
 * shared/sharedUiMain 的 [SearchContentScreen] (含 AppTitleBar + 搜索框 + 结果列表)。
 *
 * @param book 当前阅读书籍 (写入 IntentData.book 供 [SearchContentViewModelShared.initBook] 读取)
 * @param onBack 关闭搜索覆盖层回调
 * @param onOpenResult 搜索结果点击回调 (跳转章节, 由 [IosReaderScreen] 注入 viewModel.loadChapter)
 */
@Composable
fun IosSearchContentScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenResult: (SearchResult) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val shared = remember(scope) {
        SearchContentViewModelShared(
            scope = scope,
            chineseConverter = { type, text ->
                when (type) {
                    1 -> ChineseUtils.t2s(text)
                    2 -> ChineseUtils.s2t(text)
                    else -> text
                }
            },
        )
    }
    // 写入 IntentData.book 供 shared.initBook 读取 (读一次即清除, 与 desktop 一致)
    LaunchedEffect(Unit) {
        IntentData.book = book
        shared.initBook { }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var resultCount by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val durChapterIndex = shared.book?.durChapterIndex ?: -1
    var replaceEnabled by remember { mutableStateOf(false) }
    var focusEpoch by remember { mutableStateOf(0) }
    var pendingScrollIndex by remember { mutableStateOf<Int?>(null) }
    var clearFocusHandler by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 文案模板 (onSubmitSearch lambda 内 onFailure 非 @Composable, 预先 remember)
    val searchInBookFailedTemplate = rememberString("search_in_book_failed_log")

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

    val actions = IosSearchContentActions(
        onBack = onBack,
        onQueryChange = { text -> query = text },
        onSubmitSearch = { searchQuery ->
            if (searching) return@IosSearchContentActions
            searching = true
            shared.searchResultList.clear()
            shared.searchResultCounts = 0
            results = emptyList()
            resultCount = 0
            searchJob = scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        shared.searchAllChapters(searchQuery) { _ ->
                            results = shared.searchResultList.toList()
                            resultCount = shared.searchResultCounts
                        }
                    }
                }.onFailure {
                    AppLog.put(searchInBookFailedTemplate.formatNative(it.localizedMessage), it)
                }
                searching = false
                searchJob = null
                results = shared.searchResultList.toList()
                resultCount = shared.searchResultCounts
            }
        },
        onToggleReplaceEnabled = {
            replaceEnabled = !replaceEnabled
            shared.replaceEnabled = replaceEnabled
        },
        onStopSearch = {
            searchJob?.cancel()
            searchJob = null
            searching = false
        },
        onOpenResult = { result, _ -> onOpenResult(result) },
        onRequestFocusSearch = { focusEpoch++ },
        setClearFocusHandler = { handler -> clearFocusHandler = handler },
        clearFocus = { clearFocusHandler?.invoke() },
        onConsumePendingScrollIndex = { pendingScrollIndex = null },
    )

    SearchContentScreen(state = state, actions = actions)
}

private class IosSearchContentActions(
    private val onBack: () -> Unit,
    private val onQueryChange: (String) -> Unit,
    private val onSubmitSearch: (String) -> Unit,
    private val onToggleReplaceEnabled: () -> Unit,
    private val onStopSearch: () -> Unit,
    private val onOpenResult: (SearchResult, Int) -> Unit,
    private val onRequestFocusSearch: () -> Unit,
    private val setClearFocusHandler: ((() -> Unit)?) -> Unit,
    private val clearFocus: () -> Unit,
    private val onConsumePendingScrollIndex: () -> Unit,
) : SearchContentUiActions {
    override fun onBack() = onBack.invoke()
    override fun onQueryChange(text: String) = onQueryChange.invoke(text)
    override fun onSubmitSearch(query: String) = onSubmitSearch.invoke(query)
    override fun onToggleReplaceEnabled() = onToggleReplaceEnabled.invoke()
    override fun onStopSearch() = onStopSearch.invoke()
    override fun onOpenResult(item: SearchResult, index: Int) = onOpenResult.invoke(item, index)
    override fun onRequestFocusSearch() = onRequestFocusSearch.invoke()
    override fun setClearFocusHandler(handler: ((() -> Unit)?)?) = setClearFocusHandler.invoke(handler)
    override fun clearFocus() = clearFocus.invoke()
    override fun onConsumePendingScrollIndex() = onConsumePendingScrollIndex.invoke()
}
