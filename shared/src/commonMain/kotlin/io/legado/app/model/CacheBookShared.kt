package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.CacheBookCallbacks.get
import io.legado.app.model.CacheBookShared.cacheBookMap
import io.legado.app.model.CacheBookShared.close
import io.legado.app.model.CacheBookShared.processScope
import io.legado.app.model.CacheBookShared.startProcessJob
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * 跨平台 CacheBook 调度核心 (commonMain): 章节预下载调度。
 *
 * 背景: app 端 CacheBook (object 单例) + CacheBookService (Android Service 前台下载 +
 * 通知栏进度), 桌面端用 DesktopCacheBook (协程模拟 Service) 重复实现; 本类把非平台特有
 * 逻辑下沉供 app/desktop/iOS/鸿蒙复用。
 *
 * 平台差异经 [CacheBookCallback] + [CacheBookCallbacks] 注入:
 * - start/remove/stop (Context.startService) 保留 app 端薄壳 (走 ServiceLaunchers), 不下沉
 * - ReadBook 单例回调 (contentLoadFinish/downloadedChapters/downloadFailChapters) →
 *   callback 抽象; 桌面端 callback 用 postEvent 通知阅读页自行重载
 * - BookHelp (BitmapFactory 等重 Android) → [BookHelpProviders]/[BookStorageProviders];
 *   桌面端 saveImages/hasImageContent 默认 no-op/false (与原 DesktopCacheBook 跳过图片一致)
 * - appDb/AppConfig → [AppDbProviders.get()]/[AppConfigProviders.get().threadCount]
 *
 * 生命周期: object 单例进程内全局共享; [close] 由宿主 (CacheBookService.onDestroy /
 * 桌面阅读 VM) 调用。
 */
object CacheBookShared {

    /** KMP 互斥锁 (替代 @Synchronized, Native 端 @Synchronized 无效) */
    private val lock = SynchronizedObject()

    /** 按 bookUrl 索引的下载模型 map (对照 app 端 CacheBook.cacheBookMap) */
    val cacheBookMap = newConcurrentMap<String, CacheBookModelShared>()

    /**
     * 工作状态控制 (对照 app 端 CacheBook.workingState)。
     *
     * false 时 [startProcessJob] 内部 [CacheBookModelShared.download] 会被阻塞
     * (在 workingState.first { it } 处等待), 用于目录更新进行中时暂停预下载,
     * 让网络资源优先服务于目录更新 (现在更多网站限制并发)。
     */
    private val workingState = MutableStateFlow(true)

    /**
     * 下载调度全局作用域 (独立于调用方生命周期)。
     *
     * 调度循环运行在本 scope 上: 调用方 (VM/Service/UI) 协程被取消只中断
     * startProcessJob 的 join, 不中断循环本身 —— 宿主 (如 MainViewModel
     * viewModelScope) 销毁时排队书籍仍会处理完并从 map 收敛, 不再残留
     * "运行中" 状态 (原版遗留: 循环随调用方 scope 取消而中断, 管理界面
     * 缓存按钮恒显停止图标)。
     */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前调度循环 job (全局单实例, 替代原 mutex 串行等待) */
    private var processJob: Job? = null

    /** 调度 job 启动互斥 */
    private val processLock = SynchronizedObject()

    /**
     * 已成功下载的章节主键集合 (对照 app 端 CacheBook.successDownloadSet)。
     *
     * 用于 downloadSummary 统计 + 避免重复下载 (onSuccess 时 add)。
     */
    val successDownloadSet = linkedSetOf<String>()

    /**
     * 失败章节主键 -> 累计错误次数 (对照 app 端 CacheBook.errorDownloadMap)。
     *
     * 达到 3 次后不再重试 (对照 app 端 onPostError 中 `if ((errorDownloadMap[...] ?: 0) < 3)`)。
     */
    val errorDownloadMap = hashMapOf<String, Int>()

