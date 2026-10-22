package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBookScreen
import io.legado.app.ui.book.import.local.ImportBookScreenModel
import io.legado.app.ui.book.import.local.ImportBookUiActions
import io.legado.app.ui.book.import.local.ImportBookUiEvent
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入本地书籍 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ImportBookScreenModel], 渲染 [ImportBookScreen];
 * 可下沉状态 (selected/searchKey/sortState/refreshTick) 由 ScreenModel 托管,
 * 平台专属状态 (items/path/loading/emptyMsgVisible) 由 [PlatformCapabilityProviders]
 * 推送到 ScreenModel, 平台专属动作 (选择目录/扫描/上架/进入子目录/打开阅读页/过滤/排序)
 * 经 [PlatformCapabilityProviders] 派发到各端实现。
 *
 * 对照 app 端 [io.legado.app.ui.book.import.local.ImportBookActivity]。
 */
@Composable
fun ImportBookRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { ImportBookScreenModel() }
    val platform = PlatformCapabilityProviders.get()

    // 平台专属状态 (对照 Activity items/path/loading/emptyMsgVisible)
    val items by platform.importBookItems().collectAsState()
    val path by platform.importBookPath().collectAsState()
    val loading by platform.importBookLoading().collectAsState()
    val emptyMsgVisible by platform.importBookEmptyMsgVisible().collectAsState()

    // 离开页面时停掉本页发起的目录扫描 (对照原版 Activity 销毁即取消 scanDocJob)
    DisposableEffect(entry.id) {
        onDispose { platform.onImportBookPageCleared() }
    }

    // 平台状态推送到 ScreenModel, 供 selection/checkableCount 计算
    LaunchedEffect(items, path, loading, emptyMsgVisible) {
        screenModel.updatePlatformState(items, path, loading, emptyMsgVisible)
    }

    // 对照 onActivityCreated: sortState = viewModel.sort, 初始化目录 (原版 initData() 无条件执行);
    // 初始化自报是否还没有可用目录, 是则拉起一次选择器 (目录选择是导入本地书入口自身的语义,
    // 随页面进入即生效; 外部文件打开不经本页, 交启动请求消费侧直接分发, 见 LegadoApp ImportFile)
    LaunchedEffect(Unit) {
        screenModel.dispatch(ImportBookUiEvent.SetSort(AppConfigProviders.get().localBookImportSort))
        if (platform.initImportBookData()) platform.pickImportFolder()
    }

    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    val actions = object : ImportBookUiActions<ImportFileItem> {
        override fun onBack() {
            navigator.pop()
        }

        // 对照 upSearchKey: 更新搜索关键字 + VM 过滤
        override fun onUpSearchKey(key: String) {
            screenModel.dispatch(ImportBookUiEvent.SetSearchKey(key))
            platform.updateImportBookFilter(key)
        }

        override fun onPickFolder() {
            platform.pickImportFolder()
        }

        // 选文件导入: 面板多选 → 应用侧按扩展名复核 (面板过滤只是收敛可选范围) → 直接上架,
        // 不经列表勾选 (书放在提供方根目录这类选不到目录的位置也能导入)
        override fun onPickFiles() {
            scope.launch {
                val picked = withContext(IoDispatcher) {
                    PlatformServiceProviders.get().files.pickFiles(FileFilter.Books)
                }
                if (picked.isEmpty()) return@launch
                val (supported, unsupported) = picked.partition {
                    it.name.matches(AppPattern.bookFileRegex) ||
                        it.name.matches(AppPattern.archiveFileRegex)
                }
                unsupported.forEach { Toasters.get().toast("不支持的文件: ${it.name}") }
                if (supported.isEmpty()) return@launch
                // 条目构造要读文件属性 (含路径映射), 推到 IO; 导入本身在平台自己的作用域里跑
                withContext(IoDispatcher) {
                    platform.importPickedBookFiles(supported) {
                        screenModel.dispatch(ImportBookUiEvent.SelectAll(false))
                        screenModel.dispatch(ImportBookUiEvent.BumpRefreshTick)
                    }
                }
            }
        }

        // 对照 upSort: 更新排序 + 持久化 + 重排当前列表
        override fun onUpSort(sort: Int) {
            screenModel.dispatch(ImportBookUiEvent.SetSort(sort))
            platform.updateImportBookSort(sort)
        }

        override fun onScanFolder() {
            platform.scanImportFolder()
        }

        override fun onAlertImportFileName() {
            platform.alertImportFileName()
        }

        override fun onSelectAll(selectAll: Boolean) {
            screenModel.dispatch(ImportBookUiEvent.SelectAll(selectAll))
        }

        override fun onRevertSelection() {
            screenModel.dispatch(ImportBookUiEvent.RevertSelection)
        }

        // 对照 addSelectionToBookshelf: 上架成功后清空 selected + refreshTick++
        override fun onAddSelectionToBookshelf() {
            platform.addImportSelectionToBookshelf(state.selected.toList()) {
                screenModel.dispatch(ImportBookUiEvent.SelectAll(false))
                screenModel.dispatch(ImportBookUiEvent.BumpRefreshTick)
            }
        }

        // 对照 onItemClick: isUpDir→goBackDir / isDir→nextDoc /
        // !isOnBookShelf→toggleSelect / else→startRead
        // upPath 会清空 selected, 所以 isUpDir/isDir 分支也清空
        override fun onItemClick(item: ImportFileItem) {
            when {
                item.isUpDir -> {
                    platform.goBackImportDir()
                    screenModel.dispatch(ImportBookUiEvent.SelectAll(false))
                }

                item.isDir -> {
                    platform.navigateImportDir(item)
                    screenModel.dispatch(ImportBookUiEvent.SelectAll(false))
                }

                !item.isOnBookShelf -> screenModel.dispatch(ImportBookUiEvent.Toggle(item))
                else -> platform.openImportedBookReader(item)
            }
        }

        // 对照 onItemLongClick: 可勾选条目长按打开阅读页
        override fun onItemLongClick(item: ImportFileItem) {
            if (!item.isUpDir && !item.isDir && !item.isOnBookShelf) {
                platform.openImportedBookReader(item)
            }
        }
    }
    // 对照原版 setupBackPress { goBackDir() }: 有上级目录时返回键逐级返回, 到根目录才退出页面
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    val hasParentDir by platform.importBookHasParentDir().collectAsState()
    AppBackHandler(enabled = isTopEntry && hasParentDir) {
        // 与列表里点"返回上级"同形: 上一级列表不含已选中的条目, 不清空会让"加入书架"
        // 作用到当前不可见的条目上 (远程书籍页的返回键分支同理)
        platform.goBackImportDir()
        screenModel.dispatch(ImportBookUiEvent.SelectAll(false))
    }

    ImportBookScreen(state = state, actions = actions)
}
