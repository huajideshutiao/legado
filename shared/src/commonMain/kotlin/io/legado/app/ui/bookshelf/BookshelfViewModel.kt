package io.legado.app.ui.bookshelf

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.service.UpdateBookCallback
import io.legado.app.help.service.UpdateBookCallbacks
import io.legado.app.help.service.UpdateBookShared
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.FlowBus
import io.legado.app.utils.cnCompare
import io.legado.app.utils.isAndroidPlatform
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

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
 * - 仅接入 UP_BOOKSHELF/BOOKSHELF_REFRESH 事件总线 (app 端用 FlowBus 跨页通知),
 *   其余 app 端跨页事件不接入
 * - 不实现 addBookByUrl/importBookshelf 等添加流程 (依赖 WebBook/okHttpClient/IntentData),
 *   这些下沉到 shared 需更大改造, 留待后续任务
 * - 排序走已下沉的 [String.cnCompare] (commonMain expect/actual), 与 app 端拼音序一致
 *
 * 修改数据要 copy, 直接修改 entity 字段会导致 Compose 不刷新 (data class equals 按 id)。
 */
class BookshelfViewModel {

    private val bookDao get() = AppDbProviders.get().bookDao
    private val bookGroupDao get() = AppDbProviders.get().bookGroupDao
    private val appConfig get() = AppConfigProviders.get()

    /** VM 自管 scope, 桌面端无 lifecycleScope; app 端也可用, onCleared 时取消即可 */
    private val scope = screenModelScope("书架")

    /**
     * 目录更新/强制刷新编排核心 (对照 app 端 MainViewModel.updateBookShared)。
     *
     * 各端宿主启动期注册默认 [UpdateBookCallback] 后经 [UpdateBookCallbacks.getDefault] 取用
     * (app: App.kt 注册 AndroidUpdateBookCallback / 桌面: Main.kt 注册 DesktopUpdateBookCallback /
     * iOS·鸿蒙: registerNativeUpdateBookCallback)。宿主未注册时为 null, 此时 [upToc] / [forceRefresh]
     * 静默跳过 (刷新任务与转圈状态均不生效), [isRefreshing] 恒 false。
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

    /**
     * 各分组最近一次发射的书籍列表快照 (唯一数据源: 分组页/样式2 读本流切片渲染,
     * 不再各自开 Room 流; 重进首帧同步渲染, 免空态帧 + Room 查询延迟)。
     * 未访问过的分组无条目, 顶栏计数显示 ".."。
     */
    private val _booksCache = MutableStateFlow<Map<Long, List<Book>>>(emptyMap())
    val booksCache: StateFlow<Map<Long, List<Book>>> = _booksCache.asStateFlow()

    private fun updateBooksCache(groupId: Long, list: List<Book>) {
        _booksCache.value = _booksCache.value + (groupId to list)
    }

    // 正在刷新书籍的 url 集合 (对照 app 端 BaseBookshelfState.isRefreshing/bookUrlRefreshList)
    private val _refreshingUrls = MutableStateFlow<Set<String>>(emptySet())
    val refreshingUrls: StateFlow<Set<String>> = _refreshingUrls.asStateFlow()

    /** 当前分组对象 (currentGroupId 在 bookGroups 中的查找结果), 用于顶栏标题/排序回退 */
    val currentGroup: BookGroup?
        get() = _bookGroups.value.find { it.groupId == _currentGroupId.value }

    /**
     * 各分组书籍流订阅 jobs (对齐原版 fragment 各自订阅语义: pager 组合中的分组页
     * 当前 + 相邻各 1, 即最多 3 个分组各自持有 Room 流, 数据持续实时)。
     * 切换分组不取消其他流, 页离开组合/书架失活时才取消。
     */
    private val booksFlowJobs = mutableMapOf<Long, Job>()

    /** 组合中的分组页登记 (UI DisposableEffect 维护), 失活→激活时据此恢复全部订阅 */
    private val composedGroupIds = mutableSetOf<Long>()

    /** 分组列表流订阅 job */
    private var bookGroupsJob: Job? = null

    /** 书架激活态 (UI 层按 tab 激活 + 生命周期 RESUMED 驱动); false 时零 DB 订阅 */
    @Volatile
    private var active = false

    /** 书架可见态 (UI 按 "书架 tab 激活 && 主界面栈顶" 驱动): 决定 UP_BOOKSHELF 事件是否立即重查 */
    @Volatile
    private var visible = false

