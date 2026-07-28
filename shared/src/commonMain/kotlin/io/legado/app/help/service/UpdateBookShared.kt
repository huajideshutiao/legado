package io.legado.app.help.service

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.closeIfCloseable
import io.legado.app.help.coroutine.newFixedThreadPoolDispatcher
import io.legado.app.model.CacheBookShared
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FlowBus
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 跨平台 UpdateBook (目录更新 / 强制刷新 / 自动更新 / 预下载调度) 编排核心
 * (shared commonMain 版本)。
 *
 * # 背景
 *
 * app 端 `io.legado.app.ui.main.MainViewModel` 与桌面端
 * `io.legado.desktop.ui.main.DesktopMainViewModel` 各自实现了一份高度重复的
 * 目录更新编排逻辑 (upToc / forceRefresh / scheduleAutoUpdate / refreshBook /
 * updateToc / addDownload / cacheBook / startUpTocJob / onUpTocJobCompleted /
 * addToWaitUp / pollWaitUpTocBook / upPool 等), 仅在以下平台差异点不同:
 * - **协程 scope**: app 端 `viewModelScope` (随 Activity 销毁), 桌面端自有 scope
 * - **进度通知**: app 端 `NotificationManagerCompat` + `startService<UpdateBookService>`
 *   显示通知栏进度, 桌面端 `NotificationProgresses` (SystemTray 文本通知) + StateFlow
 * - **Toast 反馈**: app 端 `context.toastOnUi(R.string.xxx)`, 桌面端 `Toasters.get().toast(msg)`
 * - **CacheBook 调用**: app 端 `CacheBook` 单例 (CacheBookShared 的 app 薄壳),
 *   桌面端 `DesktopCacheBook` (CacheBookShared 的 desktop 薄壳)
 * - **bookSourceCache**: app 端 `androidx.collection.LruCache`, 桌面端
 *   `Collections.synchronizedMap(LinkedHashMap(accessOrder=true))`
 * - **ReadBook.onChapterListUpdated**: app 端调 ReadBook 单例, 桌面端 postEvent 通知
 *
 * 本类把两者**非平台特有**的编排逻辑下沉到 commonMain, 供 app / desktop / iOS / 鸿蒙
 * 复用。平台差异通过 [UpdateBookCallback] 注入抽象 (与 [CacheBookShared] +
 * [io.legado.app.model.CacheBookCallback] 模式一致)。
 *
 * # 已复用的 shared commonMain 下沉件
 *
 * - [WebBook.getBookInfoAwait] / [WebBook.getChapterListAwait] / [WebBook.runPreUpdateJs]:
 *   shared model/webBook 已下沉
 * - [onEachParallel]: shared FlowExtensionsShared 已下沉的限流并发算子
 * - [postEvent] / [EventBus.UP_BOOKSHELF] / [EventBus.STOP_UP_BOOK]: shared FlowBus 已下沉
 * - [AppDbProviders.get()] / [AppConfigProviders.get()]: DAO 与配置访问 provider
 * - [Book.isLocal] / [Book.isUpError] / [Book.addType] / [Book.removeType]:
 *   shared BookExtensionsShared 已下沉
 * - [AppConst.MAX_THREAD]: shared commonMain 已下沉
 * - [ContentProcessorProviders.get().getTitleReplaceRules]: shared 已下沉 (替代 app 端
 *   `ContentProcessor.get(book).getTitleReplaceRules()`)
 * - [BookStorageProviders.get().updateCacheFolder]: shared 已下沉 (替代 app 端
 *   `BookHelp.updateCacheFolder`)
 * - [CacheBookShared]: shared model/CacheBookShared 已下沉 (替代 app 端 `CacheBook` /
 *   桌面端 `DesktopCacheBook`, 各端 CacheBook 单例都是 CacheBookShared 的薄壳)
 *
 * # 平台差异处理 (对照 app 端 MainViewModel / 桌面端 DesktopMainViewModel)
 *
 * - **不依赖 ViewModelStore / lifecycleScope**: 由调用方构造时注入 [scope]
 *   (app 端 viewModelScope / 桌面端自有 scope / iOS-鸿蒙 NativeServiceLauncher 的 scope)
 * - **不依赖 Android 通知栏**: 进度通知通过 [UpdateBookCallback.onProgressUpdate] /
 *   [UpdateBookCallback.onProgressCancel] 抽象, 由各端 callback 实现桥接
 *   (app 端 NotificationManagerCompat + startService<UpdateBookService>,
 *   桌面端 NotificationProgresses + StateFlow, iOS/鸿蒙 NativeUpdateBookCallback
 *   桥接 NotificationProgresses + Toasters)
 * - **不依赖 ReadBook 单例**: app 端 `ReadBook.onChapterListUpdated(book)` 通过
 *   postEvent(EventBus.UP_BOOKSHELF, bookUrl) 替代 (与桌面端一致, 阅读流 Composable
 *   监听自行重载, 语义等价仅少一次内存缓存同步)
 * - **不依赖 androidx.collection.LruCache**: bookSourceCache 改用
 *   `SynchronizedObject` + synchronized 包裹普通 `LinkedHashMap` (KMP 标准 API,
 *   commonMain 的 LinkedHashMap 无 accessOrder 构造, 用超过容量清空的简化 LRU 策略,
 *   见 [bookSourceCache] 字段注释; 不再参照 CacheBookShared 用 java.util.concurrent
 *   的历史遗留 bug 模式)
 * - **不依赖 CacheBook 单例 (app) / DesktopCacheBook (desktop)**: 直接调 [CacheBookShared]
 *   (commonMain 已下沉, 各端 CacheBook 单例都是其薄壳, 行为等价)
 *
 * # 生命周期
 *
 * class (非 object), 由各端 VM 持有实例:
 * - app 端 `MainViewModel` 持有, viewModelScope 注入, `onCleared` 时调 [onCleared]
 * - 桌面端 `DesktopMainViewModel` 持有, 自有 scope 注入, `DisposableEffect.onDispose` 调 [onCleared]
 * - iOS/鸿蒙 `NativeServiceLauncher` 持有, launcher scope 注入, launcher 销毁时调 [onCleared]
 *
 * 模式参考 [CacheBookShared] (但 CacheBookShared 是 object 单例因 cacheBookMap 跨 VM 共享;
 * UpdateBookShared 是 class 因每个 VM 独立持有 upTocJob / refreshJob 等任务状态)。
 */
