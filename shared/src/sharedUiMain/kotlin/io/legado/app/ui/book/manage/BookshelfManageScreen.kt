package io.legado.app.ui.book.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_download
import legado.shared.generated.resources.bookshelf_management
import legado.shared.generated.resources.custom_export_section
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.export_all_use_book_source
import legado.shared.generated.resources.export_config
import legado.shared.generated.resources.export_folder
import legado.shared.generated.resources.export_to_web_dav
import legado.shared.generated.resources.filter_book_type
import legado.shared.generated.resources.group
import legado.shared.generated.resources.group_manage
import legado.shared.generated.resources.ic_clear_all
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.log
import legado.shared.generated.resources.menu_download_after
import legado.shared.generated.resources.menu_download_all
import legado.shared.generated.resources.move_to_group
import legado.shared.generated.resources.replace_purify
import legado.shared.generated.resources.start
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书架管理 Screen (KMP 版, 替代 app 端原 BookshelfManageScreen)。
 *
 * 下沉改动:
 * - 去掉对 `BookshelfManageActivity` 的直接依赖, 改为通过 [BookshelfManageState] +
 *   [BookshelfManageCallbacks] 传入状态与回调, 解耦 Composable 与 Android Activity
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - 原 `AndroidView + CoverImageView` 改为 `coverSlot: @Composable (Book) -> Unit` 注入,
 *   app 端用 CoverImageView 承载, desktop 端用各自的封面渲染方案
 * - RuleManageScaffold / SelectActionBar / AppTitleBar / AppSearchField /
 *   AppDropdownMenu / OverflowMenu 等组件已在 shared, 直接复用
 *
 * @param state      列表状态 (数据/选中/查询/筛选/分组/下载状态/导出开关等)
 * @param callbacks  事件回调 (查询/选中/拖拽/单项操作/批量操作/导航)
 * @param listState  外部传入的 LazyListState, 供 dragSelectable 边缘拖选复用
 * @param listModifier 施加于 LazyColumn 的 modifier (如 dragSelectable)
 * @param coverSlot  封面渲染槽: 由调用方注入 (app 端经 LocalBookCoverSlot 绑定 ShelfCover, desktop 端自定义);
 *   接收 Book 与 Modifier, 内部应将 Modifier 应用到封面根节点以承袭父级尺寸约束
 */
@Composable
fun BookshelfManageScreen(
    state: BookshelfManageState,
    callbacks: BookshelfManageCallbacks,
    listState: LazyListState,
    listModifier: Modifier,
    coverSlot: @Composable (Book, Modifier) -> Unit,
) {
    val selectedSet = state.selected
    // 读取 refreshTick 触发列表随下载/缓存事件重组
    state.refreshTick
    RuleManageScaffold(
        items = state.books,
        itemKey = { it.bookUrl },
        onMove = { from, to -> callbacks.onMove(from, to) },
        listState = listState,
        listModifier = listModifier,
        emptyText = stringResource(Res.string.empty),
        titleBar = {
            AppTitleBar(
                title = stringResource(Res.string.bookshelf_management),
                onBack = callbacks.onBack,
                titleContent = {
                    AppSearchField(
                        value = state.searchKey,
                        onValueChange = callbacks.onQueryChange,
                        hint = state.searchHint,
                    )
                },
                actions = { BookshelfManageActions(state, callbacks) },
            )
        },
        actionBar = {
            SelectActionBar(
                selectCount = selectedSet.size,
                allCount = state.books.size,
                onSelectAll = callbacks.onSelectAll,
                onRevertSelection = callbacks.onRevertSelection,
                mainActionText = stringResource(Res.string.move_to_group),
                onMainAction = callbacks.onMainAction,
                actions = callbacks.onSelectActions(),
            )
        },
    ) { item ->
        BookItem(state, callbacks, item, selectedSet.contains(item.bookUrl), coverSlot)
    }
}

/**
 * 列表状态 (immutable)。host 端持有 mutableStateOf<BookshelfManageState>, 修改时 copy 出新实例。
 */
data class BookshelfManageState(
    val books: List<Book> = emptyList(),
    val selected: Set<String> = emptySet(),
    val searchKey: String = "",
    val searchHint: String = "",
    val bookshelfTypeFilter: Int = 0,
    val canDrag: Boolean = false,
    val groups: List<BookGroup> = emptyList(),
    val downloadRunning: Boolean = false,
    // 下载/缓存/封面刷新事件桥接为重组滴答
    val refreshTick: Int = 0,
    val exportUseReplace: Boolean = false,
    val enableCustomExportChecked: Boolean = false,
    val exportToWebDav: Boolean = false,
)

