package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.help.IntentData
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 评论列表对话框（BottomSheet 风格，内容委托 shared ReviewListDialog Composable，VM 委托 ReviewViewModelShared）
 * - paragraphIndex > 0：段评（点击段落尾部气泡进入）
 * - paragraphIndex == 0：章节级评论（阅读类页面菜单进入）
 * - paragraphIndex == -1：书籍级评论（详情页菜单进入，chapter 传 null）
 * book/chapter 通过 IntentData 透传，无 DB 二次查询
 *
 * 业务逻辑（分页抓取 / 点赞点踩 / 回复 / 删除 / 规则执行 / 互斥与回滚）全部下沉到 shared [ReviewViewModelShared]，
 * 本类仅保留:
 * - BottomSheetDialogFragment 壳 (透明容器 + 撑高 + 默认展开)
 * - [ReviewViewModelShared] 实例化与输入灌入
 * - Glide 图片渲染槽 (avatarSlot / imageSlot)
 * - 平台专属行为: alert 删除确认 / ReviewPost 路由发书评 / PhotoDialog 查看大图 / sendToClip 复制
 */
class ReviewListDialog() : BottomSheetDialogFragment() {

    constructor(
        book: Book,
        chapter: BookChapter?,
        paragraphIndex: Int,
        parentReview: Review? = null,
    ) : this() {
        arguments = Bundle().apply {
            putString("bookKey", IntentData.put(book))
            chapter?.let { putString("chapterKey", IntentData.put(it)) }
            putInt("paragraphIndex", paragraphIndex)
            parentReview?.let { putString("parentReviewKey", IntentData.put(it)) }
        }
    }

    private var viewModel: ReviewViewModelShared? = null
    private var replyToReview: Review? = null
    private var parentReview: Review? = null

    // 标题/输入提示等一次性 UI 文本（onViewCreated 中计算；listTitleText 由 totalCount 流驱动）
    private var titleText by mutableStateOf("")
    private var listTitleText by mutableStateOf("")
    private var repliesTitleText by mutableStateOf("")
    private var inputHintRes by mutableIntStateOf(R.string.review_post_hint)

    private fun launchPostActivity(replyPreview: String?) {
        val book = viewModel?.book ?: return
        AppNavigatorProviders.getOrNull()?.push(
            AppRoute.ReviewPost(book.toRouteRef(), replyPreview),
            resultKey = RouteResults.REVIEW_POST,
        )
    }

    /** 点击单条段评 → 弹回复输入框，replyTo 暂存为该条 */
    private fun onReviewClicked(review: Review) {
        replyToReview = review
        launchPostActivity(review.content)
    }

    private fun confirmDelete(review: Review) {
        alert(R.string.delete, R.string.confirm_delete_review) {
            // VM 已自动从 reviews 过滤被删条目，回调留空
            yesButton { viewModel?.delete(review) { } }
            noButton { }
        }
    }

    private fun openReplies(review: Review) {
        if (review.id.isNullOrBlank()) return
        val vm = viewModel ?: return
        val book = vm.book ?: return
        showDialogFragment(
            ReviewListDialog(book, vm.chapter, vm.paragraphIndex, review)
        )
    }

