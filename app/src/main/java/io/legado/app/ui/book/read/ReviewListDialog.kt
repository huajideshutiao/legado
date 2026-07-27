package io.legado.app.ui.book.read

import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import io.legado.app.model.webBook.WebBook
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
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO

/**
 * 评论列表对话框（BottomSheet 风格，内容委托 shared ReviewListDialog Composable）
 * - paragraphIndex > 0：段评（点击段落尾部气泡进入）
 * - paragraphIndex == 0：章节级评论（阅读类页面菜单进入）
 * - paragraphIndex == -1：书籍级评论（详情页菜单进入，chapter 传 null）
 * book/chapter 通过 IntentData 透传，无 DB 二次查询
 *
 * UI 由 shared 模块 ReviewListDialog Composable 提供，本类仅保留:
 * - BottomSheetDialogFragment 壳 (透明容器 + 撑高 + 默认展开)
 * - ReviewViewModel (拉取书评列表 / 点赞点踩 / 回复 / 删除)
 * - 状态管理 (reviews / expandedKeys / votedIds / footer 状态)
 * - Glide 图片渲染槽 (avatarSlot / imageSlot)
 * - 平台专属行为: alert 删除确认 / ReviewPostActivity 发书评 / PhotoDialog 查看大图 / sendToClip 复制
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

    private val viewModel by viewModels<ReviewViewModel>()
    private var replyToReview: Review? = null
    private var parentReview: Review? = null

    // ---- 原 adapter/loadMoreView/binding 的 UI 态，改为 Compose state ----
    // neverEqualPolicy：回复数是对 Review 原地自增后 toList()，结构相等也要触发重组
    private var reviews by mutableStateOf<List<Review>>(emptyList(), neverEqualPolicy())
    private var titleText by mutableStateOf("")
    private var listTitleText by mutableStateOf("")
    private var repliesTitleText by mutableStateOf("")
    private var sortState by mutableIntStateOf(0)
    private var inputHintRes by mutableIntStateOf(R.string.review_post_hint)

    // footer 三态对照 LoadMoreView：loading 转圈 / stop 空白 / noMore 显示 bottom_line
    private var footerLoading by mutableStateOf(true)
    private var footerHasMore by mutableStateOf(true)

    // 展开态、点赞/点踩态都用 review.id 跟踪：稳定，与重载/翻页解耦
    // id 缺失的条目（极少数老书源）始终走折叠态、无法点赞，是可接受的退化
    // 无 id 条目使用 review.content 的 hashCode 作为 key（不会与正常 id 冲突，因为前者带 #）
    private var expandedKeys by mutableStateOf(emptySet<String>())
    private var votedIds by mutableStateOf(emptySet<String>())
    private var votedDownIds by mutableStateOf(emptySet<String>())
    // 已用书源初始状态种子过的 id；保证用户点击翻转后不被 item 旧值回灌覆盖
    private val voteSeeded = HashSet<String>()

    // 段评输入面板:Activity 模拟 BottomSheet,通过 launcher 回写内容
    private val reviewPostLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val content = result.data
                ?.getStringExtra(ReviewPostActivity.RESULT_CONTENT)
                .orEmpty()
            if (content.isNotBlank()) submitPost(content)
        }
    }

    private fun launchPostActivity(replyPreview: String?) {
        val intent = Intent(requireContext(), ReviewPostActivity::class.java)
        replyPreview?.let { intent.putExtra(ReviewPostActivity.EXTRA_REPLY_PREVIEW, it) }
        reviewPostLauncher.launch(intent)
    }

    /** 点击单条段评 → 弹回复输入框，replyTo 暂存为该条 */
    private fun onReviewClicked(review: Review) {
        replyToReview = review
        launchPostActivity(review.content)
    }

    private fun confirmDelete(review: Review) {
        alert(R.string.delete, R.string.confirm_delete_review) {
            yesButton { viewModel.delete(review) { id -> removeReviewById(id) } }
            noButton { }
        }
    }

    private fun openReplies(review: Review) {
        if (review.id.isNullOrBlank()) return
        val book = viewModel.book ?: return
        showDialogFragment(
            ReviewListDialog(book, viewModel.chapter, viewModel.paragraphIndex, review)
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
                        footerLoading = footerLoading,
                        footerHasMore = footerHasMore,
                        expandedKeys = expandedKeys,
                        votedIds = votedIds,
                        votedDownIds = votedDownIds,
                        onDismiss = { dismiss() },
                        onLoadMore = {
                            // 立刻置 loading 防止 snapshotFlow 重复触发, 对照原 footerLoading = true
                            footerLoading = true
                            viewModel.loadMore()
                        },
                        onChangeSort = { changeSort(it) },
                        onReviewClick = { onReviewClicked(it) },
                        onReviewLongClick = { requireContext().sendToClip(it.content) },
                        onToggleExpand = { key ->
                            expandedKeys = if (expandedKeys.contains(key)) {
                                expandedKeys - key
                            } else expandedKeys + key
                        },
                        onVoteUp = { voteUp(it) },
                        onVoteDown = { voteDown(it) },
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
        viewModel.book = book
        viewModel.chapter = chapter
        viewModel.paragraphIndex = paragraphIndex
        viewModel.replyReviewId = parentReview?.id
        sortState = viewModel.sort
        titleText = when {
            parentReview != null -> {
                inputHintRes = R.string.reply_review
                getString(R.string.review_replies_detail_title)
            }
            paragraphIndex <= 0 -> getString(R.string.review)
            else -> getString(R.string.review) + "  #" + paragraphIndex
        }
        if (parentReview != null) {
            seedVoteFromItem(parentReview)
            repliesTitleText =
                getString(R.string.review_replies_section_title, parentReview.replyCount)
        }

        viewModel.reviewsLiveData.observe(viewLifecycleOwner) { list ->
            list.forEach { seedVoteFromItem(it) }
            reviews = list
        }
        // 翻页追加：只拼接新批次，已有条目状态不动
        viewModel.appendReviewsLiveData.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                list.forEach { seedVoteFromItem(it) }
                reviews = reviews + list
            }
        }
        // 段评总数：仅段评模式有 header，回复模式忽略；规则未配置时不显示数字
        viewModel.totalCountLiveData.observe(viewLifecycleOwner) { text ->
            listTitleText = if (!text.isNullOrBlank()) {
                getString(R.string.review_list_section_title, text)
            } else ""
        }
        // footer 状态：首次 load 完成 + 每次 loadMore 完成都同步 hasMore，
        // 否则上拉到底会触发一次多余的第 2 页请求（回复页尤为明显）
        viewModel.loadMoreDoneLiveData.observe(viewLifecycleOwner) { hasMore ->
            footerLoading = false
            footerHasMore = hasMore
        }
        viewModel.load()
    }

    private fun submitPost(text: String) {
        val target = replyToReview
        replyToReview = null
        viewModel.reply(content = text, reviewId = target?.id, onHandled = handled@{
            target ?: return@handled
            target.replyCount += 1
            // 目标条目在列表中时靠列表重组刷新回复数
            reviews = reviews.toList()
        })
    }

    private fun changeSort(newSort: Int) {
        if (newSort == viewModel.sort) return
        viewModel.sort = newSort
        sortState = newSort
        // 立刻清空列表，footer 重新转起来当首屏 loading
        reviews = emptyList()
        footerLoading = true
        viewModel.load()
    }

    // ---- 原 ReviewAdapter 的展开/点赞状态逻辑 ----

    /**
     * 把书源返回的 voted/votedDown 初始态首次灌入本地集合；同一 id 只灌一次。
     * 书源解析的 voteUpCount 一般是页面上的总数（已含当前用户的点赞），
     * 而显示按 voteUpCount + (isVoted?1:0) 计算，
     * 所以这里把 item.voteUpCount 抹去"自己那 1 票"，让基数永远是"不含自己"。
     */
    private fun seedVoteFromItem(item: Review) {
        val id = item.id ?: return
        if (!voteSeeded.add(id)) return
        if (item.voted) {
            votedIds = votedIds + id
            if (item.voteUpCount > 0) item.voteUpCount -= 1
        }
        if (item.votedDown) votedDownIds = votedDownIds + id
    }

    private fun toggleVoteUp(id: String): Boolean {
        val target = !votedIds.contains(id)
        if (target) {
            votedIds = votedIds + id
            votedDownIds = votedDownIds - id // 互斥
        } else votedIds = votedIds - id
        return target
    }

    private fun toggleVoteDown(id: String): Boolean {
        val target = !votedDownIds.contains(id)
        if (target) {
            votedDownIds = votedDownIds + id
            votedIds = votedIds - id // 互斥
        } else votedDownIds = votedDownIds - id
        return target
    }

    private fun revertVoteUp(id: String) {
        votedIds = if (votedIds.contains(id)) votedIds - id else votedIds + id
    }

    private fun revertVoteDown(id: String) {
        votedDownIds = if (votedDownIds.contains(id)) votedDownIds - id else votedDownIds + id
    }

    private fun voteUp(item: Review) {
        val id = item.id ?: return
        val target = toggleVoteUp(id)
        viewModel.voteUp(item, !target) { revertVoteUp(id) }
    }

    private fun voteDown(item: Review) {
        val id = item.id ?: return
        val target = toggleVoteDown(id)
        viewModel.voteDown(item, !target) { revertVoteDown(id) }
    }

    private fun removeReviewById(reviewId: String) {
        reviews = reviews.filterNot { it.id == reviewId }
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

    class ReviewViewModel(application: Application) : BaseViewModel(application) {

        // book/chapter 由 onViewCreated 从 IntentData 直接灌入, 不查 DB
        var book: Book? = null
        var chapter: BookChapter? = null
        var paragraphIndex: Int = 0
        var replyReviewId: String? = null

        // 评论排序：0=最热，1=最新；仅列表用，注入到 reviewUrl 的 {{sort}}
        var sort: Int = 0

        private var currentPage = 1
        private var loading = false
        private var hasMore = true
        val reviewsLiveData = MutableLiveData<List<Review>>(emptyList())

        // 翻页追加的"新批次"：只携带新增的那一页，避免观察方按整表重绘
        val appendReviewsLiveData = MutableLiveData<List<Review>>()

        // 仅 loadMore 完成时发：true=还有更多，false=没了
        val loadMoreDoneLiveData = MutableLiveData<Boolean>()

        // 评论总数文本（仅首页发，翻页响应里的总数与首页等价无需重发）
        val totalCountLiveData = MutableLiveData<String?>()

        fun load() {
            currentPage = 1
            hasMore = true
            fetchPage(append = false)
        }

        fun loadMore() {
            if (!hasMore || loading) return
            currentPage += 1
            fetchPage(append = true)
        }

        private fun fetchPage(append: Boolean) {
            val b = book ?: run {
                context.toastOnUi("无当前书籍"); return
            }
            val source = b.getBookSource() ?: run {
                context.toastOnUi("无书源"); return
            }
            val ch = chapter
            val page = currentPage
            loading = true
            execute(context = IO) {
                val rid = replyReviewId
                if (rid != null) {
                    WebBook.getReviewRepliesAwait(
                        source, b, ch, paragraphIndex, rid, page
                    ).getOrThrow()
                } else {
                    WebBook.getReviewListAwait(source, b, ch, paragraphIndex, page, sort)
                        .getOrThrow()
                }
            }.onSuccess { result ->
                val reviews = result.reviews
                hasMore = result.hasNextPage
                if (reviews.isEmpty()) {
                    if (!append) reviewsLiveData.value = emptyList()
                } else {
                    if (append) appendReviewsLiveData.value = reviews
                    else reviewsLiveData.value = reviews
                }
                // 评论总数仅首页发（首页 = !append），避免翻页/排序切换覆盖同一数字
                if (!append && replyReviewId == null) {
                    totalCountLiveData.value = result.totalCount
                }
                // 首页也同步 hasMore，避免残留 true
                // 触发多余的下一页请求（回复页通常一页就完）
                loadMoreDoneLiveData.value = hasMore
            }.onError {
                if (append) currentPage -= 1
                context.toastOnUi("评论加载失败: ${it.localizedMessage}")
                if (!append) reviewsLiveData.value = emptyList()
                loadMoreDoneLiveData.value = true
            }.onFinally {
                loading = false
            }
        }

        fun reply(content: String, reviewId: String?, onHandled: (() -> Unit)? = null) = runRule(
            { it.replyRule }, content = content, reviewId = reviewId, reloadOnSuccess = true,
            onSuccess = { onHandled?.invoke() }
        )

        fun voteUp(review: Review, selected: Boolean, onError: () -> Unit) {
            val id = review.id ?: return
            runRule({ it.voteUpRule }, reviewId = id, selected = selected, onError = onError)
        }

        fun voteDown(review: Review, selected: Boolean, onError: () -> Unit) {
            val id = review.id ?: return
            runRule({ it.voteDownRule }, reviewId = id, selected = selected, onError = onError)
        }

        // onRemoved: JS 返回 true 时回调，dialog 据此本地移除；其他返回值走 load() 整页重载
        fun delete(review: Review, onRemoved: (id: String) -> Unit) {
            val id = review.id ?: return
            runRule({ it.deleteRule }, reviewId = id, onSuccess = { onRemoved(id) })
        }

        private fun asBoolean(v: Any?): Boolean {
            if (v == null) return false
            if (v is Boolean) return v
            if (v is Number) return v.toDouble() != 0.0
            val s = v.toString().trim()
            return s.isNotEmpty() && !s.equals("false", ignoreCase = true)
        }

        private fun runRule(
            ruleSelector: (io.legado.app.data.entities.rule.ReviewRule) -> String?,
            content: String? = null,
            reviewId: String? = null,
            selected: Boolean? = null,
            reloadOnSuccess: Boolean = false,
            onSuccess: ((Any?) -> Unit)? = null,
            onError: (() -> Unit)? = null,
        ) {
            val b = book ?: return
            val source = b.getBookSource() ?: return
            if (source.ruleReview.isNullOrEmpty()) return
            val rule = source.reviewRule
            val ruleText = ruleSelector(rule)
            if (ruleText.isNullOrBlank()) {
                context.toastOnUi("书源未配置此操作规则")
                onError?.invoke()
                return
            }
            val ch = chapter
            execute(context = IO) {
                WebBook.evalReviewActionAwait(
                    bookSource = source,
                    book = b,
                    bookChapter = ch,
                    rule = ruleText,
                    paragraphIndex = paragraphIndex,
                    reviewId = reviewId,
                    contentText = content,
                    selected = selected,
                ).getOrThrow()
            }.onSuccess { result ->
                // 仅返回 true 类值表示规则"已处理"，走成功路径；
                // 其它返回值表示规则"不想处理"，静默放过（不视为失败也不触发 onSuccess）；
                // 只有抛异常才算失败，由下面的 onError 处理。
                if (!asBoolean(result)) return@onSuccess
                if (reloadOnSuccess) load()
                onSuccess?.invoke(result)
            }.onError {
                context.toastOnUi("操作失败: ${it.localizedMessage}")
                onError?.invoke()
            }
        }
    }
}
