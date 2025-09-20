package io.legado.app.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.model.AudioPlay
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import org.mozilla.javascript.NativeArray
import kotlin.coroutines.coroutineContext
import kotlin.sequences.forEach

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()
    val coverData = MutableLiveData<String?>()
    val lrcData = MutableLiveData<MutableList<Pair<Int, String>>>()
    private val source by lazy { AudioPlay.bookSource ?: throw NoStackTraceException("no book source")}
    private val lrcRule by lazy { source.getContentRule().lrcRule }
    private val musicCover by lazy { source.getContentRule().musicCover }

    fun initData(intent: Intent) = AudioPlay.apply {
        execute {
            val bookUrl = intent.getStringExtra("bookUrl") ?: book?.bookUrl ?: return@execute
            val book = appDb.bookDao.getBook(bookUrl) ?: return@execute
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            initBook(book)
        }.onFinally {
            saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = AudioPlay.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            AudioPlay.upData(book)
        } else {
            AudioPlay.resetData(book)
        }
        titleData.postValue(book.name)
        refresh()
        if (book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (AudioPlay.chapterSize == 0 && !loadChapterList(book)) {
            return
        }
    }
    fun refreshData() = AudioPlay.apply {
        execute {
            refresh()
        }
    }
    private suspend fun refresh(){
        var lrcContent : NativeArray ?= null
        val chapter = appDb.bookChapterDao.getChapter(AudioPlay.book!!.bookUrl, AudioPlay.durChapterIndex)!!
        if (!lrcRule.isNullOrBlank()) {
                val analyzeRule = AnalyzeRule(AudioPlay.book, AudioPlay.bookSource)
                analyzeRule.setCoroutineContext(coroutineContext)
                analyzeRule.setBaseUrl(chapter.url)
                analyzeRule.setChapter(chapter)
                if (!musicCover.isNullOrBlank()) {
                    coverData.postValue(analyzeRule.evalJS(musicCover!!).toString())
                }
            lrcContent = analyzeRule.evalJS(lrcRule!!) as NativeArray
            }
        if(coverData.value==null)coverData.postValue(AudioPlay.book!!.getDisplayCover()?:"")
        val tmp= mutableListOf<Pair<Int, String>>()
        if(lrcContent!=null) {
            for (i in lrcContent.indices){
                var oldIndex = 0
            (lrcContent[i] as String ).trim().lineSequence().forEach { line ->
            val split = line.indexOf("]")
            if (line[1].isDigit()) {
                val textPart = line.substring(split+1)
                val min = line.substring(1, 3).toInt()
                val sec = line.substring(4, 6).toInt()
                var ms = 0
                if (split != 6) {
                    ms = line.substring(7,split).toIntOrNull() ?: 0
                    ms = ms * (if(split==10)1 else 10)
                }
                val time = min * 60_000 + sec * 1000 + ms
                if (i != 0) {
                    val index = tmp.subList(oldIndex,tmp.size).indexOfFirst { it.first == time }
                    oldIndex += index
                    tmp[oldIndex] = Pair(time, "${tmp[oldIndex].second}\n$textPart")
                } else {
                    tmp.add(Pair(time, textPart))
                }
            }
        }
            }
        }
        lrcData.postValue(tmp)
    }

    private suspend fun loadBookInfo(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(bookSource, book)
            return true
        } catch (e: Exception) {
            AppLog.put("详情页出错: ${e.localizedMessage}", e, true)
            return false
        }
    }

    private suspend fun loadChapterList(book: Book): Boolean {
        val bookSource = AudioPlay.bookSource ?: return true
        try {
            val oldBook = book.copy()
            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            AudioPlay.chapterSize = cList.size
            AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
            AudioPlay.upDurChapter()
            return true
        } catch (e: Exception) {
            context.toastOnUi(R.string.error_load_toc)
            return false
        }
    }

    fun upSource() {
        execute {
            val book = AudioPlay.book ?: return@execute
            AudioPlay.bookSource = book.getBookSource()
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        execute {
            AudioPlay.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            AudioPlay.book?.delete()
            appDb.bookDao.insert(book)
            AudioPlay.book = book
            AudioPlay.bookSource = source
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            AudioPlay.upDurChapter()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            AudioPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

}