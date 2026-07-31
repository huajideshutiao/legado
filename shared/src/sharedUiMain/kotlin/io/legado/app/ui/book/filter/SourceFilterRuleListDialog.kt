package io.legado.app.ui.book.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.close
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.source_filter_rule
import legado.shared.generated.resources.source_filter_rule_manage
import legado.shared.generated.resources.source_filter_rule_no_match
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 展示当前 scope 下命中 (已启用且范围覆盖) 的屏蔽规则, 对照 app 端同名 DialogFragment 下沉。
 * 点击编辑 → 弹 [SourceFilterEditDialog]; 底部"管理全部" → [onManageAll] 跳规则管理页。
 *
 * @param scope SearchScope 字符串 (null/空 = 全部书源)
 */
@Composable
fun SourceFilterRuleListDialog(
    scope: String?,
    onManageAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val coroutineScope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<SourceFilterRule>>(emptyList()) }
    // null=不显示编辑框; Pair(rule, isNew) 中 rule 为 null 表示新增
    var editing by remember { mutableStateOf<SourceFilterRule?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceFilterRule?>(null) }

    fun loadRules() {
        coroutineScope.launch {
            rules = withContext(IoDispatcher) { SearchBookFilter.rulesInScope(scope) }
        }
    }
    LaunchedEffect(scope) { loadRules() }

    // 对照 app 端 onSourceFilterRuleSave: 落库后重新拉取列表
    fun saveRule(rule: SourceFilterRule, isNew: Boolean) {
        coroutineScope.launch {
            withContext(IoDispatcher) { SearchBookFilter.save(rule, isNew) }
            loadRules()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 800.dp)
                    .heightIn(max = maxHeight * 0.9f)
                    .align(Alignment.Center),
                shape = DesignTokens.shapeDefault,
                color = colors.fillet,
            ) {
                RuleManageScaffold(
                    items = rules,
                    itemKey = { it.id },
                    onMove = { _, _ -> },
                    emptyText = stringResource(Res.string.source_filter_rule_no_match),
                    titleBar = {
                        DialogTitleBar(
                            title = stringResource(Res.string.source_filter_rule),
                            onBack = onDismiss,
                            actions = {
                                IconButton(onClick = { addingNew = true }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_add),
                                        contentDescription = stringResource(Res.string.add),
                                        tint = colors.primaryText,
                                    )
                                }
                            },
                        )
                    },
                    actionBar = {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppTextButton(
                                text = stringResource(Res.string.close),
                                color = colors.secondaryText,
                            ) { onDismiss() }
                            AppTextButton(
                                text = stringResource(Res.string.source_filter_rule_manage),
                            ) { onManageAll() }
                        }
                    },
                ) { item ->
                    SourceFilterRuleListItem(
                        item = item,
                        onToggleEnabled = { enabled ->
                            item.enabled = enabled
                            coroutineScope.launch(IoDispatcher) {
                                SearchBookFilter.save(item, isNew = false)
                            }
                        },
                        onEdit = { editing = item },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    if (addingNew || editing != null) {
        // SourceFilterEditDialog 只画 Surface (原 BaseComposeDialogFragment 的 Content), 需补窗口外壳
        Dialog(
            onDismissRequest = {
                addingNew = false
                editing = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 800.dp)
                        .heightIn(max = maxHeight * 0.9f)
                        .align(Alignment.Center),
                    shape = DesignTokens.shapeDefault,
                    color = colors.fillet,
                ) {
                    SourceFilterEditDialog(
                        rule = editing,
                        onConfirm = { rule -> saveRule(rule, isNew = editing == null) },
                        onDismiss = {
                            addingNew = false
                            editing = null
                        },
                        // 对照 app 端 SourceFilterEditDialog(existing = null, defaultScope = scope)
                        defaultScope = scope,
                    )
                }
            }
        }
    }

    pendingDelete?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + rule.name.ifEmpty { rule.pattern },
            okButton = AlertButton(stringResource(Res.string.ok)) {
                coroutineScope.launch {
                    withContext(IoDispatcher) { SearchBookFilter.delete(rule) }
                    loadRules()
                }
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}

/** 单条规则: 名称(为空回退 pattern) + 启用开关 + 编辑按钮 + 更多菜单(删除)。 */
@Composable
private fun SourceFilterRuleListItem(
    item: SourceFilterRule,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    // 列表项是实体对象, 原地改 enabled 不触发重组, 用本地态驱动开关显示
    var enabled by remember(item.id) { mutableStateOf(item.enabled) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.name.ifEmpty { item.pattern },
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onToggleEnabled(it)
            },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onEdit) {
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = stringResource(Res.string.edit),
                tint = colors.primaryText,
            )
        }
        IconButton(onClick = { showMenu = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(onClick = { showMenu = false; onDelete() }) {
                Text(stringResource(Res.string.delete), color = colors.primaryText)
            }
        }
    }
}
