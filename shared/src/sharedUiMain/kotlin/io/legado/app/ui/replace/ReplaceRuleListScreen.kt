package io.legado.app.ui.replace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.SelectActionBar
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 替换规则列表 Screen (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `ReplaceRuleActivity.Content` 下沉, 复用 shared/compose/component 的
 * `RuleManageScaffold` 骨架 + 16 个通用组件 (AppTitleBar/AppSearchField/AppDropdownMenu/
 * OverflowMenu/SelectActionBar/AppCheckbox/AppSwitch/dragSelectable 等):
 * - 数据/选中/查询状态由 [viewModel] 持有 (StateFlow), Compose 用 collectAsState 订阅
 * - 路由跳转全部用回调 (onBack/onAddRule/onEditRule/onImportLocal/onImportOnline/
 *   onHelp/onGroupManage/onExport), 不依赖 Activity/Intent
 * - 字符串走 [rememberString](key), app 端 getIdentifier 命中 R.string, desktop 返回 key 本身
 * - 图标走 [rememberPainter](key), app 端命中 R.drawable, desktop 未注册返回 Help 占位
 * - 删除确认用 [AppAlertDialog] (替代 app 端 alert 扩展, commonMain 无 android.app.AlertDialog 依赖)
 *
 * @param onExport 选中项导出回调, 宿主端负责文件保存 (app 走 HandleFileContract, desktop 走文件对话框)
 */
@Composable
fun ReplaceRuleListScreen(
    viewModel: ReplaceRuleListViewModel,
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onImportLocal: () -> Unit,
    onImportOnline: () -> Unit,
    onHelp: () -> Unit,
    onGroupManage: () -> Unit,
    onExport: (List<ReplaceRule>) -> Unit,
) {
    val rules by viewModel.rules.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val query by viewModel.query.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 搜索框文本: 仅承载关键词语义, 分组筛选通过下拉单独触发并清空关键词
    var searchKey by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val pendingDeleteRule = remember { mutableStateOf<ReplaceRule?>(null) }

    // 当 query 被下拉切到非 Keyword 时, 同步清空搜索框文本 (避免显示与实际查询不一致)
    LaunchedEffect(query) {
        if (query !is ReplaceQuery.Keyword) searchKey = ""
    }

    RuleManageScaffold(
        items = rules,
        itemKey = { it.id },
        onMove = { from, to ->
            // 即时交换 _rules state 供 UI 显示, 松手落库由 item 的 longPressDraggableHandle 触发
            viewModel.moveItem(from, to)
        },
        listState = listState,
        titleBar = {
            AppTitleBar(
                title = rememberString("replace_purify"),
                onBack = onBack,
                titleContent = {
                    AppSearchField(
                        value = searchKey,
                        onValueChange = { key ->
                            searchKey = key
                            viewModel.setQuery(
                                if (key.isBlank()) ReplaceQuery.All else ReplaceQuery.Keyword(key)
                            )
                        },
                        hint = rememberString("replace_purify_search"),
                    )
                },
                actions = { ReplaceRuleActions(viewModel, groups, onAddRule, onImportLocal, onImportOnline, onHelp, onGroupManage) },
            )
        },
        listModifier = Modifier.dragSelectable(
            listState = listState,
            autoScrollScope = scope,
            isSelected = { index -> rules.getOrNull(index)?.let { selected.contains(it.id) } ?: false },
            onSelectedChanged = { index, sel ->
                rules.getOrNull(index)?.let { viewModel.toggleSelected(it.id, sel) }
            },
        ),
        actionBar = {
            SelectActionBar(
                selectCount = selected.size,
                allCount = rules.size,
                onSelectAll = { viewModel.selectAll(it) },
                onRevertSelection = { viewModel.revertSelection() },
                mainActionText = rememberString("delete"),
                onMainAction = { showDeleteConfirm = true },
                actions = listOf(
                    SelectAction(rememberString("enable_selection")) {
                        viewModel.enableSelection(true)
                    },
                    SelectAction(rememberString("disable_selection")) {
                        viewModel.enableSelection(false)
                    },
                    SelectAction(rememberString("selection_to_top")) {
                        viewModel.topSelect(viewModel.selection())
                    },
                    SelectAction(rememberString("selection_to_bottom")) {
                        viewModel.bottomSelect(viewModel.selection())
                    },
                    SelectAction(rememberString("export_selection")) {
                        onExport(viewModel.selection())
                    },
                ),
            )
        },
    ) { item ->
        ReplaceRuleItem(
            item = item,
            checked = selected.contains(item.id),
            viewModel = viewModel,
            onEdit = onEditRule,
            onDelete = { pendingDeleteRule.value = item },
        )
    }

    // 批量删除确认
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = rememberString("draw"),
            message = rememberString("sure_del"),
            okButton = AlertButton(rememberString("ok")) {
                viewModel.deleteSelection()
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
    // 单条删除确认
    pendingDeleteRule.value?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteRule.value = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + rule.name,
            okButton = AlertButton(rememberString("ok")) {
                viewModel.delete(rule)
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
}

/**
 * 顶部右侧操作区: 分组筛选下拉 (分组管理/未分组/各分组) + 溢出菜单
 * (新增/本地导入/在线导入/帮助)。
 *
 * 注: 分组筛选用 ic_groups 图标, desktop jvmMain 未注册该 key 会 fallback 到 Help 占位图标,
 * app 端 getIdentifier 命中 R.drawable.ic_groups 正常显示。
 */
@Composable
private fun ReplaceRuleActions(
    viewModel: ReplaceRuleListViewModel,
    groups: List<String>,
    onAddRule: () -> Unit,
    onImportLocal: () -> Unit,
    onImportOnline: () -> Unit,
    onHelp: () -> Unit,
    onGroupManage: () -> Unit,
) {
    val colors = AppTheme.colors
    var showGroup by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showGroup = true }) {
            Icon(
                painter = rememberPainter("ic_groups"),
                contentDescription = rememberString("menu_action_group"),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showGroup, onDismissRequest = { showGroup = false }) {
            DropdownMenuItem(
                onClick = { showGroup = false; onGroupManage() },
            ) {
                Text(rememberString("group_manage"), color = colors.primaryText)
            }
            DropdownMenuItem(
                onClick = {
                    showGroup = false
                    viewModel.setQuery(ReplaceQuery.NoGroup)
                },
            ) {
                Text(rememberString("no_group"), color = colors.primaryText)
            }
            groups.forEach { group ->
                DropdownMenuItem(
                    onClick = {
                        showGroup = false
                        viewModel.setQuery(ReplaceQuery.Group(group))
                    },
                ) {
                    Text(group, color = colors.primaryText)
                }
            }
        }
    }
    OverflowMenu { dismiss ->
        DropdownMenuItem(
            onClick = {
                dismiss()
                onAddRule()
            },
        ) {
            Text(rememberString("add_replace_rule"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                onImportLocal()
            },
        ) {
            Text(rememberString("import_local"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                onImportOnline()
            },
        ) {
            Text(rememberString("import_on_line"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                onHelp()
            },
        ) {
            Text(rememberString("help"), color = colors.primaryText)
        }
    }
}

/**
 * 单条规则项: 复选框 + 名称(分组) + 启用开关 + 编辑按钮 + 更多菜单(置顶/置底/删除)。
 * 长按空白把手区触发拖拽排序 (longPressDraggableHandle 来自 reorderable 库)。
 */
@Composable
private fun RuleItemScope.ReplaceRuleItem(
    item: ReplaceRule,
    checked: Boolean,
    viewModel: ReplaceRuleListViewModel,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = { viewModel.persistOrder() })
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(
            checked = checked,
            onCheckedChange = { viewModel.toggleSelected(item.id, it) },
        )
        Text(
            text = item.getDisplayNameGroup(),
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { viewModel.toggleSelected(item.id, !checked) },
        )
        AppSwitch(
            checked = item.isEnabled,
            onCheckedChange = { viewModel.update(item.copy(isEnabled = it)) },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onEdit(item.id) }) {
            Icon(
                painter = rememberPainter("ic_edit"),
                contentDescription = rememberString("edit"),
                tint = colors.primaryText,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = rememberString("more_menu"),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    onClick = { showMenu = false; viewModel.toTop(item) },
                ) {
                    Text(rememberString("to_top"), color = colors.primaryText)
                }
                DropdownMenuItem(
                    onClick = { showMenu = false; viewModel.toBottom(item) },
                ) {
                    Text(rememberString("to_bottom"), color = colors.primaryText)
                }
                DropdownMenuItem(
                    onClick = { showMenu = false; onDelete() },
                ) {
                    Text(rememberString("delete"), color = colors.primaryText)
                }
            }
        }
    }
}
