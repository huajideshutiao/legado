package io.legado.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.ui.book.read.ReviewListDialog
import io.legado.app.ui.book.read.ReviewViewModelShared
import io.legado.app.ui.book.read.SharedUiReviewPlatform
import io.legado.app.ui.book.read.review.SharedReviewAvatar
import io.legado.app.ui.book.read.review.SharedReviewImage
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.confirm_delete_review
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.no
import legado.shared.generated.resources.reply_review
import legado.shared.generated.resources.review
import legado.shared.generated.resources.review_list_section_title
import legado.shared.generated.resources.review_post_hint
import legado.shared.generated.resources.review_replies_detail_title
import legado.shared.generated.resources.review_replies_section_title
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 段评/书评列表**底部弹窗**宿主 (KMP 共享, 对照 app 端 ReviewListDialog BottomSheetDialogFragment)。
 *
 * 原版 (app 端) 段评/书评列表是 BottomSheetDialogFragment: 底部弹窗 (顶部圆角, 可下滑收起)
 * 承载 [ReviewListDialog] 共享内容 (回复列表 + 底部发布输入栏)。此前非 Android 端
 * 只有整页路由 (ReviewListRoute, 无回复详情), 段评气泡点击 (ReadViewComposable →
 * showReviewListDialog) 更是静默无反应。本宿主把 BottomSheet 形态补到四端:
 *
 * - 外壳: [AppBottomSheetDialog] 贴底滑入 (对照原版 BaseBottomDialogFragment gravity=Bottom),
 *   高度走 [AppDialogSizes.fullHeight] (全局统一 0.7 锚点高), 顶部圆角由
 *   [ReviewListDialog] 内容自带
 * - 业务: [ReviewViewModelShared] (与 app 端同款, 分页/点赞/点踩/回复/删除/规则执行全下沉)
 * - 交互: 点击单条 → [ReviewPostDialogHost] 回复输入面板 (F39 已修: 底部弹窗 + 键盘跟随);
 *   删除二次确认 / 头像/配图大图 (photo Overlay) 与 ReviewListRoute 同款
 * - 回复详情 (原版 openReplies 再开一层 Dialog): 复用本宿主嵌套一层
 *   parentReview=目标段评 的弹窗, 对照 app 端 showDialogFragment(ReviewListDialog(..., review))
 *
 * 触发方式: 平台 [io.legado.app.ui.root.PlatformCapabilities.showReviewListDialog] 经
 * [AppOverlay.Dialog]("review_list", payload=[ReviewListDialogPayload] JSON) 弹出;
 * 渲染入口见 [ReviewListOverlayDialogContent] (LegadoApp DialogOverlayContent 按 key 分流)。
 *
 * @param book 书籍 (段评/书评的宿主书, 透传 VM, 不查 DB)
 * @param chapter 章节 (书评级 null; 段评/章节评传当前章)
 * @param paragraphIndex -1=书评, 0=章节评, >0=段评
 * @param parentReview 回复详情模式的楼主段评; null = 列表模式
 * @param onDismiss 关闭回调 (顶层弹窗; 回复详情层关闭回退到列表层)
 */
