package io.legado.app.ui.dict.rule

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
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
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
 * 字典规则管理 Screen (KMP 版, sharedUiMain 共享)。
 *
 * 对照 app 端 [DictRuleActivity] 的 `Content()` 下沉, 复用 shared/compose/component 的
 * `RuleManageScaffold` 骨架 + 通用组件 (AppTitleBar/OverflowMenu/SelectActionBar/
 * AppCheckbox/AppSwitch/dragSelectable 等):
 * - 数据/选中状态由调用方 (app DictRuleActivity) 持有, 打包为 [DictRuleUiState] 传入
 * - 路由跳转与平台依赖 (showDialogFragment/HandleFileContract/ACache/showHelp 等)
 *   全部走 [DictRuleUiActions] 回调, 不依赖 Activity/Intent/Context
 * - 字符串走 [rememberString](key), app 端 getIdentifier 命中 R.string, desktop 返回 key 本身
 * - 图标走 [rememberPainter](key), app 端命中 R.drawable, desktop 未注册返回 Help 占位
 * - 单条删除确认用 [AppAlertDialog] (替代 app 端 alert 扩展, commonMain 无 AlertDialog 依赖)
 *
 * 与 [io.legado.app.ui.replace.ReplaceRuleListScreen] 的差异:
 * - 无搜索框/分组筛选 (字典规则无此能力)
 * - 溢出菜单含「导入默认规则」项
 * - 选中批量操作无置顶/置底
 * - item 主键为 name (非自增 id), 启用字段为 enabled (非 isEnabled)
 */

// ===== state / actions =====

/**
 * 字典规则管理页完整渲染所需的全部展示状态。
 *
 * app 端 [DictRuleActivity] 在 `Content()` 内将自己的 `var` 状态字段打包为本数据类传入。
 */
data class DictRuleUiState(
    val dictRules: List<DictRule> = emptyList(),
    val selected: Set<String> = emptySet(),
)

/**
 * 字典规则管理页的用户交互回调。
 *
 * app 端 [DictRuleActivity] 实现本接口 (已有同名方法直接 override 或适配);
 * 平台依赖 (appDb/showDialogFragment/HandleFileContract/ACache/showHelp 等)
 * 均通过本接口桥接, Screen 本身不持有 Android 依赖。
 */
interface DictRuleUiActions {
    /** 返回 (替代原 `finish()`)。 */
    fun onBack()

    /** 新建规则 (替代原 `showDialogFragment<DictRuleEditDialog>()`)。 */
    fun onAddRule()

    /** 编辑指定规则 (替代原 `showDialogFragment(DictRuleEditDialog(item.name))`)。 */
    fun onEditRule(name: String)

    /** 本地导入 (替代原 `importDoc.launch { mode = FILE ... }`)。 */
    fun onImportLocal()

    /** 在线导入 (替代原 `showImportDialog()`, 含 ACache 历史记录)。 */
    fun onImportOnline()

    /** 导入默认规则 (替代原 `viewModel.importDefault()`)。 */
    fun onImportDefault()

    /** 帮助 (替代原 `showHelp("dictRuleHelp")`)。 */
    fun onHelp()

    /** 切换单条规则选中态 (替代原 `toggle(item, checked)`)。 */
    fun onToggleSelected(item: DictRule, checked: Boolean)

    /** 全选/全不选 (替代原 `onSelectAll = { all -> selected.value = ... }`)。 */
    fun onSelectAll(all: Boolean)

    /** 反选 (替代原 `onRevertSelection = { selected.value = ... - selectedSet }`)。 */
    fun onRevertSelection()

    /** 拖拽换位 (替代原 `onMove = { from, to -> dictRules = ... }`)。 */
    fun onMoveItem(from: Int, to: Int)

    /** 松手落库排序号 (替代原 `persistOrder()`)。 */
    fun onPersistOrder()

    /** 更新单条规则启用开关 (替代原 `viewModel.update(item.copy(enabled = it))`)。 */
    fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean)

    /** 删除单条规则 (删除确认由 Screen 内 AppAlertDialog 承载, 确认后回调)。 */
    fun onDeleteRule(rule: DictRule)

    /** 删除选中项 (批量, 无确认直接执行, 对照原 `onMainAction`)。 */
    fun onDeleteSelection()

    /** 启用选中项 (替代原 `viewModel.enableSelection(*selection())`)。 */
    fun onEnableSelection()

    /** 禁用选中项 (替代原 `viewModel.disableSelection(*selection())`)。 */
    fun onDisableSelection()

    /** 导出选中项 (替代原 `exportResult.launch { ... GSON.toJson(selection()) ... }`)。 */
    fun onExportSelection()
}

