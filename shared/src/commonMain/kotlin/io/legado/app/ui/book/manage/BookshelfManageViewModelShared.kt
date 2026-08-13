package io.legado.app.ui.book.manage

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 书架管理 ViewModel 共享核心 (commonMain)。
 *
 * 对照 app 端 `BookshelfManageViewModel(app) : BaseViewModel(app)`: 核心批量管理方法
 * (upCanUpdate/updateBook/deleteBook/changeSource/clearCache/loadCacheFiles) 仅依赖
 * DAO + 协程 + WebBook + BookHelp + Book.migrateTo + FileBook.deleteBook, 可下沉多端复用。
 * DAO/AppConfig 走 [AppDbProviders.get]/[AppConfigProviders.get]; `execute{...}` 链式回调
 * 下沉为直接调 [Coroutine.async], 行为等价。
 *
 * 平台专属逻辑经 [BookshelfManagePlatform] 注入: BookHelp.clearCache/getChapterFiles、
 * FileBook.deleteBook (DocumentFile/ContentResolver) 留 app 端, 本地书删除经
 * deleteLocalBook 委托; toast 文案 (R.string.clear_cache_success) 在 app 端解析成字符串。
 *
 * 状态桥接: batchChangeSourceState/Process → StateFlow, upAdapter → SharedFlow (事件语义);
 * app 端 VM 用 viewModelScope.launch { collect { liveData.postValue(it) } } 桥接。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承); saveAllUseBookSourceToFile /
 * exportBookshelf 留 app 端 (依赖 context.filesDir + 文件流)。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 * @param platform 平台专属依赖聚合 (Book.migrateTo + BookHelp.clearCache/getChapterFiles +
 *   FileBook.deleteBook + clearCacheSuccessMessage 字符串)
 */
