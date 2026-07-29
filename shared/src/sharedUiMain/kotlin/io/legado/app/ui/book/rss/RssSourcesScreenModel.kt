package io.legado.app.ui.book.rss

import io.legado.app.data.entities.Book
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RSS 源列表 UI 状态 (immutable)。
 * sources: RSS 源列表 (以 Book 形式存在, type 含 BookType.rss 位)。
 */
data class RssSourcesUiState(
    val sources: List<Book> = emptyList(),
)

/**
 * RSS 源列表 shared ScreenModel: 托管 [RssSourcesUiState]。
 * 数据订阅 (RssHelp.flowRssSources) 由调用方触发后 dispatch 推入状态。
 */
class RssSourcesScreenModel : ScreenModel {

    private val _state = MutableStateFlow(RssSourcesUiState())
    val state: StateFlow<RssSourcesUiState> = _state.asStateFlow()

    fun dispatch(event: RssSourcesUiEvent) {
        when (event) {
            is RssSourcesUiEvent.SourcesLoaded -> _state.update {
                it.copy(sources = event.sources)
            }
        }
    }
}

sealed interface RssSourcesUiEvent {
    /** RSS 源列表更新 (flowRssSources collect 触发) */
    data class SourcesLoaded(val sources: List<Book>) : RssSourcesUiEvent
}
