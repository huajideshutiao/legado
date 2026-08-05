package io.legado.app.ui.book.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SourceFilterRule
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
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.delete_all
import legado.shared.generated.resources.disable_selection
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.enable_selection
import legado.shared.generated.resources.export_selection
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.import_local
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search
import legado.shared.generated.resources.selection_to_bottom
import legado.shared.generated.resources.selection_to_top
import legado.shared.generated.resources.source_filter_rule
import legado.shared.generated.resources.source_filter_rule_delete_all_confirm
import legado.shared.generated.resources.source_filter_rule_empty
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.to_bottom
import legado.shared.generated.resources.to_top
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewFilterRules

/**
 * 源过滤规则列表 Screen (KMP 版, sharedUiMain 共享)。
 *
 * 对照 app 端 `SourceFilterRuleActivity.Content` 下沉, 复用 shared/compose/component 的
 * `RuleManageScaffold` 骨架 + 通用组件 (AppTitleBar/AppSearchField/AppDropdownMenu/
 * OverflowMenu/SelectActionBar/AppCheckbox/AppSwitch/dragSelectable 等):
 * - 数据/选中/查询状态由调用方(Activity 壳层)持有并打包成 [SourceFilterRuleUiState],
 *   本 Composable 纯渲染 + 通过 [SourceFilterRuleUiActions] 回调驱动平台层
 * - 路由跳转 / appDb / showDialogFragment / alert 全部走 actions 回调,
 *   不直接依赖 Activity/Intent/Android 框架
 * - 字符串走 [rememberString](key), app 端 getIdentifier 命中 R.string, desktop 返回 key 本身
 * - 图标走 [rememberPainter](key), app 端命中 R.drawable, desktop 未注册返回 Help 占位
 * - 删除确认(批量/单条/全部)用 [AppAlertDialog] 替代 app 端 alert 扩展,
 *   commonMain 无 android.app.AlertDialog 依赖; 确认后回调 actions 执行真正删除
 */
data class SourceFilterRuleUiState(
    val rules: List<SourceFilterRule> = emptyList(),
    val selected: Set<String> = emptySet(),
    val searchKey: String = "",
)

/**
 * 平台行为回调集合。宿主(Activity/Dialog 壳层)实现本接口, 在方法内桥接
 * appDb / ViewModel / showDialogFragment / 文件 launcher / alert 等平台依赖。
 *
 * 删除类回调([onDeleteSelection]/[onDeleteRule]/[onDeleteAll]) 在 shared 层
 * 确认对话框 OK 后才调用, 宿主实现无需再弹确认框。
 */
interface SourceFilterRuleUiActions {
    /** 返回上一页 */
    fun onBack()

    /** 搜索框输入回调, 宿主据此更新 searchKey state 并重启数据流订阅 */
    fun onSearchKeyChange(key: String)

    /** 列表项勾选/取消勾选 */
    fun onToggleSelected(item: SourceFilterRule, checked: Boolean)

    /** 全选/取消全选 */
    fun onSelectAll(all: Boolean)

    /** 反选 */
    fun onRevertSelection()

    /** 拖拽换位 (即时 state 交换由调用方负责) */
    fun onMoveItem(from: Int, to: Int)

    /** 松手落库, 持久化当前顺序 */
    fun onPersistOrder()

    /** 批量删除选中项 (确认对话框 OK 后调用) */
    fun onDeleteSelection()

    /** 单条删除 (确认对话框 OK 后调用) */
    fun onDeleteRule(rule: SourceFilterRule)

    /** 删除全部 (确认对话框 OK 后调用) */
    fun onDeleteAll()

    /** 启用选中项 */
    fun onEnableSelection()

    /** 禁用选中项 */
    fun onDisableSelection()

    /** 置顶选中项 */
    fun onTopSelect()

    /** 置底选中项 */
    fun onBottomSelect()

    /** 导出选中项 (宿主端负责文件保存) */
    fun onExportSelection()

    /** 编辑规则 */
    fun onEditRule(rule: SourceFilterRule)

    /** 单项置顶 */
    fun onToTop(rule: SourceFilterRule)

    /** 单项置底 */
    fun onToBottom(rule: SourceFilterRule)

    /** 单项启用开关切换 */
    fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean)

    /** 新增规则 (弹出编辑对话框) */
    fun onAddRule()

    /** 本地导入 */
    fun onImportLocal()

    /** 在线导入 */
    fun onImportOnline()
}

