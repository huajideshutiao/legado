package io.legado.app.ui.bookshelf

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
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
import kotlinx.coroutines.flow.flowOn
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
 * - 排序时 `cnCompare` 依赖 `java.text.Collator`+`android.os.Build` (Android-specific),
 *   commonMain 改用 [String.compareTo] (按 Unicode 序), 中文排序结果与 app 端略有差异,
 *   但 sort=2(书名)/5(作者) 两种排序语义对桌面端足够
 *
 * 修改数据要 copy, 直接修改 entity 字段会导致 Compose 不刷新 (data class equals 按 id)。
 */
class BookshelfViewModel {

    private val bookDao get() = AppDbProviders.get().bookDao
    private val bookGroupDao get() = AppDatabaseProviders.get().appDb.bookGroupDao
    private val appConfig get() = AppConfigProviders.get()

    /** VM 自管 scope, 桌面端无 lifecycleScope; app 端也可用, onCleared 时取消即可 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _bookGroups = MutableStateFlow<List<BookGroup>>(emptyList())
    val bookGroups: StateFlow<List<BookGroup>> = _bookGroups.asStateFlow()

    private val _currentGroupId = MutableStateFlow(BookGroup.IdAll)
    val currentGroupId: StateFlow<Long> = _currentGroupId.asStateFlow()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** 当前分组对象 (currentGroupId 在 bookGroups 中的查找结果), 用于顶栏标题/排序回退 */
    val currentGroup: BookGroup?
        get() = _bookGroups.value.find { it.groupId == _currentGroupId.value }

    /** 当前数据流订阅 job, 切换分组时取消上一个, 对齐 app 端 observeGroupBooks 语义 */
    private var booksFlowJob: Job? = null

    init {
        observeBookGroups()
        observeBooks(BookGroup.IdAll)
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
            bookDao.flowByGroup(groupId).catch {
                AppLog.put("书架书籍数据加载出错 groupId=$groupId", it)
            }.flowOn(Dispatchers.IO).conflate().collect { list ->
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
     * - 2: 书名 (String.compareTo, 简化自 cnCompare)
     * - 3: 手动 (order 正序)
     * - 4: 综合时间 (max(latestChapterTime, durChapterTime) 倒序)
     * - 5: 作者 (String.compareTo, 简化自 cnCompare)
     */
    private fun sortBooks(list: List<Book>, sort: Int): List<Book> = when (sort) {
        1 -> list.sortedByDescending { it.latestChapterTime }
        2 -> list.sortedWith { a, b -> a.name.compareTo(b.name) }
        3 -> list.sortedBy { it.order }
        4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        5 -> list.sortedWith { a, b -> a.author.compareTo(b.author) }
        else -> list.sortedByDescending { it.durChapterTime }
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

    // endregion

    /** 宿主销毁时调用, 取消所有数据流订阅与协程 */
    fun onCleared() {
        scope.cancel()
    }
}
