package io.legado.app.ui.book.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
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
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.platform.rememberNavigationBarPaddingValues
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
 * @param state      列表状态 (数据/查询/筛选/分组/导出开关等; 勾选/下载态已拆为独立参数)
 * @param callbacks  事件回调 (查询/选中/拖拽/单项操作/批量操作/导航)
 * @param listState  外部传入的 LazyListState, 供 dragSelectable 边缘拖选复用
 * @param listModifier 施加于 LazyColumn 的 modifier (如 dragSelectable)
 * @param coverSlot  封面渲染槽: 由调用方注入 (app 端经 LocalBookCoverSlot 绑定 ShelfCover, desktop 端自定义);
 *   接收 Book 与 Modifier, 内部应将 Modifier 应用到封面根节点以承袭父级尺寸约束
 * @param downloadRunning 下载服务运行中 (独立状态: 仅顶栏下载图标区域重组)
 * @param selectedCount 勾选计数 (独立状态: 仅批量栏计数区域重组)
 * @param checkedMap  勾选态 per-key 映射 (任务3): item 只读自己 bookUrl 的 key, 单次勾选仅该行重组
 * @param bookTicks   事件滴答 per-key 映射 (任务1): item 只读自己 bookUrl 的 key, 单书事件仅该书行重组
 * @param groupNameMap 分组名预计算表 Map<groupId, String> (任务5), item 内查表代替逐 item 计算
 */
@Composable
fun BookshelfManageScreen(
    state: BookshelfManageState,
    callbacks: BookshelfManageCallbacks,
    listState: LazyListState,
    listModifier: Modifier,
    coverSlot: @Composable (Book, Modifier) -> Unit,
    // 平台下载运行中 (独立于 state: 仅顶栏下载图标区域重组, 不整页)
    downloadRunning: Boolean,
    // 勾选计数 (独立于 state: 仅批量栏计数区域重组)
    selectedCount: Int,
    // 勾选态 per-key 映射 (任务3): item 内只读自己 bookUrl 的 key,
    // 单次勾选仅该行勾选框区域重组, 不整页重组
    checkedMap: Map<String, Boolean>,
    // 事件滴答 per-key 映射 (任务1): item 内读自己 bookUrl 的 key,
    // 单书下载/缓存事件仅该书 item 失效重组
    bookTicks: Map<String, Int>,
    // 分组名预计算表 (任务5): Map<groupId, String>, item 内 O(1) 查表
    groupNameMap: Map<Long, String>,
) {
    RuleManageScaffold(
        items = state.books,
        itemKey = { it.bookUrl },
        onMove = { from, to -> callbacks.onMove(from, to) },
        listState = listState,
        listModifier = listModifier,
        emptyText = stringResource(Res.string.empty),
        bottomPadding = rememberNavigationBarPaddingValues(),
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
                actions = { BookshelfManageActions(state, callbacks, downloadRunning) },
            )
        },
        actionBar = {
            SelectActionBar(
                selectCount = selectedCount,
                allCount = state.books.size,
                onSelectAll = callbacks.onSelectAll,
                onRevertSelection = callbacks.onRevertSelection,
                mainActionText = stringResource(Res.string.move_to_group),
                onMainAction = callbacks.onMainAction,
                actions = callbacks.onSelectActions(),
            )
        },
    ) { item ->
        BookItem(
            state = state,
            callbacks = callbacks,
            book = item,
            checked = checkedMap[item.bookUrl] == true,
            bookTicks = bookTicks,
            groupNameMap = groupNameMap,
            coverSlot = coverSlot,
        )
    }
}

/**
 * 列表状态 (immutable)。host 端持有 mutableStateOf<BookshelfManageState>, 修改时 copy 出新实例。
 *
 * 性能拆分 (任务1/3): 下载事件滴答 (bookTicks)、勾选态 (checkedMap) 与下载运行态
 * (downloadRunning) 均不在此状态内, 而是作为独立参数传入 Screen——
 * 它们的变化不重建本状态实例, 从而不触发整页重组。
 */
