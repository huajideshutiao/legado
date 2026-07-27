package io.legado.desktop.ui.book.import.remote

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.remote.RemoteBookScreen as SharedRemoteBookScreen
import io.legado.app.ui.book.import.remote.RemoteBookSort
import io.legado.app.ui.book.import.remote.RemoteBookUiActions
import io.legado.app.ui.book.import.remote.RemoteBookUiState
import io.legado.app.ui.book.import.remote.ServerConfigViewModelShared
import io.legado.app.ui.book.import.remote.ServersViewModelShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import java.io.File

/**
 * 桌面端远程书籍 (WebDav) Screen 入口 (包装 shared/sharedUiMain 的 [SharedRemoteBookScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `RemoteBookActivity`, 桌面端仅做平台适配, UI 渲染与交互骨架全部下沉到
 * shared/sharedUiMain 的 [SharedRemoteBookScreen]:
 *
 * - **平台 Provider 注入**: [DesktopThemeStoreProvider] / [DesktopAppConfigProvider] /
 *   [DesktopEventBusProvider] 经 [CompositionLocalProvider] 注入, 让 commonMain 的
 *   [AppTheme] / [SharedRemoteBookScreen] 跨平台运行
 * - **数据流**: 持有 [RemoteBookUiState] (immutable, copy 更新), actions 用 [remember]
 *   持有稳定实例避免重组
 * - **条目模型**: [DesktopRemoteBook] 替代 app 端
 *   [io.legado.app.model.remote.RemoteBook] (后者依赖 `WebDavFile`, 未下沉)
 *
 * # 简化项 (依赖未下沉功能, 用 TODO 注释 + no-op)
 *
 * - **WebDav 客户端**: app 端 `RemoteBookViewModel` 依赖 `WebDav` + `AbsWebDav`, 未下沉,
 *   桌面端 onUpPath 无法加载远程书籍列表, items 永远为空 (显示空态)
 * - **加入书架**: app 端 `RemoteBookViewModel.addToBookshelf` 依赖 `FileBook` +
 *   WebDav 下载, 未下沉, onAddSelectionToBookshelf no-op
 * - **服务器配置**: app 端 `ServersDialog` 依赖 `LocalConfig` + `webDavConfig`, 未下沉,
 *   onShowServersDialog 改用 [AlertDialog] 显示未实现提示 (替换原 javax.swing.JOptionPane)
 * - **帮助/日志**: app 端 `showHelp` / `AppLogDialog` 依赖 Activity, 未下沉,
 *   onShowWebDavHelp / onShowLogDialog 改用 [AlertDialog] 显示未实现提示 (替换原 javax.swing.JOptionPane)
 * - **打开书籍/压缩包**: app 端 `startRead` 依赖 `startActivityForBook` + `ArchiveUtils`,
 *   未下沉, onItemClick / onItemLongClick no-op
 * - **排序**: 排序状态 [sortKeyState] 持有 + 切换已实现, 但因 items 为空无实际效果
 *
 * @param onBack 返回回调 (由 DesktopApp 注入, 切回 BOOKSHELF 路由)
 */
@Composable
fun RemoteBookScreen(onBack: () -> Unit) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            RemoteBookContent(onBack = onBack)
        }
    }
}

/**
 * 桌面端远程书籍条目模型 (替代 app 端 RemoteBook)。
 *
 * app 端 [io.legado.app.model.remote.RemoteBook] 基于 `WebDavFile` (依赖 WebDav 客户端),
 * WebDav 客户端未下沉到 commonMain, 桌面端用本类替代:
 * - [tag] 取 [contentType] (与 app 端 RemoteBook.tag 一致)
 * - [itemKey] 取 [path] (与 app 端 RemoteBook.itemKey 一致)
 * - [lastModified] 桥接 [lastModify] (与 app 端 RemoteBook.lastModified 一致)
 * - [isOnBookShelf] 由加载时查询 `bookDao.hasFile(filename)` 填充
 *
 * 桌面端因 WebDav 客户端未下沉, 实际不会构造本类实例 (items 永远为空), 字段保留供
 * 后续接入 WebDav 后直接复用。
 */
