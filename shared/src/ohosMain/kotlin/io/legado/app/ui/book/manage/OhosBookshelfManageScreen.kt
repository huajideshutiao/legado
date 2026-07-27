package io.legado.app.ui.book.manage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.constant.PreferKey
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.BookshelfManageScreen as SharedBookshelfManageScreen
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端书架管理 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookshelfManageScreen])。
 *
 * 对照 iOS 端 [IosBookshelfManageScreen] 实现模式: 仅做鸿蒙平台适配, 业务展示与
 * 交互骨架全部下沉到 shared/sharedUiMain 的 [SharedBookshelfManageScreen]:
 *
 * - **数据流**: [LaunchedEffect] 订阅 `bookGroupDao.flowAll()` 取分组, `bookDao.flowByGroup(groupId)`
 *   取书籍并按全局 [AppConfigProviders.get].bookshelfSort 排序;
 *   搜索/类型筛选复用 [BookFilter.IncrementalFilter] (下沉到 commonMain)
 * - **状态**: 持有 [BookshelfManageState] (immutable, copy 更新),
 *   callbacks 用 [remember] 持有稳定实例避免重组
 * - **拖选**: [Modifier.dragSelectable] (下沉到 shared/sharedUiMain) 注入 listModifier,
 *   复刻 app 端边缘拖选批量勾选语义
 * - **封面槽**: 注入 [OhosInfoCover] (stub, 后续接入 Coil3)
 *
 * # 简化项 (依赖未下沉功能, 用 no-op + TODO 注释, 与 iOS 端一致)
 *
 * - **下载/缓存**: app 端 `CacheBook` / `BookHelp.clearCache` 未下沉, onToggleDownload /
 *   onDownloadAfter / onDownloadAll / isItemDownloading / onCacheInfo no-op
 * - **导出**: app 端导出依赖 `ExportBookService` + `HandleFileContract`, 未下沉,
 *   onExportAllUseBookSource / onSelectExportFolderMenu / onShowExportConfig no-op;
 *   exportUseReplace / enableCustomExport / exportToWebDav 三档开关读写 [PreferenceProviders]
 * - **批量改源**: [BookshelfManagePlatform] 未在鸿蒙端注册, onSelectActions "批量改源" no-op
 * - **打开书籍详情**: 由 [onOpenBook] 回调注入, 切到 BOOK_INFO 路由
 *
 * @param onBack 返回回调 (切回 BOOKSHELF 路由, 由 OhosNavHost 注入)
 * @param onOpenBook 点击封面打开书籍详情回调 (携带 Book, 切到 BOOK_INFO 路由)
 */