@Composable
fun SourceFilterRuleScreen(
    state: SourceFilterRuleUiState,
    actions: SourceFilterRuleUiActions,
) {
    val rules = state.rules
    val selected = state.selected
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 批量删除/单条删除/删除全部三个确认对话框的显示状态由本 Composable 持有,
    // 替代 app 端 alert 扩展 (commonMain 无 AlertDialog 依赖)
    var showDeleteSelectionConfirm by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    val pendingDeleteRule = remember { mutableStateOf<SourceFilterRule?>(null) }

    RuleManageScaffold(
        items = rules,
        itemKey = { it.id },
        onMove = { from, to -> actions.onMoveItem(from, to) },
        emptyText = stringResource(Res.string.source_filter_rule_empty),
        listState = listState,
        titleBar = {
            AppTitleBar(
                title = stringResource(Res.string.source_filter_rule),
                onBack = { actions.onBack() },
                titleContent = {
                    AppSearchField(
                        value = state.searchKey,
                        onValueChange = { actions.onSearchKeyChange(it) },
                        hint = stringResource(Res.string.search),
                    )
                },
                actions = {
                    SourceFilterRuleActions(
                        actions = actions,
                        onRequestDeleteAll = { showDeleteAllConfirm = true },
                    )
                },
            )
        },
        listModifier = Modifier.dragSelectable(
            listState = listState,
            autoScrollScope = scope,
            isSelected = { index -> rules.getOrNull(index)?.let { selected.contains(it.id) } ?: false },
            onSelectedChanged = { index, sel ->
                rules.getOrNull(index)?.let { actions.onToggleSelected(it, sel) }
            },
        ),
        actionBar = {
            SelectActionBar(
                selectCount = selected.size,
                allCount = rules.size,
                onSelectAll = { actions.onSelectAll(it) },
                onRevertSelection = { actions.onRevertSelection() },
                mainActionText = stringResource(Res.string.delete),
                onMainAction = { showDeleteSelectionConfirm = true },
                actions = listOf(
                    SelectAction(stringResource(Res.string.enable_selection)) {
                        actions.onEnableSelection()
                    },
                    SelectAction(stringResource(Res.string.disable_selection)) {
                        actions.onDisableSelection()
                    },
                    SelectAction(stringResource(Res.string.selection_to_top)) {
                        actions.onTopSelect()
                    },
                    SelectAction(stringResource(Res.string.selection_to_bottom)) {
                        actions.onBottomSelect()
                    },
                    SelectAction(stringResource(Res.string.export_selection)) {
                        actions.onExportSelection()
                    },
                ),
            )
        },
    ) { item ->
        SourceFilterRuleItem(
            item = item,
            checked = selected.contains(item.id),
            actions = actions,
            onDelete = { pendingDeleteRule.value = item },
        )
    }

    // 批量删除确认
    if (showDeleteSelectionConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteSelectionConfirm = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                actions.onDeleteSelection()
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
    // 删除全部确认
    if (showDeleteAllConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = stringResource(Res.string.delete_all),
            message = stringResource(Res.string.source_filter_rule_delete_all_confirm),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                actions.onDeleteAll()
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
    // 单条删除确认
    pendingDeleteRule.value?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteRule.value = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + rule.name,
            okButton = AlertButton(stringResource(Res.string.ok)) {
                actions.onDeleteRule(rule)
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}

/**
 * 顶部右侧操作区: 新增按钮 + 溢出菜单 (本地导入/在线导入/删除全部)。
 *
 * 注: 新增用 ic_add 图标, app 端 getIdentifier 命中 R.drawable.ic_add;
 * desktop jvmMain 未注册该 key 会 fallback 到 Help 占位图标。
 *
 * @param onRequestDeleteAll 点击"删除全部"时触发, 由外层切换确认对话框显示状态
 */
@Composable
private fun SourceFilterRuleActions(
    actions: SourceFilterRuleUiActions,
    onRequestDeleteAll: () -> Unit,
) {
    val colors = AppTheme.colors
    IconButton(onClick = { actions.onAddRule() }) {
        Icon(
            painter = painterResource(Res.drawable.ic_add),
            contentDescription = stringResource(Res.string.add),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        DropdownMenuItem(onClick = {
            dismiss()
            actions.onImportLocal()
        }) {
            Text(stringResource(Res.string.import_local), color = colors.primaryText)
        }
        DropdownMenuItem(onClick = {
            dismiss()
            actions.onImportOnline()
        }) {
            Text(stringResource(Res.string.import_on_line), color = colors.primaryText)
        }
        DropdownMenuItem(onClick = {
            dismiss()
            onRequestDeleteAll()
        }) {
            Text(stringResource(Res.string.delete_all), color = colors.primaryText)
        }
    }
}

/**
 * 单条规则项: 复选框 + 名称(为空回退 pattern) + 启用开关 + 编辑按钮 + 更多菜单(置顶/置底/删除)。
 * 长按空白把手区触发拖拽排序 (longPressDraggableHandle 来自 reorderable 库)。
 */
@Composable
private fun RuleItemScope.SourceFilterRuleItem(
    item: SourceFilterRule,
    checked: Boolean,
    actions: SourceFilterRuleUiActions,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    // 按压整行触发切换选中, 子按钮 (Switch/Edit/More) 自身消费点击不冒泡
    Row(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = { actions.onPersistOrder() })
            .clickable { actions.onToggleSelected(item, !checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(
            checked = checked,
            onCheckedChange = { actions.onToggleSelected(item, it) },
        )
        Text(
            text = item.name.ifEmpty { item.pattern },
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = item.enabled,
            onCheckedChange = { actions.onToggleEnabled(item, it) },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { actions.onEditRule(item) }) {
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = stringResource(Res.string.edit),
                tint = colors.primaryText,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_more_vert),
                    contentDescription = stringResource(Res.string.more_menu),
                    tint = colors.primaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(onClick = { showMenu = false; actions.onToTop(item) }) {
                    Text(stringResource(Res.string.to_top), color = colors.primaryText)
                }
                DropdownMenuItem(onClick = { showMenu = false; actions.onToBottom(item) }) {
                    Text(stringResource(Res.string.to_bottom), color = colors.primaryText)
                }
                DropdownMenuItem(onClick = { showMenu = false; onDelete() }) {
                    Text(stringResource(Res.string.delete), color = colors.primaryText)
                }
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 book/filter/SourceFilterRulePreviews.kt (SourceFilterRuleScreen) =====

// ---- SourceFilterRuleScreen ----


/** [SourceFilterRuleScreen] / [SourceFilterEditDialog] 的 @Preview。 */

private val noOpFilterActions = object : SourceFilterRuleUiActions {
    override fun onBack() {}
    override fun onSearchKeyChange(key: String) {}
    override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {}
    override fun onSelectAll(all: Boolean) {}
    override fun onRevertSelection() {}
    override fun onMoveItem(from: Int, to: Int) {}
    override fun onPersistOrder() {}
    override fun onDeleteSelection() {}
    override fun onDeleteRule(rule: SourceFilterRule) {}
    override fun onDeleteAll() {}
    override fun onEnableSelection() {}
    override fun onDisableSelection() {}
    override fun onTopSelect() {}
    override fun onBottomSelect() {}
    override fun onExportSelection() {}
    override fun onEditRule(rule: SourceFilterRule) {}
    override fun onToTop(rule: SourceFilterRule) {}
    override fun onToBottom(rule: SourceFilterRule) {}
    override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {}
    override fun onAddRule() {}
    override fun onImportLocal() {}
    override fun onImportOnline() {}
}


@Preview
@Composable
fun SourceFilterRuleScreenPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(rules = previewFilterRules),
        actions = noOpFilterActions,
    )
}

@Preview
@Composable
fun SourceFilterRuleScreenEmptyPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(),
        actions = noOpFilterActions,
    )
}

@Preview
@Composable
fun SourceFilterRuleScreenSearchingPreview() = LegadoThemePreview {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(
            rules = previewFilterRules.take(1),
            searchKey = "同人",
        ),
        actions = noOpFilterActions,
    )
}

@Preview
@Composable
fun SourceFilterRuleScreenDarkPreview() = LegadoThemePreview(dark = true) {
    SourceFilterRuleScreen(
        state = SourceFilterRuleUiState(
            rules = previewFilterRules,
            selected = setOf(previewFilterRules[0].id),
        ),
        actions = noOpFilterActions,
    )
}

