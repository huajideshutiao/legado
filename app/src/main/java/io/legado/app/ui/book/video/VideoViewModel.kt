package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.update
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class VideoViewModel(application: Application) : BaseViewModel(application) {
    val videoUrl = MutableLiveData<String>()
    val bookTitle = MutableLiveData<String>()
    val videoTitle = MutableLiveData<String>()
    val bookSource = MutableLiveData<BookSource>()
    private var book: Book? = null
    private var inBookshelf = false
    private var oldChapterIndex: Int? = null

    fun initData(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                appCtx.toastOnUi("book or source is null")
                return@execute
            }
            bookSource.postValue(book!!.getBookSource())
            bookTitle.postValue(book!!.name)
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (oldChapterIndex == null) oldChapterIndex = book!!.durChapterIndex
            initChapter(book!!)
        }
    }

    private fun initChapter(book: Book) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)
        chapter?.let {
            videoTitle.postValue(  chapter.title)
            WebBook.getContent(viewModelScope, bookSource.value!!, book, it)
                .onSuccess { content ->
                    if (content.isEmpty()) {
                        appCtx.toastOnUi("未获取到资源链接")
                    } else {
                        videoUrl.value = content
                    }
                }.onError { e ->
                    AppLog.put("获取资源链接出错\n$e", e, true)
                }

        }
    }

    fun initChapter() {
        execute {
            initChapter(book!!)
        }
    }

    fun upSource() {
        execute {
            val book = book ?: return@execute
            bookSource.value = book.getBookSource()
        }
    }

    fun saveRead() {
        val book = book ?: return
        execute {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            val chapterChanged = book.durChapterIndex != oldChapterIndex
            if (chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }
            }
            book.update()
        }
    }

//    private suspend fun loadChapterList(book: Book): Boolean {
//        val bookSource = AudioPlay.bookSource ?: return true
//        try {
//            val oldBook = book.copy()
//            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
//            if (oldBook.bookUrl == book.bookUrl) {
//                appDb.bookDao.update(book)
//            } else {
//                appDb.bookDao.replace(oldBook, book)
//            }
//            appDb.bookChapterDao.delByBook(book.bookUrl)
//            appDb.bookChapterDao.insert(*cList.toTypedArray())
//            AudioPlay.chapterSize = cList.size
//            AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
//            AudioPlay.upDurChapter()
//            return true
//        } catch (e: Exception) {
//            context.toastOnUi(R.string.error_load_toc)
//            return false
//        }
//    }
}