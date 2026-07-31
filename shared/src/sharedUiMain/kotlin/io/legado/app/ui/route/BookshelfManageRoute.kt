package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
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
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.service.ServiceLaunchers
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.BookshelfManageCallbacks
import io.legado.app.ui.book.manage.BookshelfManagePlatformProviders
import io.legado.app.ui.book.manage.BookshelfManageScreen
import io.legado.app.ui.book.manage.BookshelfManageScreenModel
import io.legado.app.ui.book.manage.BookshelfManageState
import io.legado.app.ui.book.manage.BookshelfManageUiEvent
import io.legado.app.ui.book.manage.BookshelfManageViewModelShared
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_to_group
import legado.shared.generated.resources.allow_update
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.change_source
import legado.shared.generated.resources.change_source_batch
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.check_selected_interval
import legado.shared.generated.resources.clear_cache
import legado.shared.generated.resources.clear_cache_success
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.delete_book_file
import legado.shared.generated.resources.disable_update
import legado.shared.generated.resources.download_count
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.export_all
import legado.shared.generated.resources.export_bookshelf
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.local_book
import legado.shared.generated.resources.no_group
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.screen
import legado.shared.generated.resources.select_one_book
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.stringResource

/**
 * 书架管理 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [BookshelfManageScreenModel], 渲染 [BookshelfManageScreen]。
 * 平台专属能力 (下载/导出/弹窗) 用 [ServiceLaunchers] / [CacheBookShared] / shared 对话框实现。
 */
