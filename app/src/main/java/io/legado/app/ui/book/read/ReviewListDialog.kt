package io.legado.app.ui.book.read

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.toColorInt
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
import io.legado.app.data.appDb
import io.legado.app.data.entities.Review
import io.legado.app.databinding.DialogReviewListBinding
import io.legado.app.databinding.ItemReviewBinding
import io.legado.app.databinding.ItemReviewListHeaderBinding
import io.legado.app.databinding.ItemReviewRepliesHeaderBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.IntentData
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.model.ReadBook
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
 * 段评列表对话框（BottomSheet 风格）
 */
class ReviewListDialog() : BottomSheetDialogFragment() {

    constructor(
        chapterIndex: Int,
        paragraphIndex: Int,
        parentReview: Review? = null,
    ) : this() {
        arguments = Bundle().apply {
            putInt("chapterIndex", chapterIndex)
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

    // 楼主原评论的点赞/点踩态（仅回复详情页用）；点赞乐观更新走这里
    private var parentVoted: Boolean = false
    private var parentVotedDown: Boolean = false
    private val parentVoteUpColor = "#E53935".toColorInt()
    private var parentDefaultVoteCountColors: android.content.res.ColorStateList? = null
    private val adapter by lazy { ReviewAdapter(requireContext()) }

    /** 点击单条段评 → 弹回复输入框，replyTo 暂存为该条 */
    private fun onReviewClicked(review: Review) {
        replyToReview = review
        showDialogFragment(ReviewPostDialog(replyPreview = review.content))
    }

    private fun confirmDelete(review: Review, onRemove: (id: String) -> Unit) {
        alert(R.string.delete, R.string.confirm_delete_review) {
            yesButton { viewModel.delete(review, onRemove) }
            noButton { }
        }
    }

    private fun openReplies(review: Review) {
        if (review.id.isNullOrBlank()) return
        showDialogFragment(
            ReviewListDialog(viewModel.chapterIndex, viewModel.paragraphIndex, review)
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
        val chapterIndex = arguments?.getInt("chapterIndex") ?: 0
        val paragraphIndex = arguments?.getInt("paragraphIndex") ?: 0
        val parentReview = arguments?.getString("parentReviewKey")?.let {
            IntentData.get<Review>(it)
        }
        viewModel.chapterIndex = chapterIndex
        viewModel.paragraphIndex = paragraphIndex
        viewModel.replyReviewId = parentReview?.id
        binding.tvTitle.text = when {
            parentReview != null -> {
                binding.etInput.setHint(R.string.reply_review)
                getString(R.string.review_replies_detail_title)
            }
            paragraphIndex == 0 -> getString(R.string.review)
            else -> getString(R.string.review) + "  #" + paragraphIndex
        }
        binding.btnClose.setOnClickListener { dismiss() }

        // 接收输入对话框返回的内容并提交（输入对话框走 childFragmentManager）
        childFragmentManager.setFragmentResultListener(
            ReviewPostDialog.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val content = bundle.getString(ReviewPostDialog.KEY_CONTENT).orEmpty()
            if (content.isNotBlank()) submitPost(content)
        }

        // 列表
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        // 回复模式：顶部加楼主原评论 + "全部回复·N" 分隔条
        if (parentReview != null) {
            adapter.addHeaderView { parent ->
                val b = ItemReviewBinding.inflate(layoutInflater, parent, false)
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
        adapter.addFooterView { ViewLoadMoreBinding.bind(loadMoreView) }
        loadMoreView.stopLoad()
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
            showDialogFragment(ReviewPostDialog(replyPreview = preview))
        }

        // 中央转圈：仅段评模式下、列表为空时显示；回复详情页有顶部楼主可见，无需转圈
        viewModel.reviewsLiveData.observe(viewLifecycleOwner) { list ->
            adapter.setItems(list)
            if (list.isEmpty() && parentReview == null) binding.rotateLoading.visible()
            else binding.rotateLoading.gone()
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
        viewModel.reply(content = text, reviewId = replyToReview?.id)
        replyToReview = null
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
                    // 立刻清空列表 + 显示转圈，让用户感知到正在重新加载
                    adapter.setItems(emptyList())
                    binding.rotateLoading.visible()
                    viewModel.load()
                }
                true
            }
        }.show()
    }

    /** 回复详情页 header：渲染楼主原评论，禁用"查看回复"入口（已经在详情页内）。 */
    private fun bindParentReview(b: ItemReviewBinding, item: Review) {
        b.tvName.text = item.name.orEmpty()
        b.tvContent.text = item.content
        b.tvContent.maxLines = Int.MAX_VALUE
        b.tvPostTime.text = item.postTime.orEmpty()
        b.tvExtra.text = item.extra.orEmpty()
        parentDefaultVoteCountColors = b.tvVoteCount.textColors
        renderParentVoteState(b, item)
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
            if (item.id == null) return@OnClickListener
            val target = !parentVoted
            parentVoted = target
            if (target) parentVotedDown = false
            renderParentVoteState(b, item)
            viewModel.voteUp(item, target) {
                parentVoted = !parentVoted
                renderParentVoteState(b, item)
            }
        }
        b.btnVoteUp.setOnClickListener(voteUpClick)
        b.tvVoteCount.setOnClickListener(voteUpClick)
        b.btnVoteDown.setOnClickListener {
            if (item.id == null) return@setOnClickListener
            val target = !parentVotedDown
            parentVotedDown = target
            if (target) parentVoted = false
            renderParentVoteState(b, item)
            viewModel.voteDown(item, target) {
                parentVotedDown = !parentVotedDown
                renderParentVoteState(b, item)
            }
        }
        // 图片
        b.llImages.removeAllViews()
        if (item.images.isEmpty()) {
            b.hsvImages.gone()
        } else {
            b.hsvImages.visible()
            val density = resources.displayMetrics.density
            val size = (120 * density).toInt()
            val gap = (4 * density).toInt()
            item.images.forEachIndexed { i, url ->
                val iv = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (i > 0) marginStart = gap
                    }
                    setOnClickListener { showDialogFragment(PhotoDialog(url)) }
                }
                b.llImages.addView(iv)
                ImageLoader.load(requireContext(), url).override(size, size).into(iv)
            }
        }
    }

    private fun renderParentVoteState(b: ItemReviewBinding, item: Review) {
        val display = item.voteUpCount + if (parentVoted) 1 else 0
        b.tvVoteCount.text = if (display > 0) display.toString()
        else getString(R.string.vote_up)
        if (parentVoted) {
            b.tvVoteCount.setTextColor(parentVoteUpColor)
            b.btnVoteUp.setImageResource(R.drawable.ic_review_thumb_up_filled)
        } else {
            parentDefaultVoteCountColors?.let { b.tvVoteCount.setTextColor(it) }
            b.btnVoteUp.setImageResource(R.drawable.ic_review_thumb_up)
        }
        b.btnVoteDown.setImageResource(
            if (parentVotedDown) R.drawable.ic_review_thumb_down_filled
            else R.drawable.ic_review_thumb_down
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listHeaderBinding = null
        _binding = null
    }

    inner class ReviewAdapter(context: Context) :
        RecyclerAdapter<Review, ItemReviewBinding>(context) {

        // 展开态用 review 实例 identity 跟踪；新一轮 load() 后这些实例会被替换，状态自然清掉
        private val expanded = java.util.IdentityHashMap<Review, Boolean>()

        // 点赞/点踩态用 review.id（稳定）跟踪；翻页/重载后保留
        private val voted = HashSet<String>()
        private val votedDown = HashSet<String>()

        private val voteUpColor = "#E53935".toColorInt()
        private var defaultVoteCountColors: android.content.res.ColorStateList? = null

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
                if (defaultVoteCountColors == null) {
                    defaultVoteCountColors = tvVoteCount.textColors
                }
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
                bindImages(binding, item)
                // 展开/折叠
                val isExpanded = expanded[item] == true
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

        private fun renderVoteState(binding: ItemReviewBinding, item: Review) {
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

        /**
         * 操作失败时回滚乐观更新。
         * kind: "up" / "down"
         */
        fun revertVote(reviewId: String, kind: String) {
            when (kind) {
                "up" -> if (voted.contains(reviewId)) voted.remove(reviewId)
                else voted.add(reviewId)

                "down" -> if (votedDown.contains(reviewId)) votedDown.remove(reviewId)
                else votedDown.add(reviewId)
            }
            notifyItemByReviewId(reviewId)
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

        private fun bindImages(binding: ItemReviewBinding, item: Review) {
            val container = binding.llImages
            container.removeAllViews()
            val images = item.images
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
                expanded[item] = expanded[item] != true
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
                val target = !voted.contains(id)
                if (target) {
                    voted.add(id)
                    votedDown.remove(id) // 互斥
                } else voted.remove(id)
                notifyItemChanged(holder.layoutPosition, PAYLOAD_VOTE)
                viewModel.voteUp(item, target) { revertVote(id, "up") }
            }
            binding.btnVoteUp.setOnClickListener(voteUpClick)
            binding.tvVoteCount.setOnClickListener(voteUpClick)
            binding.btnVoteDown.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
                val id = item.id ?: return@setOnClickListener
                val target = !votedDown.contains(id)
                if (target) {
                    votedDown.add(id)
                    voted.remove(id) // 互斥
                } else votedDown.remove(id)
                notifyItemChanged(holder.layoutPosition, PAYLOAD_VOTE)
                viewModel.voteDown(item, target) { revertVote(id, "down") }
            }
            binding.tvReplyPreview.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { openReplies(it) }
            }
        }
    }

    class ReviewViewModel(application: Application) : BaseViewModel(application) {

        var chapterIndex: Int = 0
        var paragraphIndex: Int = 0
        var replyReviewId: String? = null

        // 段评排序：0=最热，1=最新；仅段评列表用，注入到 reviewUrl 的 {{sort}}
        var sort: Int = 0

        private var currentPage = 1
        private var loading = false
        private var hasMore = true
        val reviewsLiveData = MutableLiveData<List<Review>>(emptyList())

        // 翻页追加的"新批次"：只携带新增的那一页，避免观察方按整表重绘
        val appendReviewsLiveData = MutableLiveData<List<Review>>()

        // 仅 loadMore 完成时发：true=还有更多，false=没了
        val loadMoreDoneLiveData = MutableLiveData<Boolean>()

        // 段评总数文本（仅首页发，翻页响应里的总数与首页等价无需重发）
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
            val book = ReadBook.book ?: run {
                context.toastOnUi("无当前书籍"); return
            }
            val source = ReadBook.bookSource ?: run {
                context.toastOnUi("无书源"); return
            }
            val page = currentPage
            loading = true
            execute(context = IO) {
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                val rid = replyReviewId
                if (rid != null) {
                    WebBook.getReviewRepliesAwait(
                        source, book, chapter, paragraphIndex, rid, page
                    ).getOrThrow()
                } else {
                    WebBook.getReviewListAwait(source, book, chapter, paragraphIndex, page, sort)
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
                // 段评总数仅首页发（首页 = !append），避免翻页/排序切换覆盖同一数字
                if (!append && replyReviewId == null) {
                    totalCountLiveData.value = result.totalCount
                }
                // 首页也同步 hasMore，避免 loadMoreView.hasMore 残留 true
                // 触发多余的下一页请求（回复页通常一页就完）
                loadMoreDoneLiveData.value = hasMore
            }.onError {
                if (append) currentPage -= 1
                context.toastOnUi("段评加载失败: ${it.localizedMessage}")
                if (!append) reviewsLiveData.value = emptyList()
                loadMoreDoneLiveData.value = true
            }.onFinally {
                loading = false
            }
        }

        fun reply(content: String, reviewId: String?) = runRule(
            { it.replyRule }, content = content, reviewId = reviewId, reloadOnSuccess = true
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
            runRule({ it.deleteRule }, reviewId = id, onSuccess = { result ->
                if (asBoolean(result)) onRemoved(id) else load()
            })
        }

        private fun asBoolean(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            else -> v.toString().trim().equals("true", ignoreCase = true)
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
            val book = ReadBook.book ?: return
            val source = ReadBook.bookSource ?: return
            val rule = source.ruleReview ?: return
            val ruleText = ruleSelector(rule)
            if (ruleText.isNullOrBlank()) {
                context.toastOnUi("书源未配置此操作规则")
                onError?.invoke()
                return
            }
            execute(context = IO) {
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                WebBook.evalReviewActionAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    rule = ruleText,
                    paragraphIndex = paragraphIndex,
                    reviewId = reviewId,
                    contentText = content,
                    selected = selected,
                ).getOrThrow()
            }.onSuccess { result ->
                if (reloadOnSuccess) load()
                onSuccess?.invoke(result)
            }.onError {
                context.toastOnUi("操作失败: ${it.localizedMessage}")
                onError?.invoke()
            }
        }
    }
}
