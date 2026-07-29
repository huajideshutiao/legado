package io.legado.app.ui.book.import.local

import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 导入本地书籍 shared ScreenModel。
 *
 * 下沉自 app 端 [ImportBookActivity] / [ImportBookViewModel] 中可跨平台复用的纯 UI 状态:
 * - [ImportBookUiState.selected] / [searchKey] / [sortState] / [refreshTick]: 由本类托管,
 *   通过 [dispatch] 响应事件变更
 * - [ImportBookUiState.items] / [path] / [loading] / [emptyMsgVisible] / [checkableCount]:
 *   依赖平台文件系统 (DocumentFile/File), 由宿主 Activity 持有, shared 端暂留默认值占位,
 *   待平台宿主注入后合并入 state
 *
 * 平台专属动作 (选择目录/扫描目录/文件名导入 js/上架/进入子目录/返回上级/打开阅读页)
 * 不在本类处理, 由 [io.legado.app.ui.root.PlatformCapabilities] 暴露给各端实现。
 *
 * 模式参考 [io.legado.app.ui.book.read.review.ReviewPostScreenModel]。
 */
class ImportBookScreenModel : ScreenModel {

    private val _state = MutableStateFlow(
        ImportBookUiState<ImportFileItem>(
            items = emptyList(),
            selected = emptySet(),
            refreshTick = 0,
            path = null,
            loading = false,
            emptyMsgVisible = false,
            searchKey = "",
            checkableCount = 0,
            sortState = 0,
        )
    )
    val state: StateFlow<ImportBookUiState<ImportFileItem>> = _state.asStateFlow()

    /** 判定可勾选条目 (对照 [ImportBookActivity.isCheckable]) */
    private fun isCheckable(item: ImportFileItem): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    private fun checkableItems(): List<ImportFileItem> =
        _state.value.items.filter(::isCheckable)

    /** 平台状态注入: items/path/loading/emptyMsgVisible 由宿主推送, 供 selection 计算 */
    fun updatePlatformState(
        items: List<ImportFileItem>,
        path: String?,
        loading: Boolean,
        emptyMsgVisible: Boolean,
    ) {
        _state.update {
            it.copy(
                items = items,
                path = path,
                loading = loading,
                emptyMsgVisible = emptyMsgVisible,
                checkableCount = items.count { item -> !item.isUpDir && !item.isDir && !item.isOnBookShelf },
            )
        }
    }

    fun dispatch(event: ImportBookUiEvent) {
        when (event) {
            is ImportBookUiEvent.SetSearchKey -> _state.update {
                it.copy(searchKey = event.key)
            }

            is ImportBookUiEvent.SetSort -> _state.update {
                it.copy(sortState = event.sort)
            }

            is ImportBookUiEvent.SelectAll -> _state.update {
                it.copy(selected = if (event.all) checkableItems().toSet() else emptySet())
            }

            ImportBookUiEvent.RevertSelection -> _state.update {
                it.copy(selected = checkableItems().toSet() - it.selected)
            }

            is ImportBookUiEvent.Toggle -> _state.update {
                val cur = it.selected
                it.copy(selected = if (event.item in cur) cur - event.item else cur + event.item)
            }

            ImportBookUiEvent.BumpRefreshTick -> _state.update {
                it.copy(refreshTick = it.refreshTick + 1)
            }
        }
    }
}

/**
 * 导入本地书籍 UI 事件 (对照 app 端 [ImportBookUiActions] 中可下沉部分)。
 *
 * 平台专属事件 (pickFolder/scanFolder/alertImportFileName/addSelectionToBookshelf/
 * goBackDir/nextDoc/startRead) 不在此处, 由 [io.legado.app.ui.root.PlatformCapabilities] 暴露。
 */
sealed interface ImportBookUiEvent {
    /** 搜索框输入 (对照 onUpSearchKey) */
    data class SetSearchKey(val key: String) : ImportBookUiEvent

    /** 切换排序方式 (对照 onUpSort) */
    data class SetSort(val sort: Int) : ImportBookUiEvent

    /** 全选/取消全选 (对照 onSelectAll) */
    data class SelectAll(val all: Boolean) : ImportBookUiEvent

    /** 反选 (对照 onRevertSelection) */
    object RevertSelection : ImportBookUiEvent

    /** 切换单项选中态 (对照 onItemClick 中 !isOnBookShelf 分支的 toggleSelect) */
    data class Toggle(val item: ImportFileItem) : ImportBookUiEvent

    /** 上架标记原地变更后强制列表重组 (对照 addSelectionToBookshelf 中 refreshTick++) */
    object BumpRefreshTick : ImportBookUiEvent
}
