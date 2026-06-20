package io.legado.app.ui.main

import android.app.Application
import androidx.collection.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDav
import io.legado.app.help.DefaultData
import io.legado.app.help.NotificationHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.help.config.AppConfig
import io.legado.app.help.setLiveProgress
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.service.UpdateBookService
import io.legado.app.utils.FlowBus
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class MainViewModel(application: Application) : BaseViewModel(application) {
    private var threadCount = AppConfig.threadCount
    private var poolSize = min(threadCount, AppConst.MAX_THREAD)
    private var upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    private val waitLock = Any()
    private val waitUpTocBooks = LinkedHashSet<String>()
    private val onUpTocBooks = ConcurrentHashMap.newKeySet<String>()

    private var upTocJob: Job? = null
    private var refreshJob: Job? = null
    private var cacheBookJob: Job? = null

    var isActivityVisible = true

    private val upTocTotal = AtomicInteger(0)
    private val upTocCount = AtomicInteger(0)
    private val refreshTotal = AtomicInteger(0)
    private val refreshCount = AtomicInteger(0)

    /**
     * 更新目录/刷新书籍信息时缓存最近用过的书源, 避免每本书都走 DB.
     * LruCache 内部 synchronized, 配合 onEachParallel 并发安全.
     * 大小 16: 一次自动更新批次里活跃书源数通常远少于该上限.
     * 同时也缓存 "未找到" (null) 状态, 避免书源被删后重复查库.
     */
    private val bookSourceCache = LruCache<String, SourceWrapper>(16)

    private class SourceWrapper(val source: BookSource?)

    private fun getBookSource(origin: String): BookSource? {
        val wrapper = bookSourceCache[origin]
        if (wrapper != null) return wrapper.source
        val source = appDb.bookSourceDao.getBookSource(origin)
        bookSourceCache.put(origin, SourceWrapper(source))
        return source
    }
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

    init {
        FlowBus.with(EventBus.STOP_UP_BOOK).onEach {
            upTocJob?.cancel()
            refreshJob?.cancel()
            synchronized(waitLock) {
                waitUpTocBooks.clear()
            }
            val urls = onUpTocBooks.toList()
            onUpTocBooks.clear()
            urls.forEach {
                postEvent(EventBus.UP_BOOKSHELF, it)
            }
            upTocTotal.set(0)
            upTocCount.set(0)
            refreshTotal.set(0)
            refreshCount.set(0)
            updateUpdateNotification()
        }.launchIn(viewModelScope)
    }

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
        if (upTocJob?.isActive == true || refreshJob?.isActive == true || cacheBookJob?.isActive == true) {
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

    /**
     * 取消刷新/更新目录任务, 用于退出应用时清理, 避免弹出通知
     */
    fun cancelRefreshJobs() {
        upTocJob?.cancel()
        refreshJob?.cancel()
        synchronized(waitLock) {
            waitUpTocBooks.clear()
        }
        onUpTocBooks.clear()
        upTocTotal.set(0)
        upTocCount.set(0)
        refreshTotal.set(0)
        refreshCount.set(0)
        context.stopService<UpdateBookService>()
    }

    @Synchronized
    fun updateUpdateNotification() {
        val upTocActive = upTocJob?.isActive == true
        val refreshActive = refreshJob?.isActive == true
        if ((!upTocActive && !refreshActive) || isActivityVisible) {
            context.stopService<UpdateBookService>()
            return
        }
        context.startService<UpdateBookService>()
        val title = if (refreshActive) {
            context.getString(R.string.force_refresh_book)
        } else {
            context.getString(R.string.update_toc)
        }
        val count = upTocCount.get() + refreshCount.get()
        val total = upTocTotal.get() + refreshTotal.get()
        // progress_show 形如 "%1$s      进度 %2$d/%3$d", 第一个槽位本是书源/书名;
        // 这里整批更新没有单本名字, 传 "" 会残留 6 个前导空格使正文与标题不对齐, 故 trim 掉。
        val msg = context.getString(R.string.progress_show, "", count, total).trim()

        if (NotificationManagerCompat.from(appCtx).areNotificationsEnabled()) {
            val notificationBuilder =
                NotificationCompat.Builder(context, AppConst.channelIdDownload)
                    .setSmallIcon(R.drawable.ic_update)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setLiveProgress(
                        count,
                        total,
                        shortText = if (total > 0) "$count/$total" else null
                    )
                    .addAction(
                        R.drawable.ic_stop_black_24dp,
                        context.getString(R.string.cancel),
                        context.servicePendingIntent<UpdateBookService>(IntentAction.stop)
                    )
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            try {
                val notification = notificationBuilder.build()
                NotificationHelp.logPromotable(notification)
                NotificationManagerCompat.from(appCtx)
                    .notify(NotificationId.UpdateBookService, notification)
            } catch (e: Exception) {
                AppLog.put("更新通知失败\n${e.localizedMessage}", e)
            }
        }
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
     * 强制刷新书籍信息, 无视 canUpdate 属性 (用于菜单项)
     */
    fun forceRefresh(books: List<Book>) {
        if (books.isEmpty()) return
        if (upTocJob?.isActive == true || refreshJob?.isActive == true) {
            context.toastOnUi(R.string.force_refresh_busy)
            return
        }
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            val urls = books.filterNot { it.isLocal }.map { it.bookUrl }
            if (urls.isEmpty()) return@launch
            refreshTotal.set(urls.size)
            refreshCount.set(0)
            updateUpdateNotification()
            refreshBook(urls)
        }
    }

    /**
     * 并发刷新书籍信息.
     * 入参是 bookUrl 而非 Book: onEachParallel 限流后排书可能等较久,
     * 期间用户可能修改进度/分组等, 必须执行时从 DB 重查最新值,
     * 否则 sync(oldBook) 拷回的是入队时的旧快照, update 会冲掉用户的并发修改.
     */
    private suspend fun refreshBook(bookUrls: List<String>) {
        upPool()
        bookUrls.asFlow()
            .onEachParallel(threadCount) { bookUrl ->
                val book = appDb.bookDao.getBook(bookUrl) ?: return@onEachParallel
                val source = getBookSource(book.origin) ?: return@onEachParallel
                runCatching {
                    val oldBook = book.copy()
                    WebBook.getBookInfoAwait(source, book)
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
                    postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
                }.onFailure {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("${book.name} 强制刷新失败\n${it.localizedMessage}", it)
                }
                refreshCount.incrementAndGet()
                updateUpdateNotification()
            }.onCompletion {
                refreshCount.set(0)
                refreshTotal.set(0)
                onRefreshJobCompleted()
                updateUpdateNotification()
            }.catch {
                AppLog.put("强制刷新书籍出错\n${it.localizedMessage}", it)
            }.collect()
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
                    if (waitUpTocBooks.add(url)) {
                        upTocTotal.incrementAndGet()
                    }
                }
            }
        }
        updateUpdateNotification()
        if (upTocJob == null && refreshJob?.isActive != true) {
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
        } else {
            tryEvictBookSourceCache()
        }
    }

    @Synchronized
    private fun onRefreshJobCompleted() {
        refreshJob = null
        if (upTocJob == null) {
            if (!waitUpTocBooksEmpty()) {
                startUpTocJob()
            } else {
                tryEvictBookSourceCache()
            }
        }
    }

    /**
     * 使用书源缓存的两类 job (updateToc / refreshBook) 都空闲时才清,
     * 任一仍在跑就保留以提升命中率.
     */
    private fun tryEvictBookSourceCache() {
        if (upTocJob?.isActive == true) return
        if (refreshJob?.isActive == true) return
        bookSourceCache.evictAll()
    }

    private fun startUpTocJob() {
        if (refreshJob?.isActive == true) return
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
                upTocCount.incrementAndGet()
                updateUpdateNotification()
                postEvent(EventBus.UP_BOOKSHELF, it)
            }.onCompletion {
                upTocCount.set(0)
                upTocTotal.set(0)
                onUpTocJobCompleted()
                updateUpdateNotification()
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
        val source = getBookSource(book.origin)
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