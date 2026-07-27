package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.saveRead
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoViewModel(application: Application) : BaseReadViewModel(application) {
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    val videoSource = MutableLiveData<VideoSource>()
    val resolutions = MutableLiveData<List<VideoResolution>>()
    var currentResolutionIndex = 0
    var position: Long = 0L
    override var curBook: Book? = null

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
            chapterListData.value?.getOrNull(progress.durChapterIndex)?.let { initChapter(it) }
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
            initChapter(chapterList!![curBook!!.durChapterIndex])
            curBook?.takeIf { inBookshelf }?.let { syncBookProgress(it) }
        }
    }

    private fun initChapter(chapter: BookChapter) {
        execute {
            chapter.resourceUrl ?: getContentAwait(
                curBookSource!!, curBook!!, chapter, needSave = false
            )
        }.onSuccess { content ->
            if (content.isEmpty()) {
                context.toastOnUi("未获取到资源链接")
            } else {
                if (chapter.resourceUrl != content) {
                    chapter.resourceUrl = content
                    if (inBookshelf) appDb.bookChapterDao.update(chapter)
                }
                parseVideoContent(content)
            }
        }.onError { e ->
            AppLog.put("获取资源链接出错\n$e", e, true)
        }
    }

    fun refreshChapter() {
        chapterListData.value?.let { chapterList ->
            val chapter = chapterList[curBook!!.durChapterIndex]
            chapter.resourceUrl = null
            execute {
                initChapter(chapter)
            }
        }
    }

    private fun parseVideoContent(content: String) {
        // 解析算法已下沉至 commonMain (VideoViewModelShared.kt):
        // parseVideoSource 处理 JSON / `::` 格式, extractVideoUrlAndReferer 处理 #BASE: 格式。
        // 此处仅保留平台专属的 LiveData 推送与 AnalyzeUrl 构造, 逻辑未变。
        val source = parseVideoSource(content)

        if (source != null && source.resolutions.isNotEmpty()) {
            videoSource.postValue(source)
            resolutions.postValue(source.resolutions)
            currentResolutionIndex = source.defaultIndex
            val resolution = source.getResolution()
            if (resolution != null) {
                videoUrl.postValue(
                    AnalyzeUrl(
                        rawUrl = resolution.url,
                        source = curBookSource,
                        headerMapF = source.headers
                    )
                )
            }
        } else {
            val analyzeUrl = if (content.startsWith("http")) {
                AnalyzeUrl(content)
            } else {
                val (videoUrl, fakeUrl) = extractVideoUrlAndReferer(content)
                AnalyzeUrl("").apply {
                    url = videoUrl
                    headerMap["Referer"] = fakeUrl
                }
            }
            videoUrl.postValue(analyzeUrl)
        }
    }

    fun changeChapter(chapter: BookChapter) {
        val curBook = curBook ?: return
        if (chapter.index != curBook.durChapterIndex) {
            curBook.durChapterIndex = chapter.index
            curBook.durChapterTitle = chapter.title
            position = 0L
            currentResolutionIndex = 0
            saveRead(0L)
            initChapter(chapter)
        }
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