@Composable
fun ReviewListContent(
    book: Book,
    chapter: BookChapter?,
    paragraphIndex: Int,
    parentReview: Review? = null,
    onDismiss: () -> Unit,
    /** 回复详情嵌套承载: true=再开一层弹窗 (Host 弹窗形态, 原版两层 Dialog 语义); false=同容器替换 (宽屏面板形态) */
    wrapRepliesInSheet: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    // 对照 app 端 ReviewListDialog.onViewCreated: 实例化 shared VM 并灌入输入参数
    val viewModel = remember(book.bookUrl, paragraphIndex, parentReview?.id) {
        ReviewViewModelShared(scope = scope, platform = SharedUiReviewPlatform).apply {
            this.book = book
            this.chapter = chapter
            this.paragraphIndex = paragraphIndex
            this.replyReviewId = parentReview?.id
            this.parentReview = parentReview
        }
    }
    val reviews by viewModel.reviews.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val sortState by viewModel.sortState.collectAsState()
    val votedIds by viewModel.votedIds.collectAsState()
    val votedDownIds by viewModel.votedDownIds.collectAsState()
    val expandedKeys by viewModel.expandedKeys.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()

    // 一次性 UI 文本 (对照 app 端 onViewCreated 的 when 分支)
    val titleText = when {
        parentReview != null -> stringResource(Res.string.review_replies_detail_title)
        paragraphIndex <= 0 -> stringResource(Res.string.review)
        else -> stringResource(Res.string.review) + "  #" + paragraphIndex
    }
    val inputHint = stringResource(
        if (parentReview != null) Res.string.reply_review else Res.string.review_post_hint
    )
    // 列表头 "全部评论 · N": 规则未配置 (totalCount 空) 时不显示
    // (先拷本地再判空: 委托属性不可 smart-cast, 同 ReviewListRoute 做法)
    val count = totalCount
    val listTitleText = if (count.isNullOrBlank()) ""
    else stringResource(Res.string.review_list_section_title, count)
    // 回复模式 "全部回复 · N" (对照 app 端 repliesTitleText)
    val repliesTitleText = if (parentReview != null) {
        stringResource(Res.string.review_replies_section_title, parentReview.replyCount)
    } else ""

    LaunchedEffect(viewModel) {
        if (parentReview != null) viewModel.seedVote(parentReview)
        viewModel.load()
    }

    // 发布输入弹窗状态 (对照 app 端 ReviewListDialog 的 showPostDialog/postReplyPreview):
    // preview 非空 = 回复该条
    var showPostDialog by remember { mutableStateOf(false) }
    var postReplyPreview by remember { mutableStateOf<String?>(null) }
    var replyToReview by remember { mutableStateOf<Review?>(null) }
    val pendingDelete = remember { mutableStateOf<Review?>(null) }
    // 回复详情: 目标段评 (原版 openReplies 再开一层 Dialog), 非空时嵌套一层本宿主
    var repliesTarget by remember { mutableStateOf<Review?>(null) }

    // 提交 (对照 app 端 submitPost): 带 replyTo 时按回复该条提交并乐观递增其回复数,
    // 成功后 VM 自动 load 重载列表
    fun submitPost(text: String) {
        val target = replyToReview
        replyToReview = null
        viewModel.reply(content = text, reviewId = target?.id, onHandled = handled@{
            target ?: return@handled
            target.replyCount += 1
        })
    }

    Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
                    ReviewListDialog(
                        title = titleText,
                        parentReview = parentReview,
                        listTitleText = listTitleText,
                        repliesTitleText = repliesTitleText,
                        inputHint = inputHint,
                        reviews = reviews,
                        sortState = sortState,
                        footerLoading = loading,
                        footerHasMore = hasMore,
                        expandedKeys = expandedKeys,
                        votedIds = votedIds,
                        votedDownIds = votedDownIds,
                        onDismiss = onDismiss,
                        onLoadMore = { viewModel.loadMore() },
                        onChangeSort = { viewModel.changeSort(it) },
                        onReviewClick = { review ->
                            replyToReview = review
                            postReplyPreview = review.content
                            showPostDialog = true
                        },
                        onReviewLongClick = { review ->
                            PlatformCapabilityProviders.getOrNull()?.copyToClipboard(review.content)
                        },
                        onToggleExpand = { viewModel.toggleExpand(it) },
                        onVoteUp = { viewModel.voteUp(it) },
                        onVoteDown = { viewModel.voteDown(it) },
                        onDeleteClick = { pendingDelete.value = it },
                        // 回复详情: 再开一层弹窗 (对照 app 端 openReplies → 新 ReviewListDialog)
                        onOpenReplies = { review ->
                            if (!review.id.isNullOrBlank()) repliesTarget = review
                        },
                        // 底部输入栏: 回复模式默认回复楼主 (对照 app 端 onPostClick 分支)
                        onPostClick = {
                            replyToReview = parentReview
                            postReplyPreview = parentReview?.content
                            showPostDialog = true
                        },
                        onAvatarClick = { url ->
                            url?.takeIf { it.isNotBlank() }?.let {
                                AppNavigatorProviders.getOrNull()?.showOverlay(
                                    AppOverlay.Dialog(
                                        key = "photo",
                                        payload = it,
                                        // 可叠放 (方案 C 例外): 图片查看器叠在段评列表上,
                                        // 关闭查看器后列表仍在 (对照原版 Fragment 叠放语义)
                                        stacked = true,
                                    )
                                )
                            }
                        },
                        onImageClick = { url ->
                            AppNavigatorProviders.getOrNull()?.showOverlay(
                                AppOverlay.Dialog(
                                    key = "photo",
                                    payload = url,
                                    stacked = true,
                                )
                            )
                        },
                        // 图片渲染槽: 复用整页路由同款 (BookImageLoaders, 未注册端回退占位)
                        avatarSlot = { url, modifier -> SharedReviewAvatar(url, modifier) },
                        imageSlot = { url, modifier -> SharedReviewImage(url, modifier) },
                    )

                    // 发布输入弹窗 (对照原版 ReviewPostActivity 底部输入面板, F39 已修键盘跟随)
                    if (showPostDialog) {
                        ReviewPostDialogHost(
                            replyPreview = postReplyPreview,
                            onPosted = { content ->
                                if (content.isNotBlank()) submitPost(content)
                            },
                            onDismiss = { showPostDialog = false },
                        )
                    }
    }

    // 回复详情层: 弹窗形态=再开一层弹窗 (原版两层 Dialog 语义); 面板形态=同容器替换内容。
    // 关闭时只回退到列表层, 不关外层
    repliesTarget?.let { review ->
        if (wrapRepliesInSheet) {
            ReviewListDialogHost(
                book = book,
                chapter = chapter,
                paragraphIndex = paragraphIndex,
                parentReview = review,
                onDismiss = { repliesTarget = null },
            )
        } else {
            ReviewListContent(
                book = book,
                chapter = chapter,
                paragraphIndex = paragraphIndex,
                parentReview = review,
                onDismiss = { repliesTarget = null },
            )
        }
    }

    // 删除二次确认 (对照 app 端 alert(delete, confirm_delete_review))
    pendingDelete.value?.let { review ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.confirm_delete_review),
            okButton = AlertButton(stringResource(Res.string.yes)) {
                viewModel.delete(review) { }
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {},
        )
    }
}