/**
 * 事件回调集合。host 端用 `remember { BookshelfManageCallbacks(...) }` 持有稳定实例,
 * 避免 lambda 重组; 不用的回调用默认空实现。
 *
 * 文案类查询 (originText/groupName/cacheInfo/isItemDownloading) 由 host 端按需返回,
 * 因这些数据依赖 Activity 持有的 groupList / cacheChapters / CacheBook 等运行时状态,
 * 不下沉到 shared。
 */
data class BookshelfManageCallbacks(
    val onBack: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onPersistOrder: () -> Unit = {},
    val onSelectAll: (Boolean) -> Unit = {},
    val onRevertSelection: () -> Unit = {},
    val onMainAction: () -> Unit = {},
    val onSelectActions: () -> List<SelectAction> = { emptyList() },
    val onToggle: (Book, Boolean) -> Unit = { _, _ -> },
    val onOpenBook: (Book) -> Unit = {},
    val onToggleDownload: (Book) -> Unit = {},
    val isItemDownloading: (Book) -> Boolean = { false },
    val onOriginText: (Book) -> String = { "" },
    val onGroupName: (Long) -> String = { "" },
    val onCacheInfo: (Book) -> String? = { null },
    val onDeleteBook: (Book) -> Unit = {},
    val onEditGroup: (Book) -> Unit = {},
    val onDownloadAfter: () -> Unit = {},
    val onDownloadAll: () -> Unit = {},
    val onShowGroupManage: () -> Unit = {},
    val onSelectGroupFromMenu: (BookGroup) -> Unit = {},
    val onExportAllUseBookSource: () -> Unit = {},
    val onToggleEnableReplace: () -> Unit = {},
    val onToggleCustomExport: () -> Unit = {},
    val onToggleExportWebDav: () -> Unit = {},
    val onSelectExportFolderMenu: () -> Unit = {},
    val onShowExportConfig: () -> Unit = {},
    val onShowLog: () -> Unit = {},
    val onSetBookTypeFilter: (Int) -> Unit = {},
)

@Composable
private fun BookshelfManageActions(state: BookshelfManageState, callbacks: BookshelfManageCallbacks) {
    val colors = AppTheme.colors
    var showDownload by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    // 下载:短按下载后续/停止,长按弹「下载后续/全部下载」(对照 iconItemOnLongClick)
    Box {
        Box(
            Modifier
                .size(48.dp)
                // 默认 ripple 圆形裁切, 对齐原 toolbar 菜单项按压反馈(actionBarItemBackground)
                .clip(CircleShape)
                .combinedClickable(
                    onLongClick = { showDownload = true },
                    onClick = callbacks.onDownloadAfter,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPainter(
                    if (state.downloadRunning) "ic_stop_black_24dp" else "ic_play_24dp"
                ),
                contentDescription = stringResource(Res.string.action_download),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showDownload, onDismissRequest = { showDownload = false }) {
            MenuItem(stringResource(Res.string.menu_download_after)) {
                showDownload = false; callbacks.onDownloadAfter()
            }
            MenuItem(stringResource(Res.string.menu_download_all)) {
                showDownload = false; callbacks.onDownloadAll()
            }
        }
    }
    // 分组
    Box {
        IconButton(onClick = { showGroup = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_groups),
                contentDescription = stringResource(Res.string.group),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showGroup, onDismissRequest = { showGroup = false }) {
            MenuItem(stringResource(Res.string.group_manage)) {
                showGroup = false; callbacks.onShowGroupManage()
            }
            state.groups.forEach { group ->
                MenuItem(group.groupName) { showGroup = false; callbacks.onSelectGroupFromMenu(group) }
            }
        }
    }
    // 溢出菜单
    OverflowMenu { dismiss ->
        // 类型筛选: 点击后用筛选项替换一级菜单(对照原 Android submenu 替换行为);
        // 菜单关闭随 Popup 释放状态, 下次打开回到一级菜单
        var showFilter by remember { mutableStateOf(false) }
        if (showFilter) {
            FilterItem(state, callbacks, "all", 0) { dismiss() }
            FilterItem(state, callbacks, "book_type_novel", 1) { dismiss() }
            FilterItem(state, callbacks, "book_type_comic", 2) { dismiss() }
            FilterItem(state, callbacks, "audio", 3) { dismiss() }
            FilterItem(state, callbacks, "explore_style_video", 4) { dismiss() }
        } else {
            MenuItem(stringResource(Res.string.filter_book_type)) { showFilter = true }
            MenuItem(stringResource(Res.string.export_all_use_book_source)) {
                dismiss(); callbacks.onExportAllUseBookSource()
            }
            CheckDropdownItem(
                text = stringResource(Res.string.replace_purify),
                checked = state.exportUseReplace,
            ) { dismiss(); callbacks.onToggleEnableReplace() }
            CheckDropdownItem(
                text = stringResource(Res.string.custom_export_section),
                checked = state.enableCustomExportChecked,
            ) { dismiss(); callbacks.onToggleCustomExport() }
            CheckDropdownItem(
                text = stringResource(Res.string.export_to_web_dav),
                checked = state.exportToWebDav,
            ) { dismiss(); callbacks.onToggleExportWebDav() }
            MenuItem(stringResource(Res.string.export_folder)) { dismiss(); callbacks.onSelectExportFolderMenu() }
            MenuItem(stringResource(Res.string.export_config)) { dismiss(); callbacks.onShowExportConfig() }
            MenuItem(stringResource(Res.string.log)) { dismiss(); callbacks.onShowLog() }
        }
    }
}

@Composable
private fun FilterItem(
    state: BookshelfManageState,
    callbacks: BookshelfManageCallbacks,
    textKey: String,
    value: Int,
    onClicked: () -> Unit,
) {
    CheckDropdownItem(
        text = rememberString(textKey),
        checked = state.bookshelfTypeFilter == value,
    ) {
        callbacks.onSetBookTypeFilter(value)
        onClicked()
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

@Composable
private fun CheckDropdownItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = colors.primaryText)
        Spacer(Modifier.weight(1f))
        AppMenuCheckbox(checked = checked)
    }
}

