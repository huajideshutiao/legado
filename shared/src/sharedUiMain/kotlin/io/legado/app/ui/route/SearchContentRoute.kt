package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.chineseS2T
import io.legado.app.help.book.chineseT2S
import io.legado.app.ui.book.searchContent.SearchContentScreen
import io.legado.app.ui.book.searchContent.SearchContentScreenModel
import io.legado.app.ui.book.searchContent.SearchContentUiActions
import io.legado.app.ui.book.searchContent.SearchContentUiEvent
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent

/**
 * AppRoute.SearchContent shared 路由下沉函数。
 */
@Composable
fun SearchContentRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val emptyResultText = rememberString("search_content_empty")
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        SearchContentScreenModel(
            chineseConverter = { type, text ->
                when (type) {
                    1 -> chineseT2S(text)
                    2 -> chineseS2T(text)
                    else -> text
                }
            },
            emptyResultText = { emptyResultText },
        )
    }
    // position/searchWord 来自 AppRoute.SearchContent 路由参数
    val routeArgs = entry.route as? AppRoute.SearchContent
    LaunchedEffect(Unit) {
        val searchResultList: List<SearchResult>? = IntentData.get("searchResultList")
        screenModel.init(searchResultList, routeArgs?.index ?: 0, routeArgs?.word)
    }

    // SAVE_CONTENT 事件触发 cacheChapterNames 更新
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SAVE_CONTENT).collect { event ->
            (event as? Pair<*, *>)?.let { (book, chapter) ->
                val bookTyped = book as? Book ?: return@let
                val chapterTyped = chapter as? BookChapter ?: return@let
                screenModel.onSaveContent(bookTyped, chapterTyped)
            }
        }
    }

    // 选中结果回传: postEvent 通知 ReadBook 更新 list, IntentData 暂存 list, payload 回传 query+index
    screenModel.onOpenResult = { item, index ->
        postEvent<List<SearchResult>>(EventBus.SEARCH_RESULT, screenModel.searchResultList)
        IntentData.put("searchResultList", screenModel.searchResultList)
        navigator.pop(RouteResultPayload.SearchContent(item.query, index))
    }

    val state by screenModel.state.collectAsState()

    val actions = remember(navigator, screenModel) {
        object : SearchContentUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onQueryChange(text: String) {
                screenModel.dispatch(SearchContentUiEvent.QueryChange(text))
            }

            override fun onSubmitSearch(query: String) {
                screenModel.dispatch(SearchContentUiEvent.SubmitSearch(query))
            }

            override fun onToggleReplaceEnabled() {
                screenModel.dispatch(SearchContentUiEvent.ToggleReplaceEnabled)
            }

            override fun onStopSearch() {
                screenModel.dispatch(SearchContentUiEvent.StopSearch)
            }

            override fun onOpenResult(item: SearchResult, index: Int) {
                screenModel.dispatch(SearchContentUiEvent.OpenResult(item, index))
            }

            override fun onRequestFocusSearch() {
                screenModel.dispatch(SearchContentUiEvent.RequestFocusSearch)
            }

            override fun setClearFocusHandler(handler: (() -> Unit)?) {
                screenModel.dispatch(SearchContentUiEvent.SetClearFocusHandler(handler))
            }

            override fun clearFocus() {
                screenModel.dispatch(SearchContentUiEvent.ClearFocus)
            }

            override fun onConsumePendingScrollIndex() {
                screenModel.dispatch(SearchContentUiEvent.ConsumePendingScrollIndex)
            }
        }
    }

    SearchContentScreen(state = state, actions = actions)
}
