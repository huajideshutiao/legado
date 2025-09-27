package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.toastOnUi

class VideoViewModel(application: Application) : BaseViewModel(application) {
    val bookTitle = MutableLiveData<String>()
    val chapterList = MutableLiveData<List<BookChapter>>()
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    var position: Long = 0L
    var bookSource: BookSource? = null
    lateinit var book: Book
    var inBookshelf = false
    private var oldChapterIndex: Int? = null


    fun initData(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        val tmp = intent.getStringExtra("from") == "search"
        inBookshelf = intent.getBooleanExtra("inBookshelf", false)
        execute {
            var tmp0 = appDb.bookDao.getBook(bookUrl)
            if (tmp && tmp0 == null)tmp0 = appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()
            if (tmp0 == null) {
                context.toastOnUi("book is null")
                return@execute
            }
            book = tmp0
            bookSource = book.getBookSource() ?: return@execute
            bookTitle.postValue(book.name)
            position = book.durChapterPos.toLong()
            if (oldChapterIndex == null) oldChapterIndex = book.durChapterIndex
            if (book.tocUrl.isEmpty()) {
                book = WebBook.getBookInfoAwait(bookSource!!, book, canReName = true)
            }
            var tmp1= appDb.bookChapterDao.getChapterList(book.bookUrl)
            if (tmp&&tmp1.isEmpty())tmp1 = WebBook.getChapterListAwait(bookSource!!, book, true).getOrNull()!!
            chapterList.postValue(tmp1)
            initChapter(tmp1.getOrNull(book.durChapterIndex))
        }
    }

    private fun initChapter(chapter: BookChapter?) {
        chapter?.let {
            WebBook.getContent(viewModelScope, bookSource!!, book, it)
                .onSuccess { content ->
                    if (content.isEmpty()) {
                        context.toastOnUi("未获取到资源链接")
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

    fun addToBookshelf(success: (() -> Unit)?) {
        execute {

            book.removeType(BookType.notShelf)
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            appDb.bookDao.getBook(book.name, book.author)?.let {
                book.durChapterIndex = it.durChapterIndex
                book.durChapterPos = it.durChapterPos
                book.durChapterTitle = it.durChapterTitle
            }
            if (ReadBook.book?.isSameNameAuthor(book) == true) {
                ReadBook.book = book
            } else if (AudioPlay.book?.isSameNameAuthor(book) == true) {
                AudioPlay.book = book
            }
            book.save()

            chapterList.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
            inBookshelf = true
        }.onSuccess {
            success?.invoke()
        }
    }

    fun delBook(success: (() -> Unit)? = null) {
        execute {
            book.delete()
            if (book.coverUrl.isNullOrBlank()) {
                val future = Glide.with(context).downloadOnly()
                    .apply(RequestOptions().onlyRetrieveFromCache(true)).load(book.coverUrl)
                    .signature(ObjectKey("covers")).submit()
                if (future.get().exists()) future.get().delete()
                Glide.with(context).clear(future)
            }
            inBookshelf = false

        }.onSuccess {
            success?.invoke()
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