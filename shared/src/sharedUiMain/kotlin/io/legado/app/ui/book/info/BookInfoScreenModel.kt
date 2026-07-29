package io.legado.app.ui.book.info

import io.legado.app.data.entities.Book
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 书籍详情页 shared ScreenModel: 托管 [BookInfoUiState]。
 *
 * app 端 [BookInfoActivity] 观察 ViewModel LiveData 后通过 [dispatch] 推入状态;
 * 桌面/iOS 端可直接构造本类复用。派生状态 (isLandscape/useDevFeat/isDarkTheme/menuState)
 * 由宿主在 Composition 时经 [BookInfoUiState.copy] 覆盖, 不走 dispatch。
 */
class BookInfoScreenModel : ScreenModel {

    private val _state = MutableStateFlow(
        BookInfoUiState(
            book = null,
            bookTick = 0,
            coverTick = 0,
            inBookshelf = false,
            groupName = "",
            tocText = null,
            lastedTitle = "",
            wordCountText = null,
            isLandscape = false,
            useDevFeat = false,
            isDarkTheme = false,
            menuState = BookInfoMenuState(
                isLocal = false,
                isWebDav = false,
                hasSource = false,
                sourceHasLogin = false,
                sourceHasReviewRule = false,
                canUpdate = true,
                isLocalTxt = false,
                splitLongChapter = false,
                bookUrl = null,
                tocUrl = null,
            ),
        )
    )
    val state: StateFlow<BookInfoUiState> = _state.asStateFlow()

    fun dispatch(event: BookInfoUiEvent) {
        when (event) {
            BookInfoUiEvent.Refresh -> _state.update { it.copy(tocText = null) }
            is BookInfoUiEvent.ShowBook -> _state.update {
                it.copy(
                    book = event.book,
                    bookTick = it.bookTick + 1,
                    coverTick = it.coverTick + 1,
                    lastedTitle = event.lastedTitle,
                )
            }

            is BookInfoUiEvent.UpdateToc -> _state.update {
                it.copy(
                    tocText = event.tocText,
                    lastedTitle = event.lastedTitle ?: it.lastedTitle,
                )
            }

            is BookInfoUiEvent.UpdateBookshelf -> _state.update {
                it.copy(inBookshelf = event.inBookshelf)
            }

            is BookInfoUiEvent.UpdateGroup -> _state.update {
                it.copy(groupName = event.groupName)
            }

            is BookInfoUiEvent.UpdateWordCount -> _state.update {
                it.copy(wordCountText = event.text)
            }

            BookInfoUiEvent.BumpBookTick -> _state.update {
                it.copy(bookTick = it.bookTick + 1)
            }

            BookInfoUiEvent.BumpCoverTick -> _state.update {
                it.copy(coverTick = it.coverTick + 1)
            }
        }
    }
}

sealed interface BookInfoUiEvent {
    /** 下拉刷新: 标记目录加载中 */
    object Refresh : BookInfoUiEvent

    /** 书籍数据更新 (bookData observe 触发) */
    data class ShowBook(val book: Book, val lastedTitle: String) : BookInfoUiEvent

    /** 目录加载状态更新 (chapterListData observe / upLoading 触发) */
    data class UpdateToc(val tocText: String?, val lastedTitle: String?) : BookInfoUiEvent

    /** 书架状态更新 */
    data class UpdateBookshelf(val inBookshelf: Boolean) : BookInfoUiEvent

    /** 分组名更新 */
    data class UpdateGroup(val groupName: String) : BookInfoUiEvent

    /** 字数信息更新 */
    data class UpdateWordCount(val text: String?) : BookInfoUiEvent

    /** book 原地可变后驱动重组 (toggleCanUpdate / toggleSplitLongChapter) */
    object BumpBookTick : BookInfoUiEvent

    /** 封面变更后驱动重载 (coverChangeTo) */
    object BumpCoverTick : BookInfoUiEvent
}
