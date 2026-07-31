package io.legado.app.ui.book.source

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.model.Debug
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_book_source
import legado.shared.generated.resources.book_source
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.debug
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.disable_explore
import legado.shared.generated.resources.disabled
import legado.shared.generated.resources.disabled_explore
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.enable_explore
import legado.shared.generated.resources.enabled
import legado.shared.generated.resources.enabled_explore
import legado.shared.generated.resources.group_manage
import legado.shared.generated.resources.group_sources_by_domain
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_sort
import legado.shared.generated.resources.import_local
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.is_enabled
import legado.shared.generated.resources.login
import legado.shared.generated.resources.menu_action_group
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.need_login
import legado.shared.generated.resources.no_group
import legado.shared.generated.resources.search
import legado.shared.generated.resources.search_book_source
import legado.shared.generated.resources.sort
import legado.shared.generated.resources.sort_auto
import legado.shared.generated.resources.sort_by_lastUpdateTime
import legado.shared.generated.resources.sort_by_name
import legado.shared.generated.resources.sort_by_respondTime
import legado.shared.generated.resources.sort_by_url
import legado.shared.generated.resources.sort_desc
import legado.shared.generated.resources.sort_manual
import legado.shared.generated.resources.to_bottom
import legado.shared.generated.resources.to_top
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 书源列表 Screen (KMP 版, 替代 app 端 `io.legado.app.ui.book.source.manage.BookSourceScreen`)。
 *
 * 下沉改动:
 * - 去掉对 `BookSourceActivity` 的直接依赖, 改为通过 [BookSourceListState] + [BookSourceListCallbacks]
 *   传入状态与回调, 解耦 Composable 与 Android Activity
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - 复用 shared/commonMain 已下沉的 16 个通用组件 (RuleManageScaffold / AppTitleBar /
 *   SelectActionBar / AppDropdownMenu / AppCheckbox / AppSwitch / AppMenuCheckbox /
 *   AppSearchField / OverflowMenu)
 *
 * UI 结构 (与 app 端一致):
 * - 顶部: AppTitleBar (返回 + 标题/搜索框 + 排序/分组/溢出菜单)
 * - 中部: RuleManageScaffold 的 LazyColumn (拖拽排序 + 按域名分组头 + 单项 checkbox/switch/编辑/更多)
 * - 底部: SelectActionBar (全选/反选/删除/溢出批量操作) + 校验进度卡
 *
 * @param state      列表状态 (数据/选中/查询/排序/校验进度)
 * @param callbacks  事件回调 (查询/排序/选中/单项操作/批量操作/导航)
 * @param listState  外部传入的 LazyListState, 供 dragSelectable 边缘拖选复用
 * @param listModifier 施加于 LazyColumn 的 modifier (如 dragSelectable)
 */
@Composable
fun BookSourceListScreen(
    state: BookSourceListState,
    callbacks: BookSourceListCallbacks,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listModifier: Modifier = Modifier,
) {
    // 按域名分组时预计算各 host 组首项 url 集合, 避免 item 内 O(n) 回查 (对照 adapter.isItemHeader)
    val headerUrls = remember(state.sources, state.groupSourcesByDomain) {
        if (!state.groupSourcesByDomain) emptySet()
        else buildSet {
            var lastHost: String? = null
            state.sources.forEach { part ->
                val host = callbacks.getSourceHost(part.bookSourceUrl)
                if (host != lastHost) {
                    add(part.bookSourceUrl)
                    lastHost = host
                }
            }
        }
    }
    val checkState by Debug.checkState.collectAsState()
    // 手动排序且未按域名分组时才允许拖拽 (对照原 itemTouchCallback.isCanDrag)
    val canDrag = state.sort == BookSourceSort.Default && !state.groupSourcesByDomain
    RuleManageScaffold(
        items = state.sources,
        itemKey = { it.bookSourceUrl },
        onMove = { from, to -> callbacks.onMove(from, to) },
        modifier = modifier,
        listState = listState,
        listModifier = listModifier,
        emptyText = stringResource(Res.string.empty),
        titleBar = {
            AppTitleBar(
                title = stringResource(Res.string.book_source),
                onBack = callbacks.onBack,
                titleContent = {
                    AppSearchField(
                        value = state.searchKey,
                        onValueChange = callbacks.onQueryChange,
                        hint = stringResource(Res.string.search_book_source),
                    )
                },
                actions = { BookSourceActions(state, callbacks) },
            )
        },
        actionBar = {
            Column {
                if (state.checkSourceVisible) {
                    CheckSourceProgress(
                        msg = state.checkSourceMsg.orEmpty(),
                        onCancel = callbacks.onCancelCheckSource,
                    )
                }
                SelectActionBar(
                    selectCount = state.selected.size,
                    allCount = state.sources.size,
                    onSelectAll = callbacks.onSelectAll,
                    onRevertSelection = callbacks.onRevertSelection,
                    mainActionText = stringResource(Res.string.delete),
                    onMainAction = callbacks.onDelSelection,
                    actions = callbacks.onSelectActions(),
                )
            }
        },
    ) { item ->
        BookSourceItem(
            state = state,
            callbacks = callbacks,
            item = item,
            checked = state.selected.contains(item.bookSourceUrl),
            headerUrls = headerUrls,
            canDrag = canDrag,
            checkState = checkState,
        )
    }
}

