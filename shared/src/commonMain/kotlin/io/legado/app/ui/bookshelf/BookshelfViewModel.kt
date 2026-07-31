package io.legado.app.ui.bookshelf

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.service.UpdateBookCallback
import io.legado.app.help.service.UpdateBookCallbacks
import io.legado.app.help.service.UpdateBookShared
import io.legado.app.utils.FlowBus
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 书架布局档位 (KMP 版, 对照 app 端 ShelfTier)。
 *
 * - [LIST]：单列详情 (封面 + 书名 + 作者 + 最新章节)
 * - [GRID]：网格 (封面 + 书名, 桌面端默认)
 *
 * app 端原 [ShelfTier.VIDEO] 视频卡片档位仅在书源浏览场景使用, 书架不下沉该档位。
 */
enum class BookshelfTier { LIST, GRID }

/**
 * 书架 ViewModel (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `BookshelfViewModel` + `BaseBookshelfState` + `BookshelfState1/2` 中的
 * 数据流/排序/分组切换逻辑, 下沉后由 app/desktop 两个宿主共用同一份业务编排:
 *
 * - DAO 访问走 [AppDbProviders.get] 的 bookDao / bookGroupDao (宿主启动时注册)
 * - 配置读取走 [AppConfigProviders.get] 的 bookshelfSort / bookshelfLayout /
 *   bookshelfCoverHeight / bookshelfGridWidth / bookshelfShowGroupCount
 * - 状态用 [MutableStateFlow], Compose 宿主用 `collectAsState` 直接订阅
 * - VM 自带 [scope]/[onCleared], 桌面端无 lifecycleScope 时由宿主窗口退出时调用
 *
 * # 简化项 (对照 app 端 BaseBookshelfState)
 *
 * - 不接入 IntentData/Activity 跳转/HandleFile 导入书架等 Android-specific 流程,
 *   这些由宿主端按需以回调形式注入 (onBookClick/onBookLongClick/...)
 * - 不接入 UP_BOOKSHELF/BOOKSHELF_REFRESH 事件总线 (app 端用 FlowBus 跨页通知),
 *   桌面端无该需求; 后续如需可通过 EventBusProvider 扩展
 * - 不实现 addBookByUrl/importBookshelf 等添加流程 (依赖 WebBook/okHttpClient/IntentData),
 *   这些下沉到 shared 需更大改造, 留待后续任务
 * - 排序走已下沉的 [String.cnCompare] (commonMain expect/actual), 与 app 端拼音序一致
 *
 * 修改数据要 copy, 直接修改 entity 字段会导致 Compose 不刷新 (data class equals 按 id)。
 */
class BookshelfViewModel {

    private val bookDao get() = AppDbProviders.get().bookDao
    private val bookGroupDao get() = AppDatabaseProviders.get().appDb.bookGroupDao
    private val appConfig get() = AppConfigProviders.get()

    /** VM 自管 scope, 桌面端无 lifecycleScope; app 端也可用, onCleared 时取消即可 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 目录更新/强制刷新编排核心 (对照 app 端 MainViewModel.updateBookShared)。
     *
     * 平台未注册 [UpdateBookCallback] 时为 null, 此时 [upToc] / [forceRefresh] 走 DB 重订阅兜底
     * (与下沉前行为一致), [isRefreshing] 恒 false。
     */
    private val updateBookShared: UpdateBookShared? by lazy {
        val callback = UpdateBookCallbacks.getDefault() ?: return@lazy null
        UpdateBookShared(scope, callback)
    }

    /** 是否正在刷新 (upToc / forceRefresh), UI 用于下拉刷新指示器 */
    val isRefreshing: StateFlow<Boolean>
        get() = updateBookShared?.isRefreshing
            ?: MutableStateFlow(false).asStateFlow()

    /** 刷新进度文案 (如 "强制刷新 3/10"), null 表示无任务 */
    val progressText: StateFlow<String?>
        get() = updateBookShared?.progressText
            ?: MutableStateFlow<String?>(null).asStateFlow()

    private val _bookGroups = MutableStateFlow<List<BookGroup>>(emptyList())
    val bookGroups: StateFlow<List<BookGroup>> = _bookGroups.asStateFlow()

    private val _currentGroupId = MutableStateFlow(BookGroup.IdAll)
    val currentGroupId: StateFlow<Long> = _currentGroupId.asStateFlow()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    // 正在刷新书籍的 url 集合 (对照 app 端 BaseBookshelfState.isRefreshing/bookUrlRefreshList)
    private val _refreshingUrls = MutableStateFlow<Set<String>>(emptySet())
    val refreshingUrls: StateFlow<Set<String>> = _refreshingUrls.asStateFlow()

