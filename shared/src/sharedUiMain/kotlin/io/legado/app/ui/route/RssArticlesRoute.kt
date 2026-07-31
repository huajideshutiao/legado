package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.book.rss.RssArticlesScreen
import io.legado.app.ui.book.rss.RssArticlesScreenModel
import io.legado.app.ui.book.rss.RssArticlesUiActions
import io.legado.app.ui.book.rss.RssArticlesUiEvent
import io.legado.app.ui.rss.ArticlesUiState
import io.legado.app.ui.rss.RssArticlesViewModelShared
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS 文章列表 shared 路由入口。
 *
 * sourceUrl 定位 RSS Book (bookUrl); 复用 commonMain [RssArticlesViewModelShared]
 * 的缓存+联网+回退加载逻辑, 桥接其三态到 [RssArticlesScreenModel], 渲染 [RssArticlesScreen]。
 */
@Composable
fun RssArticlesRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.RssArticles
    val sourceUrl = route.sourceUrl

    val screenModel = screenModelStore.getOrCreateTyped(entry) { RssArticlesScreenModel() }

    // 复用 commonMain 加载逻辑 (缓存先显 → 联网刷新 → 失败回退)
    val scope = rememberCoroutineScope()
    val shared = remember { RssArticlesViewModelShared(scope) }
    var book by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(sourceUrl) {
        val loaded = withContext(IoDispatcher) {
            AppDbProviders.get().bookDao.getBook(sourceUrl)
        }
        if (loaded == null) {
            screenModel.dispatch(RssArticlesUiEvent.Error("未找到 RSS 源"))
            return@LaunchedEffect
        }
        book = loaded
        // 桥接 shared 三态 → ScreenModel 事件
        launch {
            shared.state.collect { s ->
                when (s) {
                    ArticlesUiState.Loading ->
                        screenModel.dispatch(RssArticlesUiEvent.Load)

                    is ArticlesUiState.Data ->
                        screenModel.dispatch(RssArticlesUiEvent.ArticlesLoaded(s.articles))

                    is ArticlesUiState.Error ->
                        screenModel.dispatch(RssArticlesUiEvent.Error(s.message))
                }
            }
        }
        shared.loadArticles(loaded)
    }

    val state by screenModel.state.collectAsState()
    val actions = remember(navigator, shared, book) {
        object : RssArticlesUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onRefresh() {
                book?.let { shared.loadArticles(it) }
            }

            override fun onArticleClick(article: BookChapter) {
                // 点击文章时更新 durChapterIndex, ReadRssRoute 据此加载正文
                book?.let { b ->
                    b.durChapterIndex = article.index
                    b.durChapterTitle = article.title
                    navigator.push(AppRoute.ReadRss(b.toRouteRef()))
                }
            }
        }
    }

    RssArticlesScreen(title = book?.name.orEmpty(), state = state, actions = actions)
}
