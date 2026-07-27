package io.legado.desktop.ui.book.import.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.book.import.remote.RemoteBookScreen as SharedRemoteBookScreen
import io.legado.app.ui.book.import.remote.RemoteBookSort
import io.legado.app.ui.book.import.remote.RemoteBookUiActions
import io.legado.app.ui.book.import.remote.RemoteBookUiState
import io.legado.app.ui.book.import.remote.RemoteBookViewModelShared
import io.legado.app.ui.book.import.remote.ServerConfigDialog
import io.legado.app.ui.book.import.remote.ServerConfigViewModelShared
import io.legado.app.ui.book.import.remote.ServersDialog
import io.legado.app.ui.book.import.remote.ServersViewModelShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 桌面端远程书籍 (WebDav) Screen 入口 (包装 shared/sharedUiMain 的 [SharedRemoteBookScreen])。
 *
 * 仅做平台适配: 注入 desktop 平台 Provider 让 commonMain 的 [AppTheme] / [SharedRemoteBookScreen]
 * 跨平台运行; 数据流由 [RemoteBookViewModelShared] 提供 (WebDav 客户端已下沉 commonMain)。
 *
 * @param onBack 返回回调 (由 DesktopApp 注入, 切回 BOOKSHELF 路由)
 * @param onStartRead 已上架书籍点击阅读启动回调 (由 DesktopApp 注入, 切到 READER 路由 +
 *   设置 readerBook, 对照 app 端 startReadBook)
 */
@Composable
fun RemoteBookScreen(onBack: () -> Unit, onStartRead: (Book) -> Unit = {}) {
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            RemoteBookContent(onBack = onBack, onStartRead = onStartRead)
        }
    }
}

