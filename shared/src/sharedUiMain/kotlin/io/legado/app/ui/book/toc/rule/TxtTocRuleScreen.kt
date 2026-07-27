package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.TxtTocRule
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
 * TXT 目录规则管理 UI 状态 (KMP 版, commonMain 共享)。
 *
 * app 端 [TxtTocRuleActivity] 在 `Content()` 内将自身的 `mutableStateOf` 字段
 * 打包为本数据类传入; 桌面端可同样构造本类复用 [TxtTocRuleScreen]。
 *
 * 字段语义对照原 `TxtTocRuleActivity` 同名字段:
 * - [tocRules]: 规则列表 (对照 activity.tocRules)
 * - [selected]: 选中规则 id 集合 (对照 activity.selected.value)
 */
data class TxtTocRuleUiState(
    val tocRules: List<TxtTocRule> = emptyList(),
    val selected: Set<Long> = emptySet(),
)

/**
 * TXT 目录规则管理用户交互回调。
 *
 * app 端 [TxtTocRuleActivity] 实现本接口; 已有同名方法直接 `override`,
 * 其余方法以 `override fun onXxx() = xxx()` 桥接现有方法, 不改动 Activity
 * 内部其它调用点 (参考 SearchContentActivity 薄壳模式)。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onBack] 调 finish、[onImportLocal] 走 HandleFileContract、
 * [onEditRule] 走 showDialogFragment、[onExportSelection] 走 HandleFileContract.EXPORT)
 * 由 app 端实现内部桥接到 Activity。删除确认对话框由 shared 端 [TxtTocRuleScreen]
 * 用 [AppAlertDialog] 统一呈现, [onDel]/[onDelSelection] 仅负责实际删除动作。
 */
interface TxtTocRuleUiActions {
    /** 关闭页面 (对照 activity.finish()) */
    fun onBack()
    /** 新建规则 (对照 showDialogFragment(TxtTocRuleEditDialog())) */
    fun onAddRule()
    /** 编辑规则 (对照 showDialogFragment(TxtTocRuleEditDialog(source.id))) */
    fun onEditRule(item: TxtTocRule)
    /** 本地导入 (对照 importDoc.launch { mode = FILE; allowExtensions = ... }) */
    fun onImportLocal()
    /** 在线导入 (对照 showImportDialog()) */
    fun onImportOnline()
    /** 导入默认规则 (对照 viewModel.importDefault()) */
    fun onImportDefault()
    /** 帮助 (对照 showHelp("txtTocRuleHelp")) */
    fun onHelp()
    /** 切换选中 (对照 toggle(item, checked)) */
    fun onToggleSelect(item: TxtTocRule, checked: Boolean)
    /** 全选/全不选 (对照 selected.value = if (all) ... else emptySet()) */
    fun onSelectAll(all: Boolean)
    /** 反选 (对照 selected.value = ... - selectedSet) */
    fun onRevertSelection()
    /** 批量删除 (对照 delSourceDialog 中 yesButton 内 viewModel.del(*selection().toTypedArray())) */
    fun onDelSelection()
    /** 单条删除 (对照 del 中 yesButton 内 viewModel.del(source)) */
    fun onDel(item: TxtTocRule)
    /** 启用/禁用选中 (对照 viewModel.enableSelection/disableSelection(*selection())) */
    fun onEnableSelection(enabled: Boolean)
    /** 导出选中项 (对照 exportResult.launch + GSON.toJson(selection())) */
    fun onExportSelection()
    /** 拖拽换位 (对照 tocRules = tocRules.toMutableList().apply { ... }) */
    fun onMove(from: Int, to: Int)
    /** 拖拽松手落库 (对照 persistOrder()) */
    fun onPersistOrder()
    /** 置顶 (对照 viewModel.toTop(item)) */
    fun onToTop(item: TxtTocRule)
    /** 置底 (对照 viewModel.toBottom(item)) */
    fun onToBottom(item: TxtTocRule)
    /** 切换单项启用开关 (对照 item.enable = it; viewModel.update(item)) */
    fun onEnableRule(item: TxtTocRule, enabled: Boolean)
}