    /**
     * 按 bookUrl 获取或创建 [CacheBookModelShared] (对照 app 端 CacheBook.getOrCreate(bookUrl))。
     *
     * @param bookUrl 书籍唯一标识
     * @return 书籍无书源或不存在时返回 null
     */
    suspend fun getOrCreate(bookUrl: String): CacheBookModelShared? {
        synchronized(lock) { cacheBookMap[bookUrl]?.let { return it } }
        val book = AppDbProviders.get().bookDao.getBook(bookUrl) ?: return null
        val bookSource =
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin) ?: return null
        return getOrCreate(bookSource, book)
    }

    /**
     * 按书源 + 书籍获取或创建 [CacheBookModelShared] (对照 app 端 CacheBook.getOrCreate)。
     *
     * @param bookSource 书源 (存在时更新, 书源可能会变化, 必须更新)
     * @param book 书籍
     */
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModelShared = synchronized(lock) {
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModelShared(bookSource, book)
        cacheBookMap[book.bookUrl] = cacheBook
        return cacheBook
    }

    /** 同源书源更新 (对照 app 端 CacheBook.updateBookSource) */
    private fun updateBookSource(newBookSource: BookSource) {
        cacheBookMap.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    /** 清理所有下载任务 + 清空 map (对照 app 端 CacheBook.close) */
    fun close() {
        cacheBookMap.forEach { it.value.stop() }
        cacheBookMap.clear()
        successDownloadSet.clear()
        errorDownloadMap.clear()
    }

    /** 设置工作状态 (对照 app 端 CacheBook.setWorkingState) */
    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    /**
     * 启动下载处理 job, 持续遍历 [cacheBookMap] 处理等待队列
     * (对照 app 端 CacheBook.startProcessJob)。
     *
     * 与 app 端差异 (有意修复): 调度循环不再跑在调用方协程里, 而是由
     * [processScope] 全局单实例持有 —— 调用方取消 (Activity/VM 销毁、服务停止)
     * 只中断本函数的 join 等待, 不中断循环, 排队书籍最终处理完并从 map 移除,
     * 避免残留 "运行中" 状态导致 UI 恒显停止图标。单实例语义与原 mutex 等价
     * (同一时刻至多一个循环), 差异仅是新调用方 join 活跃循环而非排队重跑。
     * flow 循环: 只要 map 非空且协程活跃就持续遍历, 对每个非 loading 的 model
     * 调用 [CacheBookModelShared.download]; 无可下载项时 delay(1000) 避免空转。
     *
     * @param context 协程上下文 (兼容调用方, 供下载任务块切 dispatcher;
     *   循环本身运行在 [processScope])
     */
    suspend fun startProcessJob(context: CoroutineContext) {
        val job = synchronized(processLock) {
            processJob?.takeIf { it.isActive } ?: launchProcessJob().also { processJob = it }
        }
        job.join()
    }

    private fun launchProcessJob(): Job = processScope.launch {
        setWorkingState(true)
        flow {
            while (currentCoroutineContext().isActive && cacheBookMap.isNotEmpty()) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    if (!model.isLoading()) {
                        emit(model)
                        emitted = true
                    }
                    workingState.first { it }
                }

                if (!emitted) {
                    delay(1000)
                }
            }
        }.onStart {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.onEachParallel(AppConfigProviders.get().threadCount) {
            coroutineScope {
                it.download(this, coroutineContext)
            }
        }.onCompletion {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.collect()
    }


    /** 下载摘要文案 (对照 app 端 CacheBook.downloadSummary) */
    val downloadSummary: String
        get() {
            return "正在下载:${onDownloadCount}|等待中:${waitCount}|失败:${errorDownloadMap.count()}|成功:${successDownloadSet.size}"
        }

    /**
     * 供通知进度条使用的 (已完成, 总数). (对照 app 端 CacheBook.downloadProgress)
     * 总数 = 成功 + 失败 + 下载中 + 等待中, 会随排队动态增长, 已完成随之逼近总数。
     */
    val downloadProgress: Pair<Int, Int>
        get() {
            val done = successDownloadSet.size + errorDownloadMap.size
            return done to (done + onDownloadCount + waitCount)
        }

    /** 是否有下载任务在跑 (对照 app 端 CacheBook.isRun) */
    val isRun: Boolean
        get() {
            cacheBookMap.forEach {
                if (it.value.isRun()) {
                    return true
                }
            }
            return false
        }

    private val waitCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.waitCount
            }
            return count
        }

    val onDownloadCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.onDownloadCount
            }
            return count
        }

    /**
     * 单本书的下载模型 (对照 app 端 CacheBook.CacheBookModel)。
     *
     * 维护:
     * - [waitDownloadSet]: 待下载章节 index 队列 (LRU 去重)
     * - [onDownloadSet]: 正在下载的章节 index 集合
     * - [tasks]: 已启动的下载任务集合 (CompositeCoroutine 管理, stop 时统一取消)
     *
     * 平台差异通过 [CacheBookCallbacks] 注入: app 端桥接 ReadBook 单例,
     * 桌面端用 postEvent 通知阅读页。
     */
    class CacheBookModelShared(var bookSource: BookSource, var book: Book) {

        /** KMP 互斥锁 (替代 @Synchronized, Native 端 @Synchronized 无效) */
        private val lock = SynchronizedObject()

        private val waitDownloadSet = linkedSetOf<Int>()
        private val onDownloadSet = linkedSetOf<Int>()
        private val tasks = CompositeCoroutine()
        private var isStopped = false
        private var waitingRetry = false
        private var isLoading = false
        /** 章节列表缓存 (addDownload 后由调用方设置, 避免每次查 DB) */
        var chapterList: List<BookChapter>? = null

        val waitCount get() = waitDownloadSet.size
        val onDownloadCount get() = onDownloadSet.size

        init {
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        fun isRun(): Boolean = synchronized(lock) {
            return waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
        }

        fun isStop(): Boolean = synchronized(lock) {
            // 内联 isRun() 逻辑, 避免同锁重入 (atomicfu synchronized Native 端可能不可重入)
            val isRunning = waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
            return isStopped || (!isRunning && !waitingRetry)
        }

        fun isLoading(): Boolean = synchronized(lock) {
            return isLoading
        }

        fun setLoading() = synchronized(lock) {
            isLoading = true
        }

        fun stop() = synchronized(lock) {
            waitDownloadSet.clear()
            tasks.clear()
            isStopped = true
            isLoading = false
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        /**
         * 添加下载范围 (对照 app 端 CacheBookModel.addDownload)。
         *
         * @param start 起始章节 index (含)
         * @param end 结束章节 index (含); 注意: 与 app 端一致, 调用方负责 clamp 到
         *           [Book.lastChapterIndex] (app 端在 CacheBookService.addDownloadData 内
         *           `min(end, book.lastChapterIndex)`, 桌面端在 DesktopCacheBook.addDownload
         *           内 clamp)。本方法不做 clamp, 保持与 app 端 CacheBookModel.addDownload 一致。
         */
        fun addDownload(start: Int, end: Int) = synchronized(lock) {
            isStopped = false
            for (i in start..end) {
                if (!onDownloadSet.contains(i)) {
                    waitDownloadSet.add(i)
                }
            }
            cacheBookMap[book.bookUrl] = this
            isLoading = false
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        private fun onSuccess(chapter: BookChapter) = synchronized(lock) {
            onDownloadSet.remove(chapter.index)
            successDownloadSet.add(chapter.primaryStr())
            errorDownloadMap.remove(chapter.primaryStr())
        }

        private fun onPreError(chapter: BookChapter, error: Throwable) = synchronized(lock) {
            waitingRetry = true
            if (error !is ConcurrentException) {
                errorDownloadMap[chapter.primaryStr()] =
                    (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            }
            onDownloadSet.remove(chapter.index)
        }

        private fun onPostError(chapter: BookChapter, error: Throwable) = synchronized(lock) {
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.message}",
                    error
                )
            }
            waitingRetry = false
        }

        private fun onError(chapter: BookChapter, error: Throwable) = synchronized(lock) {
            // 内联 onPreError + onPostError 逻辑, 避免同锁重入 (atomicfu synchronized Native 端可能不可重入)
            waitingRetry = true
            if (error !is ConcurrentException) {
                errorDownloadMap[chapter.primaryStr()] =
                    (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            }
            onDownloadSet.remove(chapter.index)
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.message}",
                    error
                )
            }
            waitingRetry = false
        }

        private fun onCancel(index: Int) = synchronized(lock) {
            onDownloadSet.remove(index)
            if (!isStopped) waitDownloadSet.add(index)
        }

        private fun onFinally() = synchronized(lock) {
            if (waitDownloadSet.isEmpty() && onDownloadSet.isEmpty()) {
                cacheBookMap.remove(book.bookUrl)
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        /**
         * 从待下载列表内取第一条下载 (对照 app 端 CacheBookModel.download)。
         *
         * 流程:
         * 1. 锁内取 waitDownloadSet 第一条并直接预占进 onDownloadSet, 保证任意时刻 index
         *    至少在一个集合中 (等价原版单 @Synchronized 块语义, isRun/isStop/onFinally 不会误判)
         * 2. 锁外查 DB chapter (suspend 不能进锁); 查不到/被 stop/isVolume/hasImageContent
         *    则加锁回滚预占
         * 3. 已缓存 (BookStorage.hasContent): 重新加载内容并 saveImages 刷新图片缓存
         *    (app 端行为, 桌面端 saveImages no-op, 等价于仅 onSuccess)
         * 4. 未缓存: getContentAwait + saveImages, 成功后通过
         *    [CacheBookCallback.onContentLoadFinish] 通知阅读流
         * 5. 任务一律 LAZY 构造 (锁内构造不执行), start() 在锁外: atomicfu 锁非重入,
         *    持锁 start 时快速完成的任务会同步回调 onSuccess/onFinally 重入自死锁
         *
         * @param scope 协程作用域 (Coroutine.async 用)
         * @param context 协程上下文 (Coroutine.async 用, 与 app 端一致)
         */
        suspend fun download(scope: CoroutineScope, context: CoroutineContext) {
            // 锁内: 取 chapterIndex 并直接预占进 onDownloadSet (中间窗口 index 不落空)
            val chapterIndex = synchronized(lock) {
                val idx = waitDownloadSet.firstOrNull()
                if (idx == null) {
                    if (!isLoading && onDownloadSet.isEmpty()) {
                        cacheBookMap.remove(book.bookUrl)
                    }
                    return
                }
                if (onDownloadSet.contains(idx)) {
                    waitDownloadSet.remove(idx)
                    return
                }
                waitDownloadSet.remove(idx)
                onDownloadSet.add(idx)
                idx
            }
            // 锁外: chapter DAO 查询 (suspend, 不能在 synchronized 块内调用)
            val chapter = AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            if (chapter == null) {
                synchronized(lock) { onDownloadSet.remove(chapterIndex) }
                return
            }
            // 锁内: 状态检查 + LAZY 任务构造; start() 必须在锁外 (见 KDoc 第 5 条)
            val task: Coroutine<*> = synchronized(lock) {
                if (isStopped) {
                    // DB 查询窗口内被 stop(): 回滚预占, 不启动任务 (等价原版 stop 先于 download)
                    onDownloadSet.remove(chapterIndex)
                    return
                }
                if (chapter.isVolume) {
                    /** 修正下载计数 */
                    postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                    onDownloadSet.remove(chapterIndex)
                    return
                }
                val bookHelp = BookHelpProviders.get()
                if (bookHelp.hasImageContent(book, chapter)) {
                    onDownloadSet.remove(chapterIndex)
                    return
                }
                if (BookStorageProviders.get().hasContent(book, chapter)) {
                    Coroutine.async(
                        scope,
                        context,
                        start = CoroutineStart.LAZY,
                        executeContext = context
                    ) {
                        bookHelp.getContent(book, chapter)?.let {
                            bookHelp.saveImages(bookSource, book, chapter, it, 1)
                        }
                    }.onSuccess {
                        onSuccess(chapter)
                    }.onError {
                        onPreError(chapter, it)
                        //出现错误等待一秒后重新加入待下载列表
                        delay(1000)
                        onPostError(chapter, it)
                    }.onCancel {
                        onCancel(chapterIndex)
                    }.onFinally {
                        onFinally()
                    }.also {
                        tasks.add(it)
                    }
                } else {
                    val nextChapterUrl = chapterList?.getOrNull(chapter.index + 1)?.url
                    Coroutine.async(
                        scope,
                        context,
                        start = CoroutineStart.LAZY,
                    ) {
                        val content = getContentAwait(bookSource, book, chapter, nextChapterUrl)
                        bookHelp.saveImages(bookSource, book, chapter, content, 2)
                        //正文落盘由 getContentAwait 内部完成 (WebBook.getContentAwait needSave=true
                        //默认调 BookHelpProviders.get().saveContent), 与 app 端原版一致,
                        //此处不再重复 saveContent (避免双重落盘)
                        content
                    }.onSuccess { content ->
                        onSuccess(chapter)
                        downloadFinish(chapter, content)
                    }.onError {
                        onPreError(chapter, it)
                        //出现错误等待一秒后重新加入待下载列表
                        delay(1000)
                        onPostError(chapter, it)
                        downloadFinish(chapter, "获取正文失败\n${it.message}")
                    }.onCancel {
                        onCancel(chapterIndex)
                    }.onFinally {
                        onFinally()
                    }.also {
                        tasks.add(it)
                    }
                }
            }
            // 锁外启动; 若 stop() 在窗口内 tasks.clear() 已取消该 LAZY 任务, start() 为 no-op
            task.start()
        }

        /**
         * 同步等待单章下载 (对照 app 端 CacheBookModel.downloadAwait)。
         *
         * app 端阅读流 (ReadBook.loadContent) 在缓存未命中时调用本方法同步获取正文。
         * 桌面端阅读流 (ReadBookViewModelShared) 不经此方法, 直接用 WebBook.getContentAwait。
         *
         * 平台差异: app 端 callback 桥接 ReadBook.downloadedChapters /
         * downloadFailChapters; 桌面端 callback no-op (无 ReadBook 单例)。
         */
        suspend fun downloadAwait(chapter: BookChapter): String {
            synchronized(lock) {
                onDownloadSet.add(chapter.index)
                waitDownloadSet.remove(chapter.index)
            }
            val callback = CacheBookCallbacks.get()
            try {
                val nextChapterUrl = chapterList?.getOrNull(chapter.index + 1)?.url
                val content = getContentAwait(bookSource, book, chapter, nextChapterUrl)
                onSuccess(chapter)
                callback.markDownloaded(chapter.index)
                callback.markDownloadSuccess(chapter.index)
                return content
            } catch (e: Exception) {
                if (e is CancellationException) {
                    onCancel(chapter.index)
                }
                onError(chapter, e)
                callback.markDownloadFailed(chapter.index)
                return "获取正文失败\n${e.message}"
            } finally {
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
        }

        /**
         * 单章异步下载 (对照 app 端 CacheBookModel.download(scope, chapter, semaphore, resetPageOffset))。
         *
         * 与 [downloadAwait] 区别: 异步启动 (LAZY + semaphore), 不阻塞调用方;
         * app 端阅读流 (ReadBook.preDownload) 用本方法预下载前/后章节。
         *
         * @param semaphore 并发信号量 (app 端 preDownloadSemaphore, 限制预下载并发数)
         * @param resetPageOffset 是否重置页码偏移 (章节内容刷新后由阅读流决定)
         */
        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false
        ) {
            // 锁内只做集合状态变更 + LAZY 任务构造; start() 在锁外, 避免任务快速完成时
            // 同步回调 onSuccess/onError 重入非重入锁自死锁 (atomicfu Native 端非重入)
            val task = synchronized(lock) {
                if (onDownloadSet.contains(chapter.index)) {
                    return
                }
                onDownloadSet.add(chapter.index)
                waitDownloadSet.remove(chapter.index)
                val callback = CacheBookCallbacks.get()
                val nextChapterUrl = chapterList?.getOrNull(chapter.index + 1)?.url
                Coroutine.async(
                    scope,
                    start = CoroutineStart.LAZY,
                    semaphore = semaphore
                ) {
                    getContentAwait(bookSource, book, chapter, nextChapterUrl)
                }.onSuccess { content ->
                    onSuccess(chapter)
                    callback.markDownloaded(chapter.index)
                    callback.markDownloadSuccess(chapter.index)
                    downloadFinish(chapter, content, resetPageOffset)
                }.onError {
                    onError(chapter, it)
                    callback.markDownloadFailed(chapter.index)
                    downloadFinish(chapter, "获取正文失败\n${it.message}", resetPageOffset)
                }.onCancel {
                    onCancel(chapter.index)
                    downloadFinish(chapter, "download canceled", resetPageOffset, true)
                }.onFinally {
                    postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
                }
            }
            task.start()
        }

        /**
         * 章节下载完成, 通知阅读流重载 (对照 app 端 CacheBookModel.downloadFinish)。
         *
         * 平台差异: app 端调 `ReadBook.contentLoadFinish(book, chapter, content, ...)`
         * (内部检查 `ReadBook.book?.bookUrl == book.bookUrl` 才生效);
         * 桌面端 callback 用 postEvent 通知阅读页 Composable 监听自行重载。
         * 由 [CacheBookCallback.onContentLoadFinish] 实现侧自行判断是否生效。
         */
        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false
        ) {
            CacheBookCallbacks.get().onContentLoadFinish(
                book, chapter, content,
                resetPageOffset = resetPageOffset,
                canceled = canceled
            )
        }

    }

}