@Composable
fun BookshelfManageRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.BookshelfManage
    val scope = rememberCoroutineScope()
    val platform = PlatformCapabilityProviders.get()
    // 批量换源/缓存扫描 VM (commonMain 下沉, 平台依赖经 BookshelfManagePlatformProviders 注入)
    val manageVm = remember(entry) {
        BookshelfManageViewModelShared(
            scope = scope,
            platform = BookshelfManagePlatformProviders.get(),
        )
    }
    val labels = rememberManageLabels()
    val screenLabel = stringResource(Res.string.screen)
    val noGroupLabel = stringResource(Res.string.no_group)
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookshelfManageScreenModel(
            screenLabel = screenLabel,
            noGroupLabel = noGroupLabel,
            // AppConfigAccessor 暂仅暴露默认 bookshelfSort, per-group override 待下沉
            resolveBookSort = { AppConfigProviders.get().bookshelfSort },
            // 缓存文件扫描委托 shared VM (对照 app 端 viewModel.loadCacheFiles)
            loadCacheFiles = { books -> manageVm.loadCacheFiles(books) },
        )
    }
    val uiState by screenModel.state.collectAsState()

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
        FlowBus.with(EventBus.SAVE_CONTENT).collect {
            manageVm.upAdapter.collect { refreshTick++ }
        }
    }

    // 目录选择目标书: TocRoute 回传后用于定位更新哪本书的进度
    var tocSelectTarget by remember { mutableStateOf<Book?>(null) }

    // 用路由 groupId 初始化分组 (对照 app 端 intent.getLongExtra("groupId", -1))
    LaunchedEffect(Unit) {
        screenModel.dispatch(BookshelfManageUiEvent.InitGroup(route.groupId))
    }

    // 书籍详情页删除结果: 刷新列表 (对照 app 端 BookshelfManageActivity 书籍列表 Flow 自动更新)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).filter { it.key == RouteResults.BOOK_INFO }
            .collect { result ->
                if (result.payload is RouteResultPayload.Deleted) {
                    screenModel.dispatch(BookshelfManageUiEvent.InitGroup(route.groupId))
                }
            }
    }

    // 目录选择结果: 跳 TocRoute 选章节后回传, 更新目标 book 阅读进度并落库 (对照 TocActivity setResult)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).filter { it.key == RouteResults.TOC }.collect { result ->
            val payload = result.payload as? RouteResultPayload.Toc ?: return@collect
            val target = tocSelectTarget ?: return@collect
            scope.launch(IoDispatcher) {
                val updated = target.copy(
                    durChapterIndex = payload.chapterIndex,
                    durChapterPos = payload.chapterPos,
                )
                AppDbProviders.get().bookDao.update(updated)
            }
            tocSelectTarget = null
        }
    }

    // 批量换源结果: 跳 ChangeSourceRoute 选源后回传, 把选中书籍批量换到该源 (任务要求"跳 ChangeSourceRoute 带 resultKey")
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).filter { it.key == RouteResults.CHANGE_SOURCE }
            .collect { result ->
                val payload = result.payload as? RouteResultPayload.ChangeSource ?: return@collect
                val selection = screenModel.selection()
                if (selection.isNotEmpty()) {
                    manageVm.changeSource(selection, payload.source)
                }
            }
    }

    // 对话框状态
    var showLogDialog by remember { mutableStateOf(false) }
    var showGroupManage by remember { mutableStateOf(false) }
    var groupSelectTarget by remember { mutableStateOf<GroupSelectTarget?>(null) }
    var selectingGroupId by remember { mutableStateOf(0L) }
    var editingSelectedGroup by remember { mutableStateOf<BookGroup?>(null) }
    var addingSelectedGroup by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    // 批量换源: 弹源选择对话框 + 进度 WaitDialog
    var showSourcePicker by remember { mutableStateOf(false) }
    var showBatchChangeSource by remember { mutableStateOf(false) }
    var batchChangeSourceText by remember { mutableStateOf("") }

    // 桥接 VM 批量换源状态流 (对照 app 端 batchChangeSourceState/ProcessLiveData observe)
    LaunchedEffect(Unit) {
        manageVm.batchChangeSourceState.collect { running ->
            showBatchChangeSource = running
        }
    }
    LaunchedEffect(Unit) {
        manageVm.batchChangeSourceProcess.collect { text ->
            batchChangeSourceText = text
        }
    }

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

    val callbacks = remember(screenModel, navigator, scope, manageVm) {
        BookshelfManageCallbacks(
            onBack = { navigator.pop() },
            onQueryChange = { screenModel.dispatch(BookshelfManageUiEvent.SetQuery(it)) },
            onMove = { from, to -> screenModel.dispatch(BookshelfManageUiEvent.Move(from, to)) },
            onPersistOrder = { screenModel.dispatch(BookshelfManageUiEvent.PersistOrder) },
            onSelectAll = { all -> screenModel.dispatch(BookshelfManageUiEvent.SelectAll(all)) },
            onRevertSelection = { screenModel.dispatch(BookshelfManageUiEvent.RevertSelection) },
            // 批量栏主按钮: 弹分组选择对话框 (移入选中书籍到分组)
            onMainAction = {
                selectingGroupId = 0L
                groupSelectTarget = GroupSelectTarget.MoveSelection
            },
            // 批量栏溢出菜单: 返回 SelectAction 列表
            onSelectActions = {
                buildSelectActions(
                    screenModel = screenModel,
                    scope = scope,
                    labels = labels,
                    onDeleteSelection = { deleteTarget = DeleteTarget.Selection },
                    onAddToGroup = {
                        selectingGroupId = 0L
                        groupSelectTarget = GroupSelectTarget.AddSelection
                    },
                    onShowSourcePicker = { showSourcePicker = true },
                    onSelectToc = { book ->
                        tocSelectTarget = book
                        navigator.push(
                            AppRoute.Toc(book.toRouteRef()),
                            resultKey = RouteResults.TOC
                        )
                    },
                    onChangeSourceRoute = { book ->
                        navigator.push(
                            AppRoute.ChangeSource(book.toRouteRef()),
                            resultKey = RouteResults.CHANGE_SOURCE,
                        )
                    },
                )
            },
            onToggle = { book, checked ->
                screenModel.dispatch(BookshelfManageUiEvent.Toggle(book, checked))
            },
            // 打开书籍详情页 (带 resultKey 以接收删除结果)
            onOpenBook = { book ->
                navigator.push(AppRoute.BookInfo(book.toRouteRef()), RouteResults.BOOK_INFO)
            },
            // 单项下载图标: 已全部缓存则跳过, 否则运行中停止/未运行开始 (对照 app 端 toggleDownload)
            onToggleDownload = { book ->
                val cs = manageVm.cacheChapters[book.bookUrl]?.size
                if (cs != book.totalChapterNum) {
                    toggleDownload(book)
                }
            },
            // 单项下载状态查询
            isItemDownloading = { book ->
                CacheBookShared.cacheBookMap[book.bookUrl]?.isStop() == false
            },
            onOriginText = { book ->
                if (book.isLocal) labels.localBook else book.originName
            },
            onGroupName = { groupId -> screenModel.groupName(groupId) },
            // 缓存进度文案: 读 VM.cacheChapters (对照 app 端 cacheInfo)
            onCacheInfo = { book -> cacheInfo(book, manageVm, labels) },
            // 单项删除: 弹确认对话框
            onDeleteBook = { book -> deleteTarget = DeleteTarget.Single(book) },
            // 单项改分组: 弹分组选择对话框
            onEditGroup = { book ->
                selectingGroupId = book.group
                groupSelectTarget = GroupSelectTarget.EditSingle(book)
            },
            // 顶栏下载后续/停止
            onDownloadAfter = {
                downloadAfter(screenModel) { book -> manageVm.cacheChapters[book.bookUrl]?.size }
            },
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
            onSelectExportFolderMenu = { platform.selectExportFolder(screenModel.selection()) },
            // 平台专属导出配置弹窗 (对照 showExportConfig)
            onShowExportConfig = { platform.showExportConfig() },
            // 日志对话框
            onShowLog = { showLogDialog = true },
            onSetBookTypeFilter = { filter ->
                screenModel.dispatch(BookshelfManageUiEvent.SetBookTypeFilter(filter))
            },
        )
    }

    // 封面 slot: 复用 LocalBookCoverSlot (宿主端注入 ShelfCover, 兜底 SharedBookCover)
    // Modifier 透传: 让 ShelfCover 按封面框 60x80dp 尺寸渲染 (不可空实现)
    val bookCoverSlot = LocalBookCoverSlot.current
    BookshelfManageScreen(
        state = state,
        callbacks = callbacks,
        listState = listState,
        listModifier = Modifier,
        coverSlot = { book, modifier -> bookCoverSlot(book, modifier, false) },
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
            onAddGroup = { addingSelectedGroup = true },
            onEditGroup = { editingSelectedGroup = it },
            onUpdateGroup = { groupVm.upGroup(it) },
            onPersistOrder = { ordered -> groupVm.upGroup(*ordered.toTypedArray()) },
            onDismiss = { showGroupManage = false },
            canAddGroup = { AppDbProviders.get().bookGroupDao.canAddGroup() },
        )
    }

    // 分组选择对话框 (移入分组/加入分组/单项改分组)
    groupSelectTarget?.let { target ->
        val groupVm = remember(scope) { GroupViewModelShared(scope) }
        GroupSelectDialog(
            groups = uiState.groups,
            initialGroupId = selectingGroupId,
            onConfirm = { groupId ->
                handleGroupSelect(target, groupId, screenModel, scope)
                groupSelectTarget = null
            },
            onDismiss = { groupSelectTarget = null },
            onPersistOrder = { ordered -> groupVm.upGroup(*ordered.toTypedArray()) },
            onAddGroup = { addingSelectedGroup = true },
            onEditGroup = { editingSelectedGroup = it },
        )
        if (addingSelectedGroup || editingSelectedGroup != null) {
            GroupEditDialog(
                group = editingSelectedGroup,
                onConfirm = { updated ->
                    if (addingSelectedGroup) {
                        groupVm.addGroup(
                            updated.groupName,
                            updated.bookSort,
                            updated.enableRefresh,
                            updated.cover,
                        ) { addingSelectedGroup = false }
                    } else {
                        groupVm.upGroup(updated) { editingSelectedGroup = null }
                    }
                },
                onDismiss = {
                    addingSelectedGroup = false
                    editingSelectedGroup = null
                },
                onDelete = { group ->
                    groupVm.delGroup(group) { editingSelectedGroup = null }
                },
            )
        }
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

    if (showSourcePicker) {
        val selection = screenModel.selection()
        val contextBook = selection.firstOrNull()
        if (contextBook == null) {
            showSourcePicker = false
        } else {
            var sources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
            LaunchedEffect(Unit) {
                sources = AppDbProviders.get().bookSourceDao.enabled()
            }
            SourcePickerDialog(
                sources = sources,
                initialDelay = AppConfigProviders.get().batchChangeSourceDelay,
                onSourceSelected = { source ->
                    manageVm.changeSource(selection, source)
                },
                onDelayChange = { delay ->
                    PreferenceProviders.get().putInt(PreferKey.batchChangeSourceDelay, delay)
                },
                onDismiss = { showSourcePicker = false },
            )
        }
    }

    // 批量换源进度 WaitDialog (对照 app 端 waitDialog + batchChangeSourceState)
    WaitDialog(
        visible = showBatchChangeSource,
        message = batchChangeSourceText.ifEmpty { labels.changeSourceBatch },
        onDismissRequest = {
            // 对照 app 端 waitDialog.onCancelListener = { coroutine.cancel() }
            manageVm.batchChangeSourceCoroutine?.cancel()
        },
    )
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
    cacheChapterCount: (Book) -> Int?,
) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    if (!CacheBookShared.isRun) {
        selection.forEach { book ->
            val cs = cacheChapterCount(book)
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

/** 缓存进度文案: 读 VM.cacheChapters + cacheSizes (对照 app 端 cacheInfo, 任务要求展示缓存大小) */
private fun cacheInfo(
    book: Book,
    vm: BookshelfManageViewModelShared,
    labels: ManageLabels,
): String? {
    if (book.isLocal) return null
    val cs = vm.cacheChapters[book.bookUrl]
    return if (cs == null) {
        labels.loading
    } else {
        val progress = labels.downloadCount.format(cs.size, book.totalChapterNum)
        val sizeBytes = vm.cacheSizes[book.bookUrl] ?: 0L
        if (sizeBytes > 0L) "$progress · ${formatCacheSize(sizeBytes)}" else progress
    }
}

/** 字节数格式化为人类可读大小 (KB/MB/GB), 与 app 端 FileUtils.formatFileSize 行为一致 */
private fun formatCacheSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * 批量栏与缓存进度用到的文案。这些取值点都在普通函数 / 点击回调里 (非 Composition 期),
 * 无法调 stringResource, 故由 Composable 层一次性取好传入。
 */
private data class ManageLabels(
    val delete: String,
    val exportAll: String,
    val allowUpdate: String,
    val disableUpdate: String,
    val addToGroup: String,
    val exportBookshelf: String,
    val changeSourceBatch: String,
    val changeSource: String,
    val toc: String,
    val clearCache: String,
    val checkSelectedInterval: String,
    val selectOneBook: String,
    val loading: String,
    val downloadCount: String,
    val clearCacheSuccess: String,
    val localBook: String,
)

@Composable
private fun rememberManageLabels(): ManageLabels = ManageLabels(
    delete = stringResource(Res.string.delete),
    exportAll = stringResource(Res.string.export_all),
    allowUpdate = stringResource(Res.string.allow_update),
    disableUpdate = stringResource(Res.string.disable_update),
    addToGroup = stringResource(Res.string.add_to_group),
    exportBookshelf = stringResource(Res.string.export_bookshelf),
    changeSourceBatch = stringResource(Res.string.change_source_batch),
    changeSource = stringResource(Res.string.change_source),
    toc = stringResource(Res.string.chapter_list),
    clearCache = stringResource(Res.string.clear_cache),
    checkSelectedInterval = stringResource(Res.string.check_selected_interval),
    selectOneBook = stringResource(Res.string.select_one_book),
    loading = stringResource(Res.string.loading),
    downloadCount = stringResource(Res.string.download_count),
    clearCacheSuccess = stringResource(Res.string.clear_cache_success),
    localBook = stringResource(Res.string.local_book),
)

/** 批量栏溢出菜单项 (对照 app 端 selectActions) */
private fun buildSelectActions(
    screenModel: BookshelfManageScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
    labels: ManageLabels,
    onDeleteSelection: () -> Unit,
    onAddToGroup: () -> Unit,
    onShowSourcePicker: () -> Unit,
    onSelectToc: (Book) -> Unit,
    onChangeSourceRoute: (Book) -> Unit,
): List<SelectAction> {
    val platform = PlatformCapabilityProviders.get()
    return listOf(
        SelectAction(labels.delete) { onDeleteSelection() },
        // 导出全部: 委托平台, 传入选中书籍 (对照 exportAll)
        SelectAction(labels.exportAll) {
            platform.exportAllBooks(screenModel.selection())
        },
        SelectAction(labels.allowUpdate) {
            upCanUpdate(screenModel, scope, true)
        },
        SelectAction(labels.disableUpdate) {
            upCanUpdate(screenModel, scope, false)
        },
        SelectAction(labels.addToGroup) { onAddToGroup() },
        // 导出书架: 委托平台, 传入选中书籍 (对照 exportBookshelf)
        SelectAction(labels.exportBookshelf) {
            platform.exportBookshelf(screenModel.selection())
        },
        // 批量改源: 弹源选择对话框 (对照 showDialogFragment<SourcePickerDialog>)
        SelectAction(labels.changeSourceBatch) { onShowSourcePicker() },
        // 单本换源: 跳 ChangeSourceRoute 带 resultKey, 回传后批量换到该源
        SelectAction(labels.changeSource) {
            singleSelection(screenModel, labels.selectOneBook)?.let(onChangeSourceRoute)
        },
        // 目录选择: 跳 TocRoute 选章节定位
        SelectAction(labels.toc) {
            singleSelection(screenModel, labels.selectOneBook)?.let(onSelectToc)
        },
        SelectAction(labels.clearCache) {
            clearCache(screenModel, scope, labels.clearCacheSuccess)
        },
        SelectAction(labels.checkSelectedInterval) {
            screenModel.dispatch(BookshelfManageUiEvent.CheckSelectedInterval)
        },
    )
}

/** 取选中单本书; 非 1 本时 toast 提示并返回 null (目录选择/单本换源入口校验) */
private fun singleSelection(
    screenModel: BookshelfManageScreenModel,
    selectOneBookLabel: String,
): Book? {
    val selection = screenModel.selection()
    return if (selection.size == 1) {
        selection.first()
    } else {
        Toasters.get().toast(selectOneBookLabel)
        null
    }
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
    successLabel: String,
) {
    val selection = screenModel.selection()
    if (selection.isEmpty()) return
    scope.launch(IoDispatcher) {
        selection.forEach { BookStorageProviders.get().clearCache(it) }
        Toasters.get().toast(successLabel)
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
        title = stringResource(Res.string.draw),
        message = stringResource(Res.string.sure_del),
        okButton = AlertButton(stringResource(Res.string.ok)) {
            onConfirm(deleteFile.value)
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)) { onDismiss() },
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
                        text = stringResource(Res.string.delete_book_file),
                        color = AppTheme.colors.primaryText,
                    )
                }
            }
        } else null,
    )
}