@Suppress("MemberVisibilityCanBePrivate")
class BookshelfManageViewModelShared(
    private val scope: CoroutineScope,
    private val platform: BookshelfManagePlatform,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** AppConfig 容器 (宿主启动时由 app 端注册 AppConfigAccessorImpl)。 */
    private val appConfig get() = AppConfigProviders.get()

    /** 书架分组 ID (Activity 在 onActivityCreated 时设置)。 */
    var groupId: Long = -1L

    /** 书架分组名 (Activity 异步加载, 用作搜索框 hint)。 */
    var groupName: String? = null

    /**
     * 批量换源进行中状态流 (对照原 `batchChangeSourceState: MutableLiveData<Boolean>`)。
     *
     * app 端用 `viewModelScope.launch { collect { batchChangeSourceState.postValue(it) } }` 桥接,
     * Activity `observe { if (it) waitDialog.show else waitDialog.dismiss }` 行为不变。
     */
    private val _batchChangeSourceState = MutableStateFlow(false)
    val batchChangeSourceState: StateFlow<Boolean> = _batchChangeSourceState.asStateFlow()

    /**
     * 批量换源进度文案流 (对照原 `batchChangeSourceProcessLiveData: MutableLiveData<String>`)。
     *
     * 格式 `"<index+1> / <total>"`, app 端桥接到 LiveData 后 Activity observe 显示到 waitDialog。
     */
    private val _batchChangeSourceProcess = MutableStateFlow("")
    val batchChangeSourceProcess: StateFlow<String> = _batchChangeSourceProcess.asStateFlow()

    /**
     * 当前批量换源协程 (对照原 `var batchChangeSourceCoroutine: Coroutine<Unit>?`)。
     *
     * Activity waitDialog.onCancelListener 调 `viewModel.batchChangeSourceCoroutine?.cancel()`
     * 取消换源, 通过 getter 转发。setter 私有: 仅 [changeSource] 内重新赋值。
     */
    var batchChangeSourceCoroutine: Coroutine<Unit>? = null
        private set

    /**
     * 章节缓存列表更新通知流 (对照原 `upAdapterLiveData: MutableLiveData<String>`)。
     *
     * [loadCacheFiles] 完成扫描后按批推送本批已扫描完成的 bookUrl 列表 (任务2: 批量发射,
     * 每批 20 本合并一次, 避免海量书籍逐本发射事件), 对应原版 notifyItemChanged。
     * 用 SharedFlow: 同一批重复推送相同 bookUrl, StateFlow 会去重导致该行不再刷新。
     */
    private val _upAdapter = MutableSharedFlow<List<String>>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val upAdapter: SharedFlow<List<String>> = _upAdapter.asSharedFlow()

    /**
     * 当前缓存加载协程 (对照原 `private var loadChapterCoroutine: Coroutine<Unit>?`)。
     *
     * Activity upBookDataByGroupId 在 books flow collect 时调用 [loadCacheFiles], 每次
     * 先 cancel 旧协程。setter 私有: 仅 [loadCacheFiles] 内重新赋值。
     */
    var loadChapterCoroutine: Coroutine<Unit>? = null
        private set

    /**
     * 每本书已缓存的章节 URL 集合 (对照原 `val cacheChapters = hashMapOf<String, HashSet<String>>()`)。
     *
     * Activity 多处直接读写 (`viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)`
     * / `?.size` / `?.let { ... }`), 通过 getter 转发暴露可变映射, 行为不变。
     */
    val cacheChapters = hashMapOf<String, HashSet<String>>()

    /**
     * 每本书缓存文件总字节 (任务要求"缓存统计:展示每本书缓存大小")。
     *
     * [loadCacheFiles] 扫描时调用 [BookshelfManagePlatform.getCacheSize] 累加, 供宿主端
     * [io.legado.app.ui.route.BookshelfManageRoute.cacheInfo] 拼接 "12/100 · 3.5MB" 文案。
     */
    val cacheSizes = hashMapOf<String, Long>()

    /**
     * 批量更新书籍 canUpdate 标记, 对应 app 端 `upCanUpdate(books, canUpdate)`。
     *
     * # 实现细节保持
     *
     * - 用 `books[it].copy(canUpdate = canUpdate)` 复制新实例 (与原一致, 不修改入参);
     * - `canUpdate = false` 时 `removeType(BookType.updateError)` 清掉更新错误标记;
     * - `appDb.bookDao.update(*array)` 批量更新。
     *
     * 业务在 IO 跑 (DAO 写入必须 IO)。
     *
     * @param books 待更新的书籍
     * @param canUpdate 是否允许更新
     */
    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        Coroutine.async(scope = scope) {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.update(*array)
        }
    }

    /**
     * 更新书籍, 对应 app 端 `updateBook(vararg book: Book)`。
     *
     * 用于拖排落库 (Activity.persistOrder) / 改分组 (upGroup 回调) / 改分组位掩码等场景,
     * 纯 DAO update, 业务在 IO 跑。
     */
    fun updateBook(vararg book: Book) {
        Coroutine.async(scope = scope) {
            appDb.bookDao.update(*book)
        }
    }

    /**
     * 删除书籍, 对应 app 端 `deleteBook(books, deleteOriginal)`。
     *
     * # 实现细节保持
     *
     * - `appDb.bookDao.delete(*books.toTypedArray())` 先从数据库删除;
     * - 本地书 (isLocal) 调 [BookshelfManagePlatform.deleteLocalBook] 删除源文件
     *   (替代原 `FileBook.deleteBook(it, deleteOriginal)`, 行为等价)。
     *
     * 业务在 IO 跑 (DAO + 文件 I/O)。
     *
     * @param books 待删除的书籍列表
     * @param deleteOriginal 是否同时删除本地源文件 (本地书场景)
     */
    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        Coroutine.async(scope = scope) {
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    platform.deleteLocalBook(it, deleteOriginal)
                }
            }
        }
    }

    /**
     * 批量换源, 对应 app 端 `changeSource(books, source)`。
     *
     * # 实现细节保持
     *
     * 1. 先 cancel 旧 [batchChangeSourceCoroutine];
     * 2. 串行遍历 books:
     *    - 推送进度文案 `"<index+1> / <size>"` (对照原 `batchChangeSourceProcessLiveData.postValue`);
     *    - 本地书跳过 (无源可换);
     *    - 已是该源跳过 (`book.origin == source.bookSourceUrl`);
     *    - `WebBook.preciseSearchAwait` 精确搜索书籍, 失败记录 AppLog 并跳过;
     *    - tocUrl 为空时 `WebBook.getBookInfoAwait` 取详情, 失败记录 AppLog 并跳过;
     *    - `WebBook.getChapterListAwait` 取目录, 失败记录 AppLog 并跳过; 成功则:
     *      * `platform.migrateBook(book, newBook, toc)` 迁移进度/分组/自定义字段
     *        (替代原 `book.migrateTo(newBook, toc)`, 行为等价);
     *      * `book.removeType(BookType.updateError)` 清更新错误标记;
     *      * `appDb.bookDao.insert(newBook)` + `appDb.bookChapterDao.insert(*toc)`;
     *    - `delay(changeSourceDelay)` 限速 (避免被源封禁, 与原一致)。
     * 3. onStart 推送 batchChangeSourceState=true (Activity 显示 waitDialog);
     *    onFinally 推送 false (Activity 隐藏 waitDialog)。
     *
     * 业务在 IO 跑, onStart/onFinally 回调切到 mainDispatcher (与 BaseViewModel.execute 默认值一致)。
     *
     * @param books 待换源的书籍列表
     * @param source 目标书源
     */
    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = Coroutine.async(scope = scope) {
            val changeSourceDelay = appConfig.batchChangeSourceDelay * 1000L
            books.forEachIndexed { index, book ->
                _batchChangeSourceProcess.value = "${index + 1} / ${books.size}"
                if (book.isLocal) return@forEachIndexed
                if (book.origin == source.bookSourceUrl) return@forEachIndexed
                val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                    .onFailure {
                        AppLog.put("搜索书籍出错\n${it.message}", it, true)
                    }.getOrNull() ?: return@forEachIndexed
                kotlin.runCatching {
                    if (newBook.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, newBook)
                    }
                }.onFailure {
                    AppLog.put("获取书籍详情出错\n${it.message}", it, true)
                    return@forEachIndexed
                }
                WebBook.getChapterListAwait(source, newBook)
                    .onFailure {
                        AppLog.put("获取目录出错\n${it.message}", it, true)
                    }.getOrNull()?.let { toc ->
                        platform.migrateBook(book, newBook, toc)
                        book.removeType(BookType.updateError)
                        appDb.bookDao.insert(newBook)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                delay(changeSourceDelay)
            }
        }.onStart {
            _batchChangeSourceState.value = true
        }.onFinally {
            _batchChangeSourceState.value = false
        }
    }

    /**
     * 清除书籍缓存, 对应 app 端 `clearCache(books)`。
     *
     * # 实现细节保持
     *
     * - 串行调 [BookshelfManagePlatform.clearCache] (替代原 `BookHelp.clearCache(it)`,
     *   行为等价);
     * - 成功后 [Toasters.get].toast([BookshelfManagePlatform.clearCacheSuccessMessage])
     *   (替代原 `context.toastOnUi(R.string.clear_cache_success)`, 字符串由 app 端
     *   platform 解析 R.string.clear_cache_success 注入)。
     *
     * 业务在 IO 跑 (文件 I/O), onSuccess 回调切到 mainDispatcher (与 BaseViewModel.execute 一致)。
     *
     * @param books 待清缓存的书籍列表
     */
    fun clearCache(books: List<Book>) {
        Coroutine.async(scope = scope) {
            books.forEach {
                platform.clearCache(it)
            }
        }.onSuccess {
            Toasters.get().toast(platform.clearCacheSuccessMessage)
        }
    }

    /**
     * 加载书籍缓存文件列表, 对应 app 端 `loadCacheFiles(books)`。
     *
     * # 实现细节保持
     *
     * 1. 先 cancel 旧 [loadChapterCoroutine];
     * 2. 串行遍历 books:
     *    - 本地书跳过 (无章节缓存文件);
     *    - 已缓存过 (cacheChapters.contains) 跳过, 避免重复扫描 (diff: 仅结果集合
     *      变化时发射, 已扫描过的书不会重复推送事件);
     *    - 否则取 [BookshelfManagePlatform.getChapterFiles] (替代原
     *      `BookHelp.getChapterFiles(book)`, 返回已缓存的章节文件名集合);
     *    - 若缓存文件名非空, 取 `appDb.bookChapterDao.getChapterList(bookUrl)` 同步
     *      `book.totalChapterNum`, 遍历章节判断:
     *      * `cacheNames.contains(chapter.getFileName())` 文件存在 → 加入 chapterCaches;
     *      * `chapter.isVolume` 卷章节 (无正文) → 也视为已缓存 (与原一致);
     *    - 把 chapterCaches 存入 [cacheChapters];
     *    - 调 [BookshelfManagePlatform.getCacheSize] 取缓存总字节存入 [cacheSizes]
     *      (任务要求"缓存统计:展示每本书缓存大小");
     *    - 每扫描完成 20 本合并推送一次 [_upAdapter] (任务2: 批量发射, 对照原
     *      `upAdapterLiveData.sendValue(book.bookUrl)` 逐本通知, 由宿主端按 bookUrl
     *      per-key 聚合, 单书事件仅触发该书行局部刷新, 不再整页重组);
     *    - `ensureActive()` 检查协程取消 (与原一致)。
     *
     * 业务在 IO 跑。
     *
     * @param books 待扫描缓存的书籍列表
     */
    fun loadCacheFiles(books: List<Book>) {
        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = Coroutine.async(scope = scope) {
            // 批量发射窗口: 每批 20 本合并 emit 一次, 降低事件量 (任务2)
            val batch = ArrayList<String>(20)
            books.forEach { book ->
                if (!book.isLocal && !cacheChapters.contains(book.bookUrl)) {
                    val chapterCaches = hashSetOf<String>()
                    val cacheNames = platform.getChapterFiles(book)
                    if (cacheNames.isNotEmpty()) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).also {
                            book.totalChapterNum = it.size
                        }.forEach { chapter ->
                            if (cacheNames.contains(chapter.getFileName()) || chapter.isVolume) {
                                chapterCaches.add(chapter.url)
                            }
                        }
                    }
                    cacheChapters[book.bookUrl] = chapterCaches
                    // 缓存大小统计: 平台遍历缓存目录求和 (任务要求"展示每本书缓存大小")
                    cacheSizes[book.bookUrl] = platform.getCacheSize(book)
                    batch.add(book.bookUrl)
                    if (batch.size >= 20) {
                        _upAdapter.tryEmit(batch.toList())
                        batch.clear()
                    }
                }
                ensureActive()
            }
            if (batch.isNotEmpty()) {
                _upAdapter.tryEmit(batch.toList())
            }
        }
    }

}