class UpdateBookShared(
    /** 协程作用域, 由调用方注入 (app: viewModelScope / desktop: 自有 scope / iOS-鸿蒙: launcher scope) */
    private val scope: CoroutineScope,
    /** 平台差异回调 (通知 / toast), 由各端 actual 实现 */
    private val callback: UpdateBookCallback,
) {

    // region 依赖间接访问 (provider 间接, 与 DesktopMainViewModel 一致)
    private val appDb get() = AppDbProviders.get()
    private val appConfig get() = AppConfigProviders.get()
    // endregion

    /**
     * 目录更新 / 强制刷新共用的限流线程池 (对照 app 端 upTocPool / 桌面端 upTocPool)。
     *
     * 池大小 = `min(threadCount, MAX_THREAD)`, threadCount 来自 AppConfigProviders
     * (默认 16, 用户可改), MAX_THREAD=9 上限保护 (与 app 端一致)。
     * [upPool] 在每次启动新批次前检查并按需重建 (用户改了 threadCount 后下一批次生效)。
     */
    private var threadCount = appConfig.threadCount
    private var poolSize = min(threadCount, AppConst.MAX_THREAD)
    private var upTocPool = newFixedThreadPoolDispatcher(poolSize)

    /** 目录等待/执行集合共用一把锁，集合变化时同步发布 [_tocBusy]，避免跨锁快照竞态。 */
    private val tocStateLock = SynchronizedObject()
    private val waitUpTocBooks = LinkedHashSet<String>()
    private val onUpTocBooks = LinkedHashSet<String>()
    private val _tocBusy = MutableStateFlow(false)

    private var upTocJob: Job? = null
    private var refreshJob: Job? = null
    /** 章节预下载 job (对照 app 端 MainViewModel.cacheBookJob), 由 [cacheBook] 启动 */
    private var cacheBookJob: Job? = null

    private val upTocTotal: AtomicInt = atomic(0)
    private val upTocCount: AtomicInt = atomic(0)
    private val refreshTotal: AtomicInt = atomic(0)
    private val refreshCount: AtomicInt = atomic(0)

    /**
     * 更新目录 / 刷新书籍信息时缓存最近用过的书源, 避免每本书都走 DB (对照 app 端 bookSourceCache)。
     *
     * app 端用 androidx.collection.LruCache (Android 专属), 本类用
     * [SynchronizedObject] + synchronized 包裹普通 [LinkedHashMap] 实现 LRU
     * (KMP 标准 API, 与 AppLog 同模式):
     * - 容量 16 (与 app 端一致, 一次自动更新批次活跃书源数通常远少于该上限)
     * - 同时缓存 "未找到" (null) 状态, 避免书源被删后重复查库
     * - synchronized 包裹保证 onEachParallel 并发访问安全
     *
     * 简化 LRU: commonMain 的 kotlin.collections.LinkedHashMap 无 accessOrder 构造参数
     * (JVM 专属), 改用超过容量时整体 clear 的简化策略 (与 app 端 LruCache 逐条淘汰行为有差异,
     * 但一次自动更新批次通常 < 16 本书源, 超过时清空重填可接受)。
     *
     * 内部包装 [SourceWrapper] 而非直接存 BookSource?, 与 app 端保持一致 (LruCache 不存 null)。
     */
    /** 调用方必须持有 [tocStateLock]。 */
    private fun publishTocBusyLocked() {
        _tocBusy.value = waitUpTocBooks.isNotEmpty() || onUpTocBooks.isNotEmpty()
    }

    private val bookSourceCacheLock = SynchronizedObject()
    private val bookSourceCache = LinkedHashMap<String, SourceWrapper>()

    // KMP: addDownload 原子性保护 (替代 @Synchronized, Native 端 @Synchronized 无效)
    private val addDownloadLock = SynchronizedObject()

    private class SourceWrapper(val source: BookSource?)

    // KMP: 改 suspend 避免 runBlocking 持锁阻塞 (Native 端 deadlock 风险)
    // 逻辑等价: 先查缓存(含 null source 的 wrapper), 未命中查 DB 后回填
    private suspend fun getBookSource(origin: String): BookSource? {
        val cached = synchronized(bookSourceCacheLock) { bookSourceCache[origin] }
        if (cached != null) return cached.source
        val source = appDb.bookSourceDao.getBookSource(origin)
        synchronized(bookSourceCacheLock) {
            // 简化 LRU: 超过 16 个时清空 (commonMain 无 accessOrder, 见字段注释)
            if (bookSourceCache.size > 16) bookSourceCache.clear()
            bookSourceCache[origin] = SourceWrapper(source)
        }
        return source
    }

    /**
     * 本次应用进程内已触发过自动更新的分组 ID, 避免 fragment 回收重建 / Composable 重组后再次触发
     * (对照 app 端 autoUpdatedGroups)。
     */
    private val autoUpdatedGroupsLock = SynchronizedObject()
    private val autoUpdatedGroups = HashSet<Long>()

    fun markGroupAutoUpdated(groupId: Long): Boolean =
        synchronized(autoUpdatedGroupsLock) { autoUpdatedGroups.add(groupId) }

    /**
     * 监听 STOP_UP_BOOK 事件 (对照 app 端 MainViewModel.init 块)。
     *
     * 用户从通知/菜单取消更新时, FlowBus 发 STOP_UP_BOOK 事件, 本 init 块:
     * 1. 取消 upTocJob / refreshJob
     * 2. 清空等待队列 waitUpTocBooks
     * 3. 把正在更新的 onUpTocBooks 逐个 postEvent(UP_BOOKSHELF) 通知 UI 刷新
     * 4. 清空 onUpTocBooks
     * 5. 重置计数器
     * 6. updateProgress 更新通知状态
     *
     * 不调 stopService (Android Service 专属), 各端通过 callback.onProgressCancel 自行清理。
     */
    init {
        FlowBus.with(EventBus.STOP_UP_BOOK).onEach {
            upTocJob?.cancel()
            refreshJob?.cancel()
            val urls = synchronized(tocStateLock) {
                waitUpTocBooks.clear()
                val list = onUpTocBooks.toList()
                onUpTocBooks.clear()
                publishTocBusyLocked()
                list
            }
            urls.forEach {
                postEvent(EventBus.UP_BOOKSHELF, it)
            }
            upTocTotal.value = 0
            upTocCount.value = 0
            refreshTotal.value = 0
            refreshCount.value = 0
            updateProgress()
        }.launchIn(scope)
    }

    // region UI 状态暴露 (替代 app 端 updateUpdateNotification 的通知栏进度)

    /** 是否正在刷新 (upToc 或 refresh 任一在跑), UI 用于禁用刷新按钮 / 显示 loading */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 进度文案 (如 "更新目录 3/10"), null 表示无任务; UI 可选订阅显示在顶栏 */
    private val _progressText = MutableStateFlow<String?>(null)
    val progressText: StateFlow<String?> = _progressText.asStateFlow()
    // endregion

    companion object {
        /** 自动更新时, 距上次检查不足该时长的书籍跳过 (对照 app 端 AUTO_UPDATE_STALE_MS) */
        private const val AUTO_UPDATE_STALE_MS = 10 * 60 * 1000L
    }

    /**
     * 取消刷新 / 更新目录任务, 用于退出应用或切换路由时清理 (对照 app 端 cancelRefreshJobs)。
     *
     * 不调 stopService (Android Service 专属), 仅取消协程 Job + 清空等待队列 + 重置计数器。
     * 同时取消 [cacheBookJob] + 通过 callback.onProgressCancel 取消进度通知
     * (对照 app 端 cancelRefreshJobs 调 stopService<UpdateBookService> 间接触发通知取消)。
     */
    fun cancelRefreshJobs() {
        upTocJob?.cancel()
        refreshJob?.cancel()
        cacheBookJob?.cancel()
        synchronized(tocStateLock) {
            waitUpTocBooks.clear()
            onUpTocBooks.clear()
            publishTocBusyLocked()
        }
        upTocTotal.value = 0
        upTocCount.value = 0
        refreshTotal.value = 0
        refreshCount.value = 0
        _isRefreshing.value = false
        _progressText.value = null
        // 取消进度通知 (对照 app 端 cancelRefreshJobs 间接触发 UpdateBookService.onDestroy 取消通知)
        callback.onProgressCancel()
    }

    fun isUpdate(bookUrl: String): Boolean {
        return synchronized(tocStateLock) { onUpTocBooks.contains(bookUrl) }
    }

    /**
     * 主动更新目录, 不做时间窗判断 (用于下拉刷新 / 菜单项)。
     * 对照 app 端 `MainViewModel.upToc(books)`。
     *
     * 仅入队 [Book.bookUrl], 实际目录更新在 [startUpTocJob] 中按 threadCount 限流并发执行。
     * 本地书 / 不可更新书 (canUpdate=false) 跳过。
     */
    fun upToc(books: List<Book>) {
        if (books.isEmpty()) return
        scope.launch(Dispatchers.Default) {
            val urls = books.mapNotNull {
                if (!it.isLocal && it.canUpdate) it.bookUrl else null
            }
            addToWaitUp(urls)
        }
    }

    /**
     * 强制刷新书籍信息, 无视 canUpdate 属性 (用于菜单项)。
     * 对照 app 端 `MainViewModel.forceRefresh(books)`。
     *
     * 与 [upToc] 区别: upToc 只刷新目录 (getChapterListAwait), forceRefresh 还会先刷书籍详情
     * (getBookInfoAwait), 用于书源规则变更后整体回填。本地书跳过。
     *
     * 任一刷新任务在跑时拒绝重复触发, toast 提示 (对照 app 端 R.string.force_refresh_busy)。
     */
    fun forceRefresh(books: List<Book>) {
        if (books.isEmpty()) return
        if (upTocJob?.isActive == true || refreshJob?.isActive == true) {
            callback.toastForceRefreshBusy()
            return
        }
        refreshJob = scope.launch(Dispatchers.Default) {
            val urls = books.filterNot { it.isLocal }.map { it.bookUrl }
            if (urls.isEmpty()) return@launch
            refreshTotal.value = urls.size
            refreshCount.value = 0
            _isRefreshing.value = true
            updateProgress()
            callback.toastForceRefreshStart(urls.size)
            refreshBook(urls)
        }
    }

    /**
     * 并发刷新书籍信息 (对照 app 端 `MainViewModel.refreshBook`)。
     *
     * 入参是 bookUrl 而非 Book: onEachParallel 限流后排书可能等较久, 期间用户可能修改进度 /
     * 分组等, 必须执行时从 DB 重查最新值, 否则 [syncBookProgress] 拷回的是入队时的旧快照,
     * update 会冲掉用户的并发修改。
     */
    private suspend fun refreshBook(bookUrls: List<String>) {
        upPool()
        bookUrls.asFlow()
            .onEachParallel(threadCount) { bookUrl ->
                val book = appDb.bookDao.getBook(bookUrl) ?: return@onEachParallel
                val source = getBookSource(book.origin) ?: return@onEachParallel
                try {
                    // 在 WebBook 修改 book 字段前快照 (replace 用 oldBook 按旧 bookUrl 删除,
                    // 必须保存修改前的 bookUrl, 与 app 端 `val oldBook = book.copy()` 一致)
                    val oldBook = book.copy()
                    val oldBookUrl = book.bookUrl
                    WebBook.getBookInfoAwait(source, book)
                    val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                    syncBookProgress(book, oldBookUrl)
                    book.removeType(BookType.updateError)
                    val bookUrlChanged = book.bookUrl != bookUrl
                    // 合并 DB 写入: 用 suspend 事务 (KMP 安全, 无 runBlocking 死锁风险)
                    // Android 端走 room-ktx withTransaction, Desktop/Native 降级为直接执行
                    appDb.runInTransactionSuspending {
                        if (bookUrlChanged) appDb.bookDao.replace(oldBook, book)
                        else appDb.bookDao.update(book)
                        appDb.bookChapterDao.delByBook(bookUrl)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                    // bookUrl 变更时迁移本地缓存目录 (对照 app 端 BookHelp.updateCacheFolder)
                    if (bookUrlChanged) {
                        BookStorageProviders.get().updateCacheFolder(oldBook, book)
                    }
                    // 预下载后续章节 (对照 app 端 addDownload, 受 preDownloadNum 控制)
                    addDownload(source, book)
                    postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("${book.name} 强制刷新失败\n${e.message}", e)
                }
                refreshCount.incrementAndGet()
                updateProgress()
            }.onCompletion {
                refreshCount.value = 0
                refreshTotal.value = 0
                onRefreshJobCompleted()
                // 正常完成 (非 cancel/异常) 时 toast 反馈, 对照 app 端 forceRefresh 完成反馈;
                // 自动更新 upToc 完成不 toast 避免打扰用户
                if (it == null) {
                    callback.toastForceRefreshDone()
                }
            }.catch {
                AppLog.put("强制刷新书籍出错\n${it.message}", it)
            }.collect { }
    }

    /**
     * 自动更新目录, 跳过最近已检查过的书籍 (对照 app 端 `MainViewModel.scheduleAutoUpdate`)。
     *
     * 时间窗 [AUTO_UPDATE_STALE_MS] = 10 分钟, 距上次检查不足该时长的书籍跳过,
     * 避免短时间内反复刷新同一本书 (如 Composable 重组 / 路由切换)。
     *
     * 受 [AppConfigAccessor.autoRefreshBook] 控制: 配置关闭时直接 return (对照 app 端
     * observeGroupBooks 内 `AppConfig.autoRefreshBook` 判断)。
     *
     * # iOS/鸿蒙 ServiceLauncher.startUpdateBookService 入口
     * iOS/鸿蒙端无 MainViewModel, NativeServiceLauncher.startUpdateBookService 调用本方法
     * 触发全书架自动更新 (从 DB 查所有书), 替代原 stub。
     */
    fun scheduleAutoUpdate(books: List<Book>) {
        if (books.isEmpty()) return
        if (!appConfig.autoRefreshBook) return
        val now = systemCurrentTimeMillis()
        scope.launch(Dispatchers.Default) {
            val urls = books.mapNotNull {
                if (!it.isLocal && it.canUpdate
                    && now - it.lastCheckTime > AUTO_UPDATE_STALE_MS
                ) it.bookUrl else null
            }
            addToWaitUp(urls)
        }
    }

    // KMP: 删除 @Synchronized (Native 无效), 目录状态由 tocStateLock 统一保护。
    private fun addToWaitUp(urls: Collection<String>) {
        if (urls.isEmpty()) return
        synchronized(tocStateLock) {
            urls.forEach { url ->
                if (url !in onUpTocBooks && waitUpTocBooks.add(url)) {
                    upTocTotal.incrementAndGet()
                }
            }
            publishTocBusyLocked()
        }
        updateProgress()
        if (upTocJob == null && refreshJob?.isActive != true) {
            startUpTocJob()
        }
    }

    /** 从等待集合原子迁移到执行集合，避免两次操作之间短暂发布空闲状态。 */
    private fun pollWaitUpTocBook(): String? = synchronized(tocStateLock) {
        val iter = waitUpTocBooks.iterator()
        if (iter.hasNext()) {
            val value = iter.next()
            iter.remove()
            onUpTocBooks.add(value)
            publishTocBusyLocked()
            value
        } else null
    }

    private fun waitUpTocBooksEmpty(): Boolean = synchronized(tocStateLock) {
        waitUpTocBooks.isEmpty()
    }

    // KMP: 删除 @Synchronized (Native 无效), 内部已用 synchronized 保护
    private fun onUpTocJobCompleted() {
        upTocJob = null
        if (!waitUpTocBooksEmpty()) {
            // 等待队列还有书, 启动下一批 (startUpTocJob 会重设 upTocJob + _isRefreshing=true)
            startUpTocJob()
        } else {
            tryEvictBookSourceCache()
        }
        updateProgress()
    }

    // KMP: 删除 @Synchronized (Native 无效), 内部已用 synchronized 保护
    private fun onRefreshJobCompleted() {
        refreshJob = null
        if (upTocJob == null) {
            if (!waitUpTocBooksEmpty()) {
                // refresh 完成后顺带处理等待队列里 upToc 任务 (对照 app 端 startUpTocJob 调用)
                startUpTocJob()
            } else {
                tryEvictBookSourceCache()
            }
        }
        updateProgress()
    }

    /**
     * 使用书源缓存的两类 job (updateToc / refreshBook) 都空闲时才清,
     * 任一仍在跑就保留以提升命中率 (对照 app 端 tryEvictBookSourceCache)。
     */
    private fun tryEvictBookSourceCache() {
        if (upTocJob?.isActive == true) return
        if (refreshJob?.isActive == true) return
        synchronized(bookSourceCacheLock) {
            bookSourceCache.clear()
        }
    }

    /**
     * 启动目录更新 job, 从等待队列持续拉 bookUrl 并发执行 (对照 app 端 startUpTocJob)。
     *
     * 与 [refreshBook] 区别: 仅刷目录 (getChapterListAwait), 不刷书籍详情; 当 book.tocUrl
     * 为空时先调 getBookInfoAwait 补全 tocUrl; 失败回退到详情页重新开始。
     *
     * 完成后在 onCompletion 中触发 [cacheBook] 预下载 (对照 app 端 startUpTocJob.onCompletion
     * 内 `if (it == null && cacheBookJob == null && !CacheBookService.isRun) cacheBook()`)。
     */
    private fun startUpTocJob() {
        if (refreshJob?.isActive == true) return
        upPool()
        _isRefreshing.value = true
        upTocJob = scope.launch(upTocPool) {
            flow {
                while (true) {
                    emit(pollWaitUpTocBook() ?: break)
                }
            }.onEachParallel(threadCount) {
                postEvent(EventBus.UP_BOOKSHELF, it)
                try {
                    updateToc(it)
                    upTocCount.incrementAndGet()
                    updateProgress()
                } finally {
                    synchronized(tocStateLock) {
                        onUpTocBooks.remove(it)
                        publishTocBusyLocked()
                    }
                    postEvent(EventBus.UP_BOOKSHELF, it)
                }
            }.onCompletion {
                upTocCount.value = 0
                upTocTotal.value = 0
                // onUpTocJobCompleted 内会判断是否启动下一批 + updateProgress 同步进度状态
                onUpTocJobCompleted()
                updateProgress()
                // 所有目录更新完成 (非 cancel/异常) 且无预下载任务在跑时, 启动预下载
                // (对照 app 端 startUpTocJob.onCompletion 内
                //  `if (it == null && cacheBookJob == null && !CacheBookService.isRun) cacheBook()`)
                // 平台差异: 用 CacheBookShared.isRun 替代 CacheBookService.isRun / DesktopCacheBook.isRun
                // (各端 CacheBook 单例都是 CacheBookShared 的薄壳, isRun 直接查 cacheBookMap 内任务状态)
                if (it == null && cacheBookJob == null && !CacheBookShared.isRun) {
                    cacheBook()
                }
            }.catch {
                AppLog.put("更新目录出错\n${it.message}", it)
            }.collect { }
        }
    }

    /**
     * 更新单本目录 (对照 app 端 `MainViewModel.updateToc`)。
     *
     * 流程:
     * 1. 从 DB 查最新 book (入队后可能已被其他流程修改)
     * 2. 取书源 (带 LRU 缓存), 找不到则标记 updateError 写回
     * 3. tocUrl 为空先 getBookInfoAwait 补全, 再 getChapterListAwait 拉目录
     * 4. 失败回退: 带 tocUrl 刷新失败时, 重走 getBookInfoAwait + getChapterListAwait
     * 5. [syncBookProgress] 同步阅读进度, 避免冲掉用户并发修改
     * 6. 写回 DB (book + chapter), postEvent 通知 UI
     *
     * 失败时标记 updateError + 更新 lastCheckTime, 避免短时间内反复重试失败书。
     */
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
        try {
            // 在 WebBook 修改 book 字段前快照 (replace 用 oldBook 按旧 bookUrl 删除,
            // 必须保存修改前的 bookUrl, 与 app 端 `val oldBook = book.copy()` 一致)
            val oldBook = book.copy()
            val oldBookUrl = book.bookUrl
            var tocResult = if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
                WebBook.getChapterListAwait(source, book)
            } else {
                WebBook.runPreUpdateJs(source, book)
                WebBook.getChapterListAwait(source, book)
            }

            // 失败回退逻辑: 如果带 tocUrl 刷新失败, 尝试从详情页重新开始
            if (tocResult.isFailure && book.tocUrl.isNotBlank()) {
                WebBook.getBookInfoAwait(source, book)
                tocResult = WebBook.getChapterListAwait(source, book)
            }

            val toc = tocResult.getOrThrow()
            syncBookProgress(book, oldBookUrl)
            book.removeType(BookType.updateError)
            val bookUrlChanged = book.bookUrl != bookUrl
            // KMP: 用 suspend 事务 (runInTransactionSuspending), 禁用 runBlocking (Native 端死锁)
            // updateToc 本身是 suspend, 可直接调 suspend 版本事务, block 内 DAO 也是 suspend
            appDb.runInTransactionSuspending {
                if (bookUrlChanged) appDb.bookDao.replace(oldBook, book)
                else appDb.bookDao.update(book)
                appDb.bookChapterDao.delByBook(bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            // bookUrl 变更时迁移本地缓存目录 (对照 app 端 BookHelp.updateCacheFolder)
            if (bookUrlChanged) {
                BookStorageProviders.get().updateCacheFolder(oldBook, book)
            }
            // 预下载后续章节 (对照 app 端 addDownload, 受 preDownloadNum 控制)
            addDownload(source, book)
            postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            AppLog.put("${book.name} 更新目录失败\n${e.message}", e)
            // 这里可能因为时间太长书籍信息已经更改, 所以重新获取
            appDb.bookDao.getBook(book.bookUrl)?.let { latest ->
                latest.addType(BookType.updateError)
                latest.lastCheckTime = systemCurrentTimeMillis()
                appDb.bookDao.update(latest)
            }
        }
    }

    /**
     * 同步阅读进度到刷新后的 book (对照 app 端 `Book.sync(oldBook)`, 不简化)。
     *
     * app 端 sync 依赖 `ContentProcessor.get(this).getTitleReplaceRules()` 重算 durChapterTitle
     * (ContentProcessor 重 Android 依赖, 未下沉), 本类用 [ContentProcessorProviders.get]
     * (各端 actual 注册, app 端桥接 ContentProcessor, 桌面端桥接 replaceRuleDao) 替代,
     * 行为一致:
     * - 查标题作用域替换规则
     * - 用 [getDisplayTitle] 应用替换规则 + [getUseReplaceRule] 判断是否启用
     *
     * @param book 刷新后的 book (会被修改字段)
     * @param oldBookUrl 刷新前的 bookUrl (用于从 DB 查当前阅读进度)
     */
    private suspend fun syncBookProgress(book: Book, oldBookUrl: String) {
        val curBook = appDb.bookDao.getBook(oldBookUrl) ?: return
        book.durChapterTime = curBook.durChapterTime
        book.durChapterPos = curBook.durChapterPos
        if (book.durChapterIndex != curBook.durChapterIndex) {
            book.durChapterIndex = curBook.durChapterIndex
            // 应用标题替换规则 (对照 app 端 Book.sync: ContentProcessor.get(this).getTitleReplaceRules()
            // + it.getDisplayTitle(replaceRules, getUseReplaceRule()))
            // 用 ContentProcessorProviders 替代 ContentProcessor 单例 (各端 actual 已注册),
            // 行为一致: 查 replaceRuleDao.findEnabledByTitleScope + 应用替换规则
            val replaceRules = ContentProcessorProviders.get().getTitleReplaceRules(book)
            appDb.bookChapterDao.getChapter(book.bookUrl, curBook.durChapterIndex)?.let {
                book.durChapterTitle = it.getDisplayTitle(replaceRules, book.getUseReplaceRule())
            }
        }
        book.canUpdate = curBook.canUpdate
        book.readConfig = curBook.readConfig
    }

    /**
     * 添加预下载任务 (对照 app 端 `MainViewModel.addDownload`, 不简化)。
     *
     * 流程与 app 端完全一致:
     * 1. preDownloadNum == 0 时直接返回 (用户关闭预下载)
     * 2. 计算结束 index = min(totalChapterNum - 1, durChapterIndex + preDownloadNum)
     * 3. CacheBookShared.getOrCreate(source, book) 获取或创建下载模型
     * 4. cacheBook.addDownload(durChapterIndex, endIndex) 入队
     *
     * @Synchronized 与 app 端一致, 保护 getOrCreate + addDownload 的原子性
     * (避免两个并发调用同时 getOrCreate 同一本书产生两个 CacheBookModel)
     *
     * 平台差异: 直接调 [CacheBookShared.getOrCreate] (替代 app 端 `CacheBook.getOrCreate` /
     * 桌面端 `DesktopCacheBook.getOrCreate`, 各端 CacheBook 单例都是 CacheBookShared 的薄壳)。
     */
    // KMP: @Synchronized 改 synchronized(addDownloadLock) (Native 端 @Synchronized 无效)
    // 保护 getOrCreate + addDownload 原子性, 避免并发产生两个 CacheBookModel
    private fun addDownload(source: BookSource, book: Book) {
        if (appConfig.preDownloadNum == 0) return
        synchronized(addDownloadLock) {
            // 结束章节 index (含): 不超过最后章节 - 1, 不超过当前阅读 + preDownloadNum
            val endIndex = min(
                book.totalChapterNum - 1,
                book.durChapterIndex.plus(appConfig.preDownloadNum)
            )
            val cacheBook = CacheBookShared.getOrCreate(source, book)
            cacheBook.addDownload(book.durChapterIndex, endIndex)
        }
    }

    /**
     * 启动章节预下载 job (对照 app 端 `MainViewModel.cacheBook`, 不简化)。
     *
     * 流程与 app 端完全一致:
     * 1. preDownloadNum == 0 时直接返回
     * 2. cancel 已有的 cacheBookJob (避免重复启动)
     * 3. 在 upTocPool 上启动新 job:
     *    - 订阅 [_tocBusy]: 有目录更新时暂停预下载 (setWorkingState(false)),
     *      优先让网络资源服务目录更新 (与 app 端注释"现在更多网站限制并发"一致)
     *    - 调用 CacheBookShared.startProcessJob(upTocPool) 持续处理 cacheBookMap
     *
     * 平台差异:
     * - 直接调 [CacheBookShared.startProcessJob] (替代 app 端 `CacheBook.startProcessJob` /
     *   桌面端 `DesktopCacheBook.startProcessJob`, 各端 CacheBook 单例都是 CacheBookShared 薄壳)
     * - 用 [CacheBookShared.isRun] 替代 app 端 `CacheBookService.isRun` /
     *   桌面端 `DesktopCacheBook.isRun` (CacheBookShared.isRun 直接查 cacheBookMap 内任务状态)
     * - 用 [CacheBookShared.setWorkingState] 替代 app 端 `CacheBook.setWorkingState` /
     *   桌面端 `DesktopCacheBook.setWorkingState`
     */
    private fun cacheBook() {
        if (appConfig.preDownloadNum == 0) return
        cacheBookJob?.cancel()
        cacheBookJob = scope.launch(upTocPool) {
            val busyJob = launch {
                // 有目录更新时不缓存；集合增删会立即发布忙闲状态，无需定时轮询。
                _tocBusy.collect { busy ->
                    CacheBookShared.setWorkingState(!busy)
                }
            }
            try {
                // startProcessJob 持续遍历 cacheBookMap 处理待下载章节
                // (内部 onEachParallel(MAX_THREAD) 并发, Mutex 互斥避免重复启动)
                CacheBookShared.startProcessJob(upTocPool)
            } finally {
                busyJob.cancel()
                CacheBookShared.setWorkingState(true)
            }
        }
    }

    /**
     * 按需重建 [upTocPool] (对照 app 端 upPool)。
     *
     * 用户改了 threadCount 后, 下一批次启动前重建线程池让新值生效。
     * 任一 job 在跑时跳过 (避免取消运行中的任务)。
     *
     * public: 供 app 端 MainViewModel.upPool() / 桌面端等价物转发,
     * 在 PreferKey.threadCount 变化时立即触发重建。
     */
    fun upPool() {
        threadCount = appConfig.threadCount
        // 平台差异: app 端 upPool 检查 upTocJob/refreshJob/cacheBookJob, 本类对齐
        // (cacheBookJob 也在 upTocPool 上跑, 改池子需先等它结束, 避免任务被错误取消)
        if (upTocJob?.isActive == true || refreshJob?.isActive == true || cacheBookJob?.isActive == true) {
            return
        }
        val newPoolSize = min(threadCount, AppConst.MAX_THREAD)
        if (poolSize == newPoolSize) {
            return
        }
        poolSize = newPoolSize
        upTocPool.closeIfCloseable()
        upTocPool = newFixedThreadPoolDispatcher(poolSize)
    }

    /**
     * 更新进度文案 + 刷新状态 + 平台进度通知 (对照 app 端 updateUpdateNotification /
     * 桌面端 updateProgress)。
     *
     * 平台差异通过 [UpdateBookCallback.onProgressUpdate] 抽象:
     * - app 端 callback 实现: startService<UpdateBookService> + NotificationManagerCompat.notify
     *   (NotificationCompat.Builder 设置进度, 与原 updateUpdateNotification 一致)
     * - 桌面端 callback 实现: NotificationProgresses.get().showProgress (SystemTray 文本通知) +
     *   StateFlow 暴露给 UI
     * - iOS/鸿蒙 callback 实现: NativeUpdateBookCallback 桥接 NotificationProgresses + Toasters
     *
     * 两个 job 任一在跑时显示对应文案 + isRefreshing=true, 都空闲时清空 + 取消通知。
     */
    private fun updateProgress() {
        val upTocActive = upTocJob?.isActive == true
        val refreshActive = refreshJob?.isActive == true
        refreshProgress(upTocActive, refreshActive)
    }

    /**
     * 供 app 端 MainViewModel.updateUpdateNotification() / 桌面端等价物转发,
     * 在 Activity 可见性变化时立即刷新通知状态 (不依赖下一次 onProgressUpdate)。
     */
    fun refreshProgress() = refreshProgress(
        upTocJob?.isActive == true,
        refreshJob?.isActive == true
    )

    private fun refreshProgress(upTocActive: Boolean, refreshActive: Boolean) {
        if (!upTocActive && !refreshActive) {
            _progressText.value = null
            _isRefreshing.value = false
            // 取消进度通知 (对照 app 端 updateUpdateNotification 内 stopService 取消通知)
            callback.onProgressCancel()
            return
        }
        _isRefreshing.value = true
        val count = upTocCount.value + refreshCount.value
        val total = upTocTotal.value + refreshTotal.value
        val title = if (refreshActive) "强制刷新" else "更新目录"
        val msg = "$count/$total"
        _progressText.value = "$title $msg"
        // 显示进度通知 (对照 app 端 updateUpdateNotification 内 NotificationManager.notify)
        // 各端 callback 实现自行桥接平台通知 API
        callback.onProgressUpdate(
            active = true,
            title = title,
            content = msg,
            count = count,
            total = total
        )
    }

    /**
     * 宿主销毁时调用, 取消所有协程 + 关闭线程池 (对照 app 端 ViewModel.onCleared)。
     *
     * 平台差异:
     * - app 端 onCleared 仅 close upTocPool (cacheBookJob 由 viewModelScope 取消间接取消,
     *   CacheBook 单例跨 Activity 保留)
     * - 桌面端无 ViewModelStore, scope.cancel() 会自动取消所有子协程 (包括 cacheBookJob),
     *   cancelRefreshJobs 已显式 cancel cacheBookJob, 这里再 close upTocPool 兜底
     * - iOS/鸿蒙 NativeServiceLauncher 销毁时调用, 行为对齐
     *
     * 注: 不调 CacheBookShared.close() (单例跨 VM 保留, 与 app 端 CacheBook 语义一致;
     * 进程退出时 JVM 自动回收, 无需显式清理)。
     */
    fun onCleared() {
        cancelRefreshJobs()
        upTocPool.closeIfCloseable()
    }
}

/**
 * UpdateBook 编排的平台差异回调抽象 (shared commonMain)。
 *
 * # 背景
 * app 端 `MainViewModel.updateUpdateNotification` 用 `NotificationManagerCompat` +
 * `startService<UpdateBookService>` 显示通知栏进度, `context.toastOnUi(R.string.xxx)` 显示 toast;
 * 桌面端 `DesktopMainViewModel.updateProgress` 用 `NotificationProgresses` (SystemTray 文本通知) +
 * StateFlow 暴露给 UI, `Toasters.get().toast(msg)` 显示 toast;
 * iOS/鸿蒙端经各自已注册的 NotificationProgress / Toaster 真实实现桥接。
 *
 * 本接口把上述差异抽象出来, 由各端 actual 自行实现注册:
 * - Android: app 端 `MainViewModel` 持有 callback 实现, 桥接 `NotificationManagerCompat` +
 *   `startService<UpdateBookService>` + `context.toastOnUi`
 * - 桌面: `DesktopMainViewModel` 持有 callback 实现, 桥接 `NotificationProgresses` + `Toasters`
 * - iOS/鸿蒙: `NativeServiceLauncher` 持有 `NativeUpdateBookCallback`,
 *   同样桥接 `NotificationProgresses` + `Toasters`
 *
 * 模式参考 [io.legado.app.model.CacheBookCallback] / [io.legado.app.help.book.BookHelpAccessor]。
 */
interface UpdateBookCallback {

    /**
     * 更新进度通知 (对照 app 端 `updateUpdateNotification` / 桌面端 `updateProgress` 的显示分支)。
     *
     * @param active 是否有任务在跑 (true=显示进度, false=应取消, 但 false 时调 [onProgressCancel])
     * @param title 进度标题 ("更新目录" / "强制刷新")
     * @param content 进度文案 (如 "3/10")
     * @param count 已完成数
     * @param total 总数
     */
    fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int)

    /**
     * 取消进度通知 (对照 app 端 `updateUpdateNotification` 内 `stopService<UpdateBookService>` /
     * 桌面端 `NotificationProgresses.get().cancel()`)。
     *
     * 在 [UpdateBookShared.cancelRefreshJobs] / [UpdateBookShared.updateProgress] (无任务分支) /
     * [UpdateBookShared.onCleared] 时调用。
     */
    fun onProgressCancel()

    /**
     * 提示"正在刷新中, 请稍后再试" (对照 app 端 `R.string.force_refresh_busy` /
     * 桌面端 `Toasters.get().toast("正在刷新中, 请稍后再试")`)。
     *
     * 在 [UpdateBookShared.forceRefresh] 检测到已有刷新任务在跑时调用。
     */
    fun toastForceRefreshBusy()

    /**
     * 提示"开始强制刷新 N 本" (对照桌面端 `Toasters.get().toast("开始强制刷新 ${urls.size} 本")`)。
     *
     * app 端原 MainViewModel 无此 toast (仅桌面端有), app 端 callback 可 no-op。
     *
     * @param count 即将刷新的书籍数
     */
    fun toastForceRefreshStart(count: Int)

    /**
     * 提示"刷新完成" (对照桌面端 `Toasters.get().toast("刷新完成")`)。
     *
     * app 端原 MainViewModel 无此 toast (仅桌面端有), app 端 callback 可 no-op。
     */
    fun toastForceRefreshDone()
}

