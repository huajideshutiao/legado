package io.legado.app.ui.book.rss

import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RSS 文章列表 UI 状态 (immutable)。
 * articles: 文章列表 (BookChapter); isLoading: 加载中; error: 加载失败信息 (null 表示无错误)。
 */
data class RssArticlesUiState(
    val articles: List<BookChapter> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * RSS 文章列表 shared ScreenModel: 托管 [RssArticlesUiState]。
 * 实际加载 (RssHelp.loadRssArticles) 留在 ViewModelShared, 本类只承接 UI 状态与事件。
 */
class RssArticlesScreenModel : ScreenModel {

    private val _state = MutableStateFlow(RssArticlesUiState())
    val state: StateFlow<RssArticlesUiState> = _state.asStateFlow()

    fun dispatch(event: RssArticlesUiEvent) {
        when (event) {
            RssArticlesUiEvent.Load -> _state.update { it.copy(isLoading = true, error = null) }
            is RssArticlesUiEvent.ArticlesLoaded -> _state.update {
                it.copy(articles = event.articles, isLoading = false, error = null)
            }

            is RssArticlesUiEvent.Error -> _state.update {
                it.copy(isLoading = false, error = event.message)
            }
        }
    }
}

sealed interface RssArticlesUiEvent {
    /** 开始加载 (无缓存时显示 Loading) */
    object Load : RssArticlesUiEvent

    /** 文章列表加载完成 (缓存或新拉取) */
    data class ArticlesLoaded(val articles: List<BookChapter>) : RssArticlesUiEvent

    /** 加载失败且无缓存 */
    data class Error(val message: String) : RssArticlesUiEvent
}