    /**
     * books 数据可能过期标记: UP_BOOKSHELF 事件置位, 展示时机 (书架重新可见) 消费。
     * 不逐事件重查: 桌面端 Room 失效推送不可靠, 但旧实现逐事件重启全部分组流必然全量重发,
     * 是滑动卡顿源头; 收敛为可见时一次 one-shot 重查。
     */
    @Volatile
    private var bookDataDirty = false

    /** 书架可见时的重查空闲窗口 job (事件到达重置, 聚合批量刷新的连续事件) */
    private var recheckDebounceJob: Job? = null

    /** one-shot 重查去重指纹: 与最近一次发射一致则跳过发射, 避免与 Room 失效推送重复 */
    private data class ShelfFingerprint(
        val url: String,
        val name: String,
        val author: String,
        val coverUrl: String?,
        val durChapterTime: Long,
        val durChapterIndex: Int,
        val durChapterPos: Int,
        val latestChapterTime: Long,
    )

    private fun Book.toFingerprint() = ShelfFingerprint(
        bookUrl, name, author, coverUrl, durChapterTime, durChapterIndex, durChapterPos, latestChapterTime,
    )

    /** 各分组最近一次发射的指纹快照 (唯一数据源: onGroupBooks; 线程安全同 [booksCache]) */
    private val groupFingerprints = MutableStateFlow<Map<Long, List<ShelfFingerprint>>>(emptyMap())

    init {
        // 书籍/分组订阅改由 setBookshelfActive 挂 UI 组合生命周期, 不再 init 常驻
        observeUpBookshelfEvents()
    }

    /**
     * 订阅 UP_BOOKSHELF 事件, 维护 [refreshingUrls] (对照 app 端 FlowBus UP_BOOKSHELF 收敛)。
     *
     * 触发 [upToc] / [forceRefresh] 时把目标 books 的 url 加入集合, 收到 UP_BOOKSHELF 事件时移除,
     * 与 app 端 BaseBookshelfState 行为一致 (条目转圈在书籍刷新完成时消失)。UpdateBookShared 只在
     * 每本书完成/失败/取消时发事件 (开始事件已移除, 否则转圈会在渲染前被清掉, 见
     * UpdateBookShared.startUpTocJob 注释)。
     *
     * 不再在此重启分组流: 所有 UP_BOOKSHELF 发射源 (目录更新/进度落库/强制刷新/停止更新, 见
     * UpdateBookShared / ReadBookViewModelShared.uploadProgressAwait) 都伴随 book 表落库,
     * 当前分组持续流由 Room 失效推送自动增量更新 (distinctUntilChanged + debounce 聚合批量写入:
     * 一次滑动切换前后的一批更新只触发一次发射/重组); 相邻分组 one-shot 流在滑动切换时由
     * [selectGroup] 重启重新获取 (对齐原版: 相邻 fragment STARTED 态只查一次, 切到 RESUMED 才
     * 重订阅)。旧实现每本书完成都取消全部组合流并重查重发——重启即新流, distinctUntilChanged
     * 无旧值可比, 必然全列表发射, 批量刷新/阅读进度更新时反复触发全屏条目重组, 是分组滑动
     * 卡顿的主要来源之一。
     *
     * 删除后的兜底改由本收集器置脏标记 + [scheduleRecheckIfVisible] / [setBookshelfVisible]
     * 收敛重查: 书架重新可见时对当前分组做一次 one-shot 重查 (指纹去重), 不重建持续流,
     * 既不依赖桌面端不可靠的 Room 失效推送, 也避免逐事件全流重启的重组风暴。
     */
    private fun observeUpBookshelfEvents() {
        scope.launch {
            FlowBus.with(EventBus.UP_BOOKSHELF).collect { e ->
                val url = e as? String ?: return@collect
                _refreshingUrls.value = _refreshingUrls.value - url
                // 数据可能已过期: 置脏标记 + 重查 (发射方注释仍承诺
                // "UP_BOOKSHELF → 重启分组流强制重查", 旧实现删除后此兜底缺失, 见
                // ReadBookViewModelShared.uploadProgressAwait / AudioPlayShared)
                bookDataDirty = true
                if (isAndroidPlatform) {
                    // Android: Room 失效推送正常 (2026-08 Android 12/16 双设备最小实验
                    // 5/5 全触发), 维持原可见时兜底即可, 不额外后台重查
                    scheduleRecheckIfVisible()
                } else {
                    // 桌面/iOS/鸿蒙: 桌面 Room 失效推送对 UPDATE 不可靠 (实证), 激活期间即重查
                    scheduleRecheckIfActive()
                }
            }
        }
    }

