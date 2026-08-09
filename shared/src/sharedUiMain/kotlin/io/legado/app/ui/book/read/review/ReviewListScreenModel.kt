package io.legado.app.ui.book.read.review

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Review
import io.legado.app.ui.book.read.ReviewViewModelShared
import io.legado.app.ui.book.read.SharedUiReviewPlatform
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书评列表页 shared ScreenModel: 薄包装 [ReviewViewModelShared]，托管 [ReviewListUiState]。
 *
 * 业务逻辑（分页抓取 / 排序 / 点赞点踩与回滚 / 回复 / 删除 / 规则执行）全部委托 shared
 * [ReviewViewModelShared]，本类仅做 shared StateFlow → [ReviewListUiState] 的状态映射。
 * 路由仅 book 级评论 (paragraphIndex = -1, chapter = null); 段评/回复仍走 app 端 Dialog。
 */
class ReviewListScreenModel(
    private val book: Book,
) : ScreenModel {

    private val scope = screenModelScope("评论列表")

    // 委托 shared VM (paragraphIndex = -1, chapter = null, 书评列表模式)
    private val shared = ReviewViewModelShared(
        scope = scope,
        platform = SharedUiReviewPlatform,
    ).apply {
        this.book = book
        this.chapter = null
        this.paragraphIndex = -1
        this.replyReviewId = null
    }

    private val _state = MutableStateFlow(ReviewListUiState())
    val state: StateFlow<ReviewListUiState> = _state.asStateFlow()

    /** 段评总数原始文本; Route 侧套 review_list_section_title 模板后回灌 [setTexts]。 */
    val totalCount: StateFlow<String?> = shared.totalCount

    /** 点击单条时暂存的回复目标 (对照 app 原版 replyToReview)。 */
    private var replyToReview: Review? = null

    init {
        // 桥接 shared 状态 → ReviewListUiState (两路 collector 各自 update, 避免七元 combine)
        // app 端 ReviewViewModel 无 error LiveData, 失败时 toast + 清空列表; 本端对齐此行为
        scope.launch {
            combine(
                shared.reviews,
                shared.loading,
                shared.hasMore,
                shared.sortState,
            ) { reviews, loading, hasMore, sort ->
                { s: ReviewListUiState ->
                    s.copy(
                        reviews = reviews,
                        footerLoading = loading,
                        footerHasMore = hasMore,
                        sortState = sort,
                    )
                }
            }.collect { patch -> _state.update(patch) }
        }
        scope.launch {
            combine(
                shared.votedIds,
                shared.votedDownIds,
                shared.expandedKeys,
            ) { voted, votedDown, expanded ->
                { s: ReviewListUiState ->
                    s.copy(
                        votedIds = voted,
                        votedDownIds = votedDown,
                        expandedKeys = expanded,
                    )
                }
            }.collect { patch -> _state.update(patch) }
        }
        shared.load()
    }

    /** Route 侧注入本地化文本 (列表头 "全部评论 · N" / 输入栏提示)。 */
    fun setTexts(listTitleText: String, inputHint: String) {
        _state.update { it.copy(listTitleText = listTitleText, inputHint = inputHint) }
    }

    /** 翻页追加 */
    fun loadMore() = shared.loadMore()

    /** 切换排序 (0=最热, 1=最新) */
    fun changeSort(sort: Int) = shared.changeSort(sort)

    /** 展开/折叠正文 */
    fun toggleExpand(key: String) = shared.toggleExpand(key)

    /** 点赞 (乐观翻转 + 失败回滚由 shared VM 负责) */
    fun voteUp(review: Review) = shared.voteUp(review)

    /** 点踩 */
    fun voteDown(review: Review) = shared.voteDown(review)

    /** 删除 (VM 已自动从 reviews 过滤被删条目, 回调留空) */
    fun delete(review: Review) = shared.delete(review) { }

    /** 暂存回复目标 (点击单条 → 跳发布页前调用, 对照 app 原版 onReviewClicked) */
    fun setReplyTo(review: Review?) {
        replyToReview = review
    }

    /**
     * 提交评论 (对照 app 原版 submitPost): 带 replyTo 时按回复该条提交并乐观递增其回复数,
     * 否则提交为顶层书评。成功后 shared VM 自动 load 重载列表。
     */
    fun submit(content: String) {
        val target = replyToReview
        replyToReview = null
        shared.reply(content = content, reviewId = target?.id, onHandled = handled@{
            target ?: return@handled
            target.replyCount += 1
        })
    }

    override fun onCleared() {
        scope.cancel()
    }
}
