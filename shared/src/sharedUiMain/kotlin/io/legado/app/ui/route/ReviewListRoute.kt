package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.Review
import io.legado.app.ui.book.read.review.ReviewListScreen
import io.legado.app.ui.book.read.review.ReviewListScreenModel
import io.legado.app.ui.book.read.review.ReviewListUiActions
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.confirm_delete_review
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.no
import legado.shared.generated.resources.review_list_section_title
import legado.shared.generated.resources.review_post_hint
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 段评/书评列表页 shared 路由入口。
 *
 * 解析 route.book, 复用 [ReviewListScreenModel] + [ReviewListScreen];
 * 仅 book 级评论 (paragraphIndex = -1, chapter = null)。
 *
 * 平台专属行为对照 app 端 ReviewListDialog:
 * - 发布/回复: 弹 [ReviewPostDialogHost] (对照原版 ReviewPostActivity 底部输入面板),
 *   提交内容直连 screenModel.submit (原 REVIEW_POST 路由回传结果处理逻辑);
 *   点击单条先 setReplyTo 暂存该条并带 replyPreview (对照 onReviewClicked)
 * - 删除: [AppAlertDialog] 二次确认 (对照 alert(R.string.delete, R.string.confirm_delete_review))
 * - 长按: 复制内容 (对照 sendToClip)
 * - 头像/配图: photo overlay 看大图 (对照 PhotoDialog)
 *
 * 未对齐项: 回复详情 (app 原版 openReplies 再开一层 Dialog) —— AppRoute 无对应路由,
 * 按任务约束不新造路由, 故 onOpenReplies 暂为空实现。
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
    val totalCount by screenModel.totalCount.collectAsState()

    // 列表头 "全部评论 · N": 规则未配置时不显示 (对照 app 端 totalCount 收集)
    val count = totalCount
    val listTitleText = if (count.isNullOrBlank()) ""
    else stringResource(Res.string.review_list_section_title, count)
    val inputHint = stringResource(Res.string.review_post_hint)
    LaunchedEffect(listTitleText, inputHint) {
        screenModel.setTexts(listTitleText, inputHint)
    }

    // 发布输入弹窗状态 (对照原版 ReviewPostActivity): preview 非空 = 回复该条
    var showPostDialog by remember { mutableStateOf(false) }
    var postReplyPreview by remember { mutableStateOf<String?>(null) }

    val pendingDelete = remember { mutableStateOf<Review?>(null) }

    val actions = object : ReviewListUiActions {

        override fun onBack() {
            navigator.pop()
        }

        override fun onLoadMore() {
            screenModel.loadMore()
        }

        override fun onChangeSort(sort: Int) {
            screenModel.changeSort(sort)
        }

        // 点击单条 = 回复该条: 暂存 replyTo, 弹发布输入面板并带原文预览
        override fun onReviewClick(review: Review) {
            screenModel.setReplyTo(review)
            postReplyPreview = review.content
            showPostDialog = true
        }

        override fun onReviewLongClick(review: Review) {
            PlatformCapabilityProviders.getOrNull()?.copyToClipboard(review.content)
        }

        override fun onToggleExpand(key: String) {
            screenModel.toggleExpand(key)
        }

        override fun onVoteUp(review: Review) {
            screenModel.voteUp(review)
        }

        override fun onVoteDown(review: Review) {
            screenModel.voteDown(review)
        }

        override fun onDeleteClick(review: Review) {
            pendingDelete.value = review
        }

        // 回复详情: AppRoute 无对应路由, 按约束不新造, 暂空实现 (见本文件 KDoc)
        override fun onOpenReplies(review: Review) = Unit

        // 发书评: 清空 replyTo, 弹发布输入面板
        override fun onPostClick() {
            screenModel.setReplyTo(null)
            postReplyPreview = null
            showPostDialog = true
        }

        override fun onAvatarClick(url: String?) {
            url?.takeIf { it.isNotBlank() }?.let {
                navigator.showOverlay(AppOverlay.Dialog(key = "photo", payload = it))
            }
        }

        override fun onImageClick(url: String) {
            navigator.showOverlay(AppOverlay.Dialog(key = "photo", payload = url))
        }
    }

    ReviewListScreen(state = state, actions = actions)

    // 发布输入弹窗 (对照原版 ReviewPostActivity 底部输入面板): 提交内容直连原 REVIEW_POST
    // 路由回传结果处理逻辑 (submit → shared VM 网络提交 + 列表重载)
    if (showPostDialog) {
        ReviewPostDialogHost(
            replyPreview = postReplyPreview,
            onPosted = { content -> if (content.isNotBlank()) screenModel.submit(content) },
            onDismiss = { showPostDialog = false },
        )
    }

    // 删除二次确认 (对照 app 端 alert(delete, confirm_delete_review) + yesButton/noButton)
    pendingDelete.value?.let { review ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.confirm_delete_review),
            okButton = AlertButton(stringResource(Res.string.yes)) {
                screenModel.delete(review)
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {},
        )
    }
}
