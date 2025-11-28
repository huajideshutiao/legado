package io.legado.app.ui.book.video

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.GlobalVars
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.toastOnUi

class VideoViewModel(application: Application) : BaseViewModel(application) {
    val bookTitle = MutableLiveData<String>()
    val chapterList = MutableLiveData<List<BookChapter>>()
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    var position: Long = 0L
    var bookSource: BookSource? = null
    val book: Book by lazy {GlobalVars.nowBook!!}
    private var oldChapterIndex: Int? = null


    fun initData() {
        execute {
            bookSource = book.getBookSource() ?: return@execute
            bookTitle.postValue(book.name)
            position = book.durChapterPos.toLong()
            if (oldChapterIndex == null) oldChapterIndex = book.durChapterIndex
            if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(bookSource!!, book, canReName = true)
            val tmp1= if (book.totalChapterNum==0)WebBook.getChapterListAwait(bookSource!!, book, true).getOrNull()!!
            else appDb.bookChapterDao.getChapterList(book.bookUrl)
            chapterList.postValue(tmp1)
            initChapter(tmp1[(book.durChapterIndex)])
        }
    }

    private fun initChapter(chapter: BookChapter) {
        Coroutine.async(viewModelScope) {
            getContentAwait(bookSource!!, book, chapter, needSave = false)
        }.onSuccess { content ->
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

    fun changeChapter(chapter: BookChapter) {
        if (chapter.index != book.durChapterIndex) {
            book.durChapterIndex = chapter.index
            book.durChapterTitle = chapter.title
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
            if (book.order == 0) book.order = appDb.bookDao.minOrder - 1
            book.save()
            chapterList.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
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
        }.onSuccess {
            success?.invoke()
        }
    }

    fun saveRead(position: Long) {
        Coroutine.async {
            book.apply {
                lastCheckCount = 0
                durChapterTime = System.currentTimeMillis()
                durChapterPos = position.toInt()
                update()
            }
        }
    }
}