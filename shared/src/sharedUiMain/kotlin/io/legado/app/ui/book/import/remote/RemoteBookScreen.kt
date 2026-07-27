package io.legado.app.ui.book.import.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.ImportFileRow
import io.legado.app.ui.book.import.ImportMenuItem
import io.legado.app.ui.book.import.ImportScaffold
import io.legado.app.ui.book.import.ImportSortItem
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_refresh_black_24dp    (刷新按钮)
 *   - ic_baseline_sort_24      (排序按钮)
 *
 * String key (string):
 *   - empty                    (空态文案, 已存在)
 *   - remote_book              (标题)
 *   - screen                   (搜索框 hint 前缀, 已存在)
 *   - add_to_bookshelf         (底部主操作按钮文案, 已存在)
 *   - refresh                  (刷新 contentDescription)
 *   - sort                     (排序 contentDescription, 已存在)
 *   - sort_by_name             (按名称排序, 已存在)
 *   - sort_by_lastUpdateTime   (按更新时间排序)
 *   - server_config            (服务器配置)
 *   - help                     (帮助)
 *   - log                      (日志, 已存在)
 *
 * L3 不可下沉项: 无 (纯 Compose + 共享组件)
 */

/**
 * 远程书籍展示状态。
 *
 * app 端 [io.legado.app.ui.book.import.remote.RemoteBookActivity] 在 `Content()` 内
 * 将自己的 `var` 状态字段打包为本数据类传入; 桌面端/iOS 端可同样构造本类复用
 * [RemoteBookScreen]。
 *
 * 字段语义对照原 `RemoteBookActivity` 同名字段:
 * - [items] / [selected] / [refreshTick] / [path] / [loading] / [emptyMsgVisible] /
 *   [searchKey] / [checkableCount] / [sortKeyState]: 与 Activity 同名字段一一对应
 */
data class RemoteBookUiState<T : ImportFileItem>(
    val items: List<T>,
    val selected: Set<T>,
    val refreshTick: Int,
    val path: String?,
    val loading: Boolean,
    val emptyMsgVisible: Boolean,
    val searchKey: String,
    val checkableCount: Int,
    val sortKeyState: RemoteBookSort,
)

/**
 * 远程书籍用户交互回调。
 *
 * app 端 [io.legado.app.ui.book.import.remote.RemoteBookActivity] 实现本接口
 * (已有同名方法直接 `override`); 桌面端/iOS 端可自行实现本接口接入各自平台行为。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onShowServersDialog] 调 showDialogFragment、[onShowWebDavHelp] 调 showHelp、
 * [onShowLogDialog] 调 showDialogFragment<AppLogDialog>)
 * 由 app 端实现内部桥接到 Activity。
 */
interface RemoteBookUiActions<T : ImportFileItem> {
    fun onBack()
    fun onUpSearchKey(key: String)
    fun onUpPath()
    fun onSortCheck(sortKey: RemoteBookSort)
    fun onShowServersDialog()
    fun onShowWebDavHelp()
    fun onShowLogDialog()
    fun onSelectAll(selectAll: Boolean)
    fun onRevertSelection()
    fun onAddSelectionToBookshelf()
    fun onItemClick(item: T)
    fun onItemLongClick(item: T)
}

/**
 * 远程书籍界面(对照原 `RemoteBookScreen(activity: RemoteBookActivity)`)。
 *
 * 下沉自 app 端原版, 将 `RemoteBookActivity` 直接依赖拆为 [state] (展示状态)
 * + [actions] (交互回调), 去除 Android `Context` / `AppConfig` / `activity` 依赖。
 *
 * 视觉/布局/动画/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)。
 *
 * @param T 条目具体类型 (app 端为 RemoteBook; 桌面/iOS 端可为各自模型)
 */
@Composable
fun <T : ImportFileItem> RemoteBookScreen(
    state: RemoteBookUiState<T>,
    actions: RemoteBookUiActions<T>,
) {
    state.refreshTick // 消费 tick：上架标记原地变更后强制重组
    ImportScaffold(
        path = state.path,
        loading = state.loading,
        emptyVisible = state.emptyMsgVisible,
        emptyText = rememberString("empty"),
        titleBar = {
            AppTitleBar(
                title = rememberString("remote_book"),
                onBack = { actions.onBack() },
                titleContent = {
                    AppSearchField(
                        value = state.searchKey,
                        onValueChange = { actions.onUpSearchKey(it) },
                        hint = rememberString("screen") + " • " +
                            rememberString("remote_book"),
                    )
                },
                actions = { RemoteBookActions(state, actions) },
            )
        },
        actionBar = {
            SelectActionBar(
                selectCount = state.selected.size,
                allCount = state.checkableCount,
                onSelectAll = { actions.onSelectAll(it) },
                onRevertSelection = { actions.onRevertSelection() },
                mainActionText = rememberString("add_to_bookshelf"),
                onMainAction = { actions.onAddSelectionToBookshelf() },
            )
        },
    ) {
        items(state.items, key = { it.itemKey }) { item ->
            ImportFileRow(
                name = item.name,
                isDir = item.isDir,
                isUpDir = item.isUpDir,
                checkable = !item.isUpDir && !item.isDir && !item.isOnBookShelf,
                checked = state.selected.contains(item),
                onBookShelf = item.isOnBookShelf,
                tag = item.tag,
                size = item.size,
                lastModified = item.lastModified,
                onClick = { actions.onItemClick(item) },
                onLongClick = { actions.onItemLongClick(item) },
            )
        }
    }
}

/** 对照 book_remote 菜单：刷新/排序(名称|更新时间单选)/服务器配置/帮助/日志 */
@Composable
private fun <T : ImportFileItem> RemoteBookActions(
    state: RemoteBookUiState<T>,
    actions: RemoteBookUiActions<T>,
) {
    val colors = AppTheme.colors
    IconButton(onClick = { actions.onUpPath() }) {
        Icon(
            painter = rememberPainter("ic_refresh_black_24dp"),
            contentDescription = rememberString("refresh"),
            tint = colors.primaryText,
        )
    }
    Box {
        var showSort by remember { mutableStateOf(false) }
        IconButton(onClick = { showSort = true }) {
            Icon(
                painter = rememberPainter("ic_baseline_sort_24"),
                contentDescription = rememberString("sort"),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
            ImportSortItem(
                text = rememberString("sort_by_name"),
                checked = state.sortKeyState == RemoteBookSort.Name,
            ) {
                showSort = false; actions.onSortCheck(RemoteBookSort.Name)
            }
            ImportSortItem(
                text = rememberString("sort_by_lastUpdateTime"),
                checked = state.sortKeyState == RemoteBookSort.Default,
            ) {
                showSort = false; actions.onSortCheck(RemoteBookSort.Default)
            }
        }
    }
    OverflowMenu { dismiss ->
        ImportMenuItem(rememberString("server_config")) {
            dismiss(); actions.onShowServersDialog()
        }
        ImportMenuItem(rememberString("help")) {
            dismiss(); actions.onShowWebDavHelp()
        }
        ImportMenuItem(rememberString("log")) {
            dismiss(); actions.onShowLogDialog()
        }
    }
}
