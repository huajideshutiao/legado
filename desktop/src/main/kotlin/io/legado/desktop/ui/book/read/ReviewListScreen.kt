package io.legado.desktop.ui.book.read

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.IntentData
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.help.toast.Toasters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"书评/段评列表"页 Screen 入口 (对照 app 端 ReviewListDialog)。
 *
 * # 职责
 *
 * - 桌面端用全屏 Surface 替代 app 端 BottomSheetDialogFragment (与 EffectiveReplacesScreen 模式一致)
 * - 通过 [IntentData] 接收 book/chapter/paragraphIndex/parentReview (与 app 端 arguments 透传对应)
 * - 复用 shared/commonMain 的 [WebBook] 段评拉取/动作接口 (getReviewListAwait /
 *   getReviewRepliesAwait / evalReviewActionAwait)
 * - UI 结构严格对照 app 端 ReviewListContent: 头部(返回+标题) + LazyColumn(列表头/项/Footer) + 输入栏
 *
 * # 与 app 端差异
 *
 * - **宿主**: app 端 BottomSheetDialogFragment → 桌面端全屏 Surface (桌面无 BottomSheet 语义)
 * - **图片加载**: app 端用 Glide (AndroidView) 加载头像/配图; 桌面端无 Glide, 用 Icon 占位
 *   (ic_bottom_person_s 头像占位, 配图省略, 后续接入网络图片加载后补全)
 * - **输入面板**: app 端启动 ReviewPostActivity (独立 Activity 模拟 BottomSheet);
 *   桌面端用 [Dialog] + [OutlinedTextField] (与桌面端其他对话框风格一致)
 * - **ViewModel**: app 端 ReviewViewModel (BaseViewModel + LiveData); 桌面端 [DesktopReviewViewModel]
 *   (普通 class + 回调, 与 desktop 其他 VM 模式一致)
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
            Surface(modifier = Modifier.fillMaxSize()) {
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
    val vm = remember(book?.bookUrl, paragraphIndex, parentReview?.id) {
        DesktopReviewViewModel(book, chapter, paragraphIndex, parentReview?.id)
    }

    // ---- UI state (对照 app 端 dialog 的 var by mutableStateOf) ----
    var reviews by remember { mutableStateOf<List<Review>>(emptyList(), neverEqualPolicy()) }
    var titleText by remember { mutableStateOf("") }
    var listTitleText by remember { mutableStateOf("") }
    var repliesTitleText by remember { mutableStateOf("") }
    var sortState by remember { mutableIntStateOf(0) }
    var inputHint by remember { mutableStateOf("") }
    var footerLoading by remember { mutableStateOf(true) }
    var footerHasMore by remember { mutableStateOf(true) }
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    var votedIds by remember { mutableStateOf(emptySet<String>()) }
    var votedDownIds by remember { mutableStateOf(emptySet<String>()) }
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
            repliesTitleText = jvmGetString("review_replies_section_title", parentReview.replyCount)
        }
    }

    // 加载评论 (对照 app 端 viewModel.load)
    LaunchedEffect(book?.bookUrl) {
        if (book == null) {
            onBack()
            return@LaunchedEffect
        }
        vm.load(
            sort = sortState,
            onTotalCount = { text ->
                listTitleText = if (!text.isNullOrBlank()) {
                    jvmGetString("review_list_section_title", text)
                } else ""
            },
            onResult = { list, hasMore ->
                list.forEach { seedVote(it, votedIds, votedDownIds) { id -> votedIds = votedIds + id } }
                reviews = list
                footerLoading = false
                footerHasMore = hasMore
            },
            onError = { msg ->
                footerLoading = false
                footerHasMore = true
                scope.launch { Toasters.get().toast(msg) }
            },
        )
    }

    // 翻到底触发 loadMore (对照 app 端 snapshotFlow)
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val atEnd = info.totalItemsCount > 0 &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
            atEnd to info.totalItemsCount
        }.collect { (atEnd, _) ->
            if (atEnd && reviews.isNotEmpty() && footerHasMore && !footerLoading) {
                footerLoading = true
                vm.loadMore(
                    onResult = { list, hasMore ->
                        if (list.isNotEmpty()) {
                            list.forEach { seedVote(it, votedIds, votedDownIds) { id -> votedIds = votedIds + id } }
                            reviews = reviews + list
                        }
                        footerLoading = false
                        footerHasMore = hasMore
                    },
                    onError = { msg ->
                        footerLoading = false
                        scope.launch { Toasters.get().toast(msg) }
                    },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        // 头部: 返回 + 标题 (对照 app 端 48dp Box + IconButton + Text)
        Box(
            Modifier.fillMaxWidth().height(48.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = rememberPainter("ic_arrow_back"),
                    contentDescription = rememberString("cancel"),
                    tint = AppTheme.colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = titleText,
                color = AppTheme.colors.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            if (parentReview != null) {
                item { ReviewItem(parentReview, isParent = true) }
                item { RepliesHeader(repliesTitleText) }
            } else {
                item {
                    ListHeader(
                        listTitleText = listTitleText,
                        sortState = sortState,
                        onChangeSort = { newSort ->
                            if (newSort == sortState) return@ListHeader
                            sortState = newSort
                            reviews = emptyList()
                            footerLoading = true
                            vm.load(
                                sort = newSort,
                                onTotalCount = { text ->
                                    listTitleText = if (!text.isNullOrBlank()) {
                                        jvmGetString("review_list_section_title", text)
                                    } else ""
                                },
                                onResult = { list, hasMore ->
                                    list.forEach { seedVote(it, votedIds, votedDownIds) { id -> votedIds = votedIds + id } }
                                    reviews = list
                                    footerLoading = false
                                    footerHasMore = hasMore
                                },
                                onError = { msg ->
                                    footerLoading = false
                                    scope.launch { Toasters.get().toast(msg) }
                                },
                            )
                        },
                    )
                }
            }
            items(reviews.size) { index ->
                ReviewItem(
                    item = reviews[index],
                    isVoted = reviews[index].id != null && votedIds.contains(reviews[index].id),
                    isVotedDown = reviews[index].id != null && votedDownIds.contains(reviews[index].id),
                    isExpanded = expandedKeys.contains(expandKey(reviews[index])),
                    onToggleExpand = {
                        val key = expandKey(reviews[index])
                        expandedKeys = if (expandedKeys.contains(key)) expandedKeys - key
                        else expandedKeys + key
                    },
                    onClick = { item ->
                        pendingReplyTo = item
                        showInputDialog = true
                    },
                    onLongClick = { item ->
                        // 复制内容到剪贴板 (对照 app 端 sendToClip)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                    java.awt.datatransfer.StringSelection(item.content), null,
                                )
                            }
                        }
                    },
                    onVoteUp = { item -> voteUp(item, vm, votedIds, votedDownIds, scope) { id, add ->
                        if (add) votedIds = votedIds + id else votedIds = votedIds - id
                    } },
                    onVoteDown = { item -> voteDown(item, vm, votedIds, votedDownIds, scope) { id, add ->
                        if (add) votedDownIds = votedDownIds + id else votedDownIds = votedDownIds - id
                    } },
                    onDelete = { item ->
                        vm.delete(item) { id ->
                            reviews = reviews.filterNot { it.id == id }
                        }
                    },
                )
            }
            item {
                LoadMoreFooter(
                    loading = footerLoading,
                    hasMore = footerHasMore,
                )
            }
        }
        // 输入栏 (对照 app 端 InputBar, 点击触发输入对话框)
        InputBar(hint = inputHint.ifBlank { rememberString("review_post_hint") }) {
            pendingReplyTo = parentReview
            showInputDialog = true
        }
    }

    // 回复/发表输入对话框 (替代 app 端 ReviewPostActivity)
    if (showInputDialog) {
        ReviewInputDialog(
            hint = inputHint.ifBlank { rememberString("review_post_hint") },
            replyPreview = pendingReplyTo?.content,
            onDismiss = { showInputDialog = false },
            onSubmit = { text ->
                val target = pendingReplyTo
                vm.reply(text, target?.id) {
                    target?.let {
                        it.replyCount += 1
                        reviews = reviews.toList()
                    }
                }
                showInputDialog = false
            },
        )
    }
}

