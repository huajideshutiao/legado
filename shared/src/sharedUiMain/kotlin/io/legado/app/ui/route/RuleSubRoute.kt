package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.DeepLinkImportTarget
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.RuleSubScreen
import io.legado.app.ui.association.RuleSubScreenModel
import io.legado.app.ui.association.RuleSubUiActions
import io.legado.app.ui.association.RuleSubUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.book_source
import legado.shared.generated.resources.book_type
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.dict_rule
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.error
import legado.shared.generated.resources.name
import legado.shared.generated.resources.non_null_name_url
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.replace_rule
import legado.shared.generated.resources.rss_source
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.tts
import legado.shared.generated.resources.txt_toc_rule
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.RuleSub 路由下沉入口: 桥接 [RuleSubScreenModel] 状态与 [RuleSubScreen] 渲染。
 *
 * 新增/编辑用 [AppAlertDialog] + customView (类型下拉 + 名称/URL 输入) 实现, 确认后 dispatch Save;
 * 删除用 [AppAlertDialog] 确认后 dispatch Delete;
 * 打开订阅按 [RuleSub.type] 映射到 [DeepLinkImportType], 经 [DeepLinkImportTarget] 触发
 * 下载解析后渲染 [ImportItemsDialog] (对照 app 端 showDialogFragment(ImportXxxDialog))。
 */
@Composable
fun RuleSubRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { RuleSubScreenModel() }
    val state by screenModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // 编辑弹窗 / 删除确认 / 订阅打开导入对话框状态
    val pendingEdit = remember { mutableStateOf<RuleSub?>(null) }
    val pendingDelete = remember { mutableStateOf<RuleSub?>(null) }
    val importTarget = remember { mutableStateOf<DeepLinkImportTarget?>(null) }

    val actions = remember(screenModel, navigator, scope) {
        object : RuleSubUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 弹出新增编辑弹窗, 确认后 dispatch Save (对照 app 端 showEditDialog(RuleSub()))
            override fun onAdd() {
                pendingEdit.value = RuleSub()
            }

            // 弹出编辑弹窗 (回填字段), 确认后 dispatch Save (对照 app 端 showEditDialog(ruleSub))
            override fun onEdit(ruleSub: RuleSub) {
                pendingEdit.value = ruleSub.copy()
            }

            // 按 type 打开对应导入对话框 (对照 app 端 openSubscription)
            override fun onOpenSubscription(ruleSub: RuleSub) {
                if (!ruleSub.url.isAbsUrl()) {
                    // 对照 app 端: URL 非法时 toastOnUi(R.string.non_null_name_url)
                    scope.launch {
                        Toasters.get().toast(getString(Res.string.non_null_name_url))
                    }
                    return
                }
                val type = when (ruleSub.type) {
                    0, 1 -> DeepLinkImportType.BOOK_SOURCE
                    2 -> DeepLinkImportType.REPLACE_RULE
                    3 -> DeepLinkImportType.TXT_TOC_RULE
                    4 -> DeepLinkImportType.DICT_RULE
                    5 -> DeepLinkImportType.HTTP_TTS
                    else -> {
                        // 对照 app 端: 未知类型 toastOnUi(R.string.error)
                        scope.launch { Toasters.get().toast(getString(Res.string.error)) }
                        return
                    }
                }
                importTarget.value = DeepLinkImportTarget.of(type, scope)
                    ?.also { it.startImport(ruleSub.url) }
            }

            // 弹出删除确认弹窗, 确认后 dispatch Delete (对照 app 端 delete alert)
            override fun onDelete(ruleSub: RuleSub) {
                pendingDelete.value = ruleSub
            }

            override fun onMove(from: Int, to: Int) {
                screenModel.dispatch(RuleSubUiEvent.Move(from, to))
            }

            override fun onPersistOrder() {
                screenModel.dispatch(RuleSubUiEvent.PersistOrder)
            }

            override fun onToTop(ruleSub: RuleSub) {
                screenModel.dispatch(RuleSubUiEvent.ToTop(ruleSub))
            }

            override fun onToBottom(ruleSub: RuleSub) {
                screenModel.dispatch(RuleSubUiEvent.ToBottom(ruleSub))
            }
        }
    }

    RuleSubScreen(state = state, actions = actions)

    // 编辑弹窗 (新增/编辑), 对照 app 端 showEditDialog
    pendingEdit.value?.let { ruleSub ->
        RuleSubEditDialog(
            ruleSub = ruleSub,
            onDismiss = { pendingEdit.value = null },
            onConfirm = { name, url, type ->
                ruleSub.name = name
                ruleSub.url = url
                ruleSub.type = type
                screenModel.dispatch(RuleSubUiEvent.Save(ruleSub))
                pendingEdit.value = null
            },
        )
    }
    // 删除确认弹窗, 对照 app 端 delete alert
    pendingDelete.value?.let { ruleSub ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete.value = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + ruleSub.name,
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(RuleSubUiEvent.Delete(ruleSub))
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
    // 订阅打开导入对话框, 对照 app 端 showDialogFragment(ImportXxxDialog)
    importTarget.value?.let { target ->
        ImportTargetDialog(
            target = target,
            onDismiss = { importTarget.value = null },
        )
    }
}

