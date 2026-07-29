package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 换源 UI 状态 (immutable)。
 * sources: 搜索结果列表; isLoading: 搜索中标志;
 * curBookUrl: 当前书源 URL (高亮当前源); durText: 底栏进度文案。
 *
 * 下沉自 app 端 ChangeBookSourceDialog.Content 本地状态:
 * - groups / searchGroup: 启用分组列表 + 当前选中 (对照 flowEnabledGroups 收集 + AppConfig.searchGroup)
 * - checkAuthor / loadInfo / loadToc / loadWordCount: 4 个换源开关 (对照 AppConfig.changeSource*)
 * - totalSourceCount: 书源总数 (进度文案 "结果 X, 当前进度 Y / Z: name" 用)
 * - book: 当前书籍 (oldBook, 用于切源时 sameBookTypeLocal 判断书类型)
 */
data class ChangeSourceUiState(
    val sources: List<SearchBook> = emptyList(),
    val isLoading: Boolean = false,
    val curBookUrl: String? = null,
    val durText: String = "",
    val groups: List<String> = emptyList(),
    val searchGroup: String = "",
    val checkAuthor: Boolean = false,
    val loadInfo: Boolean = false,
    val loadToc: Boolean = false,
    val loadWordCount: Boolean = false,
    val totalSourceCount: Int = 0,
    val book: Book? = null,
)

/**
 * 换源 shared ScreenModel: 托管 [ChangeSourceUiState]。
 * 实际搜索/切源业务留在 [ChangeBookSourceViewModelShared], 本类只承接 UI 状态与事件。
 */
class ChangeSourceScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ChangeSourceUiState())
    val state: StateFlow<ChangeSourceUiState> = _state.asStateFlow()

    fun dispatch(event: ChangeSourceUiEvent) {
        when (event) {
            is ChangeSourceUiEvent.LoadingChanged -> _state.update {
                it.copy(isLoading = event.isLoading)
            }

            is ChangeSourceUiEvent.SourcesLoaded -> _state.update {
                it.copy(sources = event.sources)
            }

            is ChangeSourceUiEvent.CurBookUrlChanged -> _state.update {
                it.copy(curBookUrl = event.url)
            }

            is ChangeSourceUiEvent.DurTextChanged -> _state.update {
                it.copy(durText = event.text)
            }

            is ChangeSourceUiEvent.GroupsLoaded -> _state.update {
                it.copy(groups = event.groups)
            }

            is ChangeSourceUiEvent.SearchGroupChanged -> _state.update {
                it.copy(searchGroup = event.group)
            }

            is ChangeSourceUiEvent.CheckAuthorChanged -> _state.update {
                it.copy(checkAuthor = event.value)
            }

            is ChangeSourceUiEvent.LoadInfoChanged -> _state.update {
                it.copy(loadInfo = event.value)
            }

            is ChangeSourceUiEvent.LoadTocChanged -> _state.update {
                it.copy(loadToc = event.value)
            }

            is ChangeSourceUiEvent.LoadWordCountChanged -> _state.update {
                it.copy(loadWordCount = event.value)
            }

            is ChangeSourceUiEvent.TotalSourceCountChanged -> _state.update {
                it.copy(totalSourceCount = event.value)
            }

            is ChangeSourceUiEvent.BookInitialized -> _state.update {
                it.copy(book = event.book)
            }
        }
    }
}

sealed interface ChangeSourceUiEvent {
    /** 搜索状态变化 (searchStateData observe 触发) */
    data class LoadingChanged(val isLoading: Boolean) : ChangeSourceUiEvent

    /** 搜索结果更新 (searchDataFlow collect 触发) */
    data class SourcesLoaded(val sources: List<SearchBook>) : ChangeSourceUiEvent

    /** 当前书源 URL 变化 (SOURCE_CHANGED 事件 / 初始化触发) */
    data class CurBookUrlChanged(val url: String?) : ChangeSourceUiEvent

    /** 进度文案更新 (切源进度回调触发) */
    data class DurTextChanged(val text: String) : ChangeSourceUiEvent

    /** 启用分组列表更新 (flowEnabledGroups collect 触发) */
    data class GroupsLoaded(val groups: List<String>) : ChangeSourceUiEvent

    /** 当前选中分组变化 (onGroupPickerSelect 触发) */
    data class SearchGroupChanged(val group: String) : ChangeSourceUiEvent

    /** 校验作者开关变化 (菜单 CheckMenuItem 触发) */
    data class CheckAuthorChanged(val value: Boolean) : ChangeSourceUiEvent

    /** 加载详情开关变化 (菜单 CheckMenuItem 触发) */
    data class LoadInfoChanged(val value: Boolean) : ChangeSourceUiEvent

    /** 加载目录开关变化 (菜单 CheckMenuItem 触发) */
    data class LoadTocChanged(val value: Boolean) : ChangeSourceUiEvent

    /** 加载字数开关变化 (菜单 CheckMenuItem 触发) */
    data class LoadWordCountChanged(val value: Boolean) : ChangeSourceUiEvent

    /** 书源总数变化 (startSearch 后 totalSourceCount 读取) */
    data class TotalSourceCountChanged(val value: Int) : ChangeSourceUiEvent

    /** 当前书籍初始化 (Route initData 触发) */
    data class BookInitialized(val book: Book) : ChangeSourceUiEvent
}
