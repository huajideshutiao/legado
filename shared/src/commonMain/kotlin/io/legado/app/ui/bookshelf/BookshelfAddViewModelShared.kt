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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 书架"添加网址"/"导入书架" (KMP 版, 下沉自 app 端 `BookshelfViewModel`)。
 *
 * app 原版依赖 Application/LiveData/toastOnUi, 此处改为自管 [scope] +
 * [addBookProgress] 事件流 + [Toasters], 其余流程逐行对齐原版。
 * `Book.migrateTo` / `Book.save` 走已有 provider (前者 [BookshelfManagePlatformProviders],
 * 后者内联 removeType + insert/update, 对照 BookController.saveBook)。
 */
class BookshelfAddViewModelShared(private val scope: CoroutineScope) {

    private val appDb get() = AppDbProviders.get()

    /**
     * 添加进度事件 (对照原 addBookProgressLiveData): -1 = 任务结束, >=0 = 已成功条数。
     *
     * 语义对齐 LiveData.postValue: 每次投递都要驱动宿主对话框, 故用 replay=1 的
     * SharedFlow 而非按值去重的 StateFlow (否则一次未成功的添加只发 -1, 与上次的 -1
     * 相同被吞掉, 对话框关不掉; 重复添加同一网址亦然)。
     */
    private val _addBookProgress = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val addBookProgress: SharedFlow<Int> = _addBookProgress.asSharedFlow()

    /** 当前添加任务 (对照原 addBookJob), 供 [cancelAddBook] 取消 */
    private var addBookJob: Job? = null

    /** 取消添加 (对照 waitDialog.onCancelListener → viewModel.addBookJob?.cancel()) */
    fun cancelAddBook() {
        addBookJob?.cancel()
    }

    /** 对照原 BookshelfViewModel.addBookByUrl: 逐行 URL 抓详情+目录后入库 */
    fun addBookByUrl(bookUrls: String) {
        var successCount = 0
        val urls = bookUrls.split("\n")
        addBookJob = scope.launch {
            _addBookProgress.tryEmit(0)
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
                        _addBookProgress.tryEmit(successCount)
                    } catch (e: Throwable) {
                        AppLog.put("添加 $bookUrl 失败\n${e.message}", e, true)
                    }
                }
                // 对照原版 onSuccess 守卫: 取消后不弹成功/失败 toast (executeInternal 末尾 ensureActive)
                currentCoroutineContext().ensureActive()
                Toasters.get().toast(
                    if (successCount > 0) "$successCount/${urls.size} 成功" else "添加网址失败"
                )
            } finally {
                _addBookProgress.tryEmit(-1)
            }
        }
    }

    /** 对照原 BookshelfViewModel.importBookshelf: url 下载或直接 json, 校验后逐条导入 */
    fun importBookshelf(str: String, groupId: Long) {
        var successCount = 0
        addBookJob = scope.launch {
            _addBookProgress.tryEmit(0)
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
                    _addBookProgress.tryEmit(successCount)
                }
                Toasters.get().toast("成功")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toasters.get().toast(e.message ?: "ERROR")
            } finally {
                _addBookProgress.tryEmit(-1)
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
        // Map<String, String?> : 书单 JSON 字段可能为 null (如 origin/bookUrl/intro 等), 对齐原版
        // Gson 宽容语义 (null 存进 map 不报错); kotlinx-serialization 的 Map<String, String>
        // 遇 null 抛 JsonDecodingException 导致整个导入失败, 故用可空 String 值类型
        GSON.fromJsonArray<Map<String, String?>>(json).getOrThrow().forEach { bookInfo ->
            val name = bookInfo["name"].orEmpty()
            val author = bookInfo["author"].orEmpty()
            val origin = bookInfo["origin"]
            val bookUrl = bookInfo["bookUrl"]
            if (name.isEmpty() || appDb.bookDao.has(name, author)) return@forEach
            semaphore.withPermit {
                (if (origin != null && bookUrl != null) {
                    val book = Book(bookUrl)
                    bookInfo.forEach { (key, value) ->
                        // null 字段跳过不赋值, 对齐原版 `if (value is String)` 语义
                        val v = value ?: return@forEach
                        when (key) {
                            "name" -> book.name = v
                            "author" -> book.author = v
                            "kind" -> book.kind = v
                            "coverUrl" -> book.coverUrl = v
                            "customCoverUrl" -> book.customCoverUrl = v
                            "intro" -> book.intro = v
                            "customIntro" -> book.customIntro = v
                            "origin" -> book.origin = v
                            "originName" -> book.originName = v
                            "wordCount" -> book.wordCount = v
                            "tocUrl" -> book.tocUrl = v
                            "type" -> v.toIntOrNull()?.let { book.type = it }
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
