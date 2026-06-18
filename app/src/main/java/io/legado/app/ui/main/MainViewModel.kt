package io.legado.app.ui.main

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDav
import io.legado.app.help.DefaultData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

class MainViewModel(application: Application) : BaseViewModel(application) {
    private var threadCount = AppConfig.threadCount
    private var poolSize = min(threadCount, AppConst.MAX_THREAD)
    private var upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    private val waitLock = Any()
    private val waitUpTocBooks = LinkedHashSet<String>()
    private val onUpTocBooks = ConcurrentHashMap.newKeySet<String>()

    private var upTocJob: Job? = null
    private var cacheBookJob: Job? = null
    val booksListRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 30)
    }
    val booksGridRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 100)
    }

    /**
     * 本次应用进程内已触发过自动更新的分组ID, 避免 fragment 回收重建后再次触发
     */
    private val autoUpdatedGroups = ConcurrentHashMap.newKeySet<Long>()

    fun markGroupAutoUpdated(groupId: Long): Boolean = autoUpdatedGroups.add(groupId)

    companion object {
        /** 自动更新时, 距上次检查不足该时长的书籍跳过 */
        private const val AUTO_UPDATE_STALE_MS = 10 * 60 * 1000L
    }

//    init {
//        deleteNotShelfBook()
//    }

    override fun onCleared() {
        super.onCleared()
        upTocPool.close()
    }

    fun upPool() {
        threadCount = AppConfig.threadCount
        if (upTocJob?.isActive == true || cacheBookJob?.isActive == true) {
            return
        }
        val newPoolSize = min(threadCount, AppConst.MAX_THREAD)
        if (poolSize == newPoolSize) {
            return
        }
        poolSize = newPoolSize
        upTocPool.close()
        upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    }

    fun isUpdate(bookUrl: String): Boolean {
        return onUpTocBooks.contains(bookUrl)
    }

    /**
     * 主动更新目录, 不做时间窗判断 (用于下拉刷新 / 菜单项)
     */
    fun upToc(books: List<Book>) {
        if (books.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val urls = books.mapNotNull {
                if (!it.isLocal && it.canUpdate) it.bookUrl else null
            }
            addToWaitUp(urls)
        }
    }

    /**
     * 自动更新目录, 跳过最近已检查过的书籍
     */
    fun scheduleAutoUpdate(books: List<Book>) {
        if (books.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) {
            val urls = books.mapNotNull {
                if (!it.isLocal && it.canUpdate
                    && now - it.lastCheckTime > AUTO_UPDATE_STALE_MS
                ) it.bookUrl else null
            }
            addToWaitUp(urls)
        }
    }

    /**
     * 观察一个分组的书籍列表, 并在首次发射时触发一次自动更新.
     *
     * 排序逻辑由调用方通过 [sorter] 提供 (style1 用本地 bookSort, style2 用 AppConfig 按 groupId 取).
     * 生命周期感知由 [lifecycle] 接入: 只在 RESUMED 时下发, 与首次自动更新触发时机绑定.
     * 上游在 Default 上执行, 调用方在 collect 块里只做 UI 更新.
     */
    fun observeGroupBooks(
        groupId: Long,
        lifecycle: Lifecycle,
        sorter: (List<Book>) -> List<Book>,
    ): Flow<List<Book>> = appDb.bookDao.flowByGroup(groupId)
        .map { sorter(it) }
        .flowWithLifecycleAndDatabaseChangeFirst(
            lifecycle,
            Lifecycle.State.RESUMED,
            AppDatabase.BOOK_TABLE_NAME
        )
        .catch { AppLog.put("书架更新出错", it) }
        .onEach { list ->
            if (markGroupAutoUpdated(groupId) && AppConfig.autoRefreshBook) {
                scheduleAutoUpdate(list)
            }
        }
        .conflate()
        .flowOn(Dispatchers.Default)

    @Synchronized
    private fun addToWaitUp(urls: Collection<String>) {
        if (urls.isEmpty()) return
        synchronized(waitLock) {
            urls.forEach { url ->
                if (url !in onUpTocBooks) {
                    waitUpTocBooks.add(url) // LinkedHashSet 自带去重
                }
            }
        }
        if (upTocJob == null) {
            startUpTocJob()
        }
    }

    private fun pollWaitUpTocBook(): String? = synchronized(waitLock) {
        val iter = waitUpTocBooks.iterator()
        if (iter.hasNext()) {
            val value = iter.next()
            iter.remove()
            value
        } else null
    }

    private fun waitUpTocBooksEmpty(): Boolean = synchronized(waitLock) {
        waitUpTocBooks.isEmpty()
    }


    @Synchronized
    private fun onUpTocJobCompleted() {
        upTocJob = null
        if (!waitUpTocBooksEmpty()) {
            startUpTocJob()
        }
    }

    private fun startUpTocJob() {
        upPool()
        upTocJob = viewModelScope.launch(upTocPool) {
            flow {
                while (true) {
                    emit(pollWaitUpTocBook() ?: break)
                }
            }.onEachParallel(threadCount) {
                onUpTocBooks.add(it)
                postEvent(EventBus.UP_BOOKSHELF, it)
                updateToc(it)
                onUpTocBooks.remove(it)
                postEvent(EventBus.UP_BOOKSHELF, it)
            }.onCompletion {
                onUpTocJobCompleted()
                if (it == null && cacheBookJob == null && !CacheBookService.isRun) {
                    //所有目录更新完再开始缓存章节
                    cacheBook()
                }
            }.catch {
                AppLog.put("更新目录出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun updateToc(bookUrl: String) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        if (source == null) {
            if (!book.isUpError) {
                book.addType(BookType.updateError)
                appDb.bookDao.update(book)
            }
            return
        }
        kotlin.runCatching {
            val oldBook = book.copy()
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            } else {
                WebBook.runPreUpdateJs(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            book.sync(oldBook)
            book.removeType(BookType.updateError)
            if (book.bookUrl == bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadBook.onChapterListUpdated(book)
            addDownload(source, book)
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("${book.name} 更新目录失败\n${it.localizedMessage}", it)
            //这里可能因为时间太长书籍信息已经更改,所以重新获取
            appDb.bookDao.getBook(book.bookUrl)?.let { book ->
                book.addType(BookType.updateError)
                appDb.bookDao.update(book)
            }
        }
    }


    @Synchronized
    private fun addDownload(source: BookSource, book: Book) {
        if (AppConfig.preDownloadNum == 0) return
        val endIndex = min(
            book.totalChapterNum - 1,
            book.durChapterIndex.plus(AppConfig.preDownloadNum)
        )
        val cacheBook = CacheBook.getOrCreate(source, book)
        cacheBook.addDownload(book.durChapterIndex, endIndex)
    }

    /**
     * 缓存书籍
     */
    private fun cacheBook() {
        if (AppConfig.preDownloadNum == 0) return
        cacheBookJob?.cancel()
        cacheBookJob = viewModelScope.launch(upTocPool) {
            launch {
                while (isActive && CacheBook.isRun) {
                    //有目录更新是不缓存,优先更新目录,现在更多网站限制并发
                    CacheBook.setWorkingState(waitUpTocBooksEmpty() && onUpTocBooks.isEmpty())
                    delay(1000)
                }
            }
            CacheBook.startProcessJob(upTocPool)
        }
    }

    fun postLoad() {
        execute {
            if (appDb.httpTTSDao.count == 0) {
                DefaultData.httpTTS.let {
                    appDb.httpTTSDao.insert(*it.toTypedArray())
                }
            }
        }
    }

    fun restoreWebDav(name: String) {
        execute {
            AppWebDav.restoreWebDav(name)
        }
    }

}