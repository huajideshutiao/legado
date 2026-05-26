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

/**
 * 音频播放 ViewModel。
 *
 * 与 Activity 的契约:
 * - 数据来源:仍由 [AudioPlay] 单例持有跨组件状态(book / chapterList / durChapter / lrcData ...),
 *   Activity 直接读这些字段。
 * - 命令面:Activity 不再直接调 [AudioPlay] 单例的命令方法,统一通过本类暴露的方法,
 *   这样 UI 层与 Service 派发之间多一层间接,便于后续替换为绑定/Flow 的方案。
 */
class AudioPlayViewModel(application: Application) : BaseReadViewModel(application) {

    val titleData = MutableLiveData<String>()

    override var curBook: Book? = null
    override var inBookshelf: Boolean
        get() = AudioPlay.inBookshelf
        set(value) {
            AudioPlay.inBookshelf = value
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
                (if (intent.action != "activity") IntentData.book else book) ?: return@execute
            upBook(book)
            withContext(Dispatchers.Main) {
                AudioPlay.chapterList = chapterListData.value
            }
            if (AudioPlay.book?.bookUrl == book.bookUrl) upData(curBook!!) else resetData(curBook!!)
            titleData.postValue(book.name)
            if (status == Status.STOP) loadOrUpPlayUrl()
        }
    }

    // ---------- 播放命令 ----------

    /** 根据当前状态切换播放/暂停/开播 */
    fun togglePlay() {
        when (AudioPlay.status) {
            Status.PLAY -> AudioPlay.pause()
            Status.PAUSE -> AudioPlay.resume()
            else -> AudioPlay.loadOrUpPlayUrl()
        }
    }

    fun stop() = AudioPlay.stop()

    fun resume() = AudioPlay.resume()

    fun next() = AudioPlay.next()

    fun prev() = AudioPlay.prev()

    fun skipTo(index: Int) = AudioPlay.skipTo(index)

    fun adjustSpeed(speed: Float) = AudioPlay.adjustSpeed(speed)

    fun adjustProgress(position: Int) = AudioPlay.adjustProgress(position)

    fun setTimer(minute: Int) = AudioPlay.setTimer(minute)

    fun changePlayMode() = AudioPlay.changePlayMode()
}
