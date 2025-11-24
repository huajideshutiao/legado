package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
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
        addBookJob = execute {
            val hasBookUrlPattern: List<BookSourcePart> by lazy {
                appDb.bookSourceDao.hasBookUrlPattern
            }
            val urls = bookUrls.split("\n")
            for (url in urls) {
                val bookUrl = url.trim()
                if (bookUrl.isEmpty()) continue
                if (appDb.bookDao.getBook(bookUrl) != null) {
                    successCount++
                    continue
                }
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl) ?: continue
                var source = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
                if (source == null) {
                    for (bookSource in hasBookUrlPattern) {
                        try {
                            val bs = bookSource.getBookSource()!!
                            if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                source = bs
                                break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                val bookSource = source ?: continue
                val book = Book(
                    bookUrl = bookUrl,
                    origin = bookSource.bookSourceUrl,
                    originName = bookSource.bookSourceName
                )
                kotlin.runCatching {
                    WebBook.getBookInfoAwait(bookSource, book)
                }.onSuccess {
                    val dbBook = appDb.bookDao.getBook(it.name, it.author)
                    if (dbBook != null) {
                        val toc = WebBook.getChapterListAwait(bookSource, it).getOrThrow()
                        dbBook.migrateTo(it, toc)
                        appDb.bookDao.insert(it)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    } else {
                        it.order = appDb.bookDao.minOrder - 1
                        it.save()
                    }
                    successCount++
                    addBookProgressLiveData.postValue(successCount)
                }
            }
        }.onSuccess {
            if (successCount > 0) {
                context.toastOnUi(R.string.success)
            } else {
                context.toastOnUi("添加网址失败")
            }
        }.onError {
            AppLog.put("添加网址出错\n${it.localizedMessage}", it, true)
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
                        else WebBook.getBookInfo(this, bookSource,book).onSuccess {
                            it.originName = bookSource.bookSourceName
                            if (groupId > 0) it.group = groupId
                            it.save()
                        }
                    } else {
                        val bookSourceParts = appDb.bookSourceDao.allEnabledPart
                        WebBook.preciseSearch(this, bookSourceParts, name, author, semaphore = semaphore)
                            .onSuccess {
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