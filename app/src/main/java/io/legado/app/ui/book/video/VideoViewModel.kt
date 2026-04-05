package io.legado.app.ui.book.video

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.GlobalVars
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.toastOnUi

class VideoViewModel(application: Application) : BaseViewModel(application) {
    val bookTitle by lazy { book.name }
    val chapterList = MutableLiveData<List<BookChapter>>()
    val videoUrl = MutableLiveData<AnalyzeUrl>()
    val videoSource = MutableLiveData<VideoSource>()
    val resolutions = MutableLiveData<List<VideoResolution>>()
    var currentResolutionIndex = 0
    var position: Long = 0L
    var bookSource: BookSource? = null
    val book: Book = GlobalVars.nowBook!!

    fun initData() {
        execute {
            bookSource = book.getBookSource() ?: return@execute
            position = book.durChapterPos.toLong()
            if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(bookSource!!, book, true)
            val tmp1 = when {
                GlobalVars.nowChapterList != null && GlobalVars.nowChapterList!![0].bookUrl == book.bookUrl ->
                    GlobalVars.nowChapterList!!

                book.isNotShelf || book.totalChapterNum == 0 ->
                    WebBook.getChapterListAwait(bookSource!!, book, true).getOrThrow()

                else -> appDb.bookChapterDao.getChapterList(book.bookUrl)
            }
            chapterList.postValue(tmp1)
            initChapter(tmp1[(book.durChapterIndex)])
        }
    }

    fun initChapter(chapter: BookChapter) {
        Coroutine.async(viewModelScope) {
            getContentAwait(bookSource!!, book, chapter, needSave = false)
        }.onSuccess { content ->
            if (content.isEmpty()) {
                context.toastOnUi("未获取到资源链接")
            } else {
                parseVideoContent(content)
            }
        }.onError { e ->
            AppLog.put("获取资源链接出错\n$e", e, true)
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
            val resolutions = content.lines()
                .filter { it.contains("::") }
                .mapNotNull { line ->
                    val parts = line.split("::", limit = 2)
                    if (parts.size == 2) {
                        VideoResolution(
                            name = parts[0].trim(),
                            url = parts[1].trim()
                        )
                    } else null
                }
                .filter { it.url.isNotEmpty() }

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
                        mUrl = resolution.url,
                        source = bookSource,
                        headerMapF = source.headers
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
        if (chapter.index != book.durChapterIndex) {
            book.durChapterIndex = chapter.index
            book.durChapterTitle = chapter.title
            position = 0L
            currentResolutionIndex = 0
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
            try {
                Glide.with(context).asFile()
                    .load(book.coverUrl)
                    .signature(ObjectKey("covers"))
                    .onlyRetrieveFromCache(true)
                    .submit().get()?.delete()
            } catch (_: Exception) {}
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