    /** 当前分组对象 (currentGroupId 在 bookGroups 中的查找结果), 用于顶栏标题/排序回退 */
    val currentGroup: BookGroup?
        get() = _bookGroups.value.find { it.groupId == _currentGroupId.value }

    /** 当前数据流订阅 job, 切换分组时取消上一个, 对齐 app 端 observeGroupBooks 语义 */
    private var booksFlowJob: Job? = null

    init {
        observeBookGroups()
        observeBooks(BookGroup.IdAll)
        observeUpBookshelfEvents()
    }

    /**
     * 订阅 UP_BOOKSHELF 事件, 维护 [refreshingUrls] (对照 app 端 FlowBus UP_BOOKSHELF 收敛)。
     *
     * 触发 [upToc] / [forceRefresh] 时把目标 books 的 url 加入集合, 收到 UP_BOOKSHELF 事件时移除,
     * 与 app 端 BaseBookshelfState 行为一致 (条目转圈在书籍刷新完成时消失)。
     */
    private fun observeUpBookshelfEvents() {
        scope.launch {
            FlowBus.with(EventBus.UP_BOOKSHELF).collect { e ->
                val url = e as? String ?: return@collect
                _refreshingUrls.value = _refreshingUrls.value - url
            }
        }
    }

    // region 数据流订阅

    /**
     * 订阅可见分组列表 (flowShow), 对照 app 端 BookshelfEffects → bookGroupDao.flowShow().
     *
     * 首次拿到分组列表后, 若当前 groupId 仍是默认 [BookGroup.IdAll] 且分组非空,
     * 自动切到首个分组 (IdAll 不在 flowShow 结果集中, 仅作初始占位)。
     */
    private fun observeBookGroups() {
        scope.launch {
            bookGroupDao.flowShow().conflate().catch {
                AppLog.put("书架分组数据加载出错", it)
            }.collect { groups ->
                _bookGroups.value = groups
                if (_currentGroupId.value == BookGroup.IdAll && groups.isNotEmpty()) {
                    selectGroup(groups.first().groupId)
                }
            }
        }
    }

    /**
     * (重新)订阅当前分组对应书籍列表, 对照 app 端 GroupBooksPage → observeGroupBooks.
     *
     * 排序键取分组自身 [BookGroup.bookSort], < 0 时回退全局 [AppConfigAccessor.bookshelfSort]
     * (对应 app 端 BookGroup.getRealBookSort 语义)。
     */
    private fun observeBooks(groupId: Long) {
        booksFlowJob?.cancel()
        booksFlowJob = scope.launch {
            // 过滤内容相同的重复 emit, 避免无关 DAO 触发重排与重组
            bookDao.flowByGroup(groupId).distinctUntilChanged().catch {
                AppLog.put("书架书籍数据加载出错 groupId=$groupId", it)
            }.flowOn(IoDispatcher).conflate().collect { list ->
                _books.value = sortBooks(list, sortOf(groupId))
            }
        }
    }

    /**
     * 取分组实际排序方式 (对照 app 端 BookGroup.getRealBookSort)。
     * bookSort < 0 时回退到全局书架排序配置。
     */
    private fun sortOf(groupId: Long): Int {
        val group = _bookGroups.value.find { it.groupId == groupId }
        val raw = group?.bookSort ?: -1
        return if (raw < 0) appConfig.bookshelfSort else raw
    }

    /**
     * 排序书籍 (对照 app 端 BookshelfState1.sortBooks / BookshelfState2.sortBooks)。
     *
     * sort:
     * - 0: 阅读时间 (durChapterTime 倒序)
     * - 1: 更新时间 (latestChapterTime 倒序)
     * - 2: 书名 (cnCompare 拼音序)
     * - 3: 手动 (order 正序)
     * - 4: 综合时间 (max(latestChapterTime, durChapterTime) 倒序)
     * - 5: 作者 (cnCompare 拼音序); 原版只有 style1 的 BooksFragment 有该档,
     *   style2 落 else 按阅读时间, 此处统一取 style1 的超集
     */
    private fun sortBooks(list: List<Book>, sort: Int): List<Book> = when (sort) {
        1 -> list.sortedByDescending { it.latestChapterTime }
        2 -> list.sortedWith { a, b -> a.name.cnCompare(b.name) }
        3 -> list.sortedBy { it.order }
        4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        5 -> list.sortedWith { a, b -> a.author.cnCompare(b.author) }
        else -> list.sortedByDescending { it.durChapterTime }
    }

