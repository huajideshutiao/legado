package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.data.entities.Book
import io.legado.app.model.rss.RssHelp
import io.legado.app.ui.book.rss.RssSourcesScreen
import io.legado.app.ui.book.rss.RssSourcesScreenModel
import io.legado.app.ui.book.rss.RssSourcesUiActions
import io.legado.app.ui.book.rss.RssSourcesUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

/**
 * RSS 源管理 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [RssSourcesScreenModel], 订阅 [RssHelp.flowRssSources]
 * 推入状态, 渲染 [RssSourcesScreen]。
 *
 * RSS 源的增删走书架 Book 流程 (见 RssSourcesViewModelShared), 添加源入口待书架流程下沉后接入。
 */
@Composable
fun RssSourcesRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { RssSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    // 订阅 RSS 源数据流, 变更时推入状态 (ScreenModel 数据订阅由调用方触发)
    LaunchedEffect(Unit) {
        RssHelp.flowRssSources().collect { sources ->
            screenModel.dispatch(RssSourcesUiEvent.SourcesLoaded(sources))
        }
    }

    val actions = remember(navigator) {
        object : RssSourcesUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 添加 RSS 源走书架 Book 搜索流程 (RSS 源 = Book(type|=rss), 通过搜索发现并上架)
            override fun onAddSource() {
                navigator.push(AppRoute.Search())
            }

            // 点击源跳转文章列表 (sourceUrl = book.bookUrl, 即 feed URL)
            override fun onOpenSource(book: Book) {
                navigator.push(AppRoute.RssArticles(book.bookUrl))
            }
        }
    }

    RssSourcesScreen(state = state, actions = actions)
}
