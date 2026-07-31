package io.legado.app.ui.book.info

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.error_get_book_info
import legado.shared.generated.resources.error_get_chapter_list
import legado.shared.generated.resources.error_no_source
import org.jetbrains.compose.resources.getString

/**
 * 书籍详情页 shared ScreenModel: 托管 [BookInfoUiState]。
 *
 * app 端 [BookInfoActivity] 观察 ViewModel LiveData 后通过 [dispatch] 推入状态;
 * 桌面/iOS 端可直接构造本类复用。派生状态 (isLandscape/useDevFeat/isDarkTheme/menuState)
 * 由宿主在 Composition 时经 [BookInfoUiState.copy] 覆盖, 不走 dispatch。
 */
class BookInfoScreenModel : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (对照 TocScreenModel, 由 ScreenModelStore 在 onCleared 取消)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /**
     * 刷新书籍信息 + 目录 (对照 app 端 BaseReadViewModel.loadBookInfo)。
     * [bookSource] 由调用方查好传入; 无论成功失败都会 dispatch UpdateToc 解除加载中状态。
     */
    fun refresh(
        book: Book,
        bookSource: BookSource?,
        lastedTitle: String,
        errorLoadToc: String,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
    ) {
        dispatch(BookInfoUiEvent.Refresh)
        scope.launch(IoDispatcher) {
            val toc = try {
                loadBookInfo(book, bookSource, canReName, runPreUpdateJs)
            } catch (e: Throwable) {
                AppLog.put("获取书籍信息失败\n${e.message}", e)
                Toasters.get().toast(getString(Res.string.error_get_book_info))
                emptyList()
            }
            // 对照 app 端 showBook + upLoading(false, chapterList)
            dispatch(BookInfoUiEvent.ShowBook(book, lastedTitle))
            dispatch(
                BookInfoUiEvent.UpdateToc(
                    tocText = if (toc.isEmpty()) errorLoadToc else book.durChapterTitle.orEmpty(),
                    lastedTitle = if (toc.isEmpty()) null else lastedTitle,
                )
            )
        }
    }

    /** 对照 app 端 BaseReadViewModel.loadBookInfo。 */
    private suspend fun loadBookInfo(
        book: Book,
        bookSource: BookSource?,
        canReName: Boolean,
        runPreUpdateJs: Boolean,
    ): List<BookChapter> {
        if (book.isLocal) {
            val tmp = book.copy()
            FileBook.upBookInfo(book)
            return if (tmp.tocUrl != book.tocUrl || book.totalChapterNum == 0) {
                loadChapterList(book, bookSource, runPreUpdateJs)
            } else {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }
        }
        val source = bookSource ?: let {
            Toasters.get().toast(getString(Res.string.error_no_source))
            return emptyList()
        }
        return try {
            WebBook.getBookInfoAwait(source, book, canReName)
            if (_state.value.inBookshelf) saveBook(book)
            // app 端 webFile 走 loadWebFile 下载导入 (平台专属), shared 只读已入库目录
            if (book.isWebFile) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                loadChapterList(book, source, runPreUpdateJs)
            }
        } catch (e: Throwable) {
            AppLog.put("获取书籍信息失败\n${e.message}", e)
            Toasters.get().toast(getString(Res.string.error_get_book_info))
            emptyList()
        }
    }

    /** 对照 app 端 BaseReadViewModel.loadChapterList。 */
    private suspend fun loadChapterList(
        book: Book,
        bookSource: BookSource?,
        runPreUpdateJs: Boolean,
    ): List<BookChapter> {
        if (book.isLocal) {
            return try {
                FileBook.getChapterList(book).also {
                    appDb.bookDao.update(book)
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    if (_state.value.inBookshelf) appDb.bookChapterDao.insert(*it.toTypedArray())
                }
            } catch (e: Throwable) {
                AppLog.put("LoadTocError:${e.message}", e)
                Toasters.get().toast("LoadTocError:${e.message}")
                emptyList()
            }
        }
        val source = bookSource ?: let {
            Toasters.get().toast(getString(Res.string.error_no_source))
            return emptyList()
        }
        val oldBook = book.copy()
        return try {
            val tmp = WebBook.getChapterListAwait(source, book, runPreUpdateJs).getOrThrow()
            if (_state.value.inBookshelf) {
                appDb.bookDao.replace(oldBook, book)
                // runPreUpdateJs 有可能会修改 book 的 bookUrl
                if (oldBook.bookUrl != book.bookUrl) {
                    BookStorageProviders.get().updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.insert(*tmp.toTypedArray())
            }
            tmp
        } catch (e: Throwable) {
            AppLog.put("获取目录失败\n${e.message}", e)
            Toasters.get().toast(getString(Res.string.error_get_chapter_list))
            emptyList()
        }
    }

    /** 对照 app 端 Book.save() (BookExtensions), shared 无该扩展。 */
    private suspend fun saveBook(book: Book) {
        book.removeType(BookType.notShelf)
        if (appDb.bookDao.has(book.bookUrl)) {
            appDb.bookDao.update(book)
        } else {
            appDb.bookDao.insert(book)
        }
    }

    override fun onCleared() {
        scope.cancel()
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
