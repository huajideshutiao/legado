package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.book.toc.rule.TxtTocRuleScreen
import io.legado.app.ui.book.toc.rule.TxtTocRuleScreenModel
import io.legado.app.ui.book.toc.rule.TxtTocRuleUiActions
import io.legado.app.ui.book.toc.rule.TxtTocRuleUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.create
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_clear_all
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.import_default_rule
import legado.shared.generated.resources.import_local
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.txt_toc_rule
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * TXT 目录规则管理 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [TxtTocRuleScreenModel], 渲染 [TxtTocRuleScreen]。
 */
@Composable
fun TxtTocRuleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        TxtTocRuleScreenModel(importDefaultRules = { DefaultDataShared.importDefaultTocRules() })
    }
    val state by screenModel.state.collectAsState()

    val actions = remember(screenModel, navigator) {
        object : TxtTocRuleUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 对话框类: 规则编辑/在线导入/本地导入/帮助均走 Overlay, 由 shared OverlayContentHost 渲染
            override fun onAddRule() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleEdit"))
            }

            override fun onEditRule(item: TxtTocRule) {
                navigator.showOverlay(
                    AppOverlay.Dialog(key = "txtTocRuleEdit", payload = item.id.toString())
                )
            }

            override fun onImportLocal() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportLocal"))
            }

            override fun onImportOnline() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportOnline"))
            }

            override fun onHelp() {
                navigator.showOverlay(AppOverlay.Dialog(key = "help", payload = "txtTocRuleHelp"))
            }

            // dispatch 类: 状态与数据操作全部委托 ScreenModel
            override fun onImportDefault() {
                screenModel.dispatch(TxtTocRuleUiEvent.ImportDefault)
            }

            override fun onToggleSelect(item: TxtTocRule, checked: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToggleSelect(item, checked))
            }

            override fun onSelectAll(all: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.SelectAll(all))
            }

            override fun onRevertSelection() {
                screenModel.dispatch(TxtTocRuleUiEvent.RevertSelection)
            }

            override fun onDelSelection() {
                screenModel.dispatch(TxtTocRuleUiEvent.DelSelection)
            }

            override fun onDel(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.Del(item))
            }

            override fun onEnableSelection(enabled: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.EnableSelection(enabled))
            }

            override fun onMove(from: Int, to: Int) {
                screenModel.dispatch(TxtTocRuleUiEvent.Move(from, to))
            }

            override fun onPersistOrder() {
                screenModel.dispatch(TxtTocRuleUiEvent.PersistOrder)
            }

            override fun onToTop(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToTop(item))
            }

            override fun onToBottom(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToBottom(item))
            }

            override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.EnableRule(item, enabled))
            }

            // payload 为选中规则 JSON, 平台走 HandleFileContract.EXPORT + showExportSuccess
            override fun onExportSelection() {
                val json = GSON.toJson(screenModel.selection())
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleExport", payload = json))
            }
        }
    }

    TxtTocRuleScreen(state = state, actions = actions)
}

/**
 * TXT 目录规则对话框形态 (对照原版 TxtTocRuleDialog: 全高底部弹窗)。
 * 由目录弹窗/目录页"正则"入口弹起; 打开时按**当前生效规则** (当前书 `book.tocUrl`,
 * 原版 ReadBookActivity/TocActivity 传入 `book.tocUrl` 作为 durRegex) 预选中对应规则,
 * 确定后经 [onTocRegexResult] 回传选中规则的 rule 表达式, 宿主负责写回 book.tocUrl 并重载目录。
 *
 * 对照 app 端 [io.legado.app.ui.book.toc.rule.TxtTocRuleDialog]:
 * - isFullHeight 全高弹窗 → AppBottomSheetDialog + AppDialogSizes.properties()
 * - initData: observeAll 订阅规则列表
 * - initSelectedName: durRegex(book.tocUrl) 匹配规则预选中, 无匹配 selectedName=""
 * - tvOk: 按 selectedName 找规则回传 rule (无匹配不关闭)
 * - 行内: 单选/启用开关/编辑/删除 (编辑/删除同原版, 删除走 alert 确认)
 * - 菜单: 新增/本地导入/在线导入/导入默认/帮助 → 复用 [TxtTocRuleRoute] 同款 Overlay
 */
@Composable
fun TxtTocRuleDialogHost(
    book: Book,
    navigator: AppNavigator,
    onTocRegexResult: (tocRegex: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.fillMaxSize(),
            ) {
                TxtTocRuleDialogContent(
                    book = book,
                    navigator = navigator,
                    onTocRegexResult = onTocRegexResult,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun TxtTocRuleDialogContent(
    book: Book,
    navigator: AppNavigator,
    onTocRegexResult: (tocRegex: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    // 规则列表 (对照原版 initData observeAll 订阅)
    var tocRules by remember { mutableStateOf<List<TxtTocRule>?>(null) }
    // 选中规则名; 首次加载时按当前生效规则 (book.tocUrl) 预选中 (对照原版 initSelectedName)
    var selectedName by remember { mutableStateOf<String?>(null) }
    // 单条删除确认 (对照原版 ivDelete → alert)
    var pendingDelete by remember { mutableStateOf<TxtTocRule?>(null) }

    LaunchedEffect(Unit) {
        AppDbProviders.get().txtTocRuleDao.observeAll()
            .catch {
                AppLog.put("TXT目录规则对话框获取数据失败\n${it.message}", it)
            }
            .flowOn(IoDispatcher)
            .conflate()
            .collect { rules ->
                if (selectedName == null) {
                    // 当前生效规则 = 当前书 book.tocUrl (原版 ReadBookActivity/TocActivity 传入 durRegex)
                    selectedName = rules.firstOrNull { it.rule == book.tocUrl }?.name ?: ""
                }
                tocRules = rules
            }
    }

    Column(Modifier.fillMaxSize()) {
        // 标题栏 + 菜单 (对照原版 setupTitleBar(menuRes = R.menu.rule_list) + onMenuItemClick)
        AppTitleBar(
            title = stringResource(Res.string.txt_toc_rule),
            onBack = onDismiss,
            actions = {
                IconButton(onClick = {
                    navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleEdit"))
                }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
                        contentDescription = stringResource(Res.string.create),
                        tint = colors.primaryText,
                    )
                }
                OverflowMenu { dismiss ->
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportLocal"))
                        },
                    ) {
                        Text(stringResource(Res.string.import_local), color = colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportOnline"))
                        },
                    ) {
                        Text(stringResource(Res.string.import_on_line), color = colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            scope.launch(IoDispatcher) {
                                DefaultDataShared.importDefaultTocRules()
                            }
                        },
                    ) {
                        Text(
                            stringResource(Res.string.import_default_rule),
                            color = colors.primaryText,
                        )
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismiss()
                            navigator.showOverlay(
                                AppOverlay.Dialog(key = "help", payload = "txtTocRuleHelp")
                            )
                        },
                    ) {
                        Text(stringResource(Res.string.help), color = colors.primaryText)
                    }
                }
            },
        )
        val rules = tocRules
        if (rules == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rules, key = { it.id }) { rule ->
                    TxtTocRuleDialogItem(
                        rule = rule,
                        selected = rule.name == selectedName,
                        onSelect = { selectedName = rule.name },
                        onEnableChange = { enabled ->
                            rule.enable = enabled
                            scope.launch(IoDispatcher) {
                                AppDbProviders.get().txtTocRuleDao.update(rule)
                            }
                        },
                        onEdit = {
                            navigator.showOverlay(
                                AppOverlay.Dialog(
                                    key = "txtTocRuleEdit",
                                    payload = rule.id.toString(),
                                )
                            )
                        },
                        onDelete = { pendingDelete = rule },
                    )
                }
            }
        }
        // 底部按钮 (对照原版 bottomLayout tvCancel/tvOk)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel), color = colors.primaryText)
            }
            TextButton(onClick = {
                val name = selectedName ?: return@TextButton
                val rule = tocRules?.firstOrNull { it.name == name }
                if (rule != null) {
                    onTocRegexResult(rule.rule)
                }
            }) {
                Text(stringResource(Res.string.ok), color = colors.primaryText)
            }
        }
    }

    // 单条删除确认 (对照原版 ivDelete → alert(draw/sure_del))
    pendingDelete?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + rule.name,
            okButton = AlertButton(stringResource(Res.string.ok)) {
                scope.launch(IoDispatcher) {
                    AppDbProviders.get().txtTocRuleDao.delete(rule)
                }
                pendingDelete = null
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) { pendingDelete = null },
        )
    }
}

/**
 * 规则行: 单选 + 名称/示例 + 启用开关 + 编辑 + 删除 (对照原版 ItemTocRegexBinding)。
 */
@Composable
private fun TxtTocRuleDialogItem(
    rule: TxtTocRule,
    selected: Boolean,
    onSelect: () -> Unit,
    onEnableChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(selected = selected, onClick = onSelect)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = rule.name,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!rule.example.isNullOrEmpty()) {
                    Text(
                        text = rule.example!!,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AppSwitch(
                checked = rule.enable,
                onCheckedChange = onEnableChange,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit) {
                Icon(
                    painter = painterResource(Res.drawable.ic_edit),
                    contentDescription = stringResource(Res.string.edit),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(Res.drawable.ic_clear_all),
                    contentDescription = stringResource(Res.string.delete),
                    tint = colors.primaryText,
                )
            }
        }
    }
}
