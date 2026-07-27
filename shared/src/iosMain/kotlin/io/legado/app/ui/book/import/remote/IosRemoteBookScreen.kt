package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.remote.RemoteBookScreen as SharedRemoteBookScreen
import io.legado.app.ui.book.import.remote.RemoteBookSort
import io.legado.app.ui.book.import.remote.RemoteBookUiActions
import io.legado.app.ui.book.import.remote.RemoteBookUiState
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端远程书籍 (WebDav) Screen 入口 (包装 shared/sharedUiMain 的 [SharedRemoteBookScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `RemoteBookActivity` / desktop `RemoteBookScreen.kt`, iOS 端仅做平台适配,
 * UI 渲染与交互骨架全部下沉到 shared/sharedUiMain 的 [SharedRemoteBookScreen]:
 *
 * - **数据流**: 持有 [RemoteBookUiState] (immutable, copy 更新), actions 用 [remember]
 *   持有稳定实例避免重组 (与 desktop 一致; iOS Provider 已在 MainViewController 顶层注入,
 *   不需要再嵌套 CompositionLocalProvider)
 * - **条目模型**: [IosRemoteBook] 替代 app 端
 *   [io.legado.app.model.remote.RemoteBook] (后者依赖 `WebDavFile`, 未下沉)
 *
 * # 简化项 (依赖未下沉功能, 用 TODO 注释 + no-op)
 *
 * - **WebDav 客户端**: app 端 `RemoteBookViewModel` 依赖 `WebDav` + `AbsWebDav`, 未下沉,
 *   iOS 端 onUpPath 无法加载远程书籍列表, items 永远为空 (显示空态)
 * - **加入书架**: app 端 `RemoteBookViewModel.addToBookshelf` 依赖 `FileBook` +
 *   WebDav 下载, 未下沉, onAddSelectionToBookshelf 仅标记 isOnBookShelf=true + refreshTick++
 * - **服务器配置**: 已接入 shared/sharedUiMain 下沉的 [ServersDialog] / [ServerConfigDialog],
 *   增删改查全链路打通 (ServersViewModelShared / ServerConfigViewModelShared + AppDbProviders);
 *   切换服务器后写 PreferKey.remoteServerId, 待 RemoteBookViewModelShared 接入后刷新书籍列表
 * - **帮助/日志**: app 端 `showHelp` / `AppLogDialog` 依赖 Activity, 未下沉,
 *   onShowWebDavHelp / onShowLogDialog 改用 [AppAlertDialog] 显示未实现提示 (与 desktop 一致)
 * - **打开书籍/压缩包**: app 端 `startRead` 依赖 `startActivityForBook` + `ArchiveUtils`,
 *   未下沉, onItemClick / onItemLongClick no-op
 * - **排序**: 排序状态 [sortKeyState] 持有 + 切换已实现, 但因 items 为空无实际效果
 *
 * @param onBack 返回回调 (由 IosNavHost 注入, 切回 BOOKSHELF 路由)
 */
@Composable
fun IosRemoteBookScreen(onBack: () -> Unit) {
    IosRemoteBookContent(onBack = onBack)
}

/**
 * iOS 端远程书籍条目模型 (替代 app 端 RemoteBook; 与 desktop `DesktopRemoteBook` 字段一致)。
 *
 * app 端 [io.legado.app.model.remote.RemoteBook] 基于 `WebDavFile` (依赖 WebDav 客户端),
 * WebDav 客户端未下沉到 commonMain, iOS 端用本类替代:
 * - [tag] 取 [contentType] (与 app 端 RemoteBook.tag 一致)
 * - [itemKey] 取 [path] (与 app 端 RemoteBook.itemKey 一致)
 * - [lastModified] 桥接 [lastModify] (与 app 端 RemoteBook.lastModified 一致)
 * - [isOnBookShelf] 由加载时查询 `bookDao.hasFile(filename)` 填充
 *
 * iOS 端因 WebDav 客户端未下沉, 实际不会构造本类实例 (items 永远为空), 字段保留供
 * 后续接入 WebDav 后直接复用。
 */
private data class IosRemoteBook(
    val filename: String,
    val path: String,
    override val size: Long,
    val lastModify: Long,
    var contentType: String = "folder",
    override var isOnBookShelf: Boolean = false,
    override val isUpDir: Boolean = false,
) : ImportFileItem {
    override val isDir: Boolean get() = contentType == "folder" && !isUpDir
    override val name: String get() = if (isUpDir) ".." else filename
    override val lastModified: Long get() = lastModify
    override val tag: String get() = contentType
    override val itemKey: Any get() = path
}

@Composable
private fun IosRemoteBookContent(onBack: () -> Unit) {
    // 持有最新 onBack 引用, 避免 object 内 override fun onBack() 名称遮蔽导致递归调用
    val onBackUpdated = rememberUpdatedState(onBack)

    // KMP shared VM (注入 rememberCoroutineScope, 供 ServersDialog / ServerConfigDialog 回调使用)。
    // serversShared.delete / serverConfigShared.save 走 AppDbProviders + Toasters,
    // iOS 端直接复用 shared/sharedUiMain 下沉的 ServersDialog / ServerConfigDialog Composable。
    val scope = rememberCoroutineScope()
    val serversShared = remember(scope) { ServersViewModelShared(scope = scope) }
    val serverConfigShared = remember(scope) { ServerConfigViewModelShared(scope = scope) }

    // 服务器列表 (订阅 serverDao.observeAll flow, 与 app 端 ServersDialog LaunchedEffect 同源)
    val servers by produceState<List<Server>>(emptyList()) {
        AppDbProviders.get().serverDao.observeAll().collect { value = it }
    }
    // 进入 ServersDialog 时选中的服务器 ID (读 AppConfigProviders, 与 app 端 AppConfig.remoteServerId 同源)
    val initialServerId = remember { AppConfigProviders.get().remoteServerId }

    // ServerConfigDialog 状态:
    // - showServerConfigDialog: 是否显示
    // - editingServer: 待编辑服务器 (null=新增, 非 null=编辑已加载的 Server)
    // - pendingEditServerId: 用户点击编辑后异步加载目标 Server 的 id (LaunchedEffect 消费后清空)
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<Server?>(null) }
    var pendingEditServerId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingEditServerId) {
        // 用户在 ServersDialog 列表点编辑 → 异步按 id 加载 Server → 显示 ServerConfigDialog
        pendingEditServerId?.let { id ->
            editingServer = AppDbProviders.get().serverDao.get(id)
            showServerConfigDialog = true
            pendingEditServerId = null
        }
    }

    // WebDav/日志提示文案 (actions lambda 非 @Composable, 需预先缓存)
    val webDavHelpNotImplementedLabel = rememberString("web_dav_help_not_implemented")
    val helpLabel = rememberString("help")
    val logViewNotImplementedLabel = rememberString("log_view_not_implemented")
    val logLabel = rememberString("log")
    val okLabel = rememberString("ok")
    val webDavClientNotAvailableLogText = rememberString("ios_webdav_client_not_available_log")

    // 信息展示对话框状态 (各 onShowXxx 方法触发显示, 末尾 AlertDialog 渲染分支读取)
    var showServersDialog by remember { mutableStateOf(false) }
    var showWebDavHelpDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    // 列表条目 (iOS 端 WebDav 客户端未下沉, 永远为空)
    var items by remember { mutableStateOf<List<IosRemoteBook>>(emptyList()) }
    // 选中集
    var selected by remember { mutableStateOf<Set<IosRemoteBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 RemoteBookActivity.refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 子目录栈 (面包屑路径用, 空表示在根目录)
    val dirList = remember { mutableListOf<IosRemoteBook>() }
    // 面包屑路径 (null 时不显示)
    var path by remember { mutableStateOf<String?>(null) }
    // 加载条
    var loading by remember { mutableStateOf(false) }
    // 空态提示 (初始 true, 因 WebDav 未接入直接显示空态)
    var emptyMsgVisible by remember { mutableStateOf(true) }
    // 搜索关键字
    var searchKey by remember { mutableStateOf("") }
    // 排序方式: Default=按更新时间 / Name=按名称
    var sortKeyState by remember { mutableStateOf(RemoteBookSort.Default) }
    // 排序升降序 (对照 RemoteBookViewModel.sortAscending, iOS 端虽无数据但保留状态)
    var sortAscending by remember { mutableStateOf(true) }

    fun isCheckable(item: IosRemoteBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 加载远程书籍列表 (对照 RemoteBookActivity.upPath + RemoteBookViewModel.loadRemoteBookList) */
    fun upPath() {
        // TODO: 依赖 RemoteBookViewModel.loadRemoteBookList + WebDav 客户端, 未下沉
        //  iOS 端暂不实现远程书籍加载, items 保持空, 显示空态
        var p = "books/"
        dirList.forEach { p += it.filename + "/" }
        path = p
        selected = emptySet()
        loading = false
        emptyMsgVisible = true
        AppLog.put(webDavClientNotAvailableLogText)
    }

    /** 排序切换 (对照 RemoteBookActivity.sortCheck) */
    fun sortCheck(sortKey: RemoteBookSort) {
        if (sortKeyState == sortKey) {
            sortAscending = !sortAscending
        } else {
            sortAscending = true
            sortKeyState = sortKey
        }
        // iOS 端 items 为空, 排序切换无实际效果, 仅更新状态
        upPath()
    }

    // ---- UiActions 实现 (remember 持有稳定实例) ----
    val actions = remember {
        object : RemoteBookUiActions<IosRemoteBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                if (dirList.isEmpty()) onBackUpdated.value.invoke()
                else {
                    dirList.removeAt(dirList.lastIndex)
                    upPath()
                }
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                // TODO: app 端调 viewModel.updateCallBackFlow(newText) 重过滤, iOS 端无数据 no-op
            }

            override fun onUpPath() = upPath()

            override fun onSortCheck(sortKey: RemoteBookSort) = sortCheck(sortKey)

            override fun onShowServersDialog() {
                // 触发 shared/sharedUiMain ServersDialog 渲染 (末尾 ServersDialog 分支读取 showServersDialog)
                showServersDialog = true
            }

            override fun onShowWebDavHelp() {
                // TODO: 依赖 showHelp (Activity), 未下沉
                showWebDavHelpDialog = true
            }

            override fun onShowLogDialog() {
                // TODO: 依赖 AppLogDialog, 未下沉
                showLogDialog = true
            }

            override fun onSelectAll(selectAll: Boolean) {
                selected = if (selectAll) items.filter { isCheckable(it) }.toSet() else emptySet()
            }

            override fun onRevertSelection() {
                selected = items.filter { isCheckable(it) }.toSet() - selected
            }

            override fun onAddSelectionToBookshelf() {
                // TODO: 依赖 RemoteBookViewModel.addToBookshelf + FileBook + WebDav 下载, 未下沉
                val books = selected.toHashSet()
                books.forEach { it.isOnBookShelf = true }
                selected = emptySet()
                refreshTick++
            }

            override fun onItemClick(item: IosRemoteBook) {
                when {
                    item.isUpDir -> {
                        if (dirList.isNotEmpty()) {
                            dirList.removeAt(dirList.lastIndex)
                            upPath()
                        }
                    }
                    item.isDir -> {
                        dirList.add(item)
                        upPath()
                    }
                    !item.isOnBookShelf -> {
                        // 切换选中 (对照 RemoteBookActivity.toggleSelect)
                        selected = if (item in selected) selected - item else selected + item
                    }
                    else -> {
                        // TODO: app 端 startRead(item) 依赖 startActivityForBook + ArchiveUtils, 未下沉
                    }
                }
            }

            override fun onItemLongClick(item: IosRemoteBook) {
                // TODO: app 端 addToBookShelfAgain(item) 依赖 alert 弹窗 + viewModel.addToBookshelf, 未下沉
            }
        }
    }

    // ---- 渲染 shared Screen ----
    val state = RemoteBookUiState(
        items = items,
        selected = selected,
        refreshTick = refreshTick,
        path = path,
        loading = loading,
        emptyMsgVisible = emptyMsgVisible,
        searchKey = searchKey,
        checkableCount = checkableCount,
        sortKeyState = sortKeyState,
    )
    SharedRemoteBookScreen(state, actions)

    // ---- ServersDialog (shared/sharedUiMain 下沉, 替换原"未实现"AlertDialog) ----
    // onShowServersDialog 触发 showServersDialog=true; 调用方注入 servers/initialServerId/回调,
    // 服务器列表 + 选中态 + 增删改全部由 shared ServersDialog 内部管理, iOS 端仅注入数据与回调。
    if (showServersDialog) {
        ServersDialog(
            servers = servers,
            initialServerId = initialServerId,
            onAddServer = {
                // 新增: editingServer=null 标记新增, 显示 ServerConfigDialog
                editingServer = null
                showServerConfigDialog = true
            },
            onEditServer = { id ->
                // 编辑: 触发 LaunchedEffect(pendingEditServerId) 异步按 id 加载 Server
                pendingEditServerId = id
            },
            onDeleteServer = { server ->
                // 删除: 委托 ServersViewModelShared.delete (走 AppDbProviders + Dispatchers.IO)
                serversShared.delete(server)
            },
            onSelectDefault = {
                // 切回默认 webdav (坚果云): 写 PreferKey.remoteServerId = DEFAULT_WEBDAV_ID
                // (iOS AppConfigAccessor 为只读, 直接写 PreferenceProvider, 与 desktop 一致)
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, DEFAULT_WEBDAV_ID)
                showServersDialog = false
                // TODO: 接入 RemoteBookViewModelShared 后调 initData { upPath() } 刷新书籍列表
            },
            onConfirm = { selectedId ->
                // 确定选中: 写 PreferKey.remoteServerId = selectedId
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, selectedId)
                showServersDialog = false
                // TODO: 接入 RemoteBookViewModelShared 后调 initData { upPath() } 刷新书籍列表
            },
            onDismiss = { showServersDialog = false },
        )
    }

    // ---- ServerConfigDialog (shared/sharedUiMain 下沉, 替换原"未实现"AlertDialog) ----
    // onAddServer / onEditServer 触发 showServerConfigDialog=true; editingServer=null=新增, 非 null=编辑。
    // 保存委托 ServerConfigViewModelShared.save (内部 delete 旧 + insert 新, 与 app 端一致)。
    if (showServerConfigDialog) {
        ServerConfigDialog(
            server = editingServer,
            onSave = { newServer ->
                serverConfigShared.save(newServer) {
                    showServerConfigDialog = false
                }
            },
            onDismiss = { showServerConfigDialog = false },
        )
    }

    // ---- AppAlertDialog 渲染 (剩余两个未实现提示, 帮助/日志依赖未下沉) ----
    if (showWebDavHelpDialog) {
        AppAlertDialog(
            onDismissRequest = { showWebDavHelpDialog = false },
            title = helpLabel,
            message = webDavHelpNotImplementedLabel,
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                showWebDavHelpDialog = false
            },
        )
    }
    if (showLogDialog) {
        AppAlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = logLabel,
            message = logViewNotImplementedLabel,
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                showLogDialog = false
            },
        )
    }
}