data class BookshelfManageState(
    val books: List<Book> = emptyList(),
    val searchKey: String = "",
    val searchHint: String = "",
    val bookshelfTypeFilter: Int = 0,
    val canDrag: Boolean = false,
    val groups: List<BookGroup> = emptyList(),
    val exportUseReplace: Boolean = false,
    val enableCustomExportChecked: Boolean = false,
    val exportToWebDav: Boolean = false,
)

/**
 * 事件回调集合。host 端用 `remember { BookshelfManageCallbacks(...) }` 持有稳定实例,
 * 避免 lambda 重组; 不用的回调用默认空实现。
 *
 * 文案类查询 (originText/cacheInfo/isItemDownloading) 由 host 端按需返回,
 * 因这些数据依赖 Activity 持有的 groupList / cacheChapters / CacheBook 等运行时状态,
 * 不下沉到 shared。分组名不再经回调逐 item 计算 (任务5), 改由预计算表 groupNameMap 传入。
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
private fun BookshelfManageActions(
    state: BookshelfManageState,
    callbacks: BookshelfManageCallbacks,
    downloadRunning: Boolean,
) {
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
                    if (downloadRunning) "ic_stop_black_24dp" else "ic_play_24dp"
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
    bookTicks: Map<String, Int>,
    groupNameMap: Map<Long, String>,
    coverSlot: @Composable (Book, Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    // 任务1: 本书事件滴答 — per-key snapshot 读, 只有本 bookUrl 的事件才使本 item 失效;
    // 其它书/秒级服务滴答不触发本 item 重组
    val tick = bookTicks[book.bookUrl] ?: 0
    // 派生值 item 级缓存: onCacheInfo/isItemDownloading 依赖 VM 普通 map (非 snapshot),
    // 由 tick 驱动重算; 其它 item 重组/勾选/滚动不重算这些开销。
    // remember key 用 book 引用: 换源等 Book 内容变化 (新实例) 时也能重算, 不滞留旧值
    val cacheInfoText = remember(book, tick) { callbacks.onCacheInfo(book) }
    val downloading = remember(book, tick) { callbacks.isItemDownloading(book) }
    val originText = remember(book) { callbacks.onOriginText(book) }
    // 任务5: 分组名预计算表查表 (常见单分组 O(1) 直查; 多分组位掩码回退过滤),
    // 仅 book.group 或分组表变化时重算, item 重组不重算
    val groupName = remember(book.group, groupNameMap) {
        val direct = groupNameMap[book.group]
        if (direct != null) direct
        else groupNameMap.filterKeys { it and book.group > 0 }.values.joinToString(",")
    }
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
                    // 对照原版 tv_author 用 tv_text_summary(arco_text_3), 非 secondaryText
                    color = colors.summaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = originText,
                    // 对照原版 tv_origin 用 tv_text_summary(arco_text_3)
                    color = colors.summaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (groupName.isNotEmpty()) {
                    Text(
                        text = groupName,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 分组名与书源间保留 8dp 间距(有意偏离原版紧贴, 便于视觉区分)
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            cacheInfoText?.let { info ->
                Text(
                    text = info,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 右侧动作簇: 下载/分组/删除同心垂直居中(对照原版 iv_download top/bottom 对齐 tv_group,
        // tv_group/iv_delete 约束于「作者底~item底」区间居中), 本地书隐藏下载图标(对照 upDownloadIv)
        Row(
            modifier = Modifier.align(Alignment.Bottom),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!book.isLocal) {
                IconButton(
                    onClick = { callbacks.onToggleDownload(book) },
                ) {
                    Icon(
                        painter = rememberPainter(
                            if (downloading) "ic_stop_black_24dp" else "ic_play_24dp"
                        ),
                        contentDescription = stringResource(Res.string.start),
                        // 对照原版 iv_download 容器 28dp 内 24dp 图标
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(Res.string.group),
                color = colors.secondaryText,
                modifier = Modifier
                    .clickable { callbacks.onEditGroup(book) }
                    .padding(8.dp),
            )
            IconButton(
                onClick = { callbacks.onDeleteBook(book) },
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_clear_all),
                    contentDescription = stringResource(Res.string.delete),
                    tint = colors.primaryText,
                )
            }
        }
    }
}