/**
 * 书架管理平台专属依赖聚合接口 (KMP 注入点)。
 *
 * 用一个聚合接口封装所有平台专属依赖, 避免在 [BookshelfManageViewModelShared] 构造函数
 * 列出 5+ 个 lambda 参数 (违反"避免超多继承与参数传递"原则)。
 *
 * 各端实现:
 * - **Android**: `AndroidBookshelfManagePlatform` 包装 `Book.migrateTo` / `BookHelp.clearCache`
 *   / `BookHelp.getChapterFiles` / `FileBook.deleteBook` / `context.getString(R.string.clear_cache_success)`;
 * - **桌面**: 简化实现 (用 BookStorageProviders / LocalBookLocators 替代 BookHelp/FileBook,
 *   migrateBook 取 default 章节, clearCacheSuccessMessage 用硬编码字符串);
 * - **iOS / 鸿蒙**: 暂未实现 (stub), 后续补。
 *
 * # 为何不扩展既有 Provider 接口
 *
 * - [io.legado.app.help.book.BookHelpAccessor] 仅暴露 saveContent, 不包含 clearCache /
 *   getChapterFiles (BookHelp 重 Android 依赖留 app 端);
 * - [io.legado.app.help.book.BookStorage] 虽有 clearCache(book)/getChapterFiles(book), 但
 *   App 端尚未注册 BookStorageProviders (BookHelp 仍是 app 端直接调用);
 * - [io.legado.app.help.book.LocalBookLocator] 有 deleteBook(book) 但无 deleteOriginal 参数;
 * - 用聚合接口注入只改 BookshelfManageViewModel 一处, 不扩散既有 accessor, 符合
 *   "避免超多 Provider 接口" 原则。
 *
 * 模式参考 [io.legado.app.ui.book.changesource.ChangeBookSourcePlatform]。
 */
