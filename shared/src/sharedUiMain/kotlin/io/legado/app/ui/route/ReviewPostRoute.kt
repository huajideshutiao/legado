package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.book.read.review.ReviewPostScreen
import io.legado.app.ui.book.read.review.ReviewPostScreenModel
import io.legado.app.ui.book.read.review.ReviewPostUiActions
import io.legado.app.ui.book.read.review.ReviewPostUiEvent
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook

/**
 * 发表段评/书评页 shared 路由入口。
 *
 * 与 [ReviewListRoute] (评论列表查看) 不同: 本路由用于发布评论。
 * 通过 [ScreenModelStore] 复用 [ReviewPostScreenModel], 渲染 [ReviewPostScreen]。
 *
 * 对照 app 端 [io.legado.app.ui.book.read.ReviewPostActivity]:
 * - onCreate 从 intent EXTRA_REPLY_PREVIEW 构造 hint (回复评论时 "回复 xxx…", 否则 review_post_hint) →
 *   Route 用 [ReviewPostUiEvent.ShowHint] 注入 ScreenModel.state.hint
 * - submit(text) setResult(RESULT_CONTENT) + finish → onSubmit trim + navigator.pop(payload = RouteResultPayload.ReviewPost(content))
 * - IME 跟随/退场动画属 Activity 专属 (BottomSheet 模拟), shared Screen 用全屏 DialogTitleBar 布局
 */
@Composable
fun ReviewPostRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ReviewPost
    // BookRef -> Book (与 BookInfoRoute 一致: 路由层只读, 不回写 route.book)
    @Suppress("UNUSED_VARIABLE")
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { ReviewPostScreenModel() }
    val state by screenModel.state.collectAsState()

    // 对照 Activity onCreate hint 构造: replyPreview 非空 → "回复 xxx…", 否则 review_post_hint
    val replyPreview = route.replyPreview
    val hint = if (!replyPreview.isNullOrBlank()) {
        // take(15) + 省略号, 与 app 端 ReviewPostActivity 一致
        val trimmed = replyPreview.take(15).let {
            if (replyPreview.length > 15) "$it…" else it
        }
        rememberString("reply_review_to", trimmed)
    } else {
        rememberString("review_post_hint")
    }
    LaunchedEffect(hint) {
        screenModel.dispatch(ReviewPostUiEvent.ShowHint(hint))
    }

    val actions = object : ReviewPostUiActions {
        override fun onBack() {
            navigator.pop()
        }

        override fun onContentChange(content: String) {
            screenModel.dispatch(ReviewPostUiEvent.ContentChange(content))
        }

        override fun onRatingChange(rating: Float) {
            screenModel.dispatch(ReviewPostUiEvent.RatingChange(rating))
        }

        // 提交: trim 后回传 content, 由上层 (ReviewListDialog/ReviewViewModel) 处理网络提交
        // 对照 Activity submit: setResult(RESULT_OK, Intent().putExtra(RESULT_CONTENT, trimmed)) + finish
        override fun onSubmit() {
            val trimmed = state.content.trim()
            if (trimmed.isBlank()) return
            screenModel.dispatch(ReviewPostUiEvent.SubmitStart)
            navigator.pop(payload = RouteResultPayload.ReviewPost(content = trimmed))
        }
    }

    ReviewPostScreen(state = state, actions = actions)
}