    // region 数据流订阅

    /**
     * 书架订阅开关 (对齐原版 flowWithLifecycle 的"页面可见才订阅"): UI 层在
     * "书架 tab 激活 + 窗口生命周期 RESUMED" 时开, 否则关。关闭即取消分组/书籍
     * DB 流订阅, 书架不可见时 books 表任意写入零查询; 缓存快照不随开关清空。
     */
    fun setBookshelfActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) {
            startBookGroupsFlow()
            // 恢复订阅: 当前分组 + 组合中的相邻分组页
            ensureGroupFlow(_currentGroupId.value)
            composedGroupIds.forEach { ensureGroupFlow(it) }
            // 激活时消费脏标记 (覆盖书架不可用期间到达的 UP_BOOKSHELF 事件):
            // 仅非 Android 需要 (桌面 Room 失效不可靠/iOS·鸿蒙未验证);
            // Android 维持原可见时兜底 (Room 失效推送正常, 2026-08 双设备实证)
            if (!isAndroidPlatform) recheckIfDirty()
        } else {
            bookGroupsJob?.cancel()
            bookGroupsJob = null
            booksFlowJobs.values.forEach { it.cancel() }
            booksFlowJobs.clear()
        }
    }

    /**
     * 书架可见态开关 (UI 在 "书架 tab 激活 && 主界面栈顶" 时开)。重新可见时若数据
     * 可能过期 (后台阅读/批量刷新/目录更新落库) 立即重查, 对齐原版 fragment 回到
     * RESUMED 时冷流重订阅的"返回必见最新"语义。
     */
    fun setBookshelfVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible) {
            recheckIfDirty()
        } else {
            recheckDebounceJob?.cancel()
            recheckDebounceJob = null
        }
    }

    /** 书架可见时 UP_BOOKSHELF 事件驱动重查: 500ms 空闲窗口聚合批量刷新的连续事件 */
    private fun scheduleRecheckIfVisible() {
        if (!visible) return
        recheckDebounceJob?.cancel()
        recheckDebounceJob = scope.launch {
            delay(500)
            recheckIfDirty()
        }
    }

    /**
     * 书架激活(组合)期间 UP_BOOKSHELF 事件驱动重查: 500ms 空闲窗口聚合。
     *
     * 区别于 [scheduleRecheckIfVisible]: 不要求书架可见。桌面端 Room KMP 失效推送对
     * UPDATE 类写操作不可靠 (2026-08 实测: @Query UPDATE/@Update 不触发 flow 重发,
     * 仅 INSERT/DELETE 触发; 手工复刻同 SQL 同连接却正常, 定位为 Room 3.0.1 生成代码
     * 执行路径的内部行为), 书架页在导航栈中保持组合期间 (active 恒 true), 阅读/音频/
     * 目录更新落库后立即重查, 返回书架必见最新, 不再依赖可见时机翻转。
     */
    private fun scheduleRecheckIfActive() {
        if (!active) return
        recheckDebounceJob?.cancel()
        recheckDebounceJob = scope.launch {
            delay(500)
            recheckIfDirty()
        }
    }

    /** 脏标记消费: 对当前分组 + 组合中的分组各做一次 one-shot 重查 (指纹去重) */
    private fun recheckIfDirty() {
        if (!bookDataDirty) return
        bookDataDirty = false
        recheckDebounceJob?.cancel()
        recheckDebounceJob = null
        (composedGroupIds + _currentGroupId.value).forEach { recheckGroup(it) }
    }

    /**
     * one-shot 重查指定分组 (不重建持续流): 查询 + 排序 + 指纹比对, 与当前展示一致则
     * 跳过发射。区别于旧实现的重启流: 重启即新流, distinctUntilChanged 无旧值可比,
     * 必然全列表重发; one-shot 只在数据真变化时发射一次。
     */
    private fun recheckGroup(groupId: Long) {
        scope.launch(IoDispatcher) {
            val list = try {
                bookDao.getBooksByGroup(groupId)
            } catch (e: Throwable) {
                AppLog.put("书架数据重查失败 groupId=$groupId", e)
                return@launch
            }
            val sorted = sortBooks(
                list.filterNot { (it.type and BookType.notShelf) > 0 },
                sortOf(groupId),
            )
            if (sorted.map { it.toFingerprint() } == groupFingerprints.value[groupId]) return@launch
            onGroupBooks(groupId, sorted)
        }
    }

    /** 订阅可见分组列表 (flowShow), 对照 app 端 bookGroupDao.flowShow(). */
    private fun startBookGroupsFlow() {
        if (bookGroupsJob != null) return
        bookGroupsJob = scope.launch {
            // distinctUntilChanged: Room 流在 bookGroup 表任意变更时都重查发射,
            // 内容相同的发射会让顶层整树重组 (顶栏 + 各分组页参数比较)
            bookGroupDao.flowShow().distinctUntilChanged().conflate().catch {
                AppLog.put("书架分组数据加载出错", it)
            }.collect { groups ->
                _bookGroups.value = groups
                // 首次拿到分组列表后, 若仍是默认 IdAll 且分组非空, 自动切到首个分组
                if (_currentGroupId.value == BookGroup.IdAll && groups.isNotEmpty()) {
                    selectGroup(groups.first().groupId)
                }
            }
        }
    }

    /**
     * 启动指定分组的书籍流, 严格对齐原版 observeGroupBooks + flowWithLifecycle 语义:
     *
     * - 当前分组: 持续订阅, DB 变更实时刷新 (对齐原版 RESUMED 持续收集)
     * - 非当前分组: 仅查一次初始值填缓存即结束, 不持续订阅 (对齐原版 STARTED 相邻页
     *   `flowWithLifecycleAndDatabaseChangeFirst` 的 `firstOrNull()` 一次查询);
     *   DB 变更在切回时由 [selectGroup] 重启流重新获取
     *
     * 排序 + distinctUntilChanged + debounce(100) 聚合刷新风暴同原版。
     * 任意分组的发射都回填 [booksCache] 快照; 只有当前分组同步 [_books]。
     */
    @OptIn(FlowPreview::class)
    private fun startGroupFlow(groupId: Long) {
        if (booksFlowJobs.containsKey(groupId)) return
        val isCurrent = groupId == _currentGroupId.value
        val job = scope.launch {
            try {
                // 过滤内容相同的重复 emit, 避免无关 DAO 触发重排与重组
                val source = bookDao.flowByGroup(groupId).distinctUntilChanged().catch {
                    // catch 是终结性操作: 捕获后流结束、job 摘除, 此后无重启路径会永久不刷新;
                    // 置脏标记, 由 finally 兜底重查 / 下次展示时机恢复
                    AppLog.put("书架书籍数据加载出错 groupId=$groupId", it)
                    bookDataDirty = true
                }.map { list -> sortBooks(list, sortOf(groupId)) }
                    .debounce(100)
                    .flowOn(IoDispatcher).conflate()
                if (isCurrent) {
                    source.collect { list -> onGroupBooks(groupId, list) }
                } else {
                    source.first { list ->
                        onGroupBooks(groupId, list)
                        true
                    }
                }
            } finally {
                // 只移除自己: 取消后重启 (UP_BOOKSHELF/upSort/selectGroup) 可能已注册新 job,
                // 旧 job 的 finally 若直接 remove 会误删新 job 的登记, 导致流失联后重复订阅
                if (booksFlowJobs[groupId] === coroutineContext[Job]) {
                    booksFlowJobs.remove(groupId)
                    // 自愈: 流异常结束 (catch 置脏) 且该分组仍是当前分组时立即 one-shot 兜底一次,
                    // 不等展示时机 (用户可能一直停留在书架页)
                    if (bookDataDirty && active && groupId == _currentGroupId.value) {
                        bookDataDirty = false
                        recheckGroup(groupId)
                    }
                }
            }
        }
        booksFlowJobs[groupId] = job
    }

    /** 分组书籍流发射回调: 回填缓存, 记录指纹, 触发自动更新, 当前分组同步 [_books] */
    private fun onGroupBooks(groupId: Long, list: List<Book>) {
        updateBooksCache(groupId, list)
        groupFingerprints.value = groupFingerprints.value + (groupId to list.map { it.toFingerprint() })
        autoUpdateGroup(groupId, list)
        if (groupId == _currentGroupId.value) {
            _books.value = list
        }
    }

    /** 组合中的分组页登记并确保其数据流在跑 (当前 + 相邻页共 ≤3 个流, 对齐原版) */
    fun onGroupPageComposed(groupId: Long) {
        composedGroupIds += groupId
        ensureGroupFlow(groupId)
    }

    /** 分组页离开组合, 取消对应数据流 (对齐原版 fragment 销毁取消订阅) */
    fun onGroupPageDisposed(groupId: Long) {
        composedGroupIds -= groupId
        releaseGroupFlow(groupId)
    }

    /** 书架激活时确保流在跑 (active 门控: 不可见时零 DB 订阅) */
    fun ensureGroupFlow(groupId: Long) {
        if (!active) return
        startGroupFlow(groupId)
    }

    /** 取消指定分组流 (页离开组合/排序重启前), 缓存快照保留 */
    fun releaseGroupFlow(groupId: Long) {
        booksFlowJobs.remove(groupId)?.cancel()
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
     * 切换当前分组, 严格对齐原版 ViewPager setCurrentItem + fragment 生命周期语义:
     *
     * - 旧当前分组降级: 取消其持续流 (对齐原版切走后 repeatOnLifecycle 挂起收集),
     *   页内数据由缓存快照继续显示, DB 变更不再推送, 切回时重启流重新获取
     * - 新当前分组: 取消可能仍在跑的 one-shot 预加载流, 重启为持续订阅 (对齐原版
     *   切到 RESUMED 后恢复持续收集)
     * - 切换瞬间用缓存快照回填 [_books], 免掉重启流前的一帧空态
     */
    fun selectGroup(groupId: Long) {
        if (_currentGroupId.value == groupId) return
        val previous = _currentGroupId.value
        _currentGroupId.value = groupId
        _books.value = booksCache.value[groupId].orEmpty()
        booksFlowJobs.remove(previous)?.cancel()
        booksFlowJobs.remove(groupId)?.cancel()
        startGroupFlow(groupId)
    }

    /**
     * 下拉刷新: 主动更新目录 (对照 app 端下拉刷新 → MainViewModel.upToc)。
     *
     * 把当前分组书籍 url 加入 [refreshingUrls] 触发条目转圈, 调 [UpdateBookShared.upToc]
     * 实际刷新目录 (宿主未注册 callback 时无引擎, 静默跳过)。
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
        val shared = updateBookShared ?: return // 宿主未注册 UpdateBookCallback 时无刷新引擎, 静默跳过
        // 只标记会被实际刷新的书 (过滤条件与 UpdateBookShared.upToc 一致),
        // 否则本地书/不可更新书等不到 UP_BOOKSHELF 事件, 转圈永不消失
        _refreshingUrls.value = _refreshingUrls.value +
            books.filter { !it.isLocal && it.canUpdate }.map { it.bookUrl }
        shared.upToc(books)
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
        val shared = updateBookShared ?: return // 宿主未注册 UpdateBookCallback 时无刷新引擎, 静默跳过
        // forceRefresh 在任一刷新任务在跑时会拒绝并 toast; 忙时不标记转圈, 避免被拒的书
        // 等不到 UP_BOOKSHELF 收敛事件导致转圈卡死 (仍调用引擎以保留 busy toast)
        if (!shared.isRefreshing.value) {
            // 只标记会被实际刷新的书 (UpdateBookShared.forceRefresh 跳过本地书)
            _refreshingUrls.value = _refreshingUrls.value +
                books.filterNot { it.isLocal }.map { it.bookUrl }
        }
        shared.forceRefresh(books)
    }

    /**
     * 排序配置变更后重排 (对照 app 端 BookshelfFragment1.upSort → adapter.notifyDataSetChanged)。
     *
     * 原版靠 adapter 重绑拿新 bookSort; 此处重启当前分组订阅, 让 [sortOf] 重读配置,
     * 页数据经 [booksCache] 单一数据源随流重启自动更新。
     */
    fun upSort() {
        // 排序配置变更: 重启当前分组持续流 + 组合中相邻页的预加载流 (对齐原版 adapter
        // 全量重绑, 已实例化 fragment 均 upRecyclerData 重订阅)
        (composedGroupIds + _currentGroupId.value).forEach {
            booksFlowJobs.remove(it)?.cancel()
            startGroupFlow(it)
        }
        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
    }

    // endregion

    /** 宿主销毁时调用, 取消所有数据流订阅与协程 */
    fun onCleared() {
        updateBookShared?.onCleared() // 取消任务 + 关闭 upTocPool (对照 app 端 MainViewModel.onCleared)
        scope.cancel()
    }
}
