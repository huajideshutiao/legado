package io.legado.app.ui.book.info.edit

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 书籍信息编辑页 ScreenModel: 托管 [BookInfoEditUiState] 与编辑事件分发。
 *
 * 落库核心委托 [BookInfoEditViewModelShared] (loadBook / saveBook)。
 * [updateCacheFolder] 由宿主注入 (app: BookHelp.updateCacheFolder; 桌面: BookStorageProviders.get().updateCacheFolder)。
 */
class BookInfoEditScreenModel(
    private val shared: BookInfoEditViewModelShared,
    private val updateCacheFolder: (oldBook: Book, newBook: Book) -> Unit,
) : ScreenModel {

    private val _state = MutableStateFlow(
        BookInfoEditUiState(
            book = null,
            name = "",
            author = "",
            typeIndex = 0,
            coverUrl = "",
            intro = "",
            bookUrl = "",
            coverTick = 0,
        )
    )
    val state: StateFlow<BookInfoEditUiState> = _state.asStateFlow()

    /** 当前编辑的 Book (委托 shared.book)。 */
    val book: Book? get() = shared.book

    /** 加载书籍并初始化编辑态 (对照原 BookInfoEditActivity.upView)。 */
    fun loadBook() {
        shared.loadBook()
        shared.book?.let(::upView)
    }

    private fun upView(book: Book) {
        _state.value = BookInfoEditUiState(
            book = book,
            name = book.name,
            author = book.author,
            typeIndex = when {
                book.isRss -> 5
                book.isVideo -> 4
                book.isWebFile -> 3
                book.isImage -> 2
                book.isAudio -> 1
                else -> 0
            },
            coverUrl = book.getDisplayCover().orEmpty(),
            intro = book.getDisplayIntro().orEmpty(),
            bookUrl = book.bookUrl,
            coverTick = _state.value.coverTick + 1,
        )
    }

    fun dispatch(event: BookInfoEditUiEvent) {
        when (event) {
            is BookInfoEditUiEvent.NameChange ->
                _state.update { it.copy(name = event.value) }

            is BookInfoEditUiEvent.AuthorChange ->
                _state.update { it.copy(author = event.value) }

            is BookInfoEditUiEvent.TypeChange ->
                _state.update { it.copy(typeIndex = event.index) }

            is BookInfoEditUiEvent.CoverUrlChange ->
                _state.update { it.copy(coverUrl = event.value) }

            is BookInfoEditUiEvent.IntroChange ->
                _state.update { it.copy(intro = event.value) }

            is BookInfoEditUiEvent.BookUrlChange ->
                _state.update { it.copy(bookUrl = event.value) }

            BookInfoEditUiEvent.RefreshCover -> {
                val curBook = shared.book ?: return
                curBook.customCoverUrl = _state.value.coverUrl
                _state.update { it.copy(coverTick = it.coverTick + 1) }
            }

            is BookInfoEditUiEvent.CoverChangeTo -> {
                val curBook = shared.book ?: return
                curBook.customCoverUrl = event.coverUrl
                _state.update { it.copy(coverUrl = event.coverUrl, coverTick = it.coverTick + 1) }
            }

            is BookInfoEditUiEvent.Save -> save(event.success)
        }
    }

    /** 落库 (对照原 BookInfoEditActivity.saveData): 字段修改 + type 重算 + customCoverUrl/customIntro + updateCacheFolder + shared.saveBook。 */
    private fun save(success: (() -> Unit)?) {
        val curBook = shared.book ?: return
        val s = _state.value
        val oldBook = curBook.copy()
        curBook.name = s.name
        curBook.author = s.author
        val local = if (curBook.isLocal) BookType.local else 0
        val bookType = when (s.typeIndex) {
            5 -> BookType.rss or local
            4 -> BookType.video or local
            3 -> BookType.webFile or local
            2 -> BookType.image or local
            1 -> BookType.audio or local
            else -> BookType.text or local
        }
        curBook.removeType(
            BookType.local,
            BookType.image,
            BookType.audio,
            BookType.text,
            BookType.video,
            BookType.webFile,
            BookType.rss,
        )
        curBook.addType(bookType)
        curBook.customCoverUrl = if (s.coverUrl == curBook.coverUrl) null else s.coverUrl
        curBook.customIntro = if (s.intro == curBook.intro) null else s.intro
        updateCacheFolder(oldBook, curBook)
        shared.saveBook(curBook, s.bookUrl) {
            success?.invoke()
        }
    }
}

sealed interface BookInfoEditUiEvent {
    data class NameChange(val value: String) : BookInfoEditUiEvent
    data class AuthorChange(val value: String) : BookInfoEditUiEvent
    data class TypeChange(val index: Int) : BookInfoEditUiEvent
    data class CoverUrlChange(val value: String) : BookInfoEditUiEvent
    data class IntroChange(val value: String) : BookInfoEditUiEvent
    data class BookUrlChange(val value: String) : BookInfoEditUiEvent
    object RefreshCover : BookInfoEditUiEvent
    data class CoverChangeTo(val coverUrl: String) : BookInfoEditUiEvent
    data class Save(val success: (() -> Unit)?) : BookInfoEditUiEvent
}
