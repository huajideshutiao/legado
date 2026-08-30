package io.legado.app.ui.book.manage

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isVideo
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.max

/**
 * 书架管理 ScreenModel (KMP): 托管 groups/books/selected 状态,
 * 订阅 DAO flow, 通过 dispatch 处理可下沉的 UI 事件。
 * 平台专属逻辑 (showDialogFragment / CacheBook / 导出) 留宿主 Activity。
 *
 * @param screenLabel 搜索框 hint 前缀 (app 端 R.string.screen "界面")
 * @param noGroupLabel 无分组名兜底 (app 端 R.string.no_group)
 * @param resolveBookSort 按 groupId 取排序方式 (app 端 AppConfig.getBookSortByGroupId, suspend 因 DAO 查询)
 * @param loadCacheFiles 书籍加载后扫描缓存文件 (app 端 viewModel.loadCacheFiles)
 */
class BookshelfManageScreenModel(
    private val screenLabel: String,
    private val noGroupLabel: String,
    private val resolveBookSort: suspend (Long) -> Int,
    private val loadCacheFiles: (List<Book>) -> Unit,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (app 端无 ScreenModelStore 时由宿主 DisposableEffect 调 onCleared)
    private val scope = screenModelScope("书架管理")

    /**
     * 分组流 / 书籍流 / UI 事件三路并发写同一个 state, 一律走 MutableStateFlow.update 原子改:
     * `_state.value = _state.value.copy(..)` 的读-改-写会丢更新 (分组名回填晚于书籍列表
     * 到达时把 books 打回空列表, 表现为"进管理页有分组名但列表空")。
     */
    private val _state = MutableStateFlow(BookshelfManageUiState())
    val state: StateFlow<BookshelfManageUiState> = _state.asStateFlow()

    /**
     * 勾选集合独立于主 state (任务3): 勾选/全选只变更本流,
     * 不产生新的 [BookshelfManageUiState] 实例, 从而不触发整页重组;
     * 宿主端按 bookUrl 增量 diff 同步到 per-key 勾选映射, 单行勾选只重组该行勾选框区域。
     */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val incrementalFilter = BookFilter.IncrementalFilter<Book>()

    // 书籍流协程写, 搜索/筛选事件在 UI 线程读, 需要可见性保证
    @Volatile
    private var allBooks: List<Book>? = null
    private var booksFlowJob: Job? = null
    private var groupsFlowJob: Job? = null

    init {
        observeGroups()
    }

    // ===== 分组 =====

    private fun observeGroups() {
        groupsFlowJob?.cancel()
        groupsFlowJob = scope.launch {
            appDb.bookGroupDao.flowAll()
                .catch {
                    AppLog.put("书架管理界面获取分组数据失败\n${it.message}", it)
                }.flowOn(IoDispatcher).conflate().collect { groups ->
                    _state.update { it.copy(groups = groups) }
                }
        }
    }

    /** 由 groupId 查分组名(本地内存, 同步)。
     *
     * 注: 管理页列表已改用宿主预计算的 groupNameMap (任务5, item 内 O(1) 查表),
     * 本方法保留供其它调用方使用。
     */
    fun groupName(groupId: Long): String {
        val names = _state.value.groups
            .filter { it.groupId > 0 && it.groupId and groupId > 0 }
            .map { it.groupName }
        return if (names.isEmpty()) "" else names.joinToString(",")
    }

    // ===== 书籍 =====

    private fun initGroup(groupId: Long) {
        _state.update { it.copy(groupId = groupId) }
        scope.launch(IoDispatcher) {
            val name = appDb.bookGroupDao.getByID(groupId)?.groupName ?: noGroupLabel
            _state.update {
                it.copy(
                    groupName = name,
                    searchHint = "$screenLabel • $name",
                )
            }
        }
        upBookDataByGroupId(groupId)
    }

    private fun upBookDataByGroupId(groupId: Long) {
        booksFlowJob?.cancel()
        booksFlowJob = scope.launch {
            val bookSort = resolveBookSort(groupId)
            appDb.bookDao.flowByGroup(groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.message}", it)
            }.flowOn(IoDispatcher).conflate().collect { list ->
                // 任务4: upBookData/loadCacheFiles 与 collect 解耦 — 仅当列表数据真正变化
                // (书籍集合/内容/顺序, data class equals 含顺序) 才重算过滤结果并重启缓存扫描;
                // 下载/进度等不改变集合的 DB 流量不再触发整页重组与扫描协程重启。
                if (allBooks != list) {
                    allBooks = list
                    upBookData()
                    loadCacheFiles(list)
                }
                _state.update { it.copy(canDrag = bookSort == 3) }
            }
        }
    }

    private fun upBookData() {
        val all = allBooks ?: return
        // 过滤器有内部缓存, 结果先算出来再入 state (update 的 lambda 会因竞争重跑)
        val snapshot = _state.value
        val typeFiltered = when (snapshot.bookshelfTypeFilter) {
            1 -> all.filter { !it.isImage && !it.isAudio && !it.isVideo }
            2 -> all.filter { it.isImage }
            3 -> all.filter { it.isAudio }
            4 -> all.filter { it.isVideo }
            else -> all
        }
        val books = incrementalFilter.filter(typeFiltered, snapshot.searchKey)
        _state.update { it.copy(books = books) }
        // 勾选集合独立维护 (任务3): 搜索/筛选后仅剔除已不可见的书,
        // StateFlow 值相等自动去重, 不产生无谓发射
        val visible = books.mapTo(mutableSetOf()) { it.bookUrl }
        _selected.update { it.intersect(visible) }
    }

    private fun selectGroupFromMenu(group: BookGroup) {
        // 菜单项就是 bookGroupDao.flowAll() 的行, 主键现成: 原按 groupName 反查 DB 多一趟 IO,
        // 且查不到时兜底的 groupId=0 会让 `group & 0 > 0` 恒无结果, 列表直接空掉
        _state.update {
            it.copy(
                groupId = group.groupId,
                groupName = group.groupName,
                searchHint = "$screenLabel • ${group.groupName}",
            )
        }
        upBookDataByGroupId(group.groupId)
    }

    // ===== 多选 =====

    /** 当前可见列表的 bookUrl 集合 (全选/反选/过滤后收敛勾选共用) */
    private fun visibleBookUrls(): Set<String> =
        _state.value.books.mapTo(mutableSetOf()) { it.bookUrl }

    fun selection(): List<Book> =
        _state.value.books.filter { _selected.value.contains(it.bookUrl) }

    // ===== dispatch =====

    fun dispatch(event: BookshelfManageUiEvent) {
        when (event) {
            is BookshelfManageUiEvent.InitGroup -> initGroup(event.groupId)

            is BookshelfManageUiEvent.SetQuery -> {
                _state.update { it.copy(searchKey = event.query) }
                upBookData()
            }

            is BookshelfManageUiEvent.SetBookTypeFilter -> {
                if (_state.value.bookshelfTypeFilter == event.filter) return
                _state.update { it.copy(bookshelfTypeFilter = event.filter) }
                upBookData()
            }

            is BookshelfManageUiEvent.Toggle -> {
                // 勾选集合原地更新 (任务3): 不再 copy 整个主 state, 单次勾选不触发整页重组
                _selected.update {
                    if (event.checked) it + event.book.bookUrl else it - event.book.bookUrl
                }
            }

            is BookshelfManageUiEvent.SelectAll -> {
                _selected.value = if (event.all) visibleBookUrls() else emptySet()
            }

            BookshelfManageUiEvent.RevertSelection -> {
                val all = visibleBookUrls()
                _selected.update { all - it }
            }

            BookshelfManageUiEvent.CheckSelectedInterval -> {
                val books = _state.value.books
                _selected.update { selected ->
                    val positions = books.indices.filter { selected.contains(books[it].bookUrl) }
                    if (positions.isEmpty()) selected
                    else selected + (positions.min()..positions.max()).map { books[it].bookUrl }
                }
            }

            is BookshelfManageUiEvent.Move -> {
                _state.update { state ->
                    state.copy(
                        books = state.books.toMutableList()
                            .apply { add(event.to, removeAt(event.from)) }
                    )
                }
            }

            BookshelfManageUiEvent.PersistOrder -> {
                val books = _state.value.books
                books.forEachIndexed { index, book -> book.order = index + 1 }
                scope.launch(IoDispatcher) {
                    // 只 PATCH order 列; 整行 update 会把内存副本里的旧元数据写回去
                    books.forEach { appDb.bookDao.upOrder(it.bookUrl, it.order) }
                }
            }

            is BookshelfManageUiEvent.SelectGroupFromMenu -> selectGroupFromMenu(event.group)
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/**
 * 下沉的 UI 状态: books/groups/searchKey/searchHint/bookshelfTypeFilter/canDrag。
 * 勾选集合 ([BookshelfManageScreenModel.selected]) 独立于本状态 (任务3): 勾选变化不产生
 * 新的 UiState 实例, 避免整页重组。平台专属状态 (downloadRunning/export 开关) 由宿主持有,
 * 在 Composition 时合并入 BookshelfManageState 传给 Screen。
 */
data class BookshelfManageUiState(
    val groupId: Long = -1L,
    val groupName: String? = null,
    val books: List<Book> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    val searchKey: String = "",
    val searchHint: String = "",
    val bookshelfTypeFilter: Int = 0,
    val canDrag: Boolean = false,
)

sealed interface BookshelfManageUiEvent {
    /** 初始化分组 ID (onActivityCreated 触发, 加载 groupName + 订阅 books flow) */
    data class InitGroup(val groupId: Long) : BookshelfManageUiEvent

    /** 搜索框输入 */
    data class SetQuery(val query: String) : BookshelfManageUiEvent

    /** 切换书籍类型筛选 */
    data class SetBookTypeFilter(val filter: Int) : BookshelfManageUiEvent

    /** 单项选中/取消 */
    data class Toggle(val book: Book, val checked: Boolean) : BookshelfManageUiEvent

    /** 全选/取消全选 */
    data class SelectAll(val all: Boolean) : BookshelfManageUiEvent

    /** 反选 */
    object RevertSelection : BookshelfManageUiEvent

    /** 选中区间填充 */
    object CheckSelectedInterval : BookshelfManageUiEvent

    /** 拖拽移动 */
    data class Move(val from: Int, val to: Int) : BookshelfManageUiEvent

    /** 拖拽落库 (按当前顺序重排 order 后 update) */
    object PersistOrder : BookshelfManageUiEvent

    /** 从菜单选择分组 (切换 groupId 重新订阅 books) */
    data class SelectGroupFromMenu(val group: BookGroup) : BookshelfManageUiEvent
}