interface BookshelfManagePlatform {

    /**
     * 迁移旧书信息到新书 (对照 `Book.migrateTo(newBook, toc)`)。
     *
     * 默认实现直接调下沉后的 `Book.migrateTo` (BookHelpChapterLocator + ContentProcessorProviders),
     * 各端不必再自写一份 —— 早期各端简化版跳过了标题替换规则, 与原版不一致。
     *
     * @param oldBook 旧书 (取 durChapterIndex/durChapterTitle/durChapterPos/group/order 等)
     * @param newBook 新书 (写入 durChapterIndex/durChapterTitle 等字段后返回)
     * @param toc 新源目录 (定位当前章节用)
     * @return 修改后的 [newBook] (与原 `book.migrateTo(newBook, toc)` 返回值一致)
     */
    fun migrateBook(oldBook: Book, newBook: Book, toc: List<BookChapter>): Book =
        oldBook.migrateTo(newBook, toc)

    /**
     * 清除书籍章节缓存 (对照 `BookHelp.clearCache(book)`)。
     *
     * app 端委托 `BookHelp.clearCache(book)` (内部走 BookHelp.deleteBookFiles 等)。
     *
     * @param book 待清缓存的书籍
     */
    fun clearCache(book: Book)

    /**
     * 列出书籍已缓存的章节文件名集合 (对照 `BookHelp.getChapterFiles(book): HashSet<String>`)。
     *
     * app 端委托 `BookHelp.getChapterFiles(book)` (内部扫描 book_cache/<folder>/ 目录)。
     *
     * @param book 待扫描的书籍
     * @return 已缓存的章节文件名集合 (不含路径, 用于 [BookChapter.getFileName] 比对)
     */
    fun getChapterFiles(book: Book): HashSet<String>

