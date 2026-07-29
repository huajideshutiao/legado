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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架管理 ScreenModel (KMP): 托管 groups/books/selected 状态,
 * 订阅 DAO flow, 通过 dispatch 处理可下沉的 UI 事件。
 * 平台专属逻辑 (showDialogFragment / CacheBook / 导出) 留宿主 Activity。
 *
 * @param screenLabel 搜索框 hint 前缀 (app 端 R.string.screen "界面")
 * @param noGroupLabel 无分组名兜底 (app 端 R.string.no_group)
 * @param resolveBookSort 按 groupId 取排序方式 (app 端 AppConfig.getBookSortByGroupId)
 * @param loadCacheFiles 书籍加载后扫描缓存文件 (app 端 viewModel.loadCacheFiles)
 */
class BookshelfManageScreenModel(
    private val screenLabel: String,
    private val noGroupLabel: String,
    private val resolveBookSort: (Long) -> Int,
    private val loadCacheFiles: (List<Book>) -> Unit,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (app 端无 ScreenModelStore 时由宿主 DisposableEffect 调 onCleared)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(BookshelfManageUiState())
    val state: StateFlow<BookshelfManageUiState> = _state.asStateFlow()

    private val incrementalFilter = BookFilter.IncrementalFilter<Book>()
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
                    AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
                }.flowOn(IoDispatcher).conflate().collect {
                    _state.value = _state.value.copy(groups = it)
                }
        }
    }

    /** 由 groupId 查分组名(本地内存, 同步) */
    fun groupName(groupId: Long): String {
        val names = _state.value.groups
            .filter { it.groupId > 0 && it.groupId and groupId > 0 }
            .map { it.groupName }
        return if (names.isEmpty()) "" else names.joinToString(",")
    }

    // ===== 书籍 =====

    private fun initGroup(groupId: Long) {
        _state.value = _state.value.copy(groupId = groupId)
        scope.launch(IoDispatcher) {
            val name = appDb.bookGroupDao.getByID(groupId)?.groupName ?: noGroupLabel
            _state.value = _state.value.copy(
                groupName = name,
                searchHint = "$screenLabel • $name",
            )
        }
        upBookDataByGroupId()
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        val groupId = _state.value.groupId
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
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IoDispatcher).conflate().collect {
                allBooks = it
                upBookData()
                loadCacheFiles(it)
                _state.value = _state.value.copy(canDrag = bookSort == 3)
            }
        }
    }

    private fun upBookData() {
        val all = allBooks ?: return
        val typeFiltered = when (_state.value.bookshelfTypeFilter) {
            1 -> all.filter { !it.isImage && !it.isAudio && !it.isVideo }
            2 -> all.filter { it.isImage }
            3 -> all.filter { it.isAudio }
            4 -> all.filter { it.isVideo }
            else -> all
        }
        val books = incrementalFilter.filter(typeFiltered, _state.value.searchKey)
        val selected = _state.value.selected.intersect(books.map { it.bookUrl }.toSet())
        _state.value = _state.value.copy(books = books, selected = selected)
    }

    private fun selectGroupFromMenu(group: BookGroup) {
        _state.value = _state.value.copy(
            groupName = group.groupName,
            searchHint = "$screenLabel • ${group.groupName}",
        )
        scope.launch(IoDispatcher) {
            val groupId = appDb.bookGroupDao.getByName(group.groupName)?.groupId ?: 0
            _state.value = _state.value.copy(groupId = groupId)
            upBookDataByGroupId()
        }
    }

    // ===== 多选 =====

    fun selection(): List<Book> =
        _state.value.books.filter { _state.value.selected.contains(it.bookUrl) }

    // ===== dispatch =====

    fun dispatch(event: BookshelfManageUiEvent) {
        when (event) {
            is BookshelfManageUiEvent.InitGroup -> initGroup(event.groupId)

            is BookshelfManageUiEvent.SetQuery -> {
                _state.value = _state.value.copy(searchKey = event.query)
                upBookData()
            }

            is BookshelfManageUiEvent.SetBookTypeFilter -> {
                if (_state.value.bookshelfTypeFilter == event.filter) return
                _state.value = _state.value.copy(bookshelfTypeFilter = event.filter)
                upBookData()
            }

            is BookshelfManageUiEvent.Toggle -> {
                val cur = _state.value.selected
                _state.value = _state.value.copy(
                    selected = if (event.checked) cur + event.book.bookUrl
                    else cur - event.book.bookUrl
                )
            }

            is BookshelfManageUiEvent.SelectAll -> {
                _state.value = _state.value.copy(
                    selected = if (event.all) {
                        _state.value.books.map { it.bookUrl }.toSet()
                    } else emptySet()
                )
            }

            BookshelfManageUiEvent.RevertSelection -> {
                val all = _state.value.books.map { it.bookUrl }.toSet()
                _state.value = _state.value.copy(selected = all - _state.value.selected)
            }

            BookshelfManageUiEvent.CheckSelectedInterval -> {
                val books = _state.value.books
                val positions = books.mapIndexedNotNull { index, book ->
                    index.takeIf { _state.value.selected.contains(book.bookUrl) }
                }
                if (positions.isNotEmpty()) {
                    val range = positions.min()..positions.max()
                    _state.value = _state.value.copy(
                        selected = _state.value.selected + range.map { books[it].bookUrl }
                    )
                }
            }

            is BookshelfManageUiEvent.Move -> {
                val list = _state.value.books.toMutableList()
                    .apply { add(event.to, removeAt(event.from)) }
                _state.value = _state.value.copy(books = list)
            }

            BookshelfManageUiEvent.PersistOrder -> {
                val books = _state.value.books
                books.forEachIndexed { index, book -> book.order = index + 1 }
                scope.launch(IoDispatcher) {
                    appDb.bookDao.update(*books.toTypedArray())
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
 * 下沉的 UI 状态: books/selected/groups/searchKey/searchHint/bookshelfTypeFilter/canDrag。
 * 平台专属状态 (downloadRunning/refreshTick/export 开关) 由宿主 Activity 持有,
 * 在 Composition 时合并入 BookshelfManageState 传给 Screen。
 */
data class BookshelfManageUiState(
    val groupId: Long = -1L,
    val groupName: String? = null,
    val books: List<Book> = emptyList(),
    val selected: Set<String> = emptySet(),
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
