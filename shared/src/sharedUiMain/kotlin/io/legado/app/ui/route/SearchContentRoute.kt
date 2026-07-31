package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.chineseS2T
import io.legado.app.help.book.chineseT2S
import io.legado.app.ui.book.searchContent.SearchContentScreen
import io.legado.app.ui.book.searchContent.SearchContentScreenModel
import io.legado.app.ui.book.searchContent.SearchContentUiActions
import io.legado.app.ui.book.searchContent.SearchContentUiEvent
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.FlowBus
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.search_content_empty
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.SearchContent shared 路由下沉函数。
 */
@Composable
fun SearchContentRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val emptyResultText = stringResource(Res.string.search_content_empty)
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
    LaunchedEffect(routeArgs) {
        screenModel.init(
            routeArgs?.initialResults,
            routeArgs?.index ?: 0,
            routeArgs?.word,
        )
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

    // 选中结果时将完整搜索上下文随导航结果返回，避免依赖全局临时槽。
    screenModel.onOpenResult = { item, index ->
        navigator.pop(
            RouteResultPayload.SearchContent(
                searchWord = item.query,
                searchResultIndex = index,
                searchResults = screenModel.searchResultList,
            )
        )
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
