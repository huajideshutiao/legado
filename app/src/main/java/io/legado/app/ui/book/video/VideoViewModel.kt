package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class VideoViewModel(application: Application) : BaseViewModel(application) {

    val bookTitle = MutableLiveData<String>()
    val chapterList = MutableLiveData<List<BookChapter>>()
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    var position: Long = 0L
    var bookSource: BookSource? = null
    lateinit var book: Book
    var oldChapterIndex: Int? = null
    private var inBookshelf = false

    fun initData(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        execute {
            val tmp = appDb.bookDao.getBook(bookUrl)
            if (tmp == null) {
                appCtx.toastOnUi("book is null")
                return@execute
            }
            book = tmp
            bookSource = book.getBookSource()
            bookTitle.postValue(book.name)
            position = book.durChapterPos.toLong()
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (oldChapterIndex == null) oldChapterIndex = book.durChapterIndex
            val tmp1 = appDb.bookChapterDao.getChapterList(book.bookUrl)
            chapterList.postValue(tmp1)
            initChapter(tmp1.getOrNull(book.durChapterIndex))
        }
    }

    private fun initChapter(chapter: BookChapter?) {
        chapter?.let {
            WebBook.getContent(viewModelScope, bookSource!!, book, it)
                .onSuccess { content ->
                    if (content.isEmpty()) {
                        appCtx.toastOnUi("未获取到资源链接")
                    } else {
                        val analyzeUrl = AnalyzeUrl(content, coroutineContext = coroutineContext)
                        videoUrl.postValue(analyzeUrl)
                    }
                }.onError { e ->
                    AppLog.put("获取资源链接出错\n$e", e, true)
                }

        }
    }

    fun changeChapter(chapter: BookChapter) {
        if (chapter.index != book.durChapterIndex) {
            book.durChapterIndex = chapter.index
            position = 0L
            initChapter(chapter)
        }
    }

    fun upSource() {
        execute {
            bookSource = book.getBookSource()
        }
    }

    fun saveRead(position: Long) {
        Coroutine.async {
            book.lastCheckCount = 0
            book.durChapterTime = System.currentTimeMillis()
            book.durChapterPos = position.toInt()
            book.update()
        }
    }
}