package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.import.remote.RemoteBookScreen
import io.legado.app.ui.book.import.remote.RemoteBookSort
import io.legado.app.ui.book.import.remote.RemoteBookUiActions
import io.legado.app.ui.book.import.remote.RemoteBookUiState
import io.legado.app.ui.book.import.remote.RemoteBookViewModelShared
import io.legado.app.ui.book.import.remote.ServerConfigDialog
import io.legado.app.ui.book.import.remote.ServerConfigViewModelShared
import io.legado.app.ui.book.import.remote.ServersDialog
import io.legado.app.ui.book.import.remote.ServersViewModelShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.HelpDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 远程书籍 shared 路由入口。
 *
 * 通过 [RemoteBookViewModelShared] 承载 WebDav 连接与文件操作, 渲染 [RemoteBookScreen]。
 * [onStartRead] 由 VM 在已上架书籍点击时触发 (对照 app 端 startReadBook)。
 * 服务器配置/帮助/日志弹窗在路由内以本地状态驱动, 复用已下沉的 shared 端 Dialog 组件。
 */
@Composable
fun RemoteBookRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val scope = rememberCoroutineScope()
    // 已上架书籍点击阅读: 切到 Reader 路由
    val onStartRead: (Book) -> Unit = { book -> navigator.push(AppRoute.Reader(book.toRouteRef())) }
    val shared = remember(scope) {
        RemoteBookViewModelShared(scope = scope, onStartRead = onStartRead)
    }
    val items by shared.items.collectAsState()
    val path by shared.currentPath.collectAsState()
    val loading by shared.isLoading.collectAsState()

    var selected by remember { mutableStateOf<Set<RemoteBook>>(emptySet()) }
    var refreshTick by remember { mutableStateOf(0) }
    var searchKey by remember { mutableStateOf("") }
    var sortKeyState by remember { mutableStateOf(RemoteBookSort.Default) }

    // 弹窗显示状态 (服务器配置 / WebDav 帮助 / 日志 / 重新加入书架确认 / 服务器编辑)
    var showServersDialog by remember { mutableStateOf(false) }
    var showWebDavHelp by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var reAddTarget by remember { mutableStateOf<RemoteBook?>(null) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var editingServerId by remember { mutableStateOf<Long?>(null) }

    val appDb = remember { AppDbProviders.get() }
    val servers by appDb.serverDao.observeAll().collectAsState(initial = emptyList())
    val serversVm = remember(scope) { ServersViewModelShared(scope) }
    val serverConfigVm = remember(scope) { ServerConfigViewModelShared(scope) }

    // 对照 RemoteBookActivity.onActivityCreated: 首次帮助 + initData + upPath
    LaunchedEffect(Unit) {
        val prefs = PreferenceProviders.get()
        val isLastHelp = LocalConfigShared.isLastVersion(
            lastVersion = HelpVersion.webDavBookHelp,
            versionKey = LocalConfigKeys.webDavBookHelpVersion,
            firstOpenKey = LocalConfigKeys.firstOpenWebDavBook,
            getInt = prefs::getInt,
            getBoolean = prefs::getBoolean,
            putInt = prefs::putInt,
        )
        if (!isLastHelp) showWebDavHelp = true
        shared.initData { shared.upPath() }
    }

    val emptyMsgVisible = items.isEmpty() && !loading
    fun isCheckable(item: RemoteBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    val actions = object : RemoteBookUiActions<RemoteBook> {
        override fun onBack() {
            navigator.pop()
        }

        // 对照 BaseImportBookActivity.upSearchKey: 更新本地关键字 + VM 过滤
        override fun onUpSearchKey(key: String) {
            searchKey = key
            shared.updateFilter(key)
        }

        // 对照 app upPath: 清空选中 + 重新拉取当前目录
        override fun onUpPath() {
            selected = emptySet()
            shared.upPath()
        }

        // 对照 app sortCheck: 切换排序状态后 upPath 重新请求
        override fun onSortCheck(sortKey: RemoteBookSort) {
            shared.sortCheck(
                newSortKey = sortKey,
                onSortChanged = { newKey, _ -> sortKeyState = newKey },
                reorderCurrent = false,
            )
            selected = emptySet()
            shared.upPath()
        }

        override fun onShowServersDialog() {
            showServersDialog = true
        }

        override fun onShowWebDavHelp() {
            showWebDavHelp = true
        }

        override fun onShowLogDialog() {
            showLogDialog = true
        }

        // 对照 app selectAll: 全选可勾选项或清空
        override fun onSelectAll(selectAll: Boolean) {
            selected = if (selectAll) items.filter { isCheckable(it) }.toSet() else emptySet()
        }

        // 对照 app revertSelection: 可勾选项集合减去当前选中
        override fun onRevertSelection() {
            selected = items.filter { isCheckable(it) }.toSet() - selected
        }

        // 对照 app addSelectionToBookshelf: 导入完成后清空选中 + 刷新 tick
        override fun onAddSelectionToBookshelf() {
            shared.addSelectionToBookshelf(selected) {
                selected = emptySet()
                refreshTick++
            }
        }

        // 对照 app onItemClick: 上级目录/子目录/勾选/已上架阅读
        override fun onItemClick(item: RemoteBook) {
            when {
                item.isUpDir -> {
                    if (shared.dirList.isNotEmpty()) {
                        shared.dirList.removeLastOrNull()
                        selected = emptySet()
                        shared.upPath()
                    }
                }

                item.isDir -> {
                    shared.dirList.add(item)
                    selected = emptySet()
                    shared.upPath()
                }

                !item.isOnBookShelf -> {
                    selected = if (item in selected) selected - item else selected + item
                }

                else -> shared.startRead(item)
            }
        }

        // 对照 app onItemLongClick: 已上架书籍长按弹重新加入书架确认
        override fun onItemLongClick(item: RemoteBook) {
            if (!item.isUpDir && item.isOnBookShelf) {
                reAddTarget = item
            }
        }
    }

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
    RemoteBookScreen(state = state, actions = actions)

    // 服务器配置对话框 (对照 app showDialogFragment<ServersDialog>)
    if (showServersDialog) {
        val initialServerId = remember { AppConfigProviders.get().remoteServerId }
        ServersDialog(
            servers = servers,
            initialServerId = initialServerId,
            onAddServer = {
                showServersDialog = false
                editingServerId = null
                showServerConfigDialog = true
            },
            onEditServer = { id ->
                showServersDialog = false
                editingServerId = id
                showServerConfigDialog = true
            },
            onDeleteServer = { server ->
                serversVm.delete(server)
            },
            onSelectDefault = {
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, DEFAULT_WEBDAV_ID)
                showServersDialog = false
                selected = emptySet()
                shared.initData { shared.upPath() }
            },
            onConfirm = { id ->
                PreferenceProviders.get().putLong(PreferKey.remoteServerId, id)
                showServersDialog = false
                selected = emptySet()
                shared.initData { shared.upPath() }
            },
            // 对照 app onDialogDismiss: 弹窗关闭后重新 initData + upPath
            onDismiss = {
                showServersDialog = false
                selected = emptySet()
                shared.initData { shared.upPath() }
            },
        )
    }

    // 服务器配置编辑对话框 (对照 app showDialogFragment<ServerConfigDialog>)
    if (showServerConfigDialog) {
        // var delegate 不支持智能转换, 取局部 val 让 id 在 else 分支收敛为 Long
        val editingServer by produceState<Server?>(null, editingServerId) {
            val id = editingServerId
            value = if (id == null) Server() else appDb.serverDao.get(id)
        }
        ServerConfigDialog(
            server = editingServer,
            onSave = { server ->
                serverConfigVm.save(server) {
                    showServerConfigDialog = false
                    // 对照 app onDialogDismiss: 保存后重新 initData + upPath
                    selected = emptySet()
                    shared.initData { shared.upPath() }
                }
            },
            onDismiss = {
                showServerConfigDialog = false
                // 对照 app onDialogDismiss: 关闭后重新 initData + upPath
                selected = emptySet()
                shared.initData { shared.upPath() }
            },
        )
    }

    // WebDav 帮助对话框 (对照 app showHelp("webDavBookHelp"))
    if (showWebDavHelp) {
        HelpDialog(fileName = "webDavBookHelp", onDismiss = { showWebDavHelp = false })
    }

    // 应用日志对话框 (对照 app showDialogFragment<AppLogDialog>)
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 重新加入书架确认框 (对照 app addToBookShelfAgain: 标题 R.string.sure, yesButton/noButton)
    reAddTarget?.let { target ->
        AppAlertDialog(
            onDismissRequest = { reAddTarget = null },
            title = stringResource(Res.string.ok),
            message = "是否重新加入书架？",
            okButton = AlertButton(
                text = stringResource(Res.string.yes),
                onClick = {
                    shared.addSelectionToBookshelf(setOf(target)) {
                        reAddTarget = null
                    }
                },
            ),
            cancelButton = AlertButton(text = stringResource(Res.string.no)),
        )
    }
}
