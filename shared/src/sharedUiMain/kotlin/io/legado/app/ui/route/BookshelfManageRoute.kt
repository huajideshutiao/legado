package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.service.ServiceLaunchers
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.BookshelfManageCallbacks
import io.legado.app.ui.book.manage.BookshelfManageScreen
import io.legado.app.ui.book.manage.BookshelfManageScreenModel
import io.legado.app.ui.book.manage.BookshelfManageState
import io.legado.app.ui.book.manage.BookshelfManageUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.launch

/**
 * 书架管理 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [BookshelfManageScreenModel], 渲染 [BookshelfManageScreen]。
 * 平台专属能力 (下载/导出/弹窗) 用 [ServiceLaunchers] / [CacheBookShared] / shared 对话框实现;
 * AppConfig 写入项 (exportUseReplace 等) 暂留空, 待 AppConfigAccessor 扩展 setter 后接入。
 */
@Composable
fun BookshelfManageRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookshelfManageScreenModel(
            screenLabel = sharedStringTable["screen"] ?: "screen",
            noGroupLabel = sharedStringTable["no_group"] ?: "no_group",
            // AppConfigAccessor 暂仅暴露默认 bookshelfSort, per-group override 待下沉
            resolveBookSort = { AppConfigProviders.get().bookshelfSort },
            // 缓存文件扫描委托平台实现 (对照 app 端 viewModel.loadCacheFiles)
            loadCacheFiles = { books -> PlatformCapabilityProviders.get().loadCacheFiles(books) },
        )
    }
    val uiState by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val platform = PlatformCapabilityProviders.get()

    // 平台专属状态: downloadRunning / refreshTick (订阅 FlowBus 事件, 对照 app 端 observeLiveBus)
    var downloadRunning by remember { mutableStateOf(CacheBookShared.isRun) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.UP_DOWNLOAD).collect {
            downloadRunning = CacheBookShared.isRun
            refreshTick++
        }
    }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.EXPORT_BOOK).collect { refreshTick++ }
    }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SAVE_CONTENT).collect { refreshTick++ }
    }

    // AppRoute.BookshelfManage 暂无 groupId 参数, 默认 -1L (未分组)
    LaunchedEffect(Unit) {
        screenModel.dispatch(BookshelfManageUiEvent.InitGroup(-1L))
    }

    // 对话框状态
    var showLogDialog by remember { mutableStateOf(false) }
    var showGroupManage by remember { mutableStateOf(false) }
    var groupSelectTarget by remember { mutableStateOf<GroupSelectTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    val listState = rememberLazyListState()

    // UiState + 平台状态 → BookshelfManageState
    val state = remember(uiState, downloadRunning, refreshTick) {
        BookshelfManageState(
            books = uiState.books,
            selected = uiState.selected,
            searchKey = uiState.searchKey,
            searchHint = uiState.searchHint,
            bookshelfTypeFilter = uiState.bookshelfTypeFilter,
            canDrag = uiState.canDrag,
            groups = uiState.groups,
            downloadRunning = downloadRunning,
            refreshTick = refreshTick,
            // 导出开关读取平台持久化值 (对照 AppConfig.exportUseReplace 等)
            exportUseReplace = platform.exportUseReplace(),
            enableCustomExportChecked = platform.enableCustomExport(),
            exportToWebDav = platform.exportToWebDav(),
        )
    }

    val callbacks = remember(screenModel, navigator, scope) {
        BookshelfManageCallbacks(
            onBack = { navigator.pop() },
            onQueryChange = { screenModel.dispatch(BookshelfManageUiEvent.SetQuery(it)) },
            onMove = { from, to -> screenModel.dispatch(BookshelfManageUiEvent.Move(from, to)) },
            onPersistOrder = { screenModel.dispatch(BookshelfManageUiEvent.PersistOrder) },
            onSelectAll = { all -> screenModel.dispatch(BookshelfManageUiEvent.SelectAll(all)) },
            onRevertSelection = { screenModel.dispatch(BookshelfManageUiEvent.RevertSelection) },
            // 批量栏主按钮: 弹分组选择对话框 (移入选中书籍到分组)
            onMainAction = { groupSelectTarget = GroupSelectTarget.MoveSelection },
            // 批量栏溢出菜单: 返回 SelectAction 列表
            onSelectActions = {
                buildSelectActions(
                    screenModel = screenModel,
                    scope = scope,
                    onDeleteSelection = { deleteTarget = DeleteTarget.Selection },
                    onAddToGroup = { groupSelectTarget = GroupSelectTarget.AddSelection },
                )
            },
            onToggle = { book, checked ->
                screenModel.dispatch(BookshelfManageUiEvent.Toggle(book, checked))
            },
            // 打开书籍详情页
            onOpenBook = { book -> navigator.push(AppRoute.BookInfo(book.toRouteRef())) },
            // 单项下载图标: 已全部缓存则跳过, 否则运行中停止/未运行开始 (对照 app 端 toggleDownload)
            onToggleDownload = { book ->
                val cs = platform.cacheChapterCount(book)
                if (cs != book.totalChapterNum) {
                    toggleDownload(book)
                }
            },
            // 单项下载状态查询
            isItemDownloading = { book ->
                CacheBookShared.cacheBookMap[book.bookUrl]?.isStop() == false
            },
            onOriginText = { book ->
                if (book.isLocal) sharedStringTable["local_book"] ?: "local_book"
                else book.originName
            },
            onGroupName = { groupId -> screenModel.groupName(groupId) },
            // 缓存进度文案: 委托平台实现 (对照 app 端 cacheInfo)
            onCacheInfo = { book -> platform.cacheInfo(book) },
            // 单项删除: 弹确认对话框
            onDeleteBook = { book -> deleteTarget = DeleteTarget.Single(book) },
            // 单项改分组: 弹分组选择对话框
            onEditGroup = { book -> groupSelectTarget = GroupSelectTarget.EditSingle(book) },
            // 顶栏下载后续/停止
            onDownloadAfter = { downloadAfter(screenModel, platform) },
            // 顶栏全部下载/停止
            onDownloadAll = { downloadAll(screenModel) },
            // 分组管理对话框
            onShowGroupManage = { showGroupManage = true },
            onSelectGroupFromMenu = { group ->
                screenModel.dispatch(BookshelfManageUiEvent.SelectGroupFromMenu(group))
            },
            // 平台专属文件 I/O (对照 exportAllUseBookSource)
            onExportAllUseBookSource = { platform.exportAllUseBookSource() },
            // 导出开关切换 (对照 toggleEnableReplace / toggleCustomExport / toggleExportWebDav)
            onToggleEnableReplace = { platform.toggleExportUseReplace() },
            onToggleCustomExport = { platform.toggleCustomExport() },
            onToggleExportWebDav = { platform.toggleExportWebDav() },
            // 平台专属文件夹选择器 (对照 selectExportFolderMenu)
            onSelectExportFolderMenu = { platform.selectExportFolder() },
            // 平台专属导出配置弹窗 (对照 showExportConfig)
            onShowExportConfig = { platform.showExportConfig() },
            // 日志对话框
            onShowLog = { showLogDialog = true },
            onSetBookTypeFilter = { filter ->
                screenModel.dispatch(BookshelfManageUiEvent.SetBookTypeFilter(filter))
            },
        )
    }

    BookshelfManageScreen(
        state = state,
        callbacks = callbacks,
        listState = listState,
        listModifier = Modifier,
        coverSlot = {},
    )

    // 日志对话框
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 分组管理对话框
    if (showGroupManage) {
        val groupVm = remember(scope) { GroupViewModelShared(scope) }
        GroupManageDialog(
            groups = uiState.groups,
            onAddGroup = { name -> groupVm.addGroup(name, 0, true, null) { } },
            onRenameGroup = { gid, name ->
                uiState.groups.find { it.groupId.toInt() == gid }?.let { g ->
                    groupVm.upGroup(g.copy(groupName = name))
                }
            },
            onDeleteGroup = { gid ->
                uiState.groups.find { it.groupId.toInt() == gid }?.let { g ->
                    groupVm.delGroup(g) { }
                }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // 分组选择对话框 (移入分组/加入分组/单项改分组)
    groupSelectTarget?.let { target ->
        GroupSelectDialog(
            groups = uiState.groups,
            onSelect = { groupId ->
                handleGroupSelect(target, groupId, screenModel, scope)
                groupSelectTarget = null
            },
            onDismiss = { groupSelectTarget = null },
        )
    }

    // 删除确认对话框 (单项/批量)
    deleteTarget?.let { target ->
        DeleteConfirmDialog(
            showCheckbox = target.showCheckbox,
            initialDeleteOriginal = platform.getDeleteBookOriginal(),
            onConfirm = { deleteOriginal ->
                // 对照 app alertDelSelection / deleteBook: 持久化 deleteBookOriginal 偏好
                platform.setDeleteBookOriginal(deleteOriginal)
                handleDelete(target, deleteOriginal, screenModel, scope)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ---- 平台专属操作辅助函数 ----

/** 单项下载图标点击: 运行中则移除下载任务, 否则开始下载 (对照 app 端 toggleDownload) */
private fun toggleDownload(book: Book) {
    val model = CacheBookShared.cacheBookMap[book.bookUrl]
    if (model != null && !model.isStop()) {
        ServiceLaunchers.get().removeCacheBookService(book.bookUrl)
    } else {
        ServiceLaunchers.get().startCacheBookService(book.bookUrl, 0, book.lastChapterIndex)
    }
}

/** 下载后续: 未运行则从当前进度起下载选中书籍 (跳过已全缓存), 运行中则停止 (对照 app 端 downloadAfter) */
private fun downloadAfter(
    screenModel: BookshelfManageScreenModel,
    platform: io.legado.app.ui.root.PlatformCapabilities,
) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    if (!CacheBookShared.isRun) {
        selection.forEach { book ->
            val cs = platform.cacheChapterCount(book)
            if (cs != book.totalChapterNum) {
                ServiceLaunchers.get()
                    .startCacheBookService(
                        book.bookUrl,
                        book.durChapterIndex,
                        book.lastChapterIndex
                    )
            }
        }
    } else {
        ServiceLaunchers.get().stopCacheBookService()
    }
}

/** 全部下载: 未运行则从头下载选中书籍, 运行中则停止 (对照 app 端 downloadAll) */
private fun downloadAll(screenModel: BookshelfManageScreenModel) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    if (!CacheBookShared.isRun) {
        selection.forEach { book ->
            ServiceLaunchers.get().startCacheBookService(book.bookUrl, 0, book.lastChapterIndex)
        }
    } else {
        ServiceLaunchers.get().stopCacheBookService()
    }
}

/** 批量栏溢出菜单项 (对照 app 端 selectActions) */
private fun buildSelectActions(
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onDeleteSelection: () -> Unit,
    onAddToGroup: () -> Unit,
): List<SelectAction> {
    val platform = PlatformCapabilityProviders.get()
    return listOf(
        SelectAction(sharedStringTable["delete"] ?: "delete") { onDeleteSelection() },
        // 导出全部: 委托平台 (对照 exportAll)
        SelectAction(sharedStringTable["export_all"] ?: "export_all") { platform.exportAllBooks() },
        SelectAction(sharedStringTable["allow_update"] ?: "allow_update") {
            upCanUpdate(screenModel, scope, true)
        },
        SelectAction(sharedStringTable["disable_update"] ?: "disable_update") {
            upCanUpdate(screenModel, scope, false)
        },
        SelectAction(sharedStringTable["add_to_group"] ?: "add_to_group") { onAddToGroup() },
        // 导出书架: 委托平台 (对照 exportBookshelf)
        SelectAction(
            sharedStringTable["export_bookshelf"] ?: "export_bookshelf"
        ) { platform.exportBookshelf() },
        // 批量改源: 委托平台 (对照 showDialogFragment<SourcePickerDialog>)
        SelectAction(
            sharedStringTable["change_source_batch"] ?: "change_source_batch"
        ) { platform.showSourcePickerDialog() },
        SelectAction(sharedStringTable["clear_cache"] ?: "clear_cache") {
            clearCache(screenModel, scope)
        },
        SelectAction(sharedStringTable["check_selected_interval"] ?: "check_selected_interval") {
            screenModel.dispatch(BookshelfManageUiEvent.CheckSelectedInterval)
        },
    )
}

/** 批量更新选中书籍的 canUpdate 标记 (对照 app 端 upCanUpdate) */
private fun upCanUpdate(
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
    canUpdate: Boolean,
) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    scope.launch(IoDispatcher) {
        val array = Array(selection.size) { i ->
            selection[i].copy(canUpdate = canUpdate).apply {
                if (!canUpdate) removeType(BookType.updateError)
            }
        }
        AppDbProviders.get().bookDao.update(*array)
    }
}

/** 批量清除选中书籍缓存 (对照 app 端 clearCache) */
private fun clearCache(
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    scope.launch(IoDispatcher) {
        selection.forEach { BookStorageProviders.get().clearCache(it) }
        Toasters.get().toast(sharedStringTable["clear_cache_success"] ?: "清缓存成功")
    }
}

/** 处理分组选择结果 (对照 app 端 upGroup 回调) */
private fun handleGroupSelect(
    target: GroupSelectTarget,
    groupId: Long,
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (target) {
        is GroupSelectTarget.MoveSelection -> {
            // 主按钮: 选中书籍覆盖分组
            val selection = screenModel.selection()
            if (selection.isEmpty()) return
            scope.launch(IoDispatcher) {
                val array = Array(selection.size) { i ->
                    selection[i].copy(group = groupId)
                }
                AppDbProviders.get().bookDao.update(*array)
            }
        }

        is GroupSelectTarget.AddSelection -> {
            // 加入分组: 选中书籍位掩码 OR groupId
            val selection = screenModel.selection()
            if (selection.isEmpty()) return
            scope.launch(IoDispatcher) {
                val array = Array(selection.size) { i ->
                    selection[i].copy(group = selection[i].group or groupId)
                }
                AppDbProviders.get().bookDao.update(*array)
            }
        }

        is GroupSelectTarget.EditSingle -> {
            // 单项改分组: 覆盖该书籍分组
            val book = target.book
            scope.launch(IoDispatcher) {
                AppDbProviders.get().bookDao.update(book.copy(group = groupId))
            }
        }
    }
}

/** 处理删除确认 (对照 app 端 deleteBook / alertDelSelection) */
private fun handleDelete(
    target: DeleteTarget,
    deleteOriginal: Boolean,
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val books = when (target) {
        is DeleteTarget.Single -> listOf(target.book)
        DeleteTarget.Selection -> screenModel.selection()
    }
    if (books.isEmpty()) return
    scope.launch(IoDispatcher) {
        AppDbProviders.get().bookDao.delete(*books.toTypedArray())
        // 对照 shared VM deleteBook / FileBook.deleteBook: 本地书始终清缓存+删封面,
        // deleteOriginal 控制是否额外删源文件
        books.filter { it.isLocal }.forEach { book ->
            if (deleteOriginal) {
                // LocalBookLocators.deleteBook 委托 FileBook.deleteBook(book, true):
                // 清缓存 + 删封面 + 删源文件
                LocalBookLocators.get().deleteBook(book)
            } else {
                // 对照 FileBook.deleteBook(book, false): 清缓存 + 删封面, 保留源文件
                // (LocalBookLocators.deleteBook 硬编码 deleteOriginal=true, 不能用于此分支)
                BookStorageProviders.get().clearCache(book)
            }
        }
    }
}

// ---- 对话框状态类型 ----

/** 分组选择对话框模式 (对照 app 端 selectGroup requestCode 区分) */
private sealed class GroupSelectTarget {
    /** 主按钮: 选中书籍移入分组 (覆盖 group) */
    object MoveSelection : GroupSelectTarget()

    /** 批量"加入分组": 选中书籍位掩码 OR groupId */
    object AddSelection : GroupSelectTarget()

    /** 单项改分组: 覆盖该书籍 group */
    data class EditSingle(val book: Book) : GroupSelectTarget()
}

/** 删除确认对话框目标 */
private sealed class DeleteTarget {
    data class Single(val book: Book) : DeleteTarget()
    object Selection : DeleteTarget()

    // 对照 app: 单项删除仅本地书显示复选框, 批量删除始终显示
    val showCheckbox: Boolean
        get() = when (this) {
            is Single -> book.isLocal
            Selection -> true
        }
}

// ---- 对话框 Composable ----

/** 分组选择对话框 (对照 app 端 GroupSelectDialog, 简化为列表选择) */
@Composable
private fun GroupSelectDialog(
    groups: List<BookGroup>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = rememberString("group"),
                    onBack = onDismiss,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(items = groups, key = { it.groupId }) { group ->
                        Text(
                            text = group.groupName,
                            color = colors.primaryText,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(group.groupId) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 删除确认对话框 (对照 app 端 alert + DeleteFileCheckbox) */
@Composable
private fun DeleteConfirmDialog(
    showCheckbox: Boolean,
    initialDeleteOriginal: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 对照 app: 初始值取 LocalConfig.deleteBookOriginal
    val deleteFile = remember { mutableStateOf(initialDeleteOriginal) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("draw"),
        message = rememberString("sure_del"),
        okButton = AlertButton(rememberString("ok")) {
            onConfirm(deleteFile.value)
        },
        cancelButton = AlertButton(rememberString("cancel")) { onDismiss() },
        content = if (showCheckbox) {
            {
                // "删除源文件"复选框 (对照 app 端 DeleteFileCheckbox)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = deleteFile.value,
                            onValueChange = { deleteFile.value = it },
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppCheckbox(checked = deleteFile.value, onCheckedChange = null)
                    Text(
                        text = rememberString("delete_book_file"),
                        color = AppTheme.colors.primaryText,
                    )
                }
            }
        } else null,
    )
}
