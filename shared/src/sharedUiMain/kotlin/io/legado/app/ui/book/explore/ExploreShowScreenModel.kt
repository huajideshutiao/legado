package io.legado.app.ui.book.explore

import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 发现结果页 shared ScreenModel: 托管 [ExploreShowUiState]。
 *
 * app 端 [ExploreShowActivity] 观察 ViewModel LiveData 后通过 [dispatch] 推入状态;
 * 桌面/iOS 端可直接构造本类复用。footer 状态机 (footerHasMore/errorMsg) 内部托管,
 * 通过 public getter 供 Activity 读取。
 */
class ExploreShowScreenModel : ScreenModel {

    private val _state = MutableStateFlow(
        ExploreShowUiState(
            title = "",
            books = emptyList(),
            exploreStyle = 0,
            isFavorite = false,
            bookshelfVersion = 0,
            optionsVersion = 0,
            scrollTopEpoch = 0,
            footerLoading = false,
            footerText = null,
        )
    )
    val state: StateFlow<ExploreShowUiState> = _state.asStateFlow()

    // footer 状态机内部字段 (不进 UiState, 供 Activity 点击逻辑读取)
    var footerHasMore: Boolean = true
        private set
    var errorMsg: String = ""
        private set

    fun dispatch(event: ExploreShowUiEvent) {
        when (event) {
            is ExploreShowUiEvent.TitleChanged -> _state.update { it.copy(title = event.title) }
            ExploreShowUiEvent.StartLoad -> _state.update { it.copy(footerLoading = true) }
            is ExploreShowUiEvent.BooksLoaded -> handleBooksLoaded(event)
            is ExploreShowUiEvent.ErrorLoaded -> handleErrorLoaded(event)
            ExploreShowUiEvent.LoadMore -> handleLoadMore()
            ExploreShowUiEvent.ClearBooks -> handleClearBooks()
            is ExploreShowUiEvent.ExploreStyleChanged -> _state.update { it.copy(exploreStyle = event.style) }
            is ExploreShowUiEvent.IsFavoriteChanged -> _state.update { it.copy(isFavorite = event.isFavorite) }
            ExploreShowUiEvent.BookshelfVersionBump -> _state.update {
                it.copy(bookshelfVersion = it.bookshelfVersion + 1)
            }

            ExploreShowUiEvent.OptionsVersionBump -> _state.update {
                it.copy(optionsVersion = it.optionsVersion + 1)
            }

            ExploreShowUiEvent.ScrollTopBump -> _state.update {
                it.copy(scrollTopEpoch = it.scrollTopEpoch + 1)
            }
        }
    }

    // 对照 Activity.upData: stopLoad + 条件 noMore + books 更新
    private fun handleBooksLoaded(event: ExploreShowUiEvent.BooksLoaded) {
        _state.update { it.copy(footerLoading = false) }
        val currentBooks = _state.value.books
        if (event.books.isEmpty() && currentBooks.isEmpty()) {
            footerHasMore = false
            errorMsg = ""
            _state.update { it.copy(footerText = event.emptyText) }
        } else if (currentBooks.size < event.books.size) {
            _state.update { it.copy(books = event.books) }
        }
        if (!event.hasNextPage) {
            footerHasMore = false
            errorMsg = ""
            _state.update { it.copy(footerText = event.bottomLineText) }
        }
    }

    // 对照 Activity.upError
    private fun handleErrorLoaded(event: ExploreShowUiEvent.ErrorLoaded) {
        footerHasMore = false
        errorMsg = event.errorMsg
        _state.update { it.copy(footerLoading = false, footerText = event.footerText) }
    }

    // 对照 Activity.hasMoreLoad
    private fun handleLoadMore() {
        errorMsg = ""
        footerHasMore = true
        _state.update { it.copy(footerLoading = true) }
    }

    // 对照 Activity.onExploreOptionChangedImpl: 清空 books + hasMoreLoad
    private fun handleClearBooks() {
        errorMsg = ""
        footerHasMore = true
        _state.update { it.copy(books = emptyList(), footerLoading = true) }
    }
}

sealed interface ExploreShowUiEvent {
    /** 标题初始化 (intent exploreName / discovery) */
    data class TitleChanged(val title: String) : ExploreShowUiEvent

    /** 首次加载 (footerLoading = true) */
    object StartLoad : ExploreShowUiEvent

    /** books 数据到达 (对照 upData: stopLoad + 条件 noMore + books 更新) */
    data class BooksLoaded(
        val books: List<SearchBook>,
        val hasNextPage: Boolean,
        val emptyText: String,
        val bottomLineText: String,
    ) : ExploreShowUiEvent

    /** 加载错误 (对照 upError) */
    data class ErrorLoaded(val errorMsg: String, val footerText: String) : ExploreShowUiEvent

    /** 触底加载下一页 (对照 hasMoreLoad) */
    object LoadMore : ExploreShowUiEvent

    /** 参数 chip 变化: 清空 books + 重新加载 (对照 onExploreOptionChangedImpl) */
    object ClearBooks : ExploreShowUiEvent

    /** 布局样式变化 (switchLayout / setColumnCount / sourceReady) */
    data class ExploreStyleChanged(val style: Int) : ExploreShowUiEvent

    /** 收藏状态变化 (upStarLiveData) */
    data class IsFavoriteChanged(val isFavorite: Boolean) : ExploreShowUiEvent

    /** 书架集合变化信号 (upAdapterLiveData) */
    object BookshelfVersionBump : ExploreShowUiEvent

    /** 参数 chip 结构变化信号 (optionsReadyLiveData) */
    object OptionsVersionBump : ExploreShowUiEvent

    /** 标题栏点击回顶信号 */
    object ScrollTopBump : ExploreShowUiEvent
}
