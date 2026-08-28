package io.legado.app.ui.book.info

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.addType
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.updateTo
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.ConvertUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.error_get_book_info
import legado.shared.generated.resources.error_get_chapter_list
import legado.shared.generated.resources.error_no_source
import legado.shared.generated.resources.lasted_show
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
    private val scope = screenModelScope("书籍详情")

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

    // 等待对话框显隐 (对照 BookInfoViewModelShared._waitDialogData, webFile 流程的加载指示)
    private val _waitDialog = MutableStateFlow<Boolean?>(null)
    val waitDialog: StateFlow<Boolean?> = _waitDialog.asStateFlow()

    // 动作事件 (对照 BookInfoViewModelShared._actionLive, webFile 流程的 selectBooksDir 回调)
    private val _actionLive = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionLive: SharedFlow<String> = _actionLive.asSharedFlow()

    fun upWaitDialog(show: Boolean) {
        _waitDialog.value = show
    }

    fun postAction(action: String) {
        _actionLive.tryEmit(action)
    }

    /**
     * 最近一次解析到的目录（对照 app 端 `BookInfoViewModel.chapterListData`）。
     * 未加书架的书不落库，跳目录页/阅读页只能靠这份内存章节表。
     */
    var loadedChapterList: List<BookChapter>? = null
        private set

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
     * 刷新书籍信息 + 目录 (对照 app 端 BookInfoViewModel.refreshBook + BaseReadViewModel.loadBookInfo)。
     * [bookSource] 由调用方查好传入; 无论成功失败都会 dispatch UpdateToc 解除加载中状态。
     */
    fun refresh(
        book: Book,
        bookSource: BookSource?,
        errorLoadToc: String,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        isSearchBook: Boolean = false,
    ) {
        dispatch(BookInfoUiEvent.Refresh)
        scope.launch(IoDispatcher) {
            // 对照 app 端 refreshBook 前置: 本地非漫画书拉 WebDav 远端更新, 其余同步书源名
            if (book.isLocal && !book.isImage) {
                try {
                    PlatformCapabilityProviders.get().refreshWebDavBook(book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 对照 app 端 refreshBook.onError: 远端已删则退回本地书
                    if (e is ObjectNotFoundException) {
                        book.origin = BookType.localTag
                    } else {
                        AppLog.put("下载远程书籍<${book.name}>失败", e)
                    }
                }
            } else {
                bookSource?.let {
                    if (book.originName != it.bookSourceName) book.originName = it.bookSourceName
                }
            }
            val toc = try {
                loadBookInfo(book, bookSource, canReName, runPreUpdateJs, isSearchBook)
            } catch (e: Throwable) {
                AppLog.put("获取书籍信息失败\n${e.message}", e)
                Toasters.get().toast(getString(Res.string.error_get_book_info))
                emptyList()
            }
            // 对照 app 端 showBook + upLoading(false, chapterList)
            upShowBook(book, toc, errorLoadToc)
        }
    }

    /**
     * 仅拉目录 (对照 app 端 BaseReadViewModel.upBook 中 tocUrl 非空时的 loadChapterList 分支)。
     */
    fun loadToc(
        book: Book,
        bookSource: BookSource?,
        errorLoadToc: String,
        runPreUpdateJs: Boolean = true,
    ) {
        dispatch(BookInfoUiEvent.Refresh)
        scope.launch(IoDispatcher) {
            val toc = loadChapterList(book, bookSource, runPreUpdateJs)
            upShowBook(book, toc, errorLoadToc)
        }
    }

    /** 最新章节文案按 book 现值构造 (对照 Activity showBook/upLoading 每次读 curBook.latestChapterTitle)。 */
    suspend fun lastedTitleOf(book: Book): String =
        getString(Res.string.lasted_show, book.latestChapterTitle ?: "")

    /**
     * 字数文案 (对照 Activity upKinds 字数段): book.wordCount + 本地书文件大小, 逗号拼接。
     * 详情刷新会原地改写 book.wordCount, 未在架书不落库无 DB 观察者,
     * 需在每次 showBook 后重算 (对照 archive: bookData 观察 → showBook → upKinds)。
     */
    suspend fun wordCountTextOf(book: Book): String? {
        val wordCounts = arrayListOf<String>()
        book.wordCount?.takeIf { it.isNotBlank() }?.let { wordCounts.add(it) }
        if (book.isLocal) {
            val size = try {
                if (book.bookUrl.startsWith("http", true) ||
                    book.bookUrl.startsWith("dav", true)
                ) 0L
                else PlatformCapabilityProviders.getOrNull()
                    ?.localBookFileSize(book.bookUrl) ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (size > 0) wordCounts.add(ConvertUtils.formatFileSize(size))
        }
        return when {
            wordCounts.isNotEmpty() -> wordCounts.joinToString(",")
            book.isLocal -> ""
            else -> null
        }
    }

    /** 加载完成后回填书籍与目录文案 (对照 Activity showBook + upLoading(false, chapterList))。 */
    private suspend fun upShowBook(book: Book, toc: List<BookChapter>, errorLoadToc: String) {
        if (toc.isNotEmpty()) loadedChapterList = toc
        val lasted = lastedTitleOf(book)
        dispatch(BookInfoUiEvent.ShowBook(book, lasted))
        dispatch(
            BookInfoUiEvent.UpdateToc(
                tocText = if (toc.isEmpty()) errorLoadToc else book.durChapterTitle.orEmpty(),
                lastedTitle = if (toc.isEmpty()) null else lasted,
            )
        )
        dispatch(BookInfoUiEvent.UpdateWordCount(wordCountTextOf(book)))
    }

    /** 对照 app 端 BaseReadViewModel.loadBookInfo。 */
    private suspend fun loadBookInfo(
        book: Book,
        bookSource: BookSource?,
        canReName: Boolean,
        runPreUpdateJs: Boolean,
        isSearchBook: Boolean,
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
            if (isSearchBook) {
                val dbBook = appDb.bookDao.getBook(book.bookUrl)
                    ?: appDb.bookDao.getBook(book.name, book.author)
                // 搜索来源的书加载详情后书名可能变化, 同源则并回书架那本, 异源则标记不在书架
                // (对照 app 端 loadBookInfo, 上游 #3652 #4619 #3149)
                if (dbBook != null && dbBook.origin == book.origin) {
                    dbBook.updateTo(book)
                    dispatch(BookInfoUiEvent.UpdateBookshelf(true))
                } else {
                    book.addType(BookType.notShelf)
                    dispatch(BookInfoUiEvent.UpdateBookshelf(false))
                }
            }
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
        if (!book.isLocal && bookSource == null) {
            Toasters.get().toast(getString(Res.string.error_no_source))
            return emptyList()
        }
        return try {
            BookChapterLoader.fetchFromSource(book, bookSource, runPreUpdateJs)
        } catch (e: CancellationException) {
            throw e
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
