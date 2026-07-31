package io.legado.app.ui.book.read.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Review
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bottom_line
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.review
import legado.shared.generated.resources.review_post_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 书评列表页 shared Screen (KMP 共享)。
 *
 * 对照 app 端 ReviewListDialog (BottomSheetDialogFragment), 简化为 Screen 形态:
 * - 顶部标题栏 (AppTitleBar, 返回按钮 + "评论" 标题)
 * - LazyColumn 评论项 (Card + Text 基本布局)
 * - 翻到底触发 [onLoadMore] 翻页 (snapshotFlow, 对照原 OnScrollListener)
 * - 底部发布评论入口 (点击触发 [onPostClick])
 *
 * 简化项 (待后续下沉): 点赞/点踩/回复/删除/排序/展开折叠/头像与配图渲染槽。
 *
 * @param state 列表状态
 * @param onBack 返回
 * @param onPostClick 点击底部发布入口
 * @param onLoadMore 翻到底加载更多
 */
@Composable
fun ReviewListScreen(
    state: ReviewListUiState,
    onBack: () -> Unit,
    onPostClick: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    // rememberUpdatedState: LaunchedEffect 内读最新值, 避免捕获过期闭包
    val reviewsRef = rememberUpdatedState(state.reviews)
    val footerHasMoreRef = rememberUpdatedState(state.footerHasMore)
    val footerLoadingRef = rememberUpdatedState(state.footerLoading)
    val onLoadMoreRef = rememberUpdatedState(onLoadMore)

    // 翻到底触发 loadMore, 对照原 OnScrollListener
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val atEnd = info.totalItemsCount > 0 &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
            atEnd to info.totalItemsCount
        }.collect { (atEnd, _) ->
            if (atEnd && reviewsRef.value.isNotEmpty() &&
                footerHasMoreRef.value && !footerLoadingRef.value
            ) {
                onLoadMoreRef.value()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.review),
            onBack = onBack,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.reviews) { review ->
                    ReviewCard(review)
                }
                item { ReviewListFooter(state) }
            }
            // 首屏 loading / 空态 / 错误 覆盖层
            when {
                state.loading && state.reviews.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppTheme.colors.accent)
                }

                state.error != null && state.reviews.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error,
                        color = rememberColor("primaryText"),
                        fontSize = 14.sp,
                    )
                }

                !state.loading && state.reviews.isEmpty() && state.error == null -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(Res.string.empty),
                        color = rememberColor("primaryText"),
                        fontSize = 14.sp,
                    )
                }
            }
        }
        PostEntryBar(onPostClick)
    }
}

/** 单条评论: Card + Text 基本布局 (昵称/时间/正文/点赞数) */
@Composable
private fun ReviewCard(review: Review) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        backgroundColor = rememberColor("background_card"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = review.name.orEmpty(),
                    color = rememberColor("primaryText"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = review.postTime.orEmpty(),
                    color = rememberColor("secondaryText"),
                    fontSize = 12.sp,
                )
            }
            Text(
                text = review.content,
                color = rememberColor("primaryText"),
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            if (review.voteUpCount > 0) {
                Text(
                    text = "${review.voteUpCount}",
                    color = rememberColor("secondaryText"),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 列表底部: 翻页 loading / 无更多 */
@Composable
private fun ReviewListFooter(state: ReviewListUiState) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.footerLoading -> CircularProgressIndicator(
                color = AppTheme.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(8.dp)
                    .size(36.dp),
            )

            !state.footerHasMore && state.reviews.isNotEmpty() -> Text(
                text = stringResource(Res.string.bottom_line),
                color = rememberColor("secondaryText"),
                fontSize = 14.sp,
            )
        }
    }
}

/** 底部发布评论入口 (点击触发回调, 输入面板由调用方实现) */
@Composable
private fun PostEntryBar(onPostClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 40.dp)
            .background(rememberColor("background_card"))
            .clickable { onPostClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(Res.string.review_post_hint),
            color = rememberColor("secondaryText"),
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}