    override fun getTheme() =
        com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // 注入 Android actual Provider，供 commonMain AppTheme 通过 LocalXxx 取依赖
            val themeStoreProvider = remember { AndroidThemeStoreProvider() }
            val appConfigProvider = remember { AndroidAppConfigProvider() }
            val eventBusProvider = remember { AndroidEventBusProvider() }
            val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides themeStoreProvider,
                LocalAppConfigProvider provides appConfigProvider,
                LocalEventBusProvider provides eventBusProvider,
                LocalPreferenceStoreProvider provides preferenceStoreProvider,
            ) {
                AppTheme {
                    val vm = viewModel
                    // viewModel 在 onViewCreated 中赋值，compose 首帧在 onViewCreated 之后才组合
                    if (vm == null) return@AppTheme
                    // 接收 ReviewPost 路由回传的段评内容
                    LaunchedEffect(Unit) {
                        AppNavigatorProviders.getOrNull()?.results
                            ?.filter { it.key == RouteResults.REVIEW_POST }
                            ?.collect { result ->
                                val payload = result.payload as? RouteResultPayload.ReviewPost
                                    ?: return@collect
                                if (payload.content.isNotBlank()) submitPost(payload.content)
                            }
                    }
                    val reviews by vm.reviews.collectAsState()
                    val loading by vm.loading.collectAsState()
                    val hasMore by vm.hasMore.collectAsState()
                    val sortState by vm.sortState.collectAsState()
                    val votedIds by vm.votedIds.collectAsState()
                    val votedDownIds by vm.votedDownIds.collectAsState()
                    val expandedKeys by vm.expandedKeys.collectAsState()
                    // nestedScroll 桥接: 列表到顶后继续下拉交还 BottomSheetBehavior 收起
                    val lazyListModifier = Modifier.nestedScroll(rememberNestedScrollInteropConnection())
                    ReviewListDialog(
                        title = titleText,
                        parentReview = parentReview,
                        listTitleText = listTitleText,
                        repliesTitleText = repliesTitleText,
                        inputHint = getString(inputHintRes),
                        reviews = reviews,
                        sortState = sortState,
                        footerLoading = loading,
                        footerHasMore = hasMore,
                        expandedKeys = expandedKeys,
                        votedIds = votedIds,
                        votedDownIds = votedDownIds,
                        onDismiss = { dismiss() },
                        onLoadMore = { vm.loadMore() },
                        onChangeSort = { vm.changeSort(it) },
                        onReviewClick = { onReviewClicked(it) },
                        onReviewLongClick = { requireContext().sendToClip(it.content) },
                        onToggleExpand = { vm.toggleExpand(it) },
                        onVoteUp = { vm.voteUp(it) },
                        onVoteDown = { vm.voteDown(it) },
                        onDeleteClick = { confirmDelete(it) },
                        onOpenReplies = { openReplies(it) },
                        onPostClick = {
                            replyToReview = parentReview
                            launchPostActivity(parentReview?.content)
                        },
                        onAvatarClick = { url -> url?.let { showDialogFragment(PhotoDialog(it)) } },
                        onImageClick = { showDialogFragment(PhotoDialog(it)) },
                        avatarSlot = { url, modifier -> GlideAvatar(url, modifier) },
                        imageSlot = { url, modifier -> GlideImage(url, modifier) },
                        lazyListModifier = lazyListModifier,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 透明掉 BottomSheet 默认 white 容器，让内容自己的顶部圆角显示出来
        // 同时默认完全展开 + 撑高，跟图片设计一致
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val dm = resources.displayMetrics
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = (dm.heightPixels * 0.92f).toInt()
        }
        BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            // 默认 0.1 触发 hide 太敏感，改到 0.3
            hideFriction = 0.3f
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments
        val book = args?.getString("bookKey")?.let { IntentData.get<Book>(it) }
        if (book == null) {
            dismiss(); return
        }
        val chapter = args.getString("chapterKey")?.let { IntentData.get<BookChapter>(it) }
        val paragraphIndex = args.getInt("paragraphIndex")
        val parentReview = args.getString("parentReviewKey")?.let {
            IntentData.get<Review>(it)
        }
        this.parentReview = parentReview
        // 实例化 shared VM，灌入输入参数（对照原 ReviewViewModel 字段）
        val vm = ReviewViewModelShared(
            scope = viewLifecycleOwner.lifecycleScope,
            platform = AndroidReviewPlatform(requireContext()),
        )
        vm.book = book
        vm.chapter = chapter
        vm.paragraphIndex = paragraphIndex
        vm.replyReviewId = parentReview?.id
        vm.parentReview = parentReview
        viewModel = vm
        titleText = when {
            parentReview != null -> {
                inputHintRes = R.string.reply_review
                getString(R.string.review_replies_detail_title)
            }
            paragraphIndex <= 0 -> getString(R.string.review)
            else -> getString(R.string.review) + "  #" + paragraphIndex
        }
        if (parentReview != null) {
            vm.seedVote(parentReview)
            repliesTitleText =
                getString(R.string.review_replies_section_title, parentReview.replyCount)
        }
        // 段评总数：仅段评模式有 header，回复模式忽略；规则未配置时不显示数字
        viewLifecycleOwner.lifecycleScope.launch {
            vm.totalCount.collect { text ->
                listTitleText = if (text.isNullOrBlank()) ""
                else getString(R.string.review_list_section_title, text)
            }
        }
        vm.load()
    }

    private fun submitPost(text: String) {
        val target = replyToReview
        replyToReview = null
        viewModel?.reply(content = text, reviewId = target?.id, onHandled = handled@{
            target ?: return@handled
            // 乐观递增楼主回复数；列表由 VM 的 load() 重载刷新
            target.replyCount += 1
        })
    }

    // ---- Coil3 图片渲染槽 (注入 shared Composable 的 avatarSlot / imageSlot) ----

    /** 头像 (Coil3 imageView.load, tag 防重复加载闪烁) */
    @Composable
    private fun GlideAvatar(url: String?, modifier: Modifier) {
        AndroidView(
            factory = { ctx -> AppCompatImageView(ctx) },
            modifier = modifier,
            update = { iv ->
                // url 为 null 时 tag(null)==url，靠 drawable 空判定兜首帧占位图
                if (iv.tag != url || iv.drawable == null) {
                    iv.tag = url
                    iv.load(url) {
                        placeholder(R.drawable.ic_bottom_person)
                        error(R.drawable.ic_bottom_person)
                    }
                }
            },
        )
    }

    /** 评论配图 */
    @Composable
    private fun GlideImage(url: String, modifier: Modifier) {
        AndroidView(
            factory = { ctx ->
                AppCompatImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            },
            modifier = modifier,
            update = { iv ->
                if (iv.tag != url) {
                    iv.tag = url
                    val size = 120.dpToPx()
                    iv.load(url) { size(size, size) }
                }
            },
        )
    }
}