/**
 * CacheBook 与平台阅读流集成的回调抽象 (shared commonMain)。
 *
 * # 背景
 * app 端 `io.legado.app.model.CacheBook.CacheBookModel` 内部直接引用 `ReadBook` 单例:
 * - `downloadFinish` 调 `ReadBook.contentLoadFinish(book, chapter, content, ...)`
 *   (内部检查当前阅读书 == 下载书才生效)
 * - `downloadAwait` / `download(scope, chapter, ...)` 操作
 *   `ReadBook.downloadedChapters` / `ReadBook.downloadFailChapters`
 *
 * 桌面端无 ReadBook 单例 (ReadBookShared 是 Compose 注入实例, CacheBookShared 不持有引用),
 * 改用 postEvent 通知阅读页 Composable 监听自行重载。
 *
 * 本接口把上述差异抽象出来, 由各端 actual 自行实现注册:
 * - Android: app 端 `CacheBook` 薄壳注册, 桥接到 `ReadBook` 单例
 * - 桌面: `DesktopCacheBook` 薄壳注册, 用 postEvent 通知
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders] / [io.legado.app.help.service.ServiceLaunchers]。
 */
interface CacheBookCallback {

    /**
     * 章节下载成功, 标记已下载 (对应 app 端 `ReadBook.downloadedChapters.add(index)`)。
     *
     * 桌面端无 ReadBook 单例, 默认 no-op。
     */
    fun markDownloaded(chapterIndex: Int) {}

