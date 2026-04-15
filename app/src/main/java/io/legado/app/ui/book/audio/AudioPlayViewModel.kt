package io.legado.app.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import io.legado.app.model.AudioPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPlayViewModel(application: Application) : BaseReadViewModel(application) {
    val titleData = MutableLiveData<String>()

    override var curBook: Book?
        get() = AudioPlay.book
        set(value) {
            AudioPlay.book = value
        }
    override var curBookSource: BookSource?
        get() = AudioPlay.bookSource
        set(value) {
            AudioPlay.bookSource = value
        }

    override fun onUpSource(book: Book) {
        AudioPlay.bookSource = book.getBookSource()
    }

    fun initData(intent: Intent) = AudioPlay.apply {
        execute {
            val book =
                (if (intent.action != "activity") IntentData.book else book)
                    ?: return@execute
            upBook(book)
            withContext(Dispatchers.Main){
                AudioPlay.chapterList = chapterListData.value
            }
            if (AudioPlay.book?.bookUrl == book.bookUrl) upData(curBook!!)
            else resetData(curBook!!)
            titleData.postValue(book.name)
            if (status == Status.STOP) loadOrUpPlayUrl()
        }.onFinally {
            saveRead()
        }
    }
}