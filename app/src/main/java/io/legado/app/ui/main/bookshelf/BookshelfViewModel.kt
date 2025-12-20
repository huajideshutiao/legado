package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.GlobalVars
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getBookInfoAwait
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.model.webBook.WebBook.preciseSearchAwait
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class BookshelfViewModel(application: Application) : BaseViewModel(application) {
    val addBookProgressLiveData = MutableLiveData(-1)
    var addBookJob: Coroutine<*>? = null

    fun addBookByUrl(bookUrls: String) {
        var successCount = 0
        val urls = bookUrls.split("\n")
        addBookJob = execute {
            for (url in urls) {
                val bookUrl = url.trim()
                if (bookUrl.isEmpty()) continue
                try{
                    getBookInfoByUrlAwait(bookUrl).let{
                        val dbBook = appDb.bookDao.getBook(it.name, it.author)
                        val toc = WebBook.getChapterListAwait(GlobalVars.nowSource as BookSource, it).getOrThrow()
                        if (dbBook != null) dbBook.migrateTo(it, toc)
                        else it.order = appDb.bookDao.minOrder - 1
                        appDb.bookDao.insert(it)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                        successCount++
                        addBookProgressLiveData.postValue(successCount)
                    }
                }catch (e: Throwable){
                    AppLog.put("添加 $bookUrl 失败\n${e.localizedMessage}", e, true)
                }
            }
        }.onSuccess {
            context.toastOnUi(if (successCount > 0) {
                successCount.toString()+"/"+urls.size+" "+R.string.success
            } else "添加网址失败"
            )
        }.onFinally {
            addBookProgressLiveData.postValue(-1)
        }
    }

    fun importBookshelf(str: String, groupId: Long) {
        execute {
            val text = str.trim()
            when {
                text.isAbsUrl() -> {
                    okHttpClient.newCallResponseBody {
                        url(text)
                    }.decompressed().text().let {
                        importBookshelf(it, groupId)
                    }
                }

                text.isJsonArray() -> {
                    importBookshelfByJson(text, groupId)
                }

                else -> {
                    throw NoStackTraceException("格式不对")
                }
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }

    private fun importBookshelfByJson(json: String, groupId: Long) {
        execute {
            val semaphore = Semaphore(AppConfig.threadCount)
            GSON.fromJsonArray<Map<String, Any>>(json).getOrThrow().forEach { bookInfo ->
                val name = bookInfo["name"] as String
                val author = bookInfo["author"] as String
                val origin = bookInfo["origin"] as String?
                val bookUrl = bookInfo["bookUrl"] as String?
                if (name.isEmpty() || appDb.bookDao.has(name, author)) return@forEach
                semaphore.withPermit {
                    (if(origin!=null&&bookUrl!=null) {
                        val book = Book(bookUrl)
                        bookInfo.forEach { (key, value) ->
                            if(value is String) {
                                when (key) {
                                    "name" -> book.name = value
                                    "author" -> book.author = value
                                    "kind" -> book.kind = value
                                    "coverUrl" -> book.coverUrl = value
                                    "intro" -> book.intro = value
                                    "origin" -> book.origin = value
                                }
                            }
                        }
                        val bookSource = appDb.bookSourceDao.getBookSource(origin)
                        if (bookSource==null)return@forEach
                        else Coroutine.async(this) {
                            getBookInfoAwait(bookSource, book)
                        }.onSuccess {
                            it.originName = bookSource.bookSourceName
                            if (groupId > 0) it.group = groupId
                            it.save()
                        }
                    } else {
                        val bookSources = appDb.bookSourceDao.enabled()
                        Coroutine.async(this, semaphore = semaphore) {
                            for (s in bookSources) {
                                val book = preciseSearchAwait(s, name, author).getOrNull()
                                if (book != null) {
                                    return@async Pair(book, s)
                                }
                            }
                            throw NoStackTraceException("没有搜索到<$name>$author")
                        }.onSuccess {
                            val book = it.first
                            if (groupId > 0) book.group = groupId
                            book.save()
                        }
                    }).onError { e ->
                        context.toastOnUi(e.localizedMessage)
                    }
                }
            }
        }.onError {
            it.printOnDebug()
        }.onFinally {
            context.toastOnUi(R.string.success)
        }
    }
}