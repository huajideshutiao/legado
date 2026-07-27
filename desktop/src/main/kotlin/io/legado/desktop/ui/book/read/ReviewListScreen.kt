package io.legado.desktop.ui.book.read

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReviewListDialog
import io.legado.app.ui.book.read.ReviewPlatform
import io.legado.app.ui.book.read.ReviewViewModelShared
import io.legado.app.ui.compose.component.Md2TextField
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"书评/段评列表"页 Screen 入口 (薄壳, 委托 shared ReviewListDialog + ReviewViewModelShared)。
 *
 * # 职责
 *
 * - 桌面端用全屏 Surface 替代 app 端 BottomSheetDialogFragment (与 EffectiveReplacesScreen 模式一致)
 * - 通过 [IntentData] 接收 book/chapter/paragraphIndex/parentReview (与 app 端 arguments 透传对应)
 * - 复用 shared/commonMain 的 [ReviewViewModelShared] (拉取/翻页/回复/点赞/点踩/删除/规则执行),
 *   desktop 端不再持有独立 VM 复制实现 (消除重复代码)
 * - 复用 shared/sharedUiMain 的 [ReviewListDialog] Composable (列表头/回复头/评论项/Footer/输入栏),
 *   desktop 端注入平台 slot: 头像/配图占位 (无网络图片加载), lazyListModifier (无 nestedScroll)
 * - 保留 desktop 专属行为: [ReviewInputDialog] 替代 app 端 ReviewPostActivity (与其他桌面对话框风格一致),
 *   长按复制走 java.awt.datatransfer (无 sendToClip 扩展)
 *
 * # 与 app 端差异
 *
 * - 宿主: app 端 BottomSheetDialogFragment → 桌面端全屏 Surface
 * - 图片加载: app 端用 Coil3 (AndroidView); 桌面端无网络图片加载, 用 Icon/占位 Box
 * - 输入面板: app 端启动 ReviewPostActivity (独立 Activity); 桌面端用 [Dialog] + [Md2TextField]
 * - 查看大图: app 端 PhotoDialog; 桌面端 no-op (无图片查看器)
 * - 查看回复: app 端嵌套 showDialogFragment(ReviewListDialog); 桌面端 no-op (路由不支持嵌套, 后续由 DesktopApp 接管)
 *
 * # IntentData key 约定 (与 DesktopApp 路由切换处设置一致)
 *
 * - "reviewBookKey": Book (必需)
 * - "reviewChapterKey": BookChapter? (章节级评论/段评时传)
 * - "reviewParagraphIndex": Int (0=章节级, >0=段评, -1=书籍级)
 * - "reviewParentReviewKey": Review? (回复模式时传楼主段评)
 */
@Composable
fun ReviewListScreen(onBack: () -> Unit) {
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            // color 与 shared ReviewListDialog 内 background 一致, 避免顶部圆角缺口露色
            Surface(modifier = Modifier.fillMaxSize(), color = AppTheme.colors.background) {
                ReviewListContent(onBack = onBack)
            }
        }
    }
}

@Composable
private fun ReviewListContent(onBack: () -> Unit) {
    // 从 IntentData 取参数 (与 app 端 arguments 取值对应)
    val book = remember { IntentData.get<Book>("reviewBookKey") }
    val chapter = remember { IntentData.get<BookChapter>("reviewChapterKey") }
    val paragraphIndex = remember { IntentData.get<Int>("reviewParagraphIndex") ?: 0 }
    val parentReview = remember { IntentData.get<Review>("reviewParentReviewKey") }

    val scope = rememberCoroutineScope()
    val platform = remember { DesktopReviewPlatform() }
    val vm = remember(book?.bookUrl, paragraphIndex, parentReview?.id) {
        ReviewViewModelShared(scope, platform).apply {
            this.book = book
            this.chapter = chapter
            this.paragraphIndex = paragraphIndex
            this.replyReviewId = parentReview?.id
            this.parentReview = parentReview
        }
    }

    // ---- VM StateFlow → Compose state (替代原 var by mutableStateOf) ----
    val reviews by vm.reviews.collectAsState()
    val totalCount by vm.totalCount.collectAsState()
    val footerLoading by vm.loading.collectAsState()
    val footerHasMore by vm.hasMore.collectAsState()
    val sortState by vm.sortState.collectAsState()
    val votedIds by vm.votedIds.collectAsState()
    val votedDownIds by vm.votedDownIds.collectAsState()
    val expandedKeys by vm.expandedKeys.collectAsState()

    // ---- UI 本地态 (VM 不管的部分: 标题/提示/输入对话框显隐) ----
    var titleText by remember { mutableStateOf("") }
    var inputHint by remember { mutableStateOf("") }
    var repliesTitleText by remember { mutableStateOf("") }
    var listTitleText by remember { mutableStateOf("") }
    // 回复输入对话框 (替代 app 端 ReviewPostActivity)
    var showInputDialog by remember { mutableStateOf(false) }
    var pendingReplyTo by remember { mutableStateOf<Review?>(null) }

    // 标题/提示初始化 (对照 app 端 onViewCreated)
    LaunchedEffect(parentReview, paragraphIndex) {
        titleText = when {
            parentReview != null -> {
                inputHint = jvmGetString("reply_review")
                jvmGetString("review_replies_detail_title")
            }
            paragraphIndex <= 0 -> jvmGetString("review")
            else -> jvmGetString("review") + "  #" + paragraphIndex
        }
        if (parentReview != null) {
            // 楼主原评论灌入 voted/votedDown 初始态 (对照 app 端 onViewCreated 的 seedVoteFromItem)
            vm.seedVote(parentReview)
            repliesTitleText = jvmGetString("review_replies_section_title", parentReview.replyCount)
        }
    }

    // 段评总数文本 (对照 app 端 totalCountLiveData.observe)
    LaunchedEffect(totalCount) {
        listTitleText = if (!totalCount.isNullOrBlank()) {
            jvmGetString("review_list_section_title", totalCount)
        } else ""
    }

    // 首次加载 (对照 app 端 onViewCreated 的 viewModel.load())
    LaunchedEffect(book?.bookUrl) {
        if (book == null) {
            onBack()
            return@LaunchedEffect
        }
        vm.load()
    }

    // 回复/发表输入对话框 (替代 app 端 ReviewPostActivity)
    if (showInputDialog) {
        ReviewInputDialog(
            hint = inputHint.ifBlank { rememberString("review_post_hint") },
            replyPreview = pendingReplyTo?.content,
            onDismiss = { showInputDialog = false },
            onSubmit = { text ->
                val target = pendingReplyTo
                // shared VM.reply 内部 reloadOnSuccess=true, 会自动 load() 刷新 reviews;
                // onHandled 仅做乐观 UI (递增 replyCount), 等 load() 完成后从书源重新拉取
                vm.reply(text, target?.id) {
                    target?.let { it.replyCount += 1 }
                }
                showInputDialog = false
            },
        )
    }

    // 委托 shared ReviewListDialog (列表头/回复头/评论项/Footer/输入栏全在 shared 内)
    ReviewListDialog(
        title = titleText,
        parentReview = parentReview,
        listTitleText = listTitleText,
        repliesTitleText = repliesTitleText,
        inputHint = inputHint.ifBlank { rememberString("review_post_hint") },
        reviews = reviews,
        sortState = sortState,
        footerLoading = footerLoading,
        footerHasMore = footerHasMore,
        expandedKeys = expandedKeys,
        votedIds = votedIds,
        votedDownIds = votedDownIds,
        onDismiss = onBack,
        onLoadMore = { vm.loadMore() },
        onChangeSort = { vm.changeSort(it) },
        onReviewClick = { item ->
            pendingReplyTo = item
            showInputDialog = true
        },
        onReviewLongClick = { item ->
            // 复制内容到剪贴板 (对照 app 端 sendToClip, 桌面端走 java.awt.datatransfer)
            scope.launch {
                withContext(Dispatchers.IO) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(item.content), null,
                    )
                }
            }
        },
        onToggleExpand = { key -> vm.toggleExpand(key) },
        onVoteUp = { item -> vm.voteUp(item) },
        onVoteDown = { item -> vm.voteDown(item) },
        onDeleteClick = { item ->
            // shared VM.delete 内部已自动从 reviews 过滤, 回调仅供额外 UI 副作用;
            // 桌面端无删除确认对话框 (与原 desktop 实现一致), 直接执行
            vm.delete(item) { _ -> /* no-op: VM 已自动从 reviews 过滤 */ }
        },
        onOpenReplies = {
            // 桌面端路由不支持嵌套 (currentRoute 单层), 暂 no-op;
            // TODO: 由 DesktopApp 接管 onOpenReplies, 切到嵌套 REVIEW_LIST 路由
        },
        onPostClick = {
            pendingReplyTo = parentReview
            showInputDialog = true
        },
        onAvatarClick = { /* 桌面端无图片查看大图, no-op */ },
        onImageClick = { /* 桌面端无图片查看大图, no-op */ },
        avatarSlot = { _, modifier ->
            // 桌面端无网络图片加载, 用 Icon 占位 (与原实现一致)
            Icon(
                painter = rememberPainter("ic_bottom_person_s"),
                contentDescription = null,
                tint = AppTheme.colors.secondaryText,
                modifier = modifier,
            )
        },
        imageSlot = { _, modifier ->
            // 桌面端无网络图片加载, 用占位 Box 显示数量 (与原实现一致)
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = rememberString("image_style"),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                )
            }
        },
        lazyListModifier = Modifier, // 桌面端无 nestedScroll
        modifier = Modifier.fillMaxSize(),
    )
}