@Composable
private fun RemoteBookContent(onBack: () -> Unit, onStartRead: (Book) -> Unit) {
    // 持有最新 onBack 引用, 避免 object 内 override fun onBack() 名称遮蔽导致递归调用
    val onBackUpdated = rememberUpdatedState(onBack)

    // KMP shared VM (注入 rememberCoroutineScope)。
    // serversShared/serverConfigShared 走 AppDbProviders + Toasters 管理服务器配置;
    // remoteBookShared 走已下沉的 WebDav + RemoteBook.create + FileBook.importRemoteBook,
    // 修复桌面端 items 永远为空问题。
    // 模式参考 BookInfoScreen.kt 的 `remember(scope) { XxxViewModelShared(scope) }`。
    val scope = rememberCoroutineScope()
    val serversShared = remember(scope) { ServersViewModelShared(scope = scope) }
    val serverConfigShared = remember(scope) { ServerConfigViewModelShared(scope = scope) }
    val remoteBookShared = remember(scope) {
        RemoteBookViewModelShared(scope = scope, onStartRead = onStartRead)
    }

    // 订阅 shared StateFlow 驱动 UI (对照 app 端 dataFlow.conflate().collect)
    val items by remoteBookShared.items.collectAsState()
    val path by remoteBookShared.currentPath.collectAsState()
    val loading by remoteBookShared.isLoading.collectAsState()

    // 初始化 WebDav 配置 + 拉取根目录列表 (对照 app 端 onActivityCreated 内 initData { upPath() })
    LaunchedEffect(Unit) {
        remoteBookShared.initData { remoteBookShared.upPath() }
    }

    // 服务器列表 (订阅 serverDao.observeAll flow, 与 app 端 ServersDialog LaunchedEffect 同源)
    val servers by produceState<List<Server>>(emptyList()) {
        AppDbProviders.get().serverDao.observeAll().collect { value = it }
    }
    val initialServerId = remember { AppConfigProviders.get().remoteServerId }

    // ServerConfigDialog 状态: null=新增, 非 null=编辑
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<Server?>(null) }
    var pendingEditServerId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingEditServerId) {
        pendingEditServerId?.let { id ->
            editingServer = AppDbProviders.get().serverDao.get(id)
            showServerConfigDialog = true
            pendingEditServerId = null
        }
    }

    // 提示文案 (actions lambda 非 @Composable, 需预先缓存)
    val webDavHelpNotImplementedLabel = rememberString("web_dav_help_not_implemented")
    val helpLabel = rememberString("help")
    val logViewNotImplementedLabel = rememberString("log_view_not_implemented")
    val logLabel = rememberString("log")
    val okLabel = rememberString("ok")
    // 重新加入书架确认对话框文案 (对照 app 端 R.string.sure 标题 + 硬编码消息)
    val sureLabel = rememberString("sure")
    val cancelLabel = rememberString("cancel")

    // 对话框显示状态
    var showServersDialog by remember { mutableStateOf(false) }
    var showWebDavHelpDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 重新加入书架确认对话框 (对照 app 端 addToBookShelfAgain 的 alert)
    var showReAddDialog by remember { mutableStateOf(false) }
    var reAddItem by remember { mutableStateOf<RemoteBook?>(null) }

    // 选中集 (桌面端本地状态, 对照 app 端 selected.value)
    var selected by remember { mutableStateOf<Set<RemoteBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 app 端 refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 搜索关键字 (shared VM 未暴露 filter 接口, 保留状态)
    var searchKey by remember { mutableStateOf("") }
    // 排序方式 UI 显示 (由 shared.sortCheck 回调同步, 驱动菜单选中态)
    var sortKeyState by remember { mutableStateOf(RemoteBookSort.Default) }

    // 空态: items 为空且非加载中 (对照 app 端 emptyMsgVisible = sortedRemoteBooks.isEmpty())
    val emptyMsgVisible = items.isEmpty() && !loading

    fun isCheckable(item: RemoteBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 进入子目录或返回根目录 (委托 shared VM, 对照 app 端 upPath + viewModel.loadRemoteBookList) */
    fun upPath() {
        remoteBookShared.upPath()
        selected = emptySet()
    }

    /** 排序切换 (委托 shared VM, 对照 app 端 sortCheck) */
    fun sortCheck(sortKey: RemoteBookSort) {
        remoteBookShared.sortCheck(sortKey) { newKey, _ ->
            sortKeyState = newKey
        }
    }

    // ---- UiActions 实现 (remember 持有稳定实例) ----
    val actions = remember {
        object : RemoteBookUiActions<RemoteBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                if (remoteBookShared.dirList.isEmpty()) onBackUpdated.value.invoke()
                else {
                    remoteBookShared.dirList.removeAt(remoteBookShared.dirList.lastIndex)
                    upPath()
                }
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                // TODO: shared VM 未暴露 filter 接口, 桌面端无过滤
            }

            override fun onUpPath() = upPath()

            override fun onSortCheck(sortKey: RemoteBookSort) = sortCheck(sortKey)

            override fun onShowServersDialog() {
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
                // 委托 shared VM (已下沉 FileBook.importRemoteBook + WebDav 下载)
                remoteBookShared.addSelectionToBookshelf(selected) {
                    selected = emptySet()
                    refreshTick++
                }
            }

            override fun onItemClick(item: RemoteBook) {
                when {
                    item.isUpDir -> {
                        if (remoteBookShared.dirList.isNotEmpty()) {
                            remoteBookShared.dirList.removeAt(remoteBookShared.dirList.lastIndex)
                            upPath()
                        }
                    }
                    item.isDir -> {
                        remoteBookShared.dirList.add(item)
                        upPath()
                    }
                    !item.isOnBookShelf -> {
                        // 切换选中 (对照 RemoteBookActivity.toggleSelect)
                        selected = if (item in selected) selected - item else selected + item
                    }
                    else -> {
                        // 已上架书籍点击启动阅读 (委托 shared VM, 对照 app 端 startRead)
                        remoteBookShared.startRead(item)
                    }
                }
            }

            override fun onItemLongClick(item: RemoteBook) {
                // 长按已上架书籍弹确认对话框重新加入书架 (对照 app 端 addToBookShelfAgain)
                if (!item.isUpDir && item.isOnBookShelf) {
                    reAddItem = item
                    showReAddDialog = true
                }
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

    // ---- ServersDialog (shared/sharedUiMain 下沉) ----
    if (showServersDialog) {
        ServersDialog(
            servers = servers,
            initialServerId = initialServerId,
            onAddServer = {
                editingServer = null
                showServerConfigDialog = true
            },
            onEditServer = { id ->
                pendingEditServerId = id
            },
            onDeleteServer = { server ->
                serversShared.delete(server)
            },
            onSelectDefault = {
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, DEFAULT_WEBDAV_ID)
                showServersDialog = false
                // 切换服务器后重新初始化 + 拉取列表 (对照 app 端 onDialogDismiss → initData { upPath() })
                remoteBookShared.initData { upPath() }
            },
            onConfirm = { selectedId ->
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, selectedId)
                showServersDialog = false
                remoteBookShared.initData { upPath() }
            },
            onDismiss = { showServersDialog = false },
        )
    }

    // ---- ServerConfigDialog (shared/sharedUiMain 下沉) ----
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

    // ---- 对话框渲染 (帮助/日志未实现提示) ----
    if (showWebDavHelpDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showWebDavHelpDialog = false },
            title = helpLabel,
            message = webDavHelpNotImplementedLabel,
            okButton = AlertButton(okLabel),
        )
    }
    if (showLogDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showLogDialog = false },
            title = logLabel,
            message = logViewNotImplementedLabel,
            okButton = AlertButton(okLabel),
        )
    }
    // 重新加入书架确认对话框 (对照 app 端 addToBookShelfAgain: alert(sure, "是否重新加入书架？"))
    if (showReAddDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showReAddDialog = false },
            title = sureLabel,
            message = "是否重新加入书架？",
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                reAddItem?.let { item ->
                    remoteBookShared.addSelectionToBookshelf(setOf(item)) {
                        refreshTick++
                    }
                }
                showReAddDialog = false
            },
            cancelButton = AlertButton(cancelLabel),
        )
    }
}
