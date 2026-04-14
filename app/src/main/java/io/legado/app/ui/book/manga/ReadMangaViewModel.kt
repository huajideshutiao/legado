package io.legado.app.ui.book.manga

import android.app.Application
import android.content.Intent
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReadMangaViewModel(application: Application) : BaseReadViewModel(application) {

    override var curBook: Book? = null
    override var curBookSource: BookSource?
        get() = ReadManga.bookSource
        set(value) {
            ReadManga.bookSource = value
            ReadManga.rateLimiter = ConcurrentRateLimiter(value)
        }
    override var inBookshelf: Boolean
        get() = ReadManga.inBookshelf
        set(value) {
            ReadManga.inBookshelf = value
        }

    override fun onSourceChanged(book: Book, toc: List<BookChapter>) {
        ReadManga.initData(book)
        ReadManga.loadContent()
    }

    override fun applyProgress(progress: BookProgress) {
        ReadManga.setProgress(progress)
    }

    override fun getSyncProgressMsg(): String = "已同步最新漫画阅读进度"

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            val book = IntentData.get<Book>("nowBook") ?: ReadManga.book
            when {
                book != null -> {
                    ReadManga.chapterChanged = intent.getBooleanExtra("chapterChanged", false)
                    upBook(book)
                    withContext(Dispatchers.Main) {
                        ReadManga.chapterList = chapterListData.value
                    }
                    initManga(curBook!!)
                }

                else -> context.getString(R.string.no_book)//没有找到书
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            AppLog.put("初始化数据失败\n${it.localizedMessage}", it)
        }
        //.onFinally { ReadManga.saveRead() }
    }

    private fun initManga(book: Book) {
        val isSameBook = ReadManga.book?.bookUrl == book.bookUrl
        ReadManga.initData(book)
        //开始加载内容
        if (!isSameBook) ReadManga.loadContent()
        else ReadManga.loadOrUpContent()

        if (ReadManga.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadManga.chapterChanged = false
        } else if (ReadManga.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadManga.syncProgress(
                    { progress -> ReadManga.mCallback?.sureNewProgress(progress) })
            } else syncBookProgress(book)
        }
        //自动换源
        if (!book.isLocal && ReadManga.bookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0) {
        if (index < ReadManga.chapterSize) {
            ReadManga.showLoading()
            ReadManga.durChapterIndex = index
            ReadManga.durChapterPos = durChapterPos * (if (durChapterPos < 0) -1 else 1)
            ReadManga.saveRead()
            ReadManga.loadContent()
        }
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadManga.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    openChapter(ReadManga.durChapterIndex, ReadManga.durChapterPos)
                }
        }
    }
}