/** 段评模式头部: "全部评论·N" + 排序选择 (对照 app 端 ListHeader) */
@Composable
private fun ListHeader(
    listTitleText: String,
    sortState: Int,
    onChangeSort: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listTitleText,
            color = AppTheme.colors.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box {
            var sortMenuOpen by remember { mutableStateOf(false) }
            Row(
                Modifier.clickable { sortMenuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = rememberString(if (sortState == 1) "review_sort_latest" else "review_sort_hot"),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 13.sp,
                )
                Icon(
                    painter = rememberPainter("ic_arrow_drop_down"),
                    contentDescription = null,
                    tint = AppTheme.colors.secondaryText,
                )
            }
            AppDropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(rememberString("review_sort_hot"), color = AppTheme.colors.primaryText) },
                    onClick = { sortMenuOpen = false; onChangeSort(0) },
                )
                DropdownMenuItem(
                    text = { Text(rememberString("review_sort_latest"), color = AppTheme.colors.primaryText) },
                    onClick = { sortMenuOpen = false; onChangeSort(1) },
                )
            }
        }
    }
}

/** 回复模式分隔条 (对照 app 端 RepliesHeader) */
@Composable
private fun RepliesHeader(repliesTitleText: String) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(8.dp).background(rememberColor("divider")),
        )
        Text(
            text = repliesTitleText,
            color = AppTheme.colors.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

/** 单条评论项 (对照 app 端 ReviewItem) */
@Composable
private fun ReviewItem(
    item: Review,
    isParent: Boolean = false,
    isVoted: Boolean = false,
    isVotedDown: Boolean = false,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onClick: (Review) -> Unit = {},
    onLongClick: (Review) -> Unit = {},
    onVoteUp: (Review) -> Unit = {},
    onVoteDown: (Review) -> Unit = {},
    onDelete: (Review) -> Unit = {},
) {
    val rowModifier = if (isParent) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { onClick(item) },
            onLongClick = { onLongClick(item) },
        )
    }
    var truncated by remember(item) { mutableStateOf(false) }
    Row(rowModifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 头像占位 (桌面端无 Glide, 用 Icon 占位)
        Icon(
            painter = rememberPainter("ic_bottom_person_s"),
            contentDescription = null,
            tint = AppTheme.colors.secondaryText,
            modifier = Modifier
                .padding(top = 4.dp, end = 12.dp)
                .size(36.dp)
                .clip(CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name.orEmpty(),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.extra.orEmpty(),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (!isParent) {
                    Box(contentAlignment = Alignment.Center) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Icon(
                            painter = rememberPainter("ic_more_vert"),
                            contentDescription = rememberString("menu"),
                            tint = AppTheme.colors.secondaryText,
                            modifier = Modifier.height(28.dp).clickable { menuOpen = true },
                        )
                        AppDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(rememberString("delete"), color = AppTheme.colors.primaryText) },
                                onClick = { menuOpen = false; onDelete(item) },
                            )
                        }
                    }
                }
            }
            Text(
                text = item.content,
                color = AppTheme.colors.primaryText,
                fontSize = 15.sp,
                maxLines = if (isExpanded || isParent) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { truncated = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isParent && (isExpanded || truncated)) {
                Text(
                    text = rememberString(if (isExpanded) "review_collapse" else "review_expand"),
                    color = AppTheme.colors.accent,
                    modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() }.padding(vertical = 2.dp),
                )
            }
            // 配图行: 桌面端无网络图片加载, 用占位 Box 显示数量 (后续接入图片加载后补全)
            if (item.images.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.images.forEach { _ ->
                        Box(
                            Modifier.size(120.dp).clip(RoundedCornerShape(4.dp))
                                .background(AppTheme.colors.fillet),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = rememberString("image_style"),
                                color = AppTheme.colors.secondaryText,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.postTime.orEmpty(),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                val displayVoteCount = item.voteUpCount + if (isVoted) 1 else 0
                Icon(
                    painter = rememberPainter("ic_praise"),
                    contentDescription = rememberString("vote_up"),
                    tint = if (isVoted) AppTheme.colors.accent else AppTheme.colors.secondaryText,
                    modifier = Modifier.size(20.dp).clickable { onVoteUp(item) },
                )
                Box(
                    Modifier.width(50.dp).height(20.dp).clickable { onVoteUp(item) }.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (displayVoteCount > 0) displayVoteCount.toString()
                        else rememberString("vote_up"),
                        color = if (isVoted) AppTheme.colors.accent else AppTheme.colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                // 点踩 (复用 praise 图标旋转 180 度, 桌面端无 thumb_down dedicated 图标)
                Icon(
                    painter = rememberPainter("ic_praise"),
                    contentDescription = rememberString("vote_down"),
                    tint = if (isVotedDown) AppTheme.colors.accent else AppTheme.colors.secondaryText,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp).clickable { onVoteDown(item) },
                )
            }
        }
    }
}

/** 列表底部加载状态 (对照 app 端 LoadMoreFooter) */
@Composable
private fun LoadMoreFooter(loading: Boolean, hasMore: Boolean) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(
                color = AppTheme.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(8.dp).size(36.dp),
            )
            !hasMore -> Text(
                text = rememberString("bottom_line"),
                color = AppTheme.colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** 底部输入栏 (对照 app 端 InputBar, 桌面端点击弹 [ReviewInputDialog]) */
@Composable
private fun InputBar(hint: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AppTheme.colors.fillet)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = hint,
            color = AppTheme.colors.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
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
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    placeholder = { Text(hint) },
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

// ---- 辅助函数 ----

private fun expandKey(item: Review): String = item.id ?: "#${item.content.hashCode()}"

/** 把书源返回的 voted 初始态首次灌入本地集合 (对照 app 端 seedVoteFromItem) */
private fun seedVote(
    item: Review,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    addVoted: (String) -> Unit,
) {
    val id = item.id ?: return
    if (votedIds.contains(id) || votedDownIds.contains(id)) return
    if (item.voted) {
        addVoted(id)
        if (item.voteUpCount > 0) item.voteUpCount -= 1
    }
}

private fun voteUp(
    item: Review,
    vm: DesktopReviewViewModel,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    scope: kotlinx.coroutines.CoroutineScope,
    updateVoted: (String, Boolean) -> Unit,
) {
    val id = item.id ?: return
    val target = !votedIds.contains(id)
    if (target) {
        updateVoted(id, true)
        updateVoted(id, false) // votedDown 互斥, 这里简化: 调用方需自行处理
    } else {
        updateVoted(id, false)
    }
    vm.voteUp(item, !target) {
        // 失败回退
        updateVoted(id, !target)
    }
}

private fun voteDown(
    item: Review,
    vm: DesktopReviewViewModel,
    votedIds: Set<String>,
    votedDownIds: Set<String>,
    scope: kotlinx.coroutines.CoroutineScope,
    updateVoted: (String, Boolean) -> Unit,
) {
    val id = item.id ?: return
    val target = !votedDownIds.contains(id)
    if (target) {
        updateVoted(id, true)
    } else {
        updateVoted(id, false)
    }
    vm.voteDown(item, !target) {
        updateVoted(id, !target)
    }
}

/**
 * 桌面端段评 ViewModel (对照 app 端 ReviewViewModel).
 *
 * 复用 shared/commonMain 的 [WebBook] 段评接口, 通过回调通知 UI (与 desktop 其他 VM 模式一致).
 * book/chapter/paragraphIndex 由调用方透传, 不查 DB (与 app 端一致).
 */
private class DesktopReviewViewModel(
    val book: Book?,
    val chapter: BookChapter?,
    val paragraphIndex: Int,
    val replyReviewId: String?,
) {
    private var currentPage = 1
    private var loading = false
    private var hasMore = true
    var sort: Int = 0

    fun load(
        sort: Int,
        onTotalCount: (String?) -> Unit,
        onResult: (List<Review>, Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        this.sort = sort
        currentPage = 1
        hasMore = true
        fetchPage(append = false, onTotalCount, onResult, onError)
    }

    fun loadMore(
        onResult: (List<Review>, Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!hasMore || loading) return
        currentPage += 1
        fetchPage(append = true, { _ -> }, onResult, onError)
    }

    private fun fetchPage(
        append: Boolean,
        onTotalCount: (String?) -> Unit,
        onResult: (List<Review>, Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val b = book ?: run { onError("无当前书籍"); return }
        loading = true
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
                    ?: throw IllegalStateException("无书源")
                val page = currentPage
                val result = if (replyReviewId != null) {
                    WebBook.getReviewRepliesAwait(source, b, chapter, paragraphIndex, replyReviewId, page).getOrThrow()
                } else {
                    WebBook.getReviewListAwait(source, b, chapter, paragraphIndex, page, sort).getOrThrow()
                }
                hasMore = result.hasNextPage
                if (!append && replyReviewId == null) onTotalCount(result.totalCount)
                onResult(result.reviews, hasMore)
            } catch (e: Exception) {
                if (append) currentPage -= 1
                onError("评论加载失败: ${e.localizedMessage}")
            } finally {
                loading = false
            }
        }
    }

    fun reply(content: String, reviewId: String?, onHandled: () -> Unit) {
        runRule({ it.replyRule }, content = content, reviewId = reviewId, reloadOnSuccess = true) { onHandled() }
    }

    fun voteUp(review: Review, selected: Boolean, onError: () -> Unit) {
        val id = review.id ?: return
        runRule({ it.voteUpRule }, reviewId = id, selected = selected, onError = onError)
    }

    fun voteDown(review: Review, selected: Boolean, onError: () -> Unit) {
        val id = review.id ?: return
        runRule({ it.voteDownRule }, reviewId = id, selected = selected, onError = onError)
    }

    fun delete(review: Review, onRemoved: (String) -> Unit) {
        val id = review.id ?: return
        runRule({ it.deleteRule }, reviewId = id) { onRemoved(id) }
    }

    private fun runRule(
        ruleSelector: (ReviewRule) -> String?,
        content: String? = null,
        reviewId: String? = null,
        selected: Boolean? = null,
        reloadOnSuccess: Boolean = false,
        onSuccess: (Any?) -> Unit = {},
        onError: (() -> Unit)? = null,
    ) {
        val b = book ?: return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
                    ?: return@launch
                if (source.ruleReview.isNullOrEmpty()) return@launch
                val rule = source.reviewRule
                val ruleText = ruleSelector(rule)
                if (ruleText.isNullOrBlank()) {
                    onError?.invoke()
                    return@launch
                }
                val result = WebBook.evalReviewActionAwait(
                    bookSource = source,
                    book = b,
                    bookChapter = chapter,
                    rule = ruleText,
                    paragraphIndex = paragraphIndex,
                    reviewId = reviewId,
                    contentText = content,
                    selected = selected,
                ).getOrThrow()
                if (!asBoolean(result)) return@launch
                onSuccess(result)
            } catch (e: Exception) {
                onError?.invoke()
            }
        }
    }

    private fun asBoolean(v: Any?): Boolean {
        if (v == null) return false
        if (v is Boolean) return v
        if (v is Number) return v.toDouble() != 0.0
        val s = v.toString().trim()
        return s.isNotEmpty() && !s.equals("false", ignoreCase = true)
    }
}