/**
 * 列表状态 (immutable)。host 端持有 mutableStateOf<BookSourceListState>, 修改时 copy出新实例。
 */
data class BookSourceListState(
    val sources: List<BookSourcePart> = emptyList(),
    val selected: Set<String> = emptySet(),
    val searchKey: String = "",
    val groups: List<String> = emptyList(),
    val sort: BookSourceSort = BookSourceSort.Default,
    val sortAscending: Boolean = true,
    val groupSourcesByDomain: Boolean = false,
    val checkSourceMsg: String? = null,
    val checkSourceVisible: Boolean = false,
)

/**
 * 事件回调集合。host 端用 `remember { BookSourceListCallbacks(...) }` 持有稳定实例,
 * 避免 lambda 重组; 不用的回调用默认空实现。
 */
data class BookSourceListCallbacks(
    val onBack: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSortChange: (BookSourceSort) -> Unit = {},
    val onToggleSortDesc: () -> Unit = {},
    val onToggleGroupByDomain: () -> Unit = {},
    val onToggle: (BookSourcePart, Boolean) -> Unit = { _, _ -> },
    val onSelectAll: (Boolean) -> Unit = {},
    val onRevertSelection: () -> Unit = {},
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onPersistOrder: () -> Unit = {},
    val onEdit: (BookSourcePart) -> Unit = {},
    val onEnable: (Boolean, BookSourcePart) -> Unit = { _, _ -> },
    val onEnableExplore: (Boolean, BookSourcePart) -> Unit = { _, _ -> },
    val onToTop: (BookSourcePart) -> Unit = {},
    val onToBottom: (BookSourcePart) -> Unit = {},
    val onSearchBook: (BookSourcePart) -> Unit = {},
    val onDebug: (BookSourcePart) -> Unit = {},
    val onLogin: (BookSourcePart) -> Unit = {},
    val onDel: (BookSourcePart) -> Unit = {},
    val onDelSelection: () -> Unit = {},
    val onCancelCheckSource: () -> Unit = {},
    val onAddBookSource: () -> Unit = {},
    val onImportLocal: () -> Unit = {},
    val onImportOnline: () -> Unit = {},
    val onGroupManage: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onSelectActions: () -> List<SelectAction> = { emptyList() },
    val getSourceHost: (String) -> String = { "#" },
)

// ---- 私有 Composable (对照 app 端 BookSourceScreen.kt 同名函数) ----