    /**
     * 返回指定分组的书籍流 (对照 app 端 MainViewModel.observeGroupBooks),
     * 供 HorizontalPager 各页独立订阅, 互不干扰。
     *
     * 排序键取分组自身 [BookGroup.bookSort], < 0 时回退全局 [AppConfigAccessor.bookshelfSort]
     * (与 [observeBooks] 语义一致); onEach 内对照原版触发该分组的自动更新目录。
     */
    fun booksByGroup(groupId: Long): kotlinx.coroutines.flow.Flow<List<Book>> =
        bookDao.flowByGroup(groupId)
            .distinctUntilChanged()
            .catch { AppLog.put("书架书籍数据加载出错 groupId=$groupId", it) }
            .flowOn(IoDispatcher)
            .conflate()
            .map { list -> sortBooks(list, sortOf(groupId)) }
            .onEach { list -> autoUpdateGroup(groupId, list) }

    /**
     * 进分组时自动更新目录 (对照 app 端 observeGroupBooks 的 onEach:
     * `markGroupAutoUpdated(groupId) && AppConfig.autoRefreshBook` 才 scheduleAutoUpdate)。
     *
     * markGroupAutoUpdated 每分组每进程只返回一次 true, 避免重组/翻页反复触发。
     */
    private fun autoUpdateGroup(groupId: Long, books: List<Book>) {
        val shared = updateBookShared ?: return
        if (shared.markGroupAutoUpdated(groupId) && appConfig.autoRefreshBook) {
            shared.scheduleAutoUpdate(books)
        }
    }

    /**
     * 切换当前分组, 重启书籍数据流订阅。
     * 对照 app 端 BookshelfState2.openGroup + style1 的 pagerState.scrollToPage。
     */
    fun selectGroup(groupId: Long) {
        if (_currentGroupId.value == groupId) return
        _currentGroupId.value = groupId
        observeBooks(groupId)
    }

    /**
     * 下拉刷新: 主动更新目录 (对照 app 端下拉刷新 → MainViewModel.upToc)。
     *
     * 把当前分组书籍 url 加入 [refreshingUrls] 触发条目转圈, 调 [UpdateBookShared.upToc]
     * 实际刷新目录; 平台未注册 callback 时走 DB 重订阅兜底。
     */
    fun upToc() {
        upToc(_books.value)
    }

    /**
     * 下拉刷新指定书籍 (对照 app 端 `MainViewModel.upToc(books)`)。
     *
     * 样式2 的层级 (根级/分组内) 不走 [currentGroupId], 故由调用方传入当前可见书籍。
     */
    fun upToc(books: List<Book>) {
        if (books.isEmpty()) return
        val shared = updateBookShared
        if (shared != null) {
            // 只标记会被实际刷新的书 (过滤条件与 UpdateBookShared.upToc 一致),
            // 否则本地书/不可更新书等不到 UP_BOOKSHELF 事件, 转圈永不消失
            _refreshingUrls.value = _refreshingUrls.value +
                books.filter { !it.isLocal && it.canUpdate }.map { it.bookUrl }
            shared.upToc(books)
        } else {
            // 平台未接入 UpdateBookShared: 重启 DB 订阅触发回填, 立即清空转圈
            observeBooks(_currentGroupId.value)
            _refreshingUrls.value = emptySet()
        }
    }

    /**
     * 强制刷新书籍信息 (对照 app 端菜单项 → MainViewModel.forceRefresh)。
     *
     * 与 [upToc] 区别: 先刷书籍详情 (getBookInfoAwait) 再刷目录, 用于书源规则变更后整体回填。
     * 任一刷新任务在跑时由 [UpdateBookShared] 拒绝重复触发并 toast 提示。
     */
    fun refresh() {
        val books = _books.value
        if (books.isEmpty()) return
        val shared = updateBookShared
        if (shared != null) {
            // 只标记会被实际刷新的书 (UpdateBookShared.forceRefresh 跳过本地书)
            _refreshingUrls.value = _refreshingUrls.value +
                books.filterNot { it.isLocal }.map { it.bookUrl }
            shared.forceRefresh(books)
        } else {
            // 平台未接入 UpdateBookShared: 重启 DB 订阅触发回填
            observeBooks(_currentGroupId.value)
        }
    }

    /**
     * 排序配置变更后重排 (对照 app 端 BookshelfFragment1.upSort → adapter.notifyDataSetChanged)。
     *
     * 原版靠 adapter 重绑拿新 bookSort; 此处重启当前分组订阅, 让 [sortOf] 重读配置。
     * 各 pager 页的 [booksByGroup] 流由 BOOKSHELF_REFRESH 事件驱动重订阅。
     */
    fun upSort() {
        observeBooks(_currentGroupId.value)
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
    }

    // endregion

    /** 宿主销毁时调用, 取消所有数据流订阅与协程 */
    fun onCleared() {
        scope.cancel()
    }
}
