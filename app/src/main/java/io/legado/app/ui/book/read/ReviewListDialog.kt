package io.legado.app.ui.book.read

import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Review
import io.legado.app.databinding.DialogReviewListBinding
import io.legado.app.databinding.ItemReviewBinding
import io.legado.app.databinding.ItemReviewListHeaderBinding
import io.legado.app.databinding.ItemReviewRepliesHeaderBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.gone
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO

// 用 payload 局部刷点赞/点踩 UI，避免 ImageLoader 重新拉头像导致整 item 闪烁
private const val PAYLOAD_VOTE = 1

/**
 * 评论列表对话框（BottomSheet 风格）
 * - paragraphIndex > 0：段评（点击段落尾部气泡进入）
 * - paragraphIndex == 0：章节级评论（阅读类页面菜单进入）
 * - paragraphIndex == -1：书籍级评论（详情页菜单进入，chapter 传 null）
 * book/chapter 通过 IntentData 透传，无 DB 二次查询
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

    private var _binding: DialogReviewListBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<ReviewViewModel>()
    private val loadMoreView by lazy { LoadMoreView(requireContext()) }
    private var replyToReview: Review? = null

    // 段评模式头部，sort 切换时要刷按钮文案，保留 binding 引用
    private var listHeaderBinding: ItemReviewListHeaderBinding? = null

    private val adapter by lazy { ReviewAdapter(requireContext()) }

    // 楼主原评论 binding，用于回复详情页"乐观点赞"局部刷新
    private var parentBinding: ItemReviewBinding? = null
    private var parentReview: Review? = null

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

    private fun confirmDelete(review: Review, onRemove: (id: String) -> Unit) {
        alert(R.string.delete, R.string.confirm_delete_review) {
            yesButton { viewModel.delete(review, onRemove) }
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
    ): View {
        _binding = DialogReviewListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // 透明掉 BottomSheet 默认 white 容器，让 LinearLayout 自己的顶部圆角显示出来
        // 同时默认完全展开 + 撑高，跟图片设计一致
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)
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
        binding.tvTitle.text = when {
            parentReview != null -> {
                binding.etInput.setHint(R.string.reply_review)
                getString(R.string.review_replies_detail_title)
            }
            paragraphIndex <= 0 -> getString(R.string.review)
            else -> getString(R.string.review) + "  #" + paragraphIndex
        }
        binding.btnClose.setOnClickListener { dismiss() }

        // 列表
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        // 回复模式：顶部加楼主原评论 + "全部回复·N" 分隔条
        if (parentReview != null) {
            adapter.addHeaderView { parent ->
                val b = ItemReviewBinding.inflate(layoutInflater, parent, false)
                parentBinding = b
                bindParentReview(b, parentReview)
                b
            }
            adapter.addHeaderView { parent ->
                val b = ItemReviewRepliesHeaderBinding.inflate(layoutInflater, parent, false)
                b.tvRepliesTitle.text = getString(
                    R.string.review_replies_section_title, parentReview.replyCount
                )
                b
            }
        } else {
            // 段评模式：顶部加"全部评论·N + 排序选择"分隔条
            // N 由请求级 totalCountRule 解析，通过 totalCountLiveData 灌进来
            // totalCount 与 hasMore 一起解析，回到主线程时 lambda 还没跑，
            // 由 observer 写文案就行，这里只设排序按钮和默认占位
            adapter.addHeaderView { parent ->
                val b = ItemReviewListHeaderBinding.inflate(layoutInflater, parent, false)
                listHeaderBinding = b
                b.btnSort.setText(sortLabelRes(viewModel.sort))
                b.btnSort.setOnClickListener { v -> showSortMenu(v) }
                b
            }
        }
        // 翻到底用 footer LoadMoreView 显示"加载中"
        // 首屏也复用它：默认 startLoad，等 loadMoreDoneLiveData 回来再决定 stop/noMore
        adapter.addFooterView { ViewLoadMoreBinding.bind(loadMoreView) }
        loadMoreView.startLoad()
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (recyclerView.canScrollVertically(1)) return
                if (adapter.getActualItemCount() == 0) return
                if (!loadMoreView.hasMore || loadMoreView.isLoading) return
                loadMoreView.startLoad()
                viewModel.loadMore()
            }
        })

        // 底部"输入栏"只是个触发器，点击后弹出真正的输入对话框
        // 回复详情页：默认就是回复楼主
        binding.etInput.setOnClickListener {
            replyToReview = parentReview
            val preview = parentReview?.content
            launchPostActivity(preview)
        }

        viewModel.reviewsLiveData.observe(viewLifecycleOwner) { list ->
            adapter.setItems(list)
        }
        // 翻页追加：notifyItemRangeInserted，不动已有 ViewHolder，避免抖动
        viewModel.appendReviewsLiveData.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) adapter.addItems(list)
        }
        // 段评总数：仅段评模式有 header，回复模式忽略；规则未配置时不显示数字
        viewModel.totalCountLiveData.observe(viewLifecycleOwner) { text ->
            val b = listHeaderBinding ?: return@observe
            b.tvListTitle.text = if (!text.isNullOrBlank()) {
                getString(R.string.review_list_section_title, text)
            } else ""
        }
        // footer 状态：首次 load 完成 + 每次 loadMore 完成都同步 hasMore，
        // 否则首页一返空/一返完，loadMoreView.hasMore 仍是初始 true，
        // 上拉到底会触发一次多余的第 2 页请求（回复页尤为明显）
        viewModel.loadMoreDoneLiveData.observe(viewLifecycleOwner) { hasMore ->
            if (hasMore) loadMoreView.stopLoad()
            else loadMoreView.noMore()
        }
        viewModel.load()
    }

    private fun submitPost(text: String) {
        val target = replyToReview
        replyToReview = null
        val targetIdx = target?.let { t ->
            adapter.getItems().indexOfFirst { it.id == t.id }
        } ?: -1
        viewModel.reply(content = text, reviewId = target?.id, onHandled = {
            target ?: return@reply
            target.replyCount += 1
            if (targetIdx >= 0) {
                adapter.notifyItemChanged(targetIdx + adapter.getHeaderCount())
            }
        })
    }

    private fun sortLabelRes(sort: Int): Int =
        if (sort == 1) R.string.review_sort_latest else R.string.review_sort_hot

    private fun showSortMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 0, 0, R.string.review_sort_hot)
            menu.add(0, 1, 1, R.string.review_sort_latest)
            setOnMenuItemClickListener { mi ->
                val newSort = mi.itemId
                if (newSort != viewModel.sort) {
                    viewModel.sort = newSort
                    listHeaderBinding?.btnSort?.setText(sortLabelRes(newSort))
                    // 立刻清空列表，footer 重新转起来当首屏 loading
                    adapter.setItems(emptyList())
                    loadMoreView.startLoad()
                    viewModel.load()
                }
                true
            }
        }.show()
    }

    /** 回复详情页 header：渲染楼主原评论；vote 与图片复用 adapter 的同一套逻辑。 */
    private fun bindParentReview(b: ItemReviewBinding, item: Review) {
        b.tvName.text = item.name.orEmpty()
        b.tvContent.text = item.content
        b.tvContent.maxLines = Int.MAX_VALUE
        b.tvPostTime.text = item.postTime.orEmpty()
        b.tvExtra.text = item.extra.orEmpty()
        adapter.captureDefaultVoteColors(b.tvVoteCount.textColors)
        adapter.renderVoteState(b, item)
        ImageLoader.load(requireContext(), item.avatar)
            .placeholder(R.drawable.ic_bottom_person)
            .error(R.drawable.ic_bottom_person)
            .into(b.ivAvatar)
        b.ivAvatar.setOnClickListener {
            item.avatar?.takeIf { it.isNotBlank() }?.let {
                showDialogFragment(PhotoDialog(it))
            }
        }
        b.tvExpand.gone()
        b.tvReplyPreview.gone()
        b.btnMenu.gone()
        val voteUpClick = View.OnClickListener {
            val id = item.id ?: return@OnClickListener
            val target = adapter.toggleVoteUp(id)
            adapter.renderVoteState(b, item)
            viewModel.voteUp(item, target) {
                adapter.revertVoteUp(id)
                adapter.renderVoteState(b, item)
            }
        }
        b.btnVoteUp.setOnClickListener(voteUpClick)
        b.tvVoteCount.setOnClickListener(voteUpClick)
        b.btnVoteDown.setOnClickListener {
            val id = item.id ?: return@setOnClickListener
            val target = adapter.toggleVoteDown(id)
            adapter.renderVoteState(b, item)
            viewModel.voteDown(item, target) {
                adapter.revertVoteDown(id)
                adapter.renderVoteState(b, item)
            }
        }
        adapter.bindImages(b, item.images)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listHeaderBinding = null
        parentBinding = null
        parentReview = null
        _binding = null
    }

    inner class ReviewAdapter(context: Context) :
        RecyclerAdapter<Review, ItemReviewBinding>(context) {

        // 展开态、点赞/点踩态都用 review.id 跟踪：稳定，与重载/翻页解耦
        // id 缺失的条目（极少数老书源）始终走折叠态、无法点赞，是可接受的退化
        // 无 id 条目使用 review.content 的 hashCode 作为 key（不会与正常 id 冲突，因为前者带 #）
        private val expanded = HashSet<String>()
        private val voted = HashSet<String>()
        private val votedDown = HashSet<String>()

        private val voteUpColor = ContextCompat.getColor(context, R.color.review_voted)
        private var defaultVoteCountColors: ColorStateList? = null

        private fun expandKey(item: Review): String = item.id ?: "#${item.content.hashCode()}"

        /** 父项 binding 也用这套色；首次进来时由父项捕获到默认 textColors */
        fun captureDefaultVoteColors(colors: ColorStateList) {
            if (defaultVoteCountColors == null) defaultVoteCountColors = colors
        }

        fun toggleVoteUp(id: String): Boolean {
            val target = !voted.contains(id)
            if (target) {
                voted.add(id)
                votedDown.remove(id) // 互斥
            } else voted.remove(id)
            return target
        }

        fun toggleVoteDown(id: String): Boolean {
            val target = !votedDown.contains(id)
            if (target) {
                votedDown.add(id)
                voted.remove(id) // 互斥
            } else votedDown.remove(id)
            return target
        }

        fun revertVoteUp(id: String) {
            if (voted.contains(id)) voted.remove(id) else voted.add(id)
            notifyItemByReviewId(id)
        }

        fun revertVoteDown(id: String) {
            if (votedDown.contains(id)) votedDown.remove(id) else votedDown.add(id)
            notifyItemByReviewId(id)
        }

        override fun getViewBinding(parent: ViewGroup): ItemReviewBinding {
            return ItemReviewBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReviewBinding,
            item: Review,
            payloads: MutableList<Any>
        ) {
            // 仅局部刷新点赞/点踩
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_VOTE }) {
                renderVoteState(binding, item)
                return
            }
            binding.run {
                tvName.text = item.name.orEmpty()
                tvContent.text = item.content
                tvPostTime.text = item.postTime.orEmpty()
                tvExtra.text = item.extra.orEmpty()
                captureDefaultVoteColors(tvVoteCount.textColors)
                renderVoteState(binding, item)
                if (item.replyCount > 0) {
                    tvReplyPreview.text =
                        context.getString(R.string.review_replies_count, item.replyCount)
                    tvReplyPreview.visible()
                } else {
                    tvReplyPreview.gone()
                }
                ImageLoader.load(context, item.avatar)
                    .placeholder(R.drawable.ic_bottom_person)
                    .error(R.drawable.ic_bottom_person)
                    .into(ivAvatar)
                bindImages(binding, item.images)
                // 展开/折叠
                val isExpanded = expanded.contains(expandKey(item))
                tvContent.maxLines = if (isExpanded) Int.MAX_VALUE else 6
                tvExpand.setText(
                    if (isExpanded) R.string.review_collapse else R.string.review_expand
                )
                // 折叠态下检查文本是否真的被省略；只在被截断或已经展开时显示按钮
                tvContent.post {
                    val layout = tvContent.layout ?: return@post
                    val truncated = layout.lineCount > 0 &&
                        layout.getEllipsisCount(layout.lineCount - 1) > 0
                    tvExpand.visibility = if (isExpanded || truncated) View.VISIBLE else View.GONE
                }
            }
        }

        fun renderVoteState(binding: ItemReviewBinding, item: Review) {
            val id = item.id
            val isVoted = id != null && voted.contains(id)
            val isVotedDown = id != null && votedDown.contains(id)
            val displayVoteCount = item.voteUpCount + if (isVoted) 1 else 0
            binding.tvVoteCount.text =
                if (displayVoteCount > 0) displayVoteCount.toString()
                else context.getString(R.string.vote_up)
            if (isVoted) {
                binding.tvVoteCount.setTextColor(voteUpColor)
                binding.btnVoteUp.setImageResource(R.drawable.ic_review_thumb_up_filled)
            } else {
                defaultVoteCountColors?.let { binding.tvVoteCount.setTextColor(it) }
                binding.btnVoteUp.setImageResource(R.drawable.ic_review_thumb_up)
            }
            binding.btnVoteDown.setImageResource(
                if (isVotedDown) R.drawable.ic_review_thumb_down_filled
                else R.drawable.ic_review_thumb_down
            )
        }

        private fun notifyItemByReviewId(reviewId: String) {
            for (i in 0 until getActualItemCount()) {
                if (getItem(i)?.id == reviewId) {
                    notifyItemChanged(i + getHeaderCount(), PAYLOAD_VOTE)
                    return
                }
            }
        }

        fun removeReviewById(reviewId: String) {
            for (i in 0 until getActualItemCount()) {
                if (getItem(i)?.id == reviewId) {
                    removeItem(i)
                    return
                }
            }
        }

        fun bindImages(binding: ItemReviewBinding, images: List<String>) {
            val container = binding.llImages
            container.removeAllViews()
            if (images.isEmpty()) {
                binding.hsvImages.gone()
                return
            }
            binding.hsvImages.visible()
            val density = context.resources.displayMetrics.density
            val size = (120 * density).toInt()
            val gap = (4 * density).toInt()
            images.forEachIndexed { i, url ->
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (i > 0) marginStart = gap
                    }
                    setOnClickListener { showDialogFragment(PhotoDialog(url)) }
                }
                container.addView(iv)
                ImageLoader.load(context, url)
                    .override(size, size)
                    .into(iv)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReviewBinding) {
            // 整条空白区/正文点击 → 回复；长按 → 复制内容
            val clickToReply = View.OnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { onReviewClicked(it) }
            }
            val longClickToCopy = View.OnLongClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@OnLongClickListener false
                context.sendToClip(item.content)
                true
            }
            binding.root.setOnClickListener(clickToReply)
            binding.root.setOnLongClickListener(longClickToCopy)
            binding.tvContent.setOnClickListener(clickToReply)
            binding.tvContent.setOnLongClickListener(longClickToCopy)
            binding.tvExpand.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
                val key = expandKey(item)
                if (expanded.contains(key)) expanded.remove(key) else expanded.add(key)
                notifyItemChanged(holder.layoutPosition)
            }
            binding.ivAvatar.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.avatar
                    ?.takeIf { it.isNotBlank() }
                    ?.let { showDialogFragment(PhotoDialog(it)) }
            }
            binding.btnMenu.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
                PopupMenu(context, it).apply {
                    menu.add(0, 1, 1, R.string.delete)
                    setOnMenuItemClickListener { menuItem ->
                        if (menuItem.itemId == 1) {
                            confirmDelete(item) { id -> removeReviewById(id) }
                        }
                        true
                    }
                }.show()
            }
            val voteUpClick = View.OnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@OnClickListener
                val id = item.id ?: return@OnClickListener
                val target = toggleVoteUp(id)
                notifyItemChanged(holder.layoutPosition, PAYLOAD_VOTE)
                viewModel.voteUp(item, target) { revertVoteUp(id) }
            }
            binding.btnVoteUp.setOnClickListener(voteUpClick)
            binding.tvVoteCount.setOnClickListener(voteUpClick)
            binding.btnVoteDown.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
                val id = item.id ?: return@setOnClickListener
                val target = toggleVoteDown(id)
                notifyItemChanged(holder.layoutPosition, PAYLOAD_VOTE)
                viewModel.voteDown(item, target) { revertVoteDown(id) }
            }
            binding.tvReplyPreview.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { openReplies(it) }
            }
        }
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
                // 首页也同步 hasMore，避免 loadMoreView.hasMore 残留 true
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
            val rule = source.ruleReview ?: return
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