@Composable
private fun BookSourceActions(state: BookSourceListState, callbacks: BookSourceListCallbacks) {
    val colors = AppTheme.colors
    var showSort by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    // 预取字面串, 供菜单项点击回调 (lambda) 中使用 (rememberString 是 @Composable, 不能在 lambda 里调)
    val strSortDesc = stringResource(Res.string.sort_desc)
    val strSortManual = stringResource(Res.string.sort_manual)
    val strSortAuto = stringResource(Res.string.sort_auto)
    val strSortByName = stringResource(Res.string.sort_by_name)
    val strSortByUrl = stringResource(Res.string.sort_by_url)
    val strSortByLastUpdate = stringResource(Res.string.sort_by_lastUpdateTime)
    val strSortByRespond = stringResource(Res.string.sort_by_respondTime)
    val strIsEnabled = stringResource(Res.string.is_enabled)
    val strEnabled = stringResource(Res.string.enabled)
    val strDisabled = stringResource(Res.string.disabled)
    val strNeedLogin = stringResource(Res.string.need_login)
    val strNoGroup = stringResource(Res.string.no_group)
    val strEnabledExplore = stringResource(Res.string.enabled_explore)
    val strDisabledExplore = stringResource(Res.string.disabled_explore)
    val strGroupManage = stringResource(Res.string.group_manage)
    val strAddBookSource = stringResource(Res.string.add_book_source)
    val strImportLocal = stringResource(Res.string.import_local)
    val strImportOnline = stringResource(Res.string.import_on_line)
    val strGroupSourcesByDomain = stringResource(Res.string.group_sources_by_domain)
    val strHelp = stringResource(Res.string.help)

    // 排序菜单 (降序开关 + 单选各排序方式 + 按域名分组开关)
    Box {
        IconButton(onClick = { showSort = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_sort),
                contentDescription = stringResource(Res.string.sort),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
            CheckDropdownItem(
                text = strSortDesc,
                checked = !state.sortAscending,
            ) { callbacks.onToggleSortDesc() }
            SortItem(strSortManual, BookSourceSort.Default, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strSortAuto, BookSourceSort.Weight, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strSortByName, BookSourceSort.Name, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strSortByUrl, BookSourceSort.Url, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strSortByLastUpdate, BookSourceSort.Update, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strSortByRespond, BookSourceSort.Respond, state.sort) { callbacks.onSortChange(it); showSort = false }
            SortItem(strIsEnabled, BookSourceSort.Enable, state.sort) { callbacks.onSortChange(it); showSort = false }
        }
    }
    // 分组筛选菜单
    Box {
        IconButton(onClick = { showGroup = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_groups),
                contentDescription = stringResource(Res.string.menu_action_group),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showGroup, onDismissRequest = { showGroup = false }) {
            MenuItem(strGroupManage) { showGroup = false; callbacks.onGroupManage() }
            MenuItem(strEnabled) { showGroup = false; callbacks.onQueryChange(strEnabled) }
            MenuItem(strDisabled) { showGroup = false; callbacks.onQueryChange(strDisabled) }
            MenuItem(strNeedLogin) { showGroup = false; callbacks.onQueryChange(strNeedLogin) }
            MenuItem(strNoGroup) { showGroup = false; callbacks.onQueryChange(strNoGroup) }
            MenuItem(strEnabledExplore) { showGroup = false; callbacks.onQueryChange(strEnabledExplore) }
            MenuItem(strDisabledExplore) { showGroup = false; callbacks.onQueryChange(strDisabledExplore) }
            state.groups.forEach { group ->
                MenuItem(group) { showGroup = false; callbacks.onQueryChange("group:$group") }
            }
        }
    }
    OverflowMenu { dismiss ->
        MenuItem(strAddBookSource) { dismiss(); callbacks.onAddBookSource() }
        MenuItem(strImportLocal) { dismiss(); callbacks.onImportLocal() }
        MenuItem(strImportOnline) { dismiss(); callbacks.onImportOnline() }
        CheckDropdownItem(
            text = strGroupSourcesByDomain,
            checked = state.groupSourcesByDomain,
        ) { dismiss(); callbacks.onToggleGroupByDomain() }
        MenuItem(strHelp) { dismiss(); callbacks.onHelp() }
    }
}

@Composable
private fun SortItem(
    text: String,
    value: BookSourceSort,
    current: BookSourceSort,
    onClick: (BookSourceSort) -> Unit,
) {
    val colors = AppTheme.colors
    DropdownMenuItem(onClick = { onClick(value) }) {
        Text(text, color = colors.primaryText, modifier = Modifier.weight(1f))
        AppMenuCheckbox(checked = current == value)
    }
}

@Composable
private fun CheckDropdownItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = colors.primaryText, modifier = Modifier.weight(1f))
        AppMenuCheckbox(checked = checked)
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

