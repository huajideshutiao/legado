package io.legado.app.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.GlobalVars
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.model.AudioPlay
import io.legado.app.utils.postEvent

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()

    fun initData(intent: Intent) = AudioPlay.apply {
        execute {
            val book = (if(intent.action != "activity") GlobalVars.nowBook else book)?: return@execute
            inBookshelf = !book.isNotShelf
            if (AudioPlay.book?.bookUrl == book.bookUrl) upData(book)
            else resetData(book)
            titleData.postValue(book.name)
            if (status == Status.STOP) loadOrUpPlayUrl()
        }.onFinally {
            saveRead()
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