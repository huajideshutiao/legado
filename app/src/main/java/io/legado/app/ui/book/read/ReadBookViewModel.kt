package io.legado.app.ui.book.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.toStringArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * 阅读界面数据处理
 */
class ReadBookViewModel(application: Application) : BaseReadViewModel(application) {
    val permissionDenialLiveData = MutableLiveData<Int>()
    var isInitFinish = false
    var searchContentQuery = ""
    var searchResultList: List<SearchResult>? = null
    var searchResultIndex: Int = 0

    override var curBook: Book? = null
    override var inBookshelf: Boolean
        get() = ReadBook.inBookshelf
        set(value) {
            ReadBook.inBookshelf = value
        }

    init {
        AppConfig.detectClickArea()
    }

    override fun onSourceChanged(book: Book, toc: List<BookChapter>) {
        ReadBook.upMsg(context.getString(R.string.loading))
        ReadBook.initData(book)
        ReadBook.upMsg(null)
        ReadBook.loadContent(resetPageOffset = true)
    }

    override fun applyProgress(progress: BookProgress) {
        ReadBook.setProgress(progress)
    }

    override fun onSourceChanging(msg: String) {
        if (msg.isNotEmpty()) {
            ReadBook.upMsg(msg)
        } else {
            ReadBook.upMsg(null)
        }
    }