/**
 * 订阅打开导入对话框渲染: 在独立 Composable 内 collectAsState error/success,
 * 避免 importTarget 为 null 时丢失 subscription (Compose hooks 稳定性)。
 */
@Composable
private fun ImportTargetDialog(
    target: DeepLinkImportTarget,
    onDismiss: () -> Unit,
) {
    // SharedFlow 无初始值, 用 null 表示"还没收到解析结果"(原 StateFlow 初始 null 的等价物)
    val error by target.errorState.collectAsState(initial = null)
    val success by target.successState.collectAsState(initial = null)
    ImportItemsDialog(
        title = rememberString(target.titleKey),
        vm = target.items,
        onDismiss = onDismiss,
        // 解析进行中 (success/error 均 null) 显示 loading; 解析完成或失败后由 ImportItemsDialog 自身渲染列表/错误
        loading = success == null && error == null,
        errorText = error,
    )
}

/**
 * 规则订阅编辑弹窗 (新增/编辑): 类型下拉 + 名称/URL 输入, 校验非空 + isAbsUrl 后回调 onConfirm。
 *
 * 对照 app 端 `RuleSubActivity.showEditDialog`: alert title (add/edit) + customView
 * (Row 类型下拉 + AppOutlinedTextField 名称 + AppOutlinedTextField URL) + okButton 校验 + cancelButton。
 */
@Composable
private fun RuleSubEditDialog(
    ruleSub: RuleSub,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, type: Int) -> Unit,
) {
    val colors = AppTheme.colors
    var type by remember { mutableStateOf(ruleSub.type.coerceIn(0, 5)) }
    var name by remember { mutableStateOf(ruleSub.name) }
    var url by remember { mutableStateOf(ruleSub.url) }
    var expanded by remember { mutableStateOf(false) }
    // 校验失败提示 (对照 app 端 okButton 内 toastOnUi(R.string.non_null_name_url))
    val nonNullNameUrlMsg = stringResource(Res.string.non_null_name_url)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString(if (ruleSub.name.isEmpty()) "add" else "edit"),
        okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
            val n = name.trim()
            val u = url.trim()
            if (n.isEmpty() || !u.isAbsUrl()) {
                Toasters.get().toast(nonNullNameUrlMsg)
                return@AlertButton
            }
            onConfirm(n, u, type)
            onDismiss()
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.book_type),
                    color = colors.accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box {
                    Text(
                        typeName(type),
                        color = colors.primaryText,
                        modifier = Modifier
                            .clickable { expanded = true }
                            .padding(8.dp),
                    )
                    AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        (0..5).forEach { t ->
                            DropdownMenuItem(
                                onClick = { type = t; expanded = false },
                            ) { Text(typeName(t), color = colors.primaryText) }
                        }
                    }
                }
            }
            AppOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppOutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = "Url",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 订阅类型 → 显示名称, 对照 app 端 `RuleSubActivity.typeName`。 */
@Composable
private fun typeName(type: Int): String = when (type) {
    1 -> stringResource(Res.string.rss_source)
    2 -> stringResource(Res.string.replace_rule)
    3 -> stringResource(Res.string.txt_toc_rule)
    4 -> stringResource(Res.string.dict_rule)
    5 -> stringResource(Res.string.tts)
    else -> stringResource(Res.string.book_source)
}
