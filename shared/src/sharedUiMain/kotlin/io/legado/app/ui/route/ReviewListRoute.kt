package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.book.read.review.ReviewListScreen
import io.legado.app.ui.book.read.review.ReviewListScreenModel
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook

/**
 * 段评/书评列表页 shared 路由入口。
 *
 * 解析 route.book, 复用 [ReviewListScreenModel] + [ReviewListScreen];
 * 仅 book 级评论 (paragraphIndex = -1, chapter = null)。
 * 发布评论入口跳转 [AppRoute.ReviewPost]; 点赞/回复/删除等平台专属行为待后续下沉。
 */
@Composable
fun ReviewListRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ReviewList
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        ReviewListScreenModel(book)
    }
    val state by screenModel.state.collectAsState()

    ReviewListScreen(
        state = state,
        onBack = { navigator.pop() },
        onPostClick = { navigator.push(AppRoute.ReviewPost(route.book)) },
        onLoadMore = screenModel::loadMore,
    )
}