/** 书籍条目(对照 item_arrange_book):复选框 + 封面 + 名/作者/来源/分组/缓存 + 下载 + 改分组 + 删除。 */
@Composable
private fun RuleItemScope.BookItem(
    state: BookshelfManageState,
    callbacks: BookshelfManageCallbacks,
    book: Book,
    checked: Boolean,
    coverSlot: @Composable (Book, Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    // 长按=拖拽排序(仅手动排序);点按 root=切换选中;点封面=打开详情(避让长按拖拽,不再 combinedClickable)
    // 复刻原 ConstraintLayout: 文本随封面顶端对齐(tv_name top_toTopOf iv_cover)而非整列垂直居中
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(
                enabled = state.canDrag,
                onDragStopped = callbacks.onPersistOrder,
            )
            .clickable { callbacks.onToggle(book, !checked) }
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 原 checkbox 上下约束到 parent → 垂直居中
        AppCheckbox(
            checked = checked,
            onCheckedChange = { callbacks.onToggle(book, it) },
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        // 封面槽: 由 host 端注入 (app 端经 LocalBookCoverSlot 绑定 ShelfCover, desktop 端自定义)
        // 把 Box 的尺寸约束通过 fillMaxSize 透传给 coverSlot, 让 ShelfCover 按封面框 60x80dp 渲染
        Box(
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 8.dp)
                .width(60.dp)
                .height(80.dp)
                .clickable { callbacks.onOpenBook(book) },
        ) {
            coverSlot(book, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotEmpty()) {
                Text(
                    text = book.getRealAuthor(),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = callbacks.onOriginText(book),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val groupName = callbacks.onGroupName(book.group)
                if (groupName.isNotEmpty()) {
                    Text(
                        text = groupName,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            callbacks.onCacheInfo(book)?.let { info ->
                Text(
                    text = info,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 下载图标:本地书籍隐藏(对照 upDownloadIv)；右侧动作簇原约束 bottom_toBottomOf parent
        if (!book.isLocal) {
            IconButton(
                onClick = { callbacks.onToggleDownload(book) },
                modifier = Modifier.align(Alignment.Bottom),
            ) {
                Icon(
                    painter = rememberPainter(
                        if (callbacks.isItemDownloading(book)) "ic_stop_black_24dp" else "ic_play_24dp"
                    ),
                    contentDescription = stringResource(Res.string.start),
                    tint = colors.primaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(Res.string.group),
            color = colors.secondaryText,
            modifier = Modifier
                .align(Alignment.Bottom)
                .clickable { callbacks.onEditGroup(book) }
                .padding(8.dp),
        )
        IconButton(
            onClick = { callbacks.onDeleteBook(book) },
            modifier = Modifier.align(Alignment.Bottom),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_clear_all),
                contentDescription = stringResource(Res.string.delete),
                tint = colors.primaryText,
            )
        }
    }
}