/** 回复/发表输入对话框 (替代 app 端 ReviewPostActivity) */
@Composable
private fun ReviewInputDialog(
    hint: String,
    replyPreview: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppTheme.colors.background,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = rememberString("post_review"),
                    color = AppTheme.colors.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!replyPreview.isNullOrBlank()) {
                    val preview = replyPreview.take(15).let { if (replyPreview.length > 15) "$it…" else it }
                    Text(
                        text = rememberString("reply_review_to", preview),
                        color = AppTheme.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Md2TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    placeholder = hint,
                    minLines = 3,
                    maxLines = 6,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(rememberString("cancel"))
                    }
                    TextButton(
                        onClick = { if (text.isNotBlank()) onSubmit(text.trim()) },
                        enabled = text.isNotBlank(),
                    ) {
                        Text(rememberString("post_review"))
                    }
                }
            }
        }
    }
}

/**
 * 桌面端 [ReviewPlatform] 实现 (消费 shared commonMain 接口, 不修改 shared)。
 *
 * - [toastOnUi] 走 [Toasters.get].toast (调用线程不限, 桌面端 AWT 内部线程安全)
 * - 文案: 复用 SharedStringTable 已有的 review_no_current_book / review_no_source / review_load_failed;
 *   operationFailed / noActionRule 在 SharedStringTable 无对应 key, 硬编码中文
 *   (与 ReviewViewModelShared 注释 "actual 平台提供实现, 硬编码中文" 对齐, 也与 app 端文案一致)
 */
private class DesktopReviewPlatform : ReviewPlatform {
    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }

    override fun noCurrentBook(): String = jvmGetString("review_no_current_book")
    override fun noSource(): String = jvmGetString("review_no_source")
    override fun loadFailed(cause: String?): String = jvmGetString("review_load_failed", cause)
    override fun operationFailed(cause: String?): String = "操作失败: $cause"
    override fun noActionRule(): String = "书源未配置此操作规则"
}