/**
 * [UpdateBookCallback] 容器 (provider 注入模式, 可选)。
 *
 * 与 [io.legado.app.model.CacheBookCallbacks] 模式一致, 用于 Native (iOS/鸿蒙) 端
 * 注册 NativeUpdateBookCallback (桥接 NotificationProgresses + Toasters), 让
 * UpdateBookShared 在 Native target 也能构造 (无 callback 须由调用方传入时)。
 *
 * app / 桌面端 VM 直接通过构造参数注入 callback (不经本 provider), 与
 * [io.legado.app.model.CacheBookCallback] 注册模式对齐。
 */
object UpdateBookCallbacks {

    @Volatile
    private var defaultImpl: UpdateBookCallback? = null

    /**
     * 注册默认 callback (Native 端 NativeServiceLauncher 启动期调用)。
     *
     * app / 桌面端 VM 不调本方法, 直接构造时传 callback;
     * Native 端无 VM, 由 NativeServiceLauncher 持有 callback 实例并传给 UpdateBookShared。
     */
    fun registerDefault(impl: UpdateBookCallback) {
        this.defaultImpl = impl
    }

    /** 获取已注册的默认 callback (供 Native 端 NativeServiceLauncher 取用), 未注册返回 null。 */
    fun getDefault(): UpdateBookCallback? = defaultImpl
}