// ===== DictRuleScreen =====

/**
 * 字典规则管理页内容: RuleManageScaffold 骨架 + 标题栏操作区 + 列表项 + 底部批量操作栏。
 *
 * @param state    展示状态 (规则列表 + 选中集合)
 * @param actions  交互回调 (路由跳转 / 平台依赖桥接)
 */
@Composable
fun DictRuleScreen(
    state: DictRuleUiState,
    actions: DictRuleUiActions,
) {
    val dictRules = state.dictRules
    val selectedSet = state.selected
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 单条删除待确认项 (对照原 confirmDelete 的 alert)
    val pendingDeleteRule = remember { mutableStateOf<DictRule?>(null) }

    RuleManageScaffold(
        items = dictRules,
        itemKey = { it.name },
        onMove = { from, to ->
            actions.onMoveItem(from, to)
        },
        listState = listState,
        titleBar = {
            AppTitleBar(
                title = rememberString("dict_rule"),
                onBack = { actions.onBack() },
                actions = { DictRuleActions(actions) },
            )
        },
        listModifier = Modifier.dragSelectable(
            listState = listState,
            autoScrollScope = scope,
            isSelected = { index -> dictRules.getOrNull(index)?.let { selectedSet.contains(it.name) } ?: false },
            onSelectedChanged = { index, sel ->
                dictRules.getOrNull(index)?.let { actions.onToggleSelected(it, sel) }
            },
        ),
        actionBar = {
            SelectActionBar(
                selectCount = selectedSet.size,
                allCount = dictRules.size,
                onSelectAll = { actions.onSelectAll(it) },
                onRevertSelection = { actions.onRevertSelection() },
                mainActionText = rememberString("delete"),
                onMainAction = { actions.onDeleteSelection() },
                actions = listOf(
                    SelectAction(rememberString("enable_selection")) {
                        actions.onEnableSelection()
                    },
                    SelectAction(rememberString("disable_selection")) {
                        actions.onDisableSelection()
                    },
                    SelectAction(rememberString("export_selection")) {
                        actions.onExportSelection()
                    },
                ),
            )
        },
    ) { item ->
        DictRuleItem(
            item = item,
            checked = selectedSet.contains(item.name),
            actions = actions,
            onDelete = { pendingDeleteRule.value = item },
        )
    }

    // 单条删除确认 (对照原 confirmDelete 的 alert, 改用 AppAlertDialog + rememberString)
    pendingDeleteRule.value?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteRule.value = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + rule.name,
            okButton = AlertButton(rememberString("yes")) {
                actions.onDeleteRule(rule)
            },
            cancelButton = AlertButton(rememberString("no")) {},
        )
    }
}

/**
 * 顶部右侧操作区: 新建按钮 + 溢出菜单 (本地导入/在线导入/导入默认规则/帮助)。
 */
@Composable
private fun DictRuleActions(actions: DictRuleUiActions) {
    val colors = AppTheme.colors
    IconButton(onClick = { actions.onAddRule() }) {
        Icon(
            painter = rememberPainter("ic_add"),
            contentDescription = rememberString("create"),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onImportLocal()
            },
        ) {
            Text(rememberString("import_local"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onImportOnline()
            },
        ) {
            Text(rememberString("import_on_line"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onImportDefault()
            },
        ) {
            Text(rememberString("import_default_rule"), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onHelp()
            },
        ) {
            Text(rememberString("help"), color = colors.primaryText)
        }
    }
}

/**
 * 单条规则项: 复选框 + 名称 + 启用开关 + 编辑按钮 + 更多菜单(删除)。
 * 长按空白把手区触发拖拽排序 (longPressDraggableHandle 来自 reorderable 库)。
 */
@Composable
private fun RuleItemScope.DictRuleItem(
    item: DictRule,
    checked: Boolean,
    actions: DictRuleUiActions,
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
            text = item.name,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = item.enabled,
            onCheckedChange = { actions.onUpdateRuleEnabled(item, it) },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { actions.onEditRule(item.name) }) {
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
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                ) {
                    Text(rememberString("delete"), color = colors.primaryText)
                }
            }
        }
    }
}