@Composable
private fun RuleItemScope.BookSourceItem(
    state: BookSourceListState,
    callbacks: BookSourceListCallbacks,
    item: BookSourcePart,
    checked: Boolean,
    headerUrls: Set<String>,
    canDrag: Boolean,
    checkState: Debug.CheckState,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    val debugMsg = checkState.messages[item.bookSourceUrl].orEmpty()
    val showProgress = checkState.isChecking &&
        debugMsg.isNotEmpty() &&
        !debugMsg.contains(Regex("成功|失败"))
    // 预取单项菜单文案
    val strToTop = stringResource(Res.string.to_top)
    val strToBottom = stringResource(Res.string.to_bottom)
    val strLogin = stringResource(Res.string.login)
    val strSearch = stringResource(Res.string.search)
    val strDebug = stringResource(Res.string.debug)
    val strDelete = stringResource(Res.string.delete)
    val strEnableExplore = stringResource(Res.string.enable_explore)
    val strDisableExplore = stringResource(Res.string.disable_explore)
    val strEdit = stringResource(Res.string.edit)
    val strMoreMenu = stringResource(Res.string.more_menu)

    Column(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(
                enabled = canDrag,
                onDragStopped = { callbacks.onPersistOrder() },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // 按域名分组开启时, 每个 host 组首项上方显示 host 头 (对照 tv_host_text/AccentTextView 16sp)
        if (state.groupSourcesByDomain && headerUrls.contains(item.bookSourceUrl)) {
            Text(
                text = callbacks.getSourceHost(item.bookSourceUrl),
                color = colors.accent,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 按压整行触发切换选中, 子按钮 (Switch/Edit/More) 自身消费点击不冒泡
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { callbacks.onToggle(item, !checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(
                checked = checked,
                onCheckedChange = { callbacks.onToggle(item, it) },
            )
            Box(Modifier.weight(1f)) {
                Text(
                    text = item.getDisPlayNameGroup(),
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppSwitch(
                checked = item.enabled,
                onCheckedChange = { callbacks.onEnable(it, item) },
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { callbacks.onEdit(item) }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_edit),
                    contentDescription = strEdit,
                    tint = colors.primaryText,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_more_vert),
                        contentDescription = strMoreMenu,
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
                // 发现入口开关状态点: 绿=启用/红=禁用, 无发现地址不显示 (对照 iv_explore 约束 iv_menu_more 右上角)
                if (item.hasExploreUrl) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (item.enabledExplore) Color(0xFF43A047) else Color(0xFFE53935)),
                    )
                }
                BookSourceItemMenu(
                    state = state,
                    item = item,
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    callbacks = callbacks,
                    strToTop = strToTop,
                    strToBottom = strToBottom,
                    strLogin = strLogin,
                    strSearch = strSearch,
                    strDebug = strDebug,
                    strDelete = strDelete,
                    strEnableExplore = strEnableExplore,
                    strDisableExplore = strDisableExplore,
                )
            }
        }
        // 校验进度文案 + 转圈 (对照 iv_debug_text/iv_progressBar)
        if (debugMsg.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = debugMsg,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showProgress) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSourceItemMenu(
    state: BookSourceListState,
    item: BookSourcePart,
    expanded: Boolean,
    onDismiss: () -> Unit,
    callbacks: BookSourceListCallbacks,
    strToTop: String,
    strToBottom: String,
    strLogin: String,
    strSearch: String,
    strDebug: String,
    strDelete: String,
    strEnableExplore: String,
    strDisableExplore: String,
) {
    val colors = AppTheme.colors
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (state.sort == BookSourceSort.Default) {
            DropdownMenuItem(onClick = { onDismiss(); callbacks.onToTop(item) }) {
                Text(strToTop, color = colors.primaryText)
            }
            DropdownMenuItem(onClick = { onDismiss(); callbacks.onToBottom(item) }) {
                Text(strToBottom, color = colors.primaryText)
            }
        }
        if (item.hasLoginUrl) {
            DropdownMenuItem(onClick = { onDismiss(); callbacks.onLogin(item) }) {
                Text(strLogin, color = colors.primaryText)
            }
        }
        DropdownMenuItem(onClick = { onDismiss(); callbacks.onSearchBook(item) }) {
            Text(strSearch, color = colors.primaryText)
        }
        DropdownMenuItem(onClick = { onDismiss(); callbacks.onDebug(item) }) {
            Text(strDebug, color = colors.primaryText)
        }
        DropdownMenuItem(onClick = { onDismiss(); callbacks.onDel(item) }) {
            Text(strDelete, color = colors.primaryText)
        }
        if (item.hasExploreUrl) {
            val txt = if (item.enabledExplore) strDisableExplore else strEnableExplore
            DropdownMenuItem(onClick = { onDismiss(); callbacks.onEnableExplore(!item.enabledExplore, item) }) {
                Text(txt, color = colors.primaryText)
            }
        }
    }
}

/** 底部校验进度卡 (对照 view_check_source_progress: card_background + 转圈 + 文案 + 取消)。 */
@Composable
private fun CheckSourceProgress(msg: String, onCancel: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .border(DesignTokens.strokeHairline, colors.secondaryText.copy(alpha = 0.2f), DesignTokens.shapeDefault)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = msg,
            color = colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.cancel),
            color = colors.accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onCancel() },
        )
    }
}
