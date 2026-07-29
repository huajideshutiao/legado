package io.legado.app.ui.book.read.review

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Review
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书评/段评列表页 shared ScreenModel: 托管 [ReviewListUiState]。
 *
 * 对照 app 端 ReviewListDialog.ReviewViewModel 的列表加载/翻页逻辑,
 * 去掉 LiveData/AndroidViewModel, 改为 StateFlow + 自管 scope。
 * 路由仅 book 级评论 (paragraphIndex = -1, chapter = null); 段评/回复仍走 app 端 Dialog。
 * BookSource 经 AppDbProviders 查询 (复刻 app 端 Book.getBookSource() 扩展)。
 */
class ReviewListScreenModel(
    private val book: Book,
) : ScreenModel {

    // 书籍级评论: paragraphIndex = -1, chapter = null (对照 app 端详情页入口)
    private val paragraphIndex = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ReviewListUiState())
    val state: StateFlow<ReviewListUiState> = _state.asStateFlow()

    private var currentPage = 1
    private var loading = false
    private var hasMore = true

    init {
        load()
    }

    /** 首屏加载 */
    fun load() {
        currentPage = 1
        hasMore = true
        loading = true
        _state.value = _state.value.copy(
            reviews = emptyList(),
            loading = true,
            footerLoading = false,
            footerHasMore = true,
            error = null,
        )
        fetchPage(append = false)
    }

    /** 翻页追加 */
    fun loadMore() {
        if (!hasMore || loading) return
        currentPage += 1
        loading = true
        _state.value = _state.value.copy(footerLoading = true)
        fetchPage(append = true)
    }

    private fun fetchPage(append: Boolean) {
        scope.launch {
            try {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                if (source == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        footerLoading = false,
                        error = "无书源",
                    )
                    return@launch
                }
                val result = WebBook.getReviewListAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = null,
                    paragraphIndex = paragraphIndex,
                    page = currentPage,
                    sort = 0,
                ).getOrThrow()
                val reviews = if (append) _state.value.reviews + result.reviews
                else result.reviews
                hasMore = result.hasNextPage
                _state.value = _state.value.copy(
                    reviews = reviews,
                    loading = false,
                    footerLoading = false,
                    footerHasMore = hasMore,
                    error = null,
                )
            } catch (e: Exception) {
                if (append) currentPage -= 1
                AppLog.put("评论加载失败\n${e.localizedMessage}", e)
                _state.value = _state.value.copy(
                    loading = false,
                    footerLoading = false,
                    error = "评论加载失败: ${e.localizedMessage}",
                )
            } finally {
                loading = false
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/** 评论列表页 UI 状态 (immutable)。 */
data class ReviewListUiState(
    val reviews: List<Review> = emptyList(),
    val loading: Boolean = false,
    val footerLoading: Boolean = false,
    val footerHasMore: Boolean = true,
    val error: String? = null,
)