/**
 * TXT 目录规则管理页 (KMP 版, commonMain 共享)。
 *
 * 对照 app 端 `TxtTocRuleActivity.Content` 下沉, 复用 shared/compose/component 的
 * `RuleManageScaffold` 骨架 + 通用组件 (AppTitleBar/AppDropdownMenu/OverflowMenu/
 * SelectActionBar/AppCheckbox/AppSwitch/dragSelectable 等):
 * - 数据/选中状态由宿主端持有 (mutableStateOf), 通过 [TxtTocRuleUiState] 传入
 * - 路由跳转/平台依赖全部用 [TxtTocRuleUiActions] 回调, 不依赖 Activity/Intent
 * - 字符串走 [rememberString](key), app 端 getIdentifier 命中 R.string, desktop 返回 key 本身
 * - 图标走 [rememberPainter](key), app 端命中 R.drawable, desktop 未注册返回 Help 占位
 * - 删除确认用 [AppAlertDialog] (替代 app 端 alert 扩展, commonMain 无 android.app.AlertDialog 依赖)
 */
@Composable
fun TxtTocRuleScreen(state: TxtTocRuleUiState, actions: TxtTocRuleUiActions) {
    val tocRules = state.tocRules
    val selected = state.selected
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 单条删除确认待处理项 (替代 app 端 alert(R.string.draw) { setMessage(...) })
    val pendingDeleteRule = remember { mutableStateOf<TxtTocRule?>(null) }
    // 批量删除确认 (替代 app 端 delSourceDialog 的 alert)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    RuleManageScaffold(
        items = tocRules,
        itemKey = { it.id },
        onMove = { from, to -> actions.onMove(from, to) },
        listState = listState,
        titleBar = {
            AppTitleBar(
                title = rememberString("txt_toc_rule"),
                onBack = { actions.onBack() },
                actions = { TxtTocRuleActions(actions) },
            )
        },
        listModifier = Modifier.dragSelectable(
            listState = listState,
            autoScrollScope = scope,
            isSelected = { index -> tocRules.getOrNull(index)?.let { selected.contains(it.id) } ?: false },
            onSelectedChanged = { index, sel ->
                tocRules.getOrNull(index)?.let { actions.onToggleSelect(it, sel) }
            },
        ),
        actionBar = {
            SelectActionBar(
                selectCount = selected.size,
                allCount = tocRules.size,
                onSelectAll = { actions.onSelectAll(it) },
                onRevertSelection = { actions.onRevertSelection() },
                mainActionText = rememberString("delete"),
                onMainAction = { showDeleteConfirm = true },
                actions = listOf(
                    SelectAction(rememberString("enable_selection")) {
                        actions.onEnableSelection(true)
                    },
                    SelectAction(rememberString("disable_selection")) {
                        actions.onEnableSelection(false)
                    },
                    SelectAction(rememberString("export_selection")) {
                        actions.onExportSelection()
                    },
                ),
            )
        },
    ) { item ->
        TxtTocRuleItem(
            item = item,
            checked = selected.contains(item.id),
            actions = actions,
            onDelete = { pendingDeleteRule.value = item },
        )
    }

    // 批量删除确认对话框
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = rememberString("draw"),
            message = rememberString("sure_del"),
            okButton = AlertButton(rememberString("ok")) {
                actions.onDelSelection()
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
    // 单条删除确认对话框
    pendingDeleteRule.value?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteRule.value = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + rule.name,
            okButton = AlertButton(rememberString("ok")) {
                actions.onDel(rule)
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
}

/**
 * 顶部右侧操作区: 新建规则按钮 + 溢出菜单
 * (本地导入/在线导入/导入默认规则/帮助)。
 */
@Composable
private fun TxtTocRuleActions(actions: TxtTocRuleUiActions) {
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
 * 单条规则项: 复选框 + 名称 + 启用开关 + 编辑按钮 + 更多菜单(置顶/置底/删除)。
 * 长按空白把手区触发拖拽排序 (longPressDraggableHandle 来自 reorderable 库)。
 * 当规则带示例时, 名称行下方显示 example 文案 (对照原 item_layout)。
 */
@Composable
private fun RuleItemScope.TxtTocRuleItem(
    item: TxtTocRule,
    checked: Boolean,
    actions: TxtTocRuleUiActions,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .longPressDraggableHandle(onDragStopped = { actions.onPersistOrder() })
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppCheckbox(
                checked = checked,
                onCheckedChange = { actions.onToggleSelect(item, it) },
            )
            Text(
                text = item.name,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { actions.onToggleSelect(item, !checked) },
            )
            AppSwitch(
                checked = item.enable,
                onCheckedChange = { actions.onEnableRule(item, it) },
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { actions.onEditRule(item) }) {
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
                        onClick = { showMenu = false; actions.onToTop(item) },
                    ) {
                        Text(rememberString("to_top"), color = colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = { showMenu = false; actions.onToBottom(item) },
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
        if (!item.example.isNullOrEmpty()) {
            Text(
                text = item.example!!,
                color = colors.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
