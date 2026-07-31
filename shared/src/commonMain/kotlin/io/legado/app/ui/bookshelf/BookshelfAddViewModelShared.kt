package io.legado.app.ui.bookshelf

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getBookInfoAwait
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.model.webBook.WebBook.preciseSearchAwait
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 书架"添加网址"/"导入书架" (KMP 版, 下沉自 app 端 `BookshelfViewModel`)。
 *
 * app 原版依赖 Application/LiveData/toastOnUi, 此处改为自管 [scope] +
 * [addBookProgress] StateFlow + [Toasters], 其余流程逐行对齐原版。
 * `Book.migrateTo` / `Book.save` 走已有 provider (前者 [BookshelfManagePlatformProviders],
 * 后者内联 removeType + insert/update, 对照 BookController.saveBook)。
 */
class BookshelfAddViewModelShared(private val scope: CoroutineScope) {

    private val appDb get() = AppDbProviders.get()

    /** 添加进度 (对照原 addBookProgressLiveData): -1 = 无任务, >=0 = 已成功条数 */
    private val _addBookProgress = MutableStateFlow(-1)
    val addBookProgress: StateFlow<Int> = _addBookProgress.asStateFlow()

    /** 对照原 BookshelfViewModel.addBookByUrl: 逐行 URL 抓详情+目录后入库 */
    fun addBookByUrl(bookUrls: String) {
        var successCount = 0
        val urls = bookUrls.split("\n")
        scope.launch {
            try {
                for (url in urls) {
                    val bookUrl = url.trim()
                    if (bookUrl.isEmpty()) continue
                    try {
                        val book = getBookInfoByUrlAwait(bookUrl)
                        val dbBook = appDb.bookDao.getBook(book.name, book.author)
                        // 原版取 IntentData.source (getBookInfoByUrlAwait 内部刚 setSource);
                        // shared 端 IntentDataAccessor 只写不读, 改按 book.origin 回查等价书源
                        val source = appDb.bookSourceDao.getBookSource(book.origin)
                            ?: throw NoStackTraceException("书源不存在")
                        val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                        if (dbBook != null) {
                            BookshelfManagePlatformProviders.get().migrateBook(dbBook, book, toc)
                        } else {
                            book.order = appDb.bookDao.minOrder() - 1
                        }
                        appDb.bookDao.insert(book)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                        successCount++
                        _addBookProgress.value = successCount
                    } catch (e: Throwable) {
                        AppLog.put("添加 $bookUrl 失败\n${e.message}", e, true)
                    }
                }
                Toasters.get().toast(
                    if (successCount > 0) "$successCount/${urls.size} 成功" else "添加网址失败"
                )
            } finally {
                _addBookProgress.value = -1
            }
        }
    }

    /** 对照原 BookshelfViewModel.importBookshelf: url 下载或直接 json, 校验后逐条导入 */
    fun importBookshelf(str: String, groupId: Long) {
        var successCount = 0
        scope.launch {
            _addBookProgress.value = 0
            try {
                val text = str.trim()
                val json = if (text.isAbsUrl()) {
                    val body = OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
                        url(text)
                    }.decompressed()
                    try {
                        body.bytes().decodeToString().trim()
                    } finally {
                        body.close()
                    }
                } else {
                    text
                }
                if (!json.isJsonArray()) throw NoStackTraceException("格式不对")
                importBookshelfByJsonAwait(json, groupId) {
                    successCount++
                    _addBookProgress.value = successCount
                }
                Toasters.get().toast("成功")
            } catch (e: Throwable) {
                Toasters.get().toast(e.message ?: "ERROR")
            } finally {
                _addBookProgress.value = -1
            }
        }
    }

    /** 对照原 importBookshelfByJsonAwait: 有 origin+bookUrl 走直取详情, 否则全源精确搜索 */
    private suspend fun importBookshelfByJsonAwait(
        json: String,
        groupId: Long,
        onBookAdded: () -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(AppConfigProviders.get().threadCount)
        GSON.fromJsonArray<Map<String, String>>(json).getOrThrow().forEach { bookInfo ->
            val name = bookInfo["name"].orEmpty()
            val author = bookInfo["author"].orEmpty()
            val origin = bookInfo["origin"]
            val bookUrl = bookInfo["bookUrl"]
            if (name.isEmpty() || appDb.bookDao.has(name, author)) return@forEach
            semaphore.withPermit {
                (if (origin != null && bookUrl != null) {
                    val book = Book(bookUrl)
                    bookInfo.forEach { (key, value) ->
                        when (key) {
                            "name" -> book.name = value
                            "author" -> book.author = value
                            "kind" -> book.kind = value
                            "coverUrl" -> book.coverUrl = value
                            "customCoverUrl" -> book.customCoverUrl = value
                            "intro" -> book.intro = value
                            "customIntro" -> book.customIntro = value
                            "origin" -> book.origin = value
                            "originName" -> book.originName = value
                            "wordCount" -> book.wordCount = value
                            "tocUrl" -> book.tocUrl = value
                            "type" -> value.toIntOrNull()?.let { book.type = it }
                        }
                    }
                    val bookSource = appDb.bookSourceDao.getBookSource(origin) ?: return@withPermit
                    Coroutine.async(this) {
                        getBookInfoAwait(bookSource, book)
                    }.onSuccess {
                        it.originName = bookSource.bookSourceName
                        if (groupId > 0) it.group = groupId
                        saveBook(it)
                        onBookAdded()
                    }
                } else {
                    val bookSources = appDb.bookSourceDao.enabled()
                    Coroutine.async(this, semaphore = semaphore) {
                        for (s in bookSources) {
                            val book = preciseSearchAwait(s, name, author).getOrNull()
                            if (book != null) return@async Pair(book, s)
                        }
                        throw NoStackTraceException("没有搜索到<$name>$author")
                    }.onSuccess {
                        val book = it.first
                        if (groupId > 0) book.group = groupId
                        saveBook(book)
                        onBookAdded()
                    }
                }).onError { e ->
                    AppLog.put("导入<$name>失败\n${e.message}", e)
                }
            }
        }
    }

    /** 内联 app 端 Book.save() 扩展: removeType(notShelf) + 按 bookUrl 判断 insert/update */
    private suspend fun saveBook(book: Book) {
        book.removeType(BookType.notShelf)
        if (appDb.bookDao.has(book.bookUrl)) appDb.bookDao.update(book)
        else appDb.bookDao.insert(book)
    }
}
