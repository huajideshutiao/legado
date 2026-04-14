package io.legado.app.ui.book.video

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoViewModel(application: Application) : BaseReadViewModel(application) {
    val bookTitle by lazy { curBook?.name ?: "" }
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    val videoSource = MutableLiveData<VideoSource>()
    val resolutions = MutableLiveData<List<VideoResolution>>()
    var currentResolutionIndex = 0
    var position: Long = 0L
    override var curBook: Book? = IntentData.get<Book>("nowBook")

    override fun onUpSource(book: Book) {
        curBookSource = book.getBookSource()
    }

    fun initData() {
        execute {
            val curBook = curBook ?: return@execute
            position = curBook.durChapterPos.toLong()
            upBook(curBook)
            val chapterList = withContext(Dispatchers.Main) { chapterListData.value }
            initChapter(chapterList!![curBook.durChapterIndex])
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
        val source = if (content.isJsonObject()) {
            try {
                GSON.fromJsonObject<VideoSource>(content).getOrNull()
                    ?.takeIf { it.resolutions.any { r -> r.url.isNotEmpty() } }
            } catch (e: Exception) {
                AppLog.put("解析视频源JSON出错\n$e", e)
                null
            }
        } else if (content.contains("::") && content.contains("\n")) {
            val resolutions = content.lines().filter { it.contains("::") }.mapNotNull { line ->
                val parts = line.split("::", limit = 2)
                if (parts.size == 2) {
                    VideoResolution(
                        name = parts[0].trim(), url = parts[1].trim()
                    )
                } else null
            }.filter { it.url.isNotEmpty() }

            if (resolutions.isNotEmpty()) {
                VideoSource(resolutions = resolutions)
            } else null
        } else null

        if (source != null && source.resolutions.isNotEmpty()) {
            videoSource.postValue(source)
            resolutions.postValue(source.resolutions)
            currentResolutionIndex = source.defaultIndex
            val resolution = source.getResolution()
            if (resolution != null) {
                videoUrl.postValue(
                    AnalyzeUrl(
                        mUrl = resolution.url, source = curBookSource, headerMapF = source.headers
                    )
                )
            }
        } else {
            val analyzeUrl = if (content.startsWith("http")) {
                AnalyzeUrl(content)
            } else {
                AnalyzeUrl("").apply {
                    var videoUrl = content
                    val fakeUrl = if (content.startsWith("#BASE:")) {
                        val index = content.indexOf("\n") + 1
                        videoUrl = content.substring(index)
                        content.substring(6, index - 1)
                    } else "https://example.com/memory.m3u8"
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
            initChapter(chapter)
        }
    }

    fun delBook(success: (() -> Unit)?) = delBook(false, success)

    fun saveRead(position: Long) {
        Coroutine.async {
            curBook!!.apply {
                lastCheckCount = 0
                durChapterTime = System.currentTimeMillis()
                durChapterPos = position.toInt()
                update()
            }
        }
    }
}