/**
 * 评论列表**底部弹窗**宿主 (对照 app 端 ReviewListDialog BottomSheetDialogFragment):
 * 贴底滑入 + 92% 撑高外壳, 内容与交互全部在 [ReviewListContent] (宽屏面板与弹窗共用)。
 * 回复详情在弹窗形态下再开一层本宿主 (原版两层 Dialog 语义)。
 */
@Composable
fun ReviewListDialogHost(
    book: Book,
    chapter: BookChapter?,
    paragraphIndex: Int,
    parentReview: Review? = null,
    onDismiss: () -> Unit,
) {
    // 全局统一 0.7 锚点高 (走 AppDialogSizes.fullHeight, 别再自行乘系数); 桌面端锚点 = 主窗口
    val sheetHeight = AppDialogSizes.fullHeight()

    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
        maxHeight = sheetHeight,
    ) {
        AppTheme {
            Surface(
                color = AppTheme.colors.background,
                // fillMaxSize 跟随外层: 默认被 maxHeight 钳在 0.7 锚点高, 上拉拖拽展开时
                // 跟着变高到视觉全屏 (AppBottomSheetDialog 自带的双向拖拽语义);
                // 之前写死 .height(sheetHeight) 会把展开高度钉死, 上拉只放大外层空壳
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            ) {
                ReviewListContent(
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphIndex,
                    parentReview = parentReview,
                    onDismiss = onDismiss,
                    wrapRepliesInSheet = true,
                )
            }
        }
    }
}

/**
 * 段评列表 Overlay payload: 弹窗所需的全部输入 (book/chapter/段评参数/回复模式楼主段评)。
 *
 * book/chapter 已 @Serializable (导航快照同源); Review 补 @Serializable 后可直接透传。
 */
@Serializable
data class ReviewListDialogPayload(
    val book: Book,
    val chapter: BookChapter? = null,
    val paragraphIndex: Int = 0,
    val parentReview: Review? = null,
)

/** 编码 [ReviewListDialogPayload] 为 Overlay payload JSON (供平台 showReviewListDialog 使用)。 */
fun encodeReviewListDialogPayload(
    book: Book,
    chapter: BookChapter?,
    paragraphIndex: Int,
    parentReview: Review? = null,
): String = KS_JSON.encodeToString(
    ReviewListDialogPayload(
        book = book,
        chapter = chapter,
        paragraphIndex = paragraphIndex,
        parentReview = parentReview,
    )
)

/** 解码失败返回 null (调用方关闭弹窗, 对照 app 端 book==null dismiss)。 */
fun decodeReviewListDialogPayload(json: String?): ReviewListDialogPayload? =
    runCatching { KS_JSON.decodeFromString<ReviewListDialogPayload>(json ?: return null) }
        .getOrNull()

/**
 * Overlay 渲染入口 (LegadoApp DialogOverlayContent 按 key="review_list" 分流)。
 */
@Composable
internal fun ReviewListOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val payload = decodeReviewListDialogPayload(overlay.payload)
    if (payload == null) {
        // payload 缺失/解析失败: 直接关闭, 避免白屏
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    ReviewListDialogHost(
        book = payload.book,
        chapter = payload.chapter,
        paragraphIndex = payload.paragraphIndex,
        parentReview = payload.parentReview,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