    override fun onUpSource(book: Book) {
        ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
    }

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        val book = IntentData.book ?: appDb.bookDao.lastReadBook ?: ReadBook.book ?: return
        ReadBook.upReadBookConfig(if (book is SearchBook) book.toBook() else book as Book)
        Looper.myQueue().addIdleHandler {
            execute {
                ReadBook.chapterChanged = intent.getBooleanExtra("chapterChanged", false)
                upBook(book)
                initBook(curBook!!)
            }.onSuccess {
                success?.invoke()
            }.onError {
                val msg = "初始化数据失败\n${it.localizedMessage}"
                ReadBook.upMsg(msg)
                AppLog.put(msg, it)
            }
            false
        }
        //.onFinally { ReadBook.saveRead() }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
        withContext(Dispatchers.Main) {
            ReadBook.chapterList = chapterListData.value
        }
        ReadBook.initData(book)
        isInitFinish = true
        if (book.isLocal && !checkLocalBookFileExist(book)) {
            return
        }
        if (chapterListData.value.isNullOrEmpty()) return
        ReadBook.upMsg(null)
        if (!isSameBook) {
            ReadBook.loadContent(resetPageOffset = true)
        } else {
            ReadBook.loadOrUpContent()
        }
        if (ReadBook.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadBook.chapterChanged = false
        } else if (!(isSameBook && BaseReadAloudService.isRun) && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress({ progress -> ReadBook.callBack?.sureNewProgress(progress) })
            } else {
                syncBookProgress(book)
            }
        }
        if (!book.isLocal && ReadBook.bookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            FileBook.getBookInputStream(book)
            return true
        } catch (e: Throwable) {
            ReadBook.upMsg("打开本地书籍出错: ${e.localizedMessage}")
            if (e is SecurityException || e is FileNotFoundException) {
                permissionDenialLiveData.postValue(0)
            }
            return false
        }
    }

    /**
     * 加载目录
     */
    fun loadChapterList(book: Book) {
        execute {
            if (loadChapterListAwait(book)) {
                ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        if (book.isLocal) {
            kotlin.runCatching {
                FileBook.getChapterList(book).let {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    appDb.bookDao.update(book)
                    ReadBook.onChapterListUpdated(book)
                }
                return true
            }.onFailure {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        permissionDenialLiveData.postValue(1)
                    }

                    else -> {
                        AppLog.put("LoadTocError:${it.localizedMessage}", it)
                        ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                    }
                }
                return false
            }
        } else {
            ReadBook.bookSource?.let {
                val oldBook = book.copy()
                WebBook.getChapterListAwait(it, book, true)
                    .onSuccess { cList ->
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*cList.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                        return true
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        ReadBook.upMsg(context.getString(R.string.error_load_toc))
                        return false
                    }
            }
        }
        return true
    }

    fun openChapter(index: Int, durChapterPos: Int = 0, success: (() -> Unit)? = null) {
        ReadBook.openChapter(index, durChapterPos, success = success)
    }

    override fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadBook.book
        execute {
            book?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    fun upBookSource(success: (() -> Unit)?) {
        upSource()
        success?.invoke()
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    fun refreshContentAfter(book: Book) {
        execute {
            appDb.bookChapterDao.getChapterList(
                book.bookUrl,
                ReadBook.durChapterIndex,
                book.totalChapterNum
            ).forEach { chapter ->
                BookHelp.delContent(book, chapter)
            }
            ReadBook.loadContent(false)
        }
    }

    fun refreshContentAll(book: Book) {
        execute {
            BookHelp.clearCache(book)
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存内容
     */
    fun saveContent(book: Book, content: String) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.saveText(book, chapter, content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    /**
     * 反转内容
     */
    fun reverseContent(book: Book) {
        execute {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@execute
            val content = BookHelp.getContent(book, chapter) ?: return@execute
            val stringBuilder = StringBuilder()
            content.toStringArray().forEach {
                stringBuilder.insert(0, it)
            }
            BookHelp.saveText(book, chapter, stringBuilder.toString())
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    /**
     * 内容搜索跳转
     */
    fun searchResultPositions(
        textChapter: TextChapter,
        searchResult: SearchResult
    ): Array<Int> {
        // calculate search result's pageIndex
        val pages = textChapter.pages
        val content = textChapter.getContent()
        val queryLength = searchContentQuery.length

        var count = 0
        var index = content.indexOf(searchContentQuery)
        while (count != searchResult.resultCountWithinChapter) {
            index = content.indexOf(searchContentQuery, index + queryLength)
            count += 1
        }
        val contentPosition = index
        var pageIndex = 0
        var length = pages[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < pages.size) {
            pageIndex += 1
            length += pages[pageIndex].text.length
        }

        // calculate search result's lineIndex
        val currentPage = pages[pageIndex]
        val curTextLines = currentPage.lines
        var lineIndex = 0
        var curLine = curTextLines[lineIndex]
        length = length - currentPage.text.length + curLine.text.length
        if (curLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
            lineIndex += 1
            curLine = curTextLines[lineIndex]
            length += curLine.text.length
            if (curLine.isParagraphEnd) length++
        }

        // charIndex
        val currentLine = currentPage.lines[lineIndex]
        var curLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) curLineLength++
        length -= curLineLength

        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        // change line
        if ((charIndex + queryLength) > curLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        // changePage
        if ((lineIndex + addLine + 1) > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        return arrayOf(pageIndex, lineIndex, charIndex, addLine, charIndex2)
    }

    /**
     * 翻转删除重复标题
     */
    fun reverseRemoveSameTitle() {
        execute {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            BookHelp.setRemoveSameTitle(
                book, textChapter.chapter, !textChapter.sameTitleRemoved
            )
            ReadBook.loadContent(ReadBook.durChapterIndex)
        }
    }

    /**
     * 刷新图片
     */
    fun refreshImage(src: String) {
        execute {
            ReadBook.book?.let { book ->
                val vFile = BookHelp.getImage(book, src)
                ImageProvider.bitmapLruCache.remove(vFile.absolutePath)
                vFile.delete()
            }
        }.onFinally {
            ReadBook.loadContent(false)
        }
    }

    override fun saveImage(src: String?, uri: Uri) {
        curBook = ReadBook.book
        super.saveImage(src, uri)
    }

    /**
     * 替换规则变化
     */
    fun replaceRuleChanged() {
        execute {
            ReadBook.book?.let {
                ContentProcessor.get(it.name, it.origin).upReplaceRules()
                ReadBook.loadContent(resetPageOffset = false)
            }
        }
    }

    fun disableSource() {
        execute {
            ReadBook.bookSource?.let {
                it.enabled = false
                appDb.bookSourceDao.update(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.stop(context)
        }
    }

}
