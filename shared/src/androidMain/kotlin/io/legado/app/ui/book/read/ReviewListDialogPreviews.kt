package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewReviews

/**
 * [ReviewListDialog] 的 @Preview。
 *
 * avatarSlot 用圆形占位, imageSlot 用圆角灰块; 假数据取 PreviewData.previewReviews。
 */

private val previewAvatarSlot: @Composable (String?, Modifier) -> Unit = { name, modifier ->
    Box(
        modifier.background(Color(0xFF165DFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text((name ?: "?").take(1), color = Color.White)
    }
}

private val previewImageSlot: @Composable (String, Modifier) -> Unit = { _, modifier ->
    Box(modifier.background(Color(0xFFCCCCCC), DesignTokens.shapeSm))
}

@Preview
@Composable
fun ReviewListDialogPreview() = LegadoThemePreview {
    ReviewListDialog(
        title = "第十二章 黑暗森林",
        parentReview = null,
        listTitleText = "全部评论 · 128",
        repliesTitleText = "全部回复",
        inputHint = "说点什么...",
        reviews = previewReviews,
        sortState = 0,
        footerLoading = false,
        footerHasMore = true,
        expandedKeys = emptySet(),
        votedIds = setOf("r1"),
        votedDownIds = setOf("r3"),
        onDismiss = {},
        onLoadMore = {},
        onChangeSort = {},
        onReviewClick = {},
        onReviewLongClick = {},
        onToggleExpand = {},
        onVoteUp = {},
        onVoteDown = {},
        onDeleteClick = {},
        onOpenReplies = {},
        onPostClick = {},
        onAvatarClick = {},
        onImageClick = {},
        avatarSlot = previewAvatarSlot,
        imageSlot = previewImageSlot,
    )
}

@Preview
@Composable
fun ReviewListDialogRepliesPreview() = LegadoThemePreview {
    ReviewListDialog(
        title = "第十二章 黑暗森林",
        parentReview = previewReviews.first(),
        listTitleText = "全部评论 · 128",
        repliesTitleText = "全部回复 · 42",
        inputHint = "回复 叶文洁...",
        reviews = previewReviews.drop(1),
        sortState = 1,
        footerLoading = false,
        footerHasMore = false,
        expandedKeys = setOf("r2"),
        votedIds = emptySet(),
        votedDownIds = emptySet(),
        onDismiss = {},
        onLoadMore = {},
        onChangeSort = {},
        onReviewClick = {},
        onReviewLongClick = {},
        onToggleExpand = {},
        onVoteUp = {},
        onVoteDown = {},
        onDeleteClick = {},
        onOpenReplies = {},
        onPostClick = {},
        onAvatarClick = {},
        onImageClick = {},
        avatarSlot = previewAvatarSlot,
        imageSlot = previewImageSlot,
    )
}

@Preview
@Composable
fun ReviewListDialogLoadingPreview() = LegadoThemePreview {
    ReviewListDialog(
        title = "第十二章 黑暗森林",
        parentReview = null,
        listTitleText = "全部评论",
        repliesTitleText = "全部回复",
        inputHint = "说点什么...",
        reviews = emptyList(),
        sortState = 0,
        footerLoading = true,
        footerHasMore = true,
        expandedKeys = emptySet(),
        votedIds = emptySet(),
        votedDownIds = emptySet(),
        onDismiss = {},
        onLoadMore = {},
        onChangeSort = {},
        onReviewClick = {},
        onReviewLongClick = {},
        onToggleExpand = {},
        onVoteUp = {},
        onVoteDown = {},
        onDeleteClick = {},
        onOpenReplies = {},
        onPostClick = {},
        onAvatarClick = {},
        onImageClick = {},
        avatarSlot = previewAvatarSlot,
        imageSlot = previewImageSlot,
    )
}

@Preview
@Composable
fun ReviewListDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ReviewListDialog(
        title = "第十二章 黑暗森林",
        parentReview = null,
        listTitleText = "全部评论 · 128",
        repliesTitleText = "全部回复",
        inputHint = "说点什么...",
        reviews = previewReviews,
        sortState = 0,
        footerLoading = false,
        footerHasMore = true,
        expandedKeys = emptySet(),
        votedIds = setOf("r1"),
        votedDownIds = emptySet(),
        onDismiss = {},
        onLoadMore = {},
        onChangeSort = {},
        onReviewClick = {},
        onReviewLongClick = {},
        onToggleExpand = {},
        onVoteUp = {},
        onVoteDown = {},
        onDeleteClick = {},
        onOpenReplies = {},
        onPostClick = {},
        onAvatarClick = {},
        onImageClick = {},
        avatarSlot = previewAvatarSlot,
        imageSlot = previewImageSlot,
    )
}
