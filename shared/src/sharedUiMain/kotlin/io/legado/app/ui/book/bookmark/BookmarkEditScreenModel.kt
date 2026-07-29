package io.legado.app.ui.book.bookmark

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 书签编辑对话框 shared ScreenModel：托管 [BookmarkEditUiState]，
 * 处理 bookText/content 编辑态与保存/删除入库，通过 [dispatch] 驱动。
 */
class BookmarkEditScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BookmarkEditUiState())
    val state: StateFlow<BookmarkEditUiState> = _state.asStateFlow()

    fun dispatch(event: BookmarkEditUiEvent) {
        when (event) {
            is BookmarkEditUiEvent.Init -> {
                _state.value = BookmarkEditUiState(
                    bookmark = event.bookmark,
                    bookText = event.bookmark.bookText,
                    content = event.bookmark.content,
                    showDelete = event.editPos >= 0,
                )
            }

            is BookmarkEditUiEvent.UpdateBookText -> {
                _state.value = _state.value.copy(bookText = event.text)
            }

            is BookmarkEditUiEvent.UpdateContent -> {
                _state.value = _state.value.copy(content = event.text)
            }

            is BookmarkEditUiEvent.Save -> {
                val bookmark = _state.value.bookmark ?: return
                bookmark.bookText = _state.value.bookText
                bookmark.content = _state.value.content
                Coroutine.async(scope = scope, context = IoDispatcher) {
                    AppDbProviders.get().bookmarkDao.insert(bookmark)
                }.onSuccess {
                    event.onSuccess()
                }.onError {
                    Toasters.get().toast("save error, ${it.message}")
                    it.printStackTraceOnDebug()
                }
            }

            is BookmarkEditUiEvent.Delete -> {
                val bookmark = _state.value.bookmark ?: return
                Coroutine.async(scope = scope, context = IoDispatcher) {
                    AppDbProviders.get().bookmarkDao.delete(bookmark)
                }.onSuccess {
                    event.onSuccess()
                }.onError {
                    Toasters.get().toast("delete error, ${it.message}")
                    it.printStackTraceOnDebug()
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

data class BookmarkEditUiState(
    val bookmark: Bookmark? = null,
    val bookText: String = "",
    val content: String = "",
    val showDelete: Boolean = false,
)

sealed interface BookmarkEditUiEvent {
    data class Init(val bookmark: Bookmark, val editPos: Int) : BookmarkEditUiEvent

    data class UpdateBookText(val text: String) : BookmarkEditUiEvent

    data class UpdateContent(val text: String) : BookmarkEditUiEvent

    data class Save(val onSuccess: () -> Unit) : BookmarkEditUiEvent

    data class Delete(val onSuccess: () -> Unit) : BookmarkEditUiEvent
}