    /**
     * 章节下载失败, 累计失败次数 (对应 app 端
     * `ReadBook.downloadFailChapters[index] = (ReadBook.downloadFailChapters[index] ?: 0) + 1`)。
     *
     * 桌面端无 ReadBook 单例, 默认 no-op。
     */
    fun markDownloadFailed(chapterIndex: Int) {}

    /**
     * 章节下载失败计数清零 (对应 app 端 `ReadBook.downloadFailChapters.remove(index)`)。
     *
     * 与 [markDownloadFailed] 配对: 成功后清失败计数, 避免下次失败时累计旧值。
     * app 端在 `downloadAwait` / `download(scope, chapter, ...)` 成功分支调
     * `ReadBook.downloadFailChapters.remove(chapter.index)`。
     */
    fun markDownloadSuccess(chapterIndex: Int) {}

    /**
     * 章节正文下载完成, 通知阅读流重载 (对应 app 端 `ReadBook.contentLoadFinish`)。
     *
     * 实现侧自行判断当前阅读书是否匹配:
     * - app 端检查 `ReadBook.book?.bookUrl == book.bookUrl` 才调 contentLoadFinish
     * - 桌面端无 ReadBook 单例, 用 postEvent(EventBus.UP_DOWNLOAD, bookUrl) +
     *   postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter)) 通知阅读页
     *
     * @param book 下载完成的书籍
     * @param chapter 下载完成的章节
     * @param content 章节正文 (失败时为错误文案)
     * @param resetPageOffset 是否重置页码偏移 (章节内容刷新后由阅读流决定)
     * @param canceled 是否因取消而结束
     */
    fun onContentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        resetPageOffset: Boolean = false,
        canceled: Boolean = false
    ) {
    }
}

/**
 * [CacheBookCallback] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (app 端 App.onCreate / 桌面端 desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders]。
 */
object CacheBookCallbacks {

    @Volatile
    private var impl: CacheBookCallback? = null

    /** 宿主启动早期注册一次 (任何 CacheBookShared 调用之前)。 */
    fun register(impl: CacheBookCallback) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): CacheBookCallback =
        impl ?: error("CacheBookCallbacks not registered; call register() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
