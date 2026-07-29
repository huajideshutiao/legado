package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 章节换源 UI 状态 (immutable)。
 *
 * 字段语义对照 app 端 [ChangeChapterSourceDialog] 同名字段:
 * - [sources]: 搜索结果列表 (searchDataFlow collect);
 * - [isLoading]: 搜索中标志 (searchStateData observe);
 * - [chapterTitle]: 章节标题 (标题栏显示, initData 写入);
 * - [chapterIndex]: 章节序号 (toc 定位, initData 写入);
 * - [curBookUrl]: 当前书源 URL (高亮当前源, SOURCE_CHANGED 事件触发);
 * - [groups]: 启用分组列表 (bookSourceDao.flowEnabledGroups);
 * - [searchGroup]: 当前选中分组 (GroupPickerDialog 选中);
 * - [tocVisible]: toc 预览覆盖层是否显示;
 * - [tocLoading]: toc 加载中;
 * - [tocList]: toc 列表 (null 表示未加载);
 * - [durChapterIndex]: 当前章节索引 (toc 高亮);
 * - [totalSourceCount]: 书源总数;
 * - [checkAuthor]: 校验作者开关;
 * - [loadInfo]: 加载详情开关;
 * - [loadToc]: 加载目录开关;
 * - [loadWordCount]: 加载字数开关;
 * - [book]: 当前书籍.
 */
data class ChangeChapterSourceUiState(
    val sources: List<SearchBook> = emptyList(),
    val isLoading: Boolean = false,
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val curBookUrl: String? = null,
    val groups: List<String> = emptyList(),
    val searchGroup: String = "",
    val tocVisible: Boolean = false,
    val tocLoading: Boolean = false,
    val tocList: List<BookChapter>? = null,
    val durChapterIndex: Int = 0,
    val totalSourceCount: Int = 0,
    val checkAuthor: Boolean = false,
    val loadInfo: Boolean = false,
    val loadToc: Boolean = false,
    val loadWordCount: Boolean = false,
    val book: Book? = null,
)

/**
 * 章节换源 shared ScreenModel: 托管 [ChangeChapterSourceUiState]。
 *
 * 实际搜索/切源/取目录/取正文业务留在 app 端 ViewModel
 * (复用 [ChangeBookSourceViewModelShared] 已下沉到 commonMain 的能力),
 * 本类只承接 UI 状态与事件, 与 [ChangeSourceScreenModel] 同构.
 */
class ChangeChapterSourceScreenModel : ScreenModel {

    private val _state = MutableStateFlow(ChangeChapterSourceUiState())
    val state: StateFlow<ChangeChapterSourceUiState> = _state.asStateFlow()

    fun dispatch(event: ChangeChapterSourceUiEvent) {
        when (event) {
            is ChangeChapterSourceUiEvent.LoadingChanged -> _state.update {
                it.copy(isLoading = event.isLoading)
            }

            is ChangeChapterSourceUiEvent.SourcesLoaded -> _state.update {
                it.copy(sources = event.sources)
            }

            is ChangeChapterSourceUiEvent.ChapterInfoUpdated -> _state.update {
                it.copy(chapterTitle = event.title, chapterIndex = event.index)
            }

            is ChangeChapterSourceUiEvent.CurBookUrlChanged -> _state.update {
                it.copy(curBookUrl = event.url)
            }

            is ChangeChapterSourceUiEvent.GroupsLoaded -> _state.update {
                it.copy(groups = event.groups)
            }

            is ChangeChapterSourceUiEvent.SearchGroupChanged -> _state.update {
                it.copy(searchGroup = event.group)
            }

            is ChangeChapterSourceUiEvent.TocVisibleChanged -> _state.update {
                it.copy(tocVisible = event.value)
            }

            is ChangeChapterSourceUiEvent.TocLoadingChanged -> _state.update {
                it.copy(tocLoading = event.value)
            }

            is ChangeChapterSourceUiEvent.TocListLoaded -> _state.update {
                it.copy(tocList = event.list)
            }

            is ChangeChapterSourceUiEvent.DurChapterIndexChanged -> _state.update {
                it.copy(durChapterIndex = event.value)
            }

            is ChangeChapterSourceUiEvent.TotalSourceCountChanged -> _state.update {
                it.copy(totalSourceCount = event.value)
            }

            is ChangeChapterSourceUiEvent.CheckAuthorChanged -> _state.update {
                it.copy(checkAuthor = event.value)
            }

            is ChangeChapterSourceUiEvent.LoadInfoChanged -> _state.update {
                it.copy(loadInfo = event.value)
            }

            is ChangeChapterSourceUiEvent.LoadTocChanged -> _state.update {
                it.copy(loadToc = event.value)
            }

            is ChangeChapterSourceUiEvent.LoadWordCountChanged -> _state.update {
                it.copy(loadWordCount = event.value)
            }

            is ChangeChapterSourceUiEvent.BookInitialized -> _state.update {
                it.copy(book = event.book)
            }
        }
    }
}

sealed interface ChangeChapterSourceUiEvent {
    /** 搜索状态变化 (searchStateData observe 触发) */
    data class LoadingChanged(val isLoading: Boolean) : ChangeChapterSourceUiEvent

    /** 搜索结果更新 (searchDataFlow collect 触发) */
    data class SourcesLoaded(val sources: List<SearchBook>) : ChangeChapterSourceUiEvent

    /** 章节信息更新 (initData 解析后写入) */
    data class ChapterInfoUpdated(val title: String, val index: Int) : ChangeChapterSourceUiEvent

    /** 当前书源 URL 变化 (SOURCE_CHANGED 事件 / 初始化触发) */
    data class CurBookUrlChanged(val url: String?) : ChangeChapterSourceUiEvent

    /** 启用分组列表更新 (bookSourceDao.flowEnabledGroups 触发) */
    data class GroupsLoaded(val groups: List<String>) : ChangeChapterSourceUiEvent

    /** 当前搜索分组变更 (GroupPickerDialog 选中触发) */
    data class SearchGroupChanged(val group: String) : ChangeChapterSourceUiEvent

    /** toc 预览覆盖层显示状态变化 */
    data class TocVisibleChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** toc 加载中状态变化 */
    data class TocLoadingChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** toc 列表加载完成 */
    data class TocListLoaded(val list: List<BookChapter>?) : ChangeChapterSourceUiEvent

    /** 当前章节索引变化 (toc 高亮用) */
    data class DurChapterIndexChanged(val value: Int) : ChangeChapterSourceUiEvent

    /** 书源总数变化 */
    data class TotalSourceCountChanged(val value: Int) : ChangeChapterSourceUiEvent

    /** 校验作者开关变化 */
    data class CheckAuthorChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** 加载详情开关变化 */
    data class LoadInfoChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** 加载目录开关变化 */
    data class LoadTocChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** 加载字数开关变化 */
    data class LoadWordCountChanged(val value: Boolean) : ChangeChapterSourceUiEvent

    /** 当前书籍初始化 */
    data class BookInitialized(val book: Book) : ChangeChapterSourceUiEvent
}
