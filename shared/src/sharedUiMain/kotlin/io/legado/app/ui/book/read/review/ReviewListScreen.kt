package io.legado.app.ui.book.read.review

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Review
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.ui.book.read.InputBar
import io.legado.app.ui.book.read.ReviewListBody
import io.legado.app.ui.book.read.ReviewListLoadMoreEffect
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.review
import org.jetbrains.compose.resources.stringResource

/**
 * 书评列表页 shared Screen (KMP 共享)。
 *
 * 列表主体 (排序头 / 单条评论 / 点赞点踩 / 展开折叠 / 菜单删除 / 回复入口 / footer) 与底部输入栏
 * 全部复用 app 原版下沉的 [ReviewListBody] + [InputBar],
 * 本 Screen 只提供整页外壳 (AppTitleBar + 返回), 与 BottomSheet 形态的
 * [io.legado.app.ui.book.read.ReviewListDialog] 共用同一套 item 渲染。
 *
 * @param state 列表状态
 * @param actions 用户交互回调
 */
@Composable
fun ReviewListScreen(
    state: ReviewListUiState,
    actions: ReviewListUiActions,
) {
    val listState = rememberLazyListState()

    ReviewListLoadMoreEffect(
        listState = listState,
        reviews = state.reviews,
        footerHasMore = state.footerHasMore,
        footerLoading = state.footerLoading,
        onLoadMore = actions::onLoadMore,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(rememberColor("background"))
    ) {
        AppTitleBar(
            title = stringResource(Res.string.review),
            onBack = actions::onBack,
        )
        ReviewListBody(
            listState = listState,
            // 整页路由仅书评列表模式 (无楼主原评论)
            parentReview = null,
            listTitleText = state.listTitleText,
            repliesTitleText = "",
            reviews = state.reviews,
            sortState = state.sortState,
            footerLoading = state.footerLoading,
            footerHasMore = state.footerHasMore,
            expandedKeys = state.expandedKeys,
            votedIds = state.votedIds,
            votedDownIds = state.votedDownIds,
            onLoadMore = actions::onLoadMore,
            onChangeSort = actions::onChangeSort,
            onReviewClick = actions::onReviewClick,
            onReviewLongClick = actions::onReviewLongClick,
            onToggleExpand = actions::onToggleExpand,
            onVoteUp = actions::onVoteUp,
            onVoteDown = actions::onVoteDown,
            onDeleteClick = actions::onDeleteClick,
            onOpenReplies = actions::onOpenReplies,
            onAvatarClick = actions::onAvatarClick,
            onImageClick = actions::onImageClick,
            avatarSlot = { url, modifier -> SharedReviewAvatar(url, modifier) },
            imageSlot = { url, modifier -> SharedReviewImage(url, modifier) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        InputBar(state.inputHint, actions::onPostClick)
    }
}

/**
 * 头像渲染槽默认实现: 走 [BookImageLoaders] 加载, 未注册/失败时回退圆形占位图。
 *
 * 对照 app 端 Coil3 `iv.load(url) { placeholder(ic_bottom_person); error(ic_bottom_person) }`;
 * 鸿蒙未注册 loader 时恒走占位 (模式同 [io.legado.app.ui.book.info.SharedIntroImage])。
 */
@Composable
fun SharedReviewAvatar(url: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, loader) {
        if (url.isNullOrBlank() || loader == null) return@LaunchedEffect
        bitmap = loader.loadImageOrNull(url = url, sourceOrigin = null)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        // 占位: 与 app 端 placeholder/error 同一张人像图 (调用方 modifier 已带 CircleShape 裁剪)
        Image(
            painter = rememberPainter("ic_bottom_person_e"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/**
 * 配图渲染槽默认实现: 走 [BookImageLoaders] 加载, 未注册/失败时回退浅灰占位。
 *
 * 对照 app 端 `iv.load(url) { size(120dp) }` + FIT_CENTER。
 */
@Composable
fun SharedReviewImage(url: String, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    // 按 120dp 目标尺寸降采样 (对照 app 端 size(120.dpToPx()))
    val sizePx = with(LocalDensity.current) { 120.dp.roundToPx() }
    LaunchedEffect(url, loader, sizePx) {
        if (url.isBlank() || loader == null) return@LaunchedEffect
        bitmap = loader.loadImageOrNull(
            url = url,
            sourceOrigin = null,
            widthPx = sizePx,
            heightPx = sizePx,
        )
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(rememberColor("background_card")))
    }
}

/** 评论列表页 UI 状态 (immutable)。 */
data class ReviewListUiState(
    val reviews: List<Review> = emptyList(),
    /** footer 转圈 (对照 app 原版 footerLoading = vm.loading) */
    val footerLoading: Boolean = true,
    val footerHasMore: Boolean = true,
    /** 排序 0=最热 1=最新 */
    val sortState: Int = 0,
    val expandedKeys: Set<String> = emptySet(),
    val votedIds: Set<String> = emptySet(),
    val votedDownIds: Set<String> = emptySet(),
    /** "全部评论 · N"; 书源未配总数规则时为空 */
    val listTitleText: String = "",
    /** 底部输入栏提示 */
    val inputHint: String = "",
)

/** 评论列表页用户交互回调 (对照 app 原版 ReviewListDialog 的各 onXxx)。 */
interface ReviewListUiActions {

    /** 返回 */
    fun onBack()

    /** 翻到底加载更多 */
    fun onLoadMore()

    /** 切换排序 (0=最热, 1=最新) */
    fun onChangeSort(sort: Int)

    /** 点击单条 = 回复该条 */
    fun onReviewClick(review: Review)

    /** 长按单条 = 复制内容 */
    fun onReviewLongClick(review: Review)

    /** 展开/折叠正文 */
    fun onToggleExpand(key: String)

    /** 点赞 */
    fun onVoteUp(review: Review)

    /** 点踩 */
    fun onVoteDown(review: Review)

    /** 删除 (弹二次确认) */
    fun onDeleteClick(review: Review)

    /** 打开回复详情 */
    fun onOpenReplies(review: Review)

    /** 点击底部输入栏 = 发书评 */
    fun onPostClick()

    /** 点击头像看大图 */
    fun onAvatarClick(url: String?)

    /** 点击配图看大图 */
    fun onImageClick(url: String)
}