    /**
     * 统计书籍缓存文件总字节 (任务要求"缓存统计:展示每本书缓存大小")。
     *
     * app 端委托 `BookHelp` 缓存目录遍历求和 (book_cache/<folder>/ 下所有文件 size 累加)。
     * 默认返回 0, 未实现端不显示大小, 不破坏现有行为。
     *
     * @param book 待统计的书籍
     * @return 缓存文件总字节; 0 表示无缓存或未实现
     */
    fun getCacheSize(book: Book): Long = 0L

    /**
     * 删除本地书源文件 (对照 `FileBook.deleteBook(book, deleteOriginal)`)。
     *
     * app 端委托 `FileBook.deleteBook(book, deleteOriginal)` (内部走 DocumentFile /
     * ContentResolver, 重 Android 依赖留 app 端)。
     *
     * @param book 待删除的本地书
     * @param deleteOriginal 是否同时删除源文件 (本地 txt/epub 等场景)
     */
    fun deleteLocalBook(book: Book, deleteOriginal: Boolean)

    /**
     * 清缓存成功提示文案 (对照 `context.toastOnUi(R.string.clear_cache_success)`)。
     *
     * app 端用 `context.getString(R.string.clear_cache_success)` 解析资源 ID 为字符串,
     * 供 [BookshelfManageViewModelShared.clearCache] 调 [Toasters.get].toast 显示。
     * 桌面端可用硬编码字符串 "清缓存成功" 或 i18n 资源系统。
     */
    val clearCacheSuccessMessage: String
}

/**
 * [BookshelfManagePlatform] 注入容器 (shared commonMain)。
 *
 * 模式参考 [io.legado.app.data.AppDbProviders] /
 * [io.legado.app.ui.book.changesource.ChangeBookSourcePlatformProviders]。
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 IllegalStateException。
 */
object BookshelfManagePlatformProviders {

    @kotlin.concurrent.Volatile
    private var impl: BookshelfManagePlatform? = null

    /** 宿主启动早期注册一次 (任何 BookshelfManageViewModelShared 实例化之前)。 */
    fun register(impl: BookshelfManagePlatform) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookshelfManagePlatform =
        impl ?: error("BookshelfManagePlatformProviders not registered")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