@Composable
fun OhosBookshelfManageScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = remember { mutableStateOf(BookshelfManageState()) }
    val listState = rememberLazyListState()

    var groupId by remember { mutableStateOf(BookGroup.IdAll) }
    var groupList by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    var showLogDialog by remember { mutableStateOf(false) }
    var allBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    val incrementalFilter = remember { BookFilter.IncrementalFilter<Book>() }
    var refreshTick by remember { mutableIntStateOf(0) }

    // 导出三档开关 (PreferenceProviders 持久化)
    val prefs = remember { PreferenceProviders.get() }
    var exportUseReplace by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.exportUseReplace, false))
    }
    var enableCustomExport by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.enableCustomExport, false))
    }
    var exportToWebDav by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.exportToWebDav, false))
    }

    // 文案标签
    val screenLabel = rememberString("screen")
    val noGroupLabel = rememberString("no_group")
    val localBookLabel = rememberString("local_book")
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val groupLabel = rememberString("group")
    val groupManageLabel = rememberString("group_manage")
    val allowUpdateLabel = rememberString("allow_update")
    val clearCacheLabel = rememberString("clear_cache")
    val disableUpdateLabel = rememberString("disable_update")
    val addToGroupLabel = rememberString("add_to_group")
    val changeSourceBatchLabel = rememberString("change_source_batch")
    // 文案模板 (LaunchedEffect catch lambda 非 @Composable, 预先 remember)
    val bookshelfManageLoadGroupFailedTemplate = rememberString("bookshelf_manage_load_group_failed_log")
    val bookshelfManageLoadBookFailedTemplate = rememberString("bookshelf_manage_load_book_failed_log")

    var groupName by remember { mutableStateOf(noGroupLabel) }
    var deleteTarget by remember { mutableStateOf<List<Book>?>(null) }
    var groupSelectTarget by remember { mutableStateOf<GroupSelectTarget?>(null) }

    fun selection(): List<Book> =
        state.value.books.filter { state.value.selected.contains(it.bookUrl) }

    fun upBookData() {
        val all = allBooks
        val typeFiltered = when (state.value.bookshelfTypeFilter) {
            1 -> all.filter { !it.isImage && !it.isAudio && !it.isVideo }
            2 -> all.filter { it.isImage }
            3 -> all.filter { it.isAudio }
            4 -> all.filter { it.isVideo }
            else -> all
        }
        val filtered = incrementalFilter.filter(typeFiltered, state.value.searchKey)
        state.value = state.value.copy(
            books = filtered,
            selected = state.value.selected.intersect(filtered.map { it.bookUrl }.toSet()),
        )
    }

    fun selectGroup(group: BookGroup) {
        groupId = group.groupId
        groupName = group.groupName.ifEmpty { noGroupLabel }
    }

    // 收集全部分组
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.bookGroupDao.flowAll()
            .catch { AppLog.put(String.format(bookshelfManageLoadGroupFailedTemplate, it.localizedMessage), it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { groups ->
                groupList = groups
                state.value = state.value.copy(groups = groups)
                refreshTick++
            }
    }

    // 收集当前分组书籍
    LaunchedEffect(groupId) {
        val bookSort = AppConfigProviders.get().bookshelfSort
        AppDbProviders.get().bookDao.flowByGroup(groupId)
            .map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.compareTo(o2.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }
            .catch { AppLog.put(String.format(bookshelfManageLoadBookFailedTemplate, it.localizedMessage), it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { books ->
                allBooks = books
                upBookData()
                state.value = state.value.copy(canDrag = bookSort == 3)
                refreshTick++
            }
    }

    val callbacks = remember(state) {
        BookshelfManageCallbacks(
            onBack = onBack,
            onQueryChange = { query ->
                state.value = state.value.copy(searchKey = query)
                upBookData()
            },
            onMove = { from, to ->
                state.value = state.value.copy(
                    books = state.value.books.toMutableList().apply { add(to, removeAt(from)) }
                )
            },
            onPersistOrder = {
                val books = state.value.books
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(books.size) { i -> books[i].copy(order = i + 1) }
                        AppDbProviders.get().bookDao.update(*array)
                    }
                }
            },
            onSelectAll = { all ->
                state.value = state.value.copy(
                    selected = if (all) state.value.books.map { it.bookUrl }.toSet() else emptySet()
                )
            },
            onRevertSelection = {
                state.value = state.value.copy(
                    selected = state.value.books.map { it.bookUrl }.toSet() - state.value.selected
                )
            },
            onMainAction = {
                groupSelectTarget = GroupSelectTarget.Replace(selection())
            },
            onSelectActions = {
                listOf(
                    SelectAction(deleteLabel) { deleteTarget = selection() },
                    SelectAction(allowUpdateLabel) {
                        val sel = selection()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val array = Array(sel.size) { i -> sel[i].copy(canUpdate = true) }
                                AppDbProviders.get().bookDao.update(*array)
                            }
                        }
                    },
                    SelectAction(disableUpdateLabel) {
                        val sel = selection()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val array = Array(sel.size) { i -> sel[i].copy(canUpdate = false) }
                                AppDbProviders.get().bookDao.update(*array)
                            }
                        }
                    },
                    SelectAction(addToGroupLabel) {
                        groupSelectTarget = GroupSelectTarget.Merge(selection())
                    },
                    SelectAction(changeSourceBatchLabel) {
                        // TODO: BookshelfManagePlatform 未在鸿蒙端注册, 批量改源暂不实现
                    },
                    SelectAction(clearCacheLabel) {
                        // TODO: BookHelp.clearCache 未在鸿蒙端注册, 清缓存暂不实现
                    },
                )
            },
            onToggle = { book, checked ->
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + book.bookUrl
                    else state.value.selected - book.bookUrl
                )
            },
            onOpenBook = onOpenBook,
            onToggleDownload = { /* TODO: 依赖 CacheBook, 未下沉 */ },
            isItemDownloading = { false },
            onOriginText = { book ->
                if (book.isLocal) localBookLabel else book.originName
            },
            onGroupName = { gid ->
                groupList.filter { it.groupId > 0 && it.groupId and gid > 0 }
                    .map { it.groupName }
                    .let { if (it.isEmpty()) "" else it.joinToString(",") }
            },
            onCacheInfo = { null },
            onDeleteBook = { book -> deleteTarget = listOf(book) },
            onEditGroup = { book ->
                groupSelectTarget = GroupSelectTarget.Replace(listOf(book))
            },
            onDownloadAfter = { /* TODO: 依赖 CacheBook, 未下沉 */ },
            onDownloadAll = { /* TODO: 依赖 CacheBook, 未下沉 */ },
            onShowGroupManage = { showGroupManage = true },
            onSelectGroupFromMenu = { group -> selectGroup(group) },
            onExportAllUseBookSource = { /* TODO: 依赖 HandleFileContract, 未下沉 */ },
            onToggleEnableReplace = {
                exportUseReplace = !exportUseReplace
                prefs.putBoolean(PreferKey.exportUseReplace, exportUseReplace)
            },
            onToggleCustomExport = {
                enableCustomExport = !enableCustomExport
                prefs.putBoolean(PreferKey.enableCustomExport, enableCustomExport)
            },
            onToggleExportWebDav = {
                exportToWebDav = !exportToWebDav
                prefs.putBoolean(PreferKey.exportToWebDav, exportToWebDav)
            },
            onSelectExportFolderMenu = { /* TODO: 依赖 HandleFileContract, 未下沉 */ },
            onShowExportConfig = { /* TODO: 依赖 app 端 alert 对话框, 未下沉 */ },
            onShowLog = { showLogDialog = true },
            onSetBookTypeFilter = { filter ->
                if (state.value.bookshelfTypeFilter != filter) {
                    state.value = state.value.copy(bookshelfTypeFilter = filter)
                    upBookData()
                }
            },
        )
    }

    val dragListModifier = Modifier.dragSelectable(
        listState = listState,
        autoScrollScope = scope,
        isSelected = { index ->
            state.value.books.getOrNull(index)?.bookUrl in state.value.selected
        },
        onSelectedChanged = { index, selected ->
            state.value.books.getOrNull(index)?.let { book ->
                state.value = state.value.copy(
                    selected = if (selected) state.value.selected + book.bookUrl
                    else state.value.selected - book.bookUrl
                )
            }
        },
    )

    val currentState = state.value.copy(
        searchHint = "$screenLabel • $groupName",
        refreshTick = refreshTick,
        exportUseReplace = exportUseReplace,
        enableCustomExportChecked = enableCustomExport,
        exportToWebDav = exportToWebDav,
        groups = groupList,
    )

    SharedBookshelfManageScreen(
        currentState,
        callbacks,
        listState,
        dragListModifier,
        { book -> OhosInfoCover(book = book, modifier = Modifier) },
    )

    // 选分组对话框
    groupSelectTarget?.let { target ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { groupSelectTarget = null },
            title = { Text(groupLabel) },
            text = {
                androidx.compose.foundation.layout.Column {
                    groupList.filter { it.groupId > 0 }.forEach { group ->
                        TextButton(onClick = {
                            groupSelectTarget = null
                            val selectedGroupId = group.groupId
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val array = when (target) {
                                        is GroupSelectTarget.Replace -> Array(target.books.size) { i ->
                                            target.books[i].copy(group = selectedGroupId)
                                        }
                                        is GroupSelectTarget.Merge -> Array(target.books.size) { i ->
                                            val b = target.books[i]
                                            b.copy(group = b.group or selectedGroupId)
                                        }
                                    }
                                    AppDbProviders.get().bookDao.update(*array)
                                }
                            }
                        }) { Text(group.groupName) }
                    }
                    if (groupList.none { it.groupId > 0 }) Text(groupManageLabel)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { groupSelectTarget = null }) { Text(cancelLabel) } },
        )
    }

    // 删除确认对话框
    deleteTarget?.let { books ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { deleteTarget = null },
            title = { Text(deleteLabel) },
            text = { Text(sureDelLabel) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().bookDao.delete(*books.toTypedArray())
                        }
                        val removed = books.map { it.bookUrl }.toSet()
                        state.value = state.value.copy(selected = state.value.selected - removed)
                    }
                }) { Text(okLabel) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(cancelLabel) } },
        )
    }

    // 分组管理对话框
    if (showGroupManage) {
        GroupManageDialog(
            groups = groupList,
            onAddGroup = { _ -> showAddGroupDialog = true },
            onRenameGroup = { gid, newName ->
                groupList.find { it.groupId == gid.toLong() }?.let {
                    groupVm.upGroup(it.copy(groupName = newName))
                }
            },
            onDeleteGroup = { gid ->
                groupList.find { it.groupId == gid.toLong() }?.let { editingGroup = it }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    if (showAddGroupDialog) {
        GroupEditDialog(
            group = null,
            onConfirm = { g ->
                groupVm.addGroup(g.groupName, g.bookSort, g.enableRefresh, g.cover) {}
            },
            onDismiss = { showAddGroupDialog = false },
        )
    }

    editingGroup?.let { g ->
        GroupEditDialog(
            group = g,
            onConfirm = { updated -> groupVm.upGroup(updated) },
            onDismiss = { editingGroup = null },
            onDelete = { del -> groupVm.delGroup(del) {} },
        )
    }

    // 应用日志对话框
    if (showLogDialog) {
        io.legado.app.ui.about.AppLogDialog(onDismiss = { showLogDialog = false })
    }
}

/**
 * 鸿蒙端书籍封面渲染 (stub)。
 *
 * 对照 iOS 端 [io.legado.app.ui.bookshelf.IosInfoCover], 后续接入 Coil3 KMP
 * 图片加载后替换为真实实现。当前仅占位让 shared BookshelfManageScreen 的 coverSlot 编译通过。
 *
 * @param book 书籍 (含封面 URL)
 * @param modifier 外部约束
 */
@Composable
private fun OhosInfoCover(book: Book?, modifier: Modifier = Modifier) {
    // KP-ohos: stub, 后续接入 Coil3 KMP 图片加载
    Box(modifier)
}

// 选分组对话框目标 (Replace 替换分组 / Merge 并入分组)
private sealed class GroupSelectTarget {
    abstract val books: List<Book>
    class Replace(override val books: List<Book>) : GroupSelectTarget()
    class Merge(override val books: List<Book>) : GroupSelectTarget()
}
