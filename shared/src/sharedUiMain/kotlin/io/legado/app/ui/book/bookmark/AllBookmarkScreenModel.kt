package io.legado.app.ui.book.bookmark

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.dao.sortedByLocalizedOrder
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 所有书签页 shared ScreenModel：托管 [AllBookmarkUiState]，订阅 bookmarkDao 数据流，
 * 并通过 [dispatch] 处理可下沉到 shared 层的数据事件 (如删除)。
 *
 * [book] 非空时仅订阅该书书签 (对照 TocScreenModel.upBookmarks 用 flowByBook),
 * 为空时订阅全量书签 (flowAll)。
 */
class AllBookmarkScreenModel(
    private val book: Book? = null,
) : ScreenModel {

    // 自管 scope (app 端无 ScreenModelStore 时由宿主 DisposableEffect 调 onCleared)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AllBookmarkUiState())
    val state: StateFlow<AllBookmarkUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val dao = AppDbProviders.get().bookmarkDao
            // 按书过滤: book 非空时仅订阅该书书签
            val flow = if (book != null) {
                dao.flowByBook(book.name, book.author)
            } else {
                dao.flowAll()
            }
            flow.catch { AppLog.put("所有书签界面获取数据失败\n${it.message}", it) }
                .flowOn(Dispatchers.Default)
                .collect { _state.value = AllBookmarkUiState(it.sortedByLocalizedOrder()) }
        }
    }

    fun dispatch(event: AllBookmarkUiEvent) {
        when (event) {
            is AllBookmarkUiEvent.Delete -> scope.launch {
                AppDbProviders.get().bookmarkDao.delete(event.bookmark)
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/** 所有书签页可下沉到 shared 层处理的 UI 事件 (平台相关事件如导出仍走 [AllBookmarkUiActions])。 */
sealed interface AllBookmarkUiEvent {
    data class Delete(val bookmark: Bookmark) : AllBookmarkUiEvent
}