private data class DesktopRemoteBook(
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
private fun RemoteBookContent(onBack: () -> Unit) {
    // 用 rememberUpdatedState 持有最新 onBack 引用, 避免 object 内 override fun onBack()
    // 名称遮蔽导致递归调用 (override 方法名与外层参数同名时用 .value.invoke() 消歧)
    val onBackUpdated = rememberUpdatedState(onBack)

    // KMP shared VM (注入 rememberCoroutineScope, 供 onShowServersDialog 等回调使用)。
    // 桌面端 ServersDialog / ServerConfigDialog Compose UI 未下沉, 当前 onShowServersDialog
    // 仍显示"未实现"AlertDialog; 但 shared VM 已接入 (delete / init / save 走 AppDbProviders
    // + Toasters), 后续 UI 下沉后可直接复用, 无需再改 VM 层。
    // 模式参考 BookInfoScreen.kt 的 `remember(scope) { XxxViewModelShared(scope) }`。
    val scope = rememberCoroutineScope()
    val serversShared = remember(scope) { ServersViewModelShared(scope = scope) }
    val serverConfigShared = remember(scope) { ServerConfigViewModelShared(scope = scope) }
    // 引用 shared VM 避免 unused 警告, 同时为后续 UI 下沉预留入口
    @Suppress("unused") val sharedVms = serversShared to serverConfigShared

    // WebDav/日志提示文案 (actions lambda 非 @Composable, 需预先缓存)
    val webDavConfigNotImplementedLabel = rememberString("web_dav_config_not_implemented")
    val serverConfigLabel = rememberString("server_config")
    val webDavHelpNotImplementedLabel = rememberString("web_dav_help_not_implemented")
    val helpLabel = rememberString("help")
    val logViewNotImplementedLabel = rememberString("log_view_not_implemented")
    val logLabel = rememberString("log")
    // AlertDialog 按钮文案 (替换原 JOptionPane, 与 BookSourceEditScreen 模式一致)
    val okLabel = rememberString("ok")

    // 信息展示对话框状态 (替换原 javax.swing.JOptionPane.showMessageDialog 同步阻塞,
    // 与 BookSourceEditScreen emptyUrlNameDialog 模式一致; 各 onShowXxx 方法触发显示,
    // 末尾 AlertDialog 渲染分支读取)
    var showServersDialog by remember { mutableStateOf(false) }
    var showWebDavHelpDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    // 列表条目 (桌面端 WebDav 客户端未下沉, 永远为空)
    var items by remember { mutableStateOf<List<DesktopRemoteBook>>(emptyList()) }
    // 选中集
    var selected by remember { mutableStateOf<Set<DesktopRemoteBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 RemoteBookActivity.refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 子目录栈 (面包屑路径用, 空表示在根目录)
    val dirList = remember { mutableListOf<DesktopRemoteBook>() }
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
    // 排序升降序 (对照 RemoteBookViewModel.sortAscending, 桌面端虽无数据但保留状态)
    var sortAscending by remember { mutableStateOf(true) }

    fun isCheckable(item: DesktopRemoteBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 加载远程书籍列表 (对照 RemoteBookActivity.upPath + RemoteBookViewModel.loadRemoteBookList) */
    fun upPath() {
        // TODO: 依赖 RemoteBookViewModel.loadRemoteBookList + WebDav 客户端, 未下沉
        //  桌面端暂不实现远程书籍加载, items 保持空, 显示空态
        var p = "books" + File.separator
        dirList.forEach { p += it.filename + File.separator }
        path = p
        selected = emptySet()
        loading = false
        emptyMsgVisible = true
        AppLog.put("远程书籍: WebDav 客户端未下沉, 桌面端暂不加载远程列表")
    }

    /** 排序切换 (对照 RemoteBookActivity.sortCheck) */
    fun sortCheck(sortKey: RemoteBookSort) {
        if (sortKeyState == sortKey) {
            sortAscending = !sortAscending
        } else {
            sortAscending = true
            sortKeyState = sortKey
        }
        // 桌面端 items 为空, 排序切换无实际效果, 仅更新状态
        upPath()
    }

    // ---- UiActions 实现 (remember 持有稳定实例) ----
    val actions = remember {
        object : RemoteBookUiActions<DesktopRemoteBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                // 用 onBackUpdated.value.invoke() 消歧, 避免递归调用 override fun onBack()
                if (dirList.isEmpty()) onBackUpdated.value.invoke()
                else {
                    dirList.removeAt(dirList.lastIndex)
                    upPath()
                }
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                // TODO: app 端调 viewModel.updateCallBackFlow(newText) 重过滤, 桌面端无数据 no-op
            }

            override fun onUpPath() = upPath()

            override fun onSortCheck(sortKey: RemoteBookSort) = sortCheck(sortKey)

            override fun onShowServersDialog() {
                // ServersViewModelShared / ServerConfigViewModelShared 已接入 (见上方
                // serversShared / serverConfigShared), delete / init / save 走 AppDbProviders
                // + Toasters, UI 下沉后可直接复用。当前 ServersDialog / ServerConfigDialog
                // Compose UI 未下沉, 暂触发 AlertDialog 显示未实现提示
                // (替换原 JOptionPane.showMessageDialog 同步阻塞;
                // 末尾 AlertDialog 渲染分支读取 showServersDialog)
                showServersDialog = true
            }

            override fun onShowWebDavHelp() {
                // TODO: 依赖 showHelp (Activity), 未下沉
                // 触发 AlertDialog 显示 (替换原 JOptionPane.showMessageDialog 同步阻塞;
                // 末尾 AlertDialog 渲染分支读取 showWebDavHelpDialog)
                showWebDavHelpDialog = true
            }

            override fun onShowLogDialog() {
                // TODO: 依赖 AppLogDialog, 未下沉
                // 触发 AlertDialog 显示 (替换原 JOptionPane.showMessageDialog 同步阻塞;
                // 末尾 AlertDialog 渲染分支读取 showLogDialog)
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

            override fun onItemClick(item: DesktopRemoteBook) {
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

            override fun onItemLongClick(item: DesktopRemoteBook) {
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

    // ---- AlertDialog 渲染 (替换原 javax.swing.JOptionPane.showMessageDialog) ----
    // 三个信息展示对话框 (各 onShowXxx 方法触发对应 showXxxDialog=true;
    //   text 用 @Composable rememberString 缓存的文案, confirmButton 用 okLabel,
    //   与 BookSourceEditScreen emptyUrlNameDialog 模式一致)
    if (showServersDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showServersDialog = false },
            title = { Text(serverConfigLabel) },
            text = { Text(webDavConfigNotImplementedLabel) },
            confirmButton = {
                TextButton(onClick = { showServersDialog = false }) {
                    Text(okLabel)
                }
            },
        )
    }
    if (showWebDavHelpDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showWebDavHelpDialog = false },
            title = { Text(helpLabel) },
            text = { Text(webDavHelpNotImplementedLabel) },
            confirmButton = {
                TextButton(onClick = { showWebDavHelpDialog = false }) {
                    Text(okLabel)
                }
            },
        )
    }
    if (showLogDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showLogDialog = false },
            title = { Text(logLabel) },
            text = { Text(logViewNotImplementedLabel) },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text(okLabel)
                }
            },
        )
    }
}
