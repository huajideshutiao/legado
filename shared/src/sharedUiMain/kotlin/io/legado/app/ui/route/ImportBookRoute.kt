package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBookScreen
import io.legado.app.ui.book.import.local.ImportBookScreenModel
import io.legado.app.ui.book.import.local.ImportBookUiActions
import io.legado.app.ui.book.import.local.ImportBookUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore

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

    // 平台状态推送到 ScreenModel, 供 selection/checkableCount 计算
    LaunchedEffect(items, path, loading, emptyMsgVisible) {
        screenModel.updatePlatformState(items, path, loading, emptyMsgVisible)
    }

    // 对照 onActivityCreated: sortState = viewModel.sort (读持久化排序) + initData
    LaunchedEffect(Unit) {
        screenModel.dispatch(ImportBookUiEvent.SetSort(AppConfigProviders.get().localBookImportSort))
        platform.initImportBookData()
    }

    val state by screenModel.state.collectAsState()

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
    ImportBookScreen(state = state, actions = actions)
}
