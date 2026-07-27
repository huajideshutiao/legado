package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseReadViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.saveRead
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoViewModel(application: Application) : BaseReadViewModel(application) {

    // 组合 shared VM, 委托 parseVideoContent/loadChapter/refreshChapter 等业务逻辑
    // public: 供 Activity 直接调 switchResolution/retryOnPlayError/saveVideoProgressOnExit/createBookmark
    val sharedVM = VideoPlayViewModelShared(
        scope = viewModelScope,
        prefStore = AndroidPreferenceStoreProvider(),
    )

    // LiveData 适配层: 桥接 shared VM 的 StateFlow, 供 Activity observe
    val videoUrl = MutableLiveData<AnalyzeUrlCore?>()
    val videoSource = MutableLiveData<VideoSource?>()
    val resolutions = MutableLiveData<List<VideoResolution>>()
    var currentResolutionIndex: Int
        get() = sharedVM.currentResolutionIndex
        set(value) { sharedVM.currentResolutionIndex = value }
    var position: Long = 0L
    override var curBook: Book? = null

    init {
        viewModelScope.launch {
            sharedVM.videoUrl.collect { videoUrl.postValue(it) }
        }
        viewModelScope.launch {
            sharedVM.videoSource.collect { videoSource.postValue(it) }
        }
        viewModelScope.launch {
            sharedVM.resolutions.collect { resolutions.postValue(it) }
        }
        viewModelScope.launch {
            sharedVM.error.collect { err ->
                if (!err.isNullOrEmpty()) context.toastOnUi(err)
            }
        }
    }

    override fun onUpSource(book: Book) {
        curBookSource = book.getBookSource()
    }

    override fun applyProgress(progress: BookProgress) {
        val book = curBook ?: return
        if (progress.durChapterIndex >= (chapterListData.value?.size ?: 0)) return
        val chapterChanged = book.durChapterIndex != progress.durChapterIndex
        book.durChapterIndex = progress.durChapterIndex
        book.durChapterPos = progress.durChapterPos
        book.durChapterTitle = progress.durChapterTitle
        position = progress.durChapterPos.coerceAtLeast(0).toLong()
        saveRead(progress.durChapterPos.toLong())
        if (chapterChanged) {
            sharedVM.loadChapter(progress.durChapterIndex, persistProgress = false)
        }
    }

    override fun getSyncProgressMsg(): String = "已同步最新视频播放进度"

    fun initData(intent: Intent? = null) {
        execute {
            upBook(IntentData.book ?: return@execute)
            ReadTimeRecorder.setBook(ReadTimeRecorder.Source.VIDEO, curBook!!.name)
            val overrideIndex = intent?.getIntExtra("chapterIndex", -1) ?: -1
            if (overrideIndex >= 0) {
                val overridePos = intent!!.getIntExtra("chapterPos", 0)
                intent.removeExtra("chapterIndex")
                intent.removeExtra("chapterPos")
                curBook!!.durChapterIndex = overrideIndex
                curBook!!.durChapterPos = overridePos
                saveRead(overridePos.toLong())
            }
            position = curBook!!.durChapterPos.coerceAtLeast(0).toLong()
            val chapterList = withContext(Dispatchers.Main) { chapterListData.value }
            val source = curBookSource ?: return@execute
            sharedVM.initWithExternalChapters(curBook!!, source, chapterList!!, curBook!!.durChapterIndex)
            curBook?.takeIf { inBookshelf }?.let { syncBookProgress(it) }
        }
    }

    fun refreshChapter() {
        sharedVM.refreshChapter(persistProgress = false)
    }

    /** 加载指定章节: 同步 app 端 curBook 状态后委托 sharedVM.loadChapter */
    fun loadChapter(index: Int) {
        val book = curBook ?: return
        val chapters = chapterListData.value ?: return
        val chapter = chapters.getOrNull(index) ?: return
        if (index == book.durChapterIndex) return
        book.durChapterIndex = index
        book.durChapterTitle = chapter.title
        position = 0L
        saveRead(0L)
        sharedVM.loadChapter(index, persistProgress = false)
    }

    /** 切下一章: 委托 sharedVM, 同步 app 端 curBook 状态 */
    fun moveToNextChapter(): Boolean {
        val book = curBook ?: return false
        val chapters = chapterListData.value ?: return false
        val nextIndex = book.durChapterIndex + 1
        if (nextIndex >= chapters.size) return false
        loadChapter(nextIndex)
        return true
    }

    /** 切上一章: 委托 sharedVM, 同步 app 端 curBook 状态 */
    fun moveToPrevChapter(): Boolean {
        val book = curBook ?: return false
        val chapters = chapterListData.value ?: return false
        val prevIndex = book.durChapterIndex - 1
        if (prevIndex < 0) return false
        loadChapter(prevIndex)
        return true
    }

    fun delBook(success: (() -> Unit)?) = delBook(false, success)

    fun saveRead(position: Long) {
        Coroutine.async {
            curBook!!.apply {
                durChapterPos = position.toInt()
                saveRead()
            }
        }
    }
}
