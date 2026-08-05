package io.legado.app.ui.book.read

// I18N KEYS (新增/复用, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - review_post_hint / reply_review / review_replies_detail_title / review /
//   review_replies_section_title / review_list_section_title / review_sort_hot /
//   review_sort_latest / review_expand / review_collapse / confirm_delete_review /
//   vote_up / vote_down / review_replies_count / cancel / delete / menu / bottom_line
//
// PAINTER KEYS (新增 SVG):
// - ic_review_close / ic_review_thumb_up / ic_review_thumb_up_filled /
//   ic_review_thumb_down / ic_review_thumb_down_filled / ic_arrow_drop_down /
//   ic_more_vert
//
// COLOR KEYS (新增/复用):
// - background / primaryText / secondaryText / divider / btn_bg / background_card / review_voted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Review
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bottom_line
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.ic_arrow_drop_down
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_review_close
import legado.shared.generated.resources.menu
import legado.shared.generated.resources.review_replies_count
import legado.shared.generated.resources.review_sort_hot
import legado.shared.generated.resources.review_sort_latest
import legado.shared.generated.resources.vote_down
import legado.shared.generated.resources.vote_up
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewReviews

/**
 * 评论列表对话框内容 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 ReviewListDialog (BottomSheetDialogFragment), 去掉对 Android Fragment /
 * ViewModel / LiveData / Glide / IntentData / alert DSL 的依赖, 改纯 @Composable + 回调:
 * 调用方持有全部状态传入; 交互经回调上抛; 图片渲染经 [avatarSlot]/[imageSlot] 注入
 * (app 端 Glide); 删除确认 / 发书评 / 查看大图由调用方实现平台专属行为。
 *
 * 业务对齐 (对照 app 端原版 ReviewListContent): 顶部关闭按钮 + 居中标题; LazyColumn 列表,
 * 段评模式顶部"全部评论·N"+ 排序, 回复模式顶部 parentReview 原文; 单条评论含头像/昵称/
 * 正文 6 行折叠/点赞/点踩/回复入口; footer loading/noMore; 翻底触发 onLoadMore。
 *
 * 与 app 端差异: BottomSheet 滚动交还的 nestedScrollInteropConnection 经 [lazyListModifier]
 * 由调用方注入 (Android 传 rememberNestedScrollInteropConnection(), 桌面传 Modifier)。
 *
 * @param title 顶部标题文本
 * @param parentReview 回复模式的楼主原评论; null = 段评/章节评论模式
 * @param listTitleText 段评模式 "全部评论·N" 文本 (规则未配置时为空)
 * @param repliesTitleText 回复模式 "全部回复·N" 文本
 * @param inputHint 底部输入栏提示文本
 * @param reviews 当前评论列表
 * @param sortState 当前排序 (0=最热, 1=最新)
 * @param footerLoading footer 是否显示 loading 转圈
 * @param footerHasMore 是否还有更多 (false 时 footer 显示 bottom_line)
 * @param expandedKeys 已展开全文的评论 id 集合
 * @param votedIds 已点赞的评论 id 集合
 * @param votedDownIds 已点踩的评论 id 集合
 * @param onDismiss 关闭对话框
 * @param onLoadMore 翻到底加载更多
 * @param onChangeSort 切换排序 (参数 0=最热, 1=最新)
 * @param onReviewClick 点击单条评论 (回复)
 * @param onReviewLongClick 长按单条评论 (复制内容)
 * @param onToggleExpand 切换展开/折叠 (参数为 expandKey)
 * @param onVoteUp 点赞
 * @param onVoteDown 点踩
 * @param onDeleteClick 删除 (调用方自行弹确认对话框)
 * @param onOpenReplies 打开回复详情
 * @param onPostClick 点击底部输入栏 (发书评/回复)
 * @param onAvatarClick 点击头像 (查看大图)
 * @param onImageClick 点击评论配图 (查看大图)
 * @param avatarSlot 头像渲染槽 (url, modifier) -> Unit
 * @param imageSlot 配图渲染槽 (url, modifier) -> Unit
 * @param lazyListModifier LazyColumn 的额外 modifier (Android 端注入 nestedScroll)
 * @param modifier 整体容器的 modifier
 */
@Composable
fun ReviewListDialog(
    title: String,
    parentReview: Review?,
    listTitleText: String,
    repliesTitleText: String,
    inputHint: String,
    reviews: List<Review>,
    sortState: Int,
    footerLoading: Boolean,
    footerHasMore: Boolean,
    expandedKeys: Set<String>,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onChangeSort: (Int) -> Unit,
    onReviewClick: (Review) -> Unit,
    onReviewLongClick: (Review) -> Unit,
    onToggleExpand: (String) -> Unit,
    onVoteUp: (Review) -> Unit,
    onVoteDown: (Review) -> Unit,
    onDeleteClick: (Review) -> Unit,
    onOpenReplies: (Review) -> Unit,
    onPostClick: () -> Unit,
    onAvatarClick: (String?) -> Unit,
    onImageClick: (String) -> Unit,
    avatarSlot: @Composable (String?, Modifier) -> Unit,
    imageSlot: @Composable (String, Modifier) -> Unit,
    lazyListModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    ReviewListLoadMoreEffect(
        listState = listState,
        reviews = reviews,
        footerHasMore = footerHasMore,
        footerLoading = footerLoading,
        onLoadMore = onLoadMore,
    )

    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(rememberColor("background"))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(DesignTokens.viewHeightXl)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_review_close),
                    contentDescription = stringResource(Res.string.cancel),
                    tint = rememberColor("primaryText"),
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title,
                color = rememberColor("primaryText"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ReviewListBody(
            listState = listState,
            parentReview = parentReview,
            listTitleText = listTitleText,
            repliesTitleText = repliesTitleText,
            reviews = reviews,
            sortState = sortState,
            footerLoading = footerLoading,
            footerHasMore = footerHasMore,
            expandedKeys = expandedKeys,
            votedIds = votedIds,
            votedDownIds = votedDownIds,
            onLoadMore = onLoadMore,
            onChangeSort = onChangeSort,
            onReviewClick = onReviewClick,
            onReviewLongClick = onReviewLongClick,
            onToggleExpand = onToggleExpand,
            onVoteUp = onVoteUp,
            onVoteDown = onVoteDown,
            onDeleteClick = onDeleteClick,
            onOpenReplies = onOpenReplies,
            onAvatarClick = onAvatarClick,
            onImageClick = onImageClick,
            avatarSlot = avatarSlot,
            imageSlot = imageSlot,
            modifier = lazyListModifier
                .weight(1f)
                .fillMaxWidth(),
        )
        InputBar(inputHint, onPostClick)
    }
}

/**
 * 评论列表主体 (header + items + footer), Dialog 与整页 Screen 共用。
 *
 * 外壳差异 (BottomSheet 圆角+关闭钮 / 整页 AppTitleBar+返回) 由调用方各自提供。
 * [listState] 由调用方持有以便自行挂翻页监听 (见 [ReviewListDialog] 的 snapshotFlow)。
 */
@Composable
internal fun ReviewListBody(
    listState: LazyListState,
    parentReview: Review?,
    listTitleText: String,
    repliesTitleText: String,
    reviews: List<Review>,
    sortState: Int,
    footerLoading: Boolean,
    footerHasMore: Boolean,
    expandedKeys: Set<String>,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    onLoadMore: () -> Unit,
    onChangeSort: (Int) -> Unit,
    onReviewClick: (Review) -> Unit,
    onReviewLongClick: (Review) -> Unit,
    onToggleExpand: (String) -> Unit,
    onVoteUp: (Review) -> Unit,
    onVoteDown: (Review) -> Unit,
    onDeleteClick: (Review) -> Unit,
    onOpenReplies: (Review) -> Unit,
    onAvatarClick: (String?) -> Unit,
    onImageClick: (String) -> Unit,
    avatarSlot: @Composable (String?, Modifier) -> Unit,
    imageSlot: @Composable (String, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        if (parentReview != null) {
            // 回复模式: 顶部楼主原评论 + "全部回复·N" 分隔条
            item {
                ReviewItem(
                    parentReview,
                    isParent = true,
                    expandedKeys = expandedKeys,
                    votedIds = votedIds,
                    votedDownIds = votedDownIds,
                    onReviewClick = onReviewClick,
                    onReviewLongClick = onReviewLongClick,
                    onToggleExpand = onToggleExpand,
                    onVoteUp = onVoteUp,
                    onVoteDown = onVoteDown,
                    onDeleteClick = onDeleteClick,
                    onOpenReplies = onOpenReplies,
                    onAvatarClick = onAvatarClick,
                    onImageClick = onImageClick,
                    avatarSlot = avatarSlot,
                    imageSlot = imageSlot,
                )
            }
            item { RepliesHeader(repliesTitleText) }
        } else {
            item { ListHeader(listTitleText, sortState, onChangeSort) }
        }
        items(reviews.size) { index ->
            ReviewItem(
                reviews[index],
                isParent = false,
                expandedKeys = expandedKeys,
                votedIds = votedIds,
                votedDownIds = votedDownIds,
                onReviewClick = onReviewClick,
                onReviewLongClick = onReviewLongClick,
                onToggleExpand = onToggleExpand,
                onVoteUp = onVoteUp,
                onVoteDown = onVoteDown,
                onDeleteClick = onDeleteClick,
                onOpenReplies = onOpenReplies,
                onAvatarClick = onAvatarClick,
                onImageClick = onImageClick,
                avatarSlot = avatarSlot,
                imageSlot = imageSlot,
            )
        }
        item { LoadMoreFooter(footerLoading, footerHasMore) }
    }
}

/**
 * 翻到底触发 [onLoadMore] (对照 app 原版 OnScrollListener), Dialog 与 Screen 共用。
 */
@Composable
internal fun ReviewListLoadMoreEffect(
    listState: LazyListState,
    reviews: List<Review>,
    footerHasMore: Boolean,
    footerLoading: Boolean,
    onLoadMore: () -> Unit,
) {
    // rememberUpdatedState: LaunchedEffect 内读最新参数值, 避免捕获过期闭包
    val reviewsRef = rememberUpdatedState(reviews)
    val footerHasMoreRef = rememberUpdatedState(footerHasMore)
    val footerLoadingRef = rememberUpdatedState(footerLoading)
    val onLoadMoreRef = rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            // 带 totalItemsCount: 追加一页后 footer 仍在视口时也要重新判定
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
}

/** 段评模式头部: "全部评论·N" + 排序选择 */
@Composable
internal fun ListHeader(
    listTitleText: String,
    sortState: Int,
    onChangeSort: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listTitleText,
            color = rememberColor("primaryText"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box {
            var sortMenuOpen by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .clickable { sortMenuOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = rememberString(
                        if (sortState == 1) "review_sort_latest" else "review_sort_hot"
                    ),
                    color = rememberColor("secondaryText"),
                    fontSize = 13.sp,
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    // 跟随夜间主题: 与相邻排序文本同色
                    tint = rememberColor("secondaryText"),
                )
            }
            AppDropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                DropdownMenuItem(
                    onClick = { sortMenuOpen = false; onChangeSort(0) },
                ) {
                    Text(
                        stringResource(Res.string.review_sort_hot),
                        color = AppTheme.colors.primaryText,
                    )
                }
                DropdownMenuItem(
                    onClick = { sortMenuOpen = false; onChangeSort(1) },
                ) {
                    Text(
                        stringResource(Res.string.review_sort_latest),
                        color = AppTheme.colors.primaryText,
                    )
                }
            }
        }
    }
}

/** 回复模式分隔条: 分割块 + "全部回复·N" */
@Composable
private fun RepliesHeader(repliesTitleText: String) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(rememberColor("divider"))
        )
        Text(
            text = repliesTitleText,
            color = rememberColor("primaryText"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

/** 单条评论; isParent = 回复详情页楼主原评论 (展开全文、无菜单/展开钮/回复入口) */
@Composable
internal fun ReviewItem(
    item: Review,
    isParent: Boolean,
    expandedKeys: Set<String>,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    onReviewClick: (Review) -> Unit,
    onReviewLongClick: (Review) -> Unit,
    onToggleExpand: (String) -> Unit,
    onVoteUp: (Review) -> Unit,
    onVoteDown: (Review) -> Unit,
    onDeleteClick: (Review) -> Unit,
    onOpenReplies: (Review) -> Unit,
    onAvatarClick: (String?) -> Unit,
    onImageClick: (String) -> Unit,
    avatarSlot: @Composable (String?, Modifier) -> Unit,
    imageSlot: @Composable (String, Modifier) -> Unit,
) {
    val expandKey = item.id ?: "#${item.content.hashCode()}"
    val isExpanded = isParent || expandedKeys.contains(expandKey)
    // 按 item 键控: 位置复用时不残留上一条的截断态
    var truncated by remember(item) { mutableStateOf(false) }
    val rowModifier = if (isParent) {
        Modifier.fillMaxWidth()
    } else {
        // 整条空白区/正文点击 → 回复; 长按 → 复制内容
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onReviewClick(item) },
                onLongClick = { onReviewLongClick(item) },
            )
    }
    Row(rowModifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        avatarSlot(
            item.avatar,
            Modifier
                .padding(top = 4.dp, end = 12.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable {
                    item.avatar?.takeIf { it.isNotBlank() }?.let { onAvatarClick(it) }
                },
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.viewHeightSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name.orEmpty(),
                    color = rememberColor("secondaryText"),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.extra.orEmpty(),
                    color = rememberColor("secondaryText"),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                if (!isParent) {
                    Box(contentAlignment = Alignment.Center) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Icon(
                            painter = painterResource(Res.drawable.ic_more_vert),
                            contentDescription = stringResource(Res.string.menu),
                            // 跟随夜间主题: 与相邻昵称/extra 同色
                            tint = rememberColor("secondaryText"),
                            modifier = Modifier
                                .height(28.dp)
                                .clickable { menuOpen = true },
                        )
                        AppDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                onClick = { menuOpen = false; onDeleteClick(item) },
                            ) {
                                Text(
                                    stringResource(Res.string.delete),
                                    color = AppTheme.colors.primaryText,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = item.content,
                color = rememberColor("primaryText"),
                fontSize = 15.sp,
                maxLines = if (isExpanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { truncated = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth(),
            )
            // 折叠态下被截断或已展开时才显示按钮
            if (!isParent && (isExpanded || truncated)) {
                Text(
                    text = rememberString(
                        if (isExpanded) "review_collapse" else "review_expand"
                    ),
                    color = AppTheme.colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(expandKey) }
                        .padding(vertical = 2.dp),
                )
            }
            if (item.images.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.images.forEach { url ->
                        imageSlot(
                            url,
                            Modifier
                                .size(120.dp)
                                .clickable { onImageClick(url) },
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.postTime.orEmpty(),
                    color = rememberColor("secondaryText"),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                val id = item.id
                val isVoted = id != null && votedIds.contains(id)
                val isVotedDown = id != null && votedDownIds.contains(id)
                val displayVoteCount = item.voteUpCount + if (isVoted) 1 else 0
                Icon(
                    painter = rememberPainter(
                        if (isVoted) "ic_review_thumb_up_filled" else "ic_review_thumb_up"
                    ),
                    contentDescription = stringResource(Res.string.vote_up),
                    // 跟随夜间主题: 已点赞=review_voted(与计数文本同色), 未点赞=secondaryText
                    tint = if (isVoted) rememberColor("review_voted") else rememberColor("secondaryText"),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onVoteUp(item) },
                )
                Box(
                    Modifier
                        .width(50.dp)
                        .height(20.dp)
                        .clickable { onVoteUp(item) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (displayVoteCount > 0) displayVoteCount.toString()
                        else stringResource(Res.string.vote_up),
                        color = if (isVoted) rememberColor("review_voted")
                        else rememberColor("secondaryText"),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                Icon(
                    painter = rememberPainter(
                        if (isVotedDown) "ic_review_thumb_down_filled" else "ic_review_thumb_down"
                    ),
                    contentDescription = stringResource(Res.string.vote_down),
                    // 跟随夜间主题: 已点踩=review_voted(与计数文本同色), 未点踩=secondaryText
                    tint = if (isVotedDown) rememberColor("review_voted") else rememberColor("secondaryText"),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(20.dp)
                        .clickable { onVoteDown(item) },
                )
            }
            if (!isParent && item.replyCount > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(DesignTokens.viewHeightLarge)
                        .clip(DesignTokens.shapeDefault)
                        .background(rememberColor("btn_bg"))
                        .clickable { onOpenReplies(item) }
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(Res.string.review_replies_count, item.replyCount),
                        color = AppTheme.colors.accent,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoadMoreFooter(footerLoading: Boolean, footerHasMore: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            footerLoading -> CircularProgressIndicator(
                color = AppTheme.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(8.dp)
                    .size(36.dp),
            )

            !footerHasMore -> Text(
                text = stringResource(Res.string.bottom_line),
                color = rememberColor("secondaryText"),
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** 底部"输入栏"只是个触发器, 点击后弹出输入面板; 回复详情页默认回复楼主 */
@Composable
internal fun InputBar(inputHint: String, onPostClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(rememberColor("background_card"))
            .clickable { onPostClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = inputHint,
            color = rememberColor("secondaryText"),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

// ===== @Preview 合并自 androidMain 的 book/read/ReviewListDialogPreviews.kt =====

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
