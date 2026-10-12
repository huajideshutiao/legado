package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.DeepLinkImportTarget
import io.legado.app.ui.association.ImportTargetDialog
import io.legado.app.ui.association.RuleSubScreen
import io.legado.app.ui.association.RuleSubScreenModel
import io.legado.app.ui.association.toDeepLinkImportTypeOrNull
import io.legado.app.ui.association.RuleSubUiActions
import io.legado.app.ui.association.RuleSubUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.book_source
import legado.ui.generated.resources.book_type
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.dict_rule
import legado.ui.generated.resources.draw
import legado.ui.generated.resources.error
import legado.ui.generated.resources.name
import legado.ui.generated.resources.non_null_name_url
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.replace_rule
import legado.ui.generated.resources.rule_sub_auto_update
import legado.ui.generated.resources.rss_source
import legado.ui.generated.resources.sure_del
import legado.ui.generated.resources.tts
import legado.ui.generated.resources.txt_toc_rule
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.RuleSub 路由下沉入口: 桥接 [RuleSubScreenModel] 状态与 [RuleSubScreen] 渲染。
 *
 * 新增/编辑用 [AppAlertDialog] + customView (类型下拉 + 名称/URL 输入) 实现, 确认后 dispatch Save;
 * 删除用 [AppAlertDialog] 确认后 dispatch Delete;
 * 打开订阅按 [RuleSub.type] 映射到导入类型 (映射见 [toDeepLinkImportTypeOrNull]), 经 [DeepLinkImportTarget] +
 * [ImportTargetDialog] 触发下载解析并渲染勾选导入对话框 (对照 app 端 showDialogFragment(ImportXxxDialog))。
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
                val type = ruleSub.type.toDeepLinkImportTypeOrNull()
                if (type == null) {
                    // 对照 app 端: 未知类型 toastOnUi(R.string.error)
                    scope.launch { Toasters.get().toast(getString(Res.string.error)) }
                    return
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
            onConfirm = { name, url, type, autoUpdate ->
                ruleSub.name = name
                ruleSub.url = url
                ruleSub.type = type
                ruleSub.autoUpdate = autoUpdate
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
 * 规则订阅编辑弹窗 (新增/编辑): 类型下拉 + 名称/URL 输入, 校验非空 + isAbsUrl 后回调 onConfirm。
 *
 * 对照原版 `RuleSubActivity.showEditDialog`: alert title (add/edit) + customView
 * (类型下拉与自动更新开关同排 + 名称/URL 输入) + okButton 校验 + cancelButton。
 */
@Composable
private fun RuleSubEditDialog(
    ruleSub: RuleSub,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, type: Int, autoUpdate: Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    var type by remember { mutableStateOf(ruleSub.type.coerceIn(0, 5)) }
    var autoUpdate by remember { mutableStateOf(ruleSub.autoUpdate) }
    var name by remember { mutableStateOf(ruleSub.name) }
    var url by remember { mutableStateOf(ruleSub.url) }
    // 类型下拉选项 (对照原版 AppCompatSpinner + array/rule_type)
    val ruleTypeOptions = (0..5).map { typeName(it) }
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
            onConfirm(n, u, type, autoUpdate)
            onDismiss()
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
    ) {
        Column(Modifier.padding(horizontal = DesignTokens.spacingDefault)) {
            // 类型与自动更新同排: 类型靠左, 自动更新靠右; 开关身份 (角色 + 开/关状态)
            // 由文字与开关这一段承担, 行尾开关只显示状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.book_type),
                    color = colors.accent,
                    modifier = Modifier.padding(end = DesignTokens.spacingDefault),
                )
                AppDropdownField(
                    options = ruleTypeOptions,
                    selectedIndex = type,
                    onSelect = { type = it },
                )
                Spacer(Modifier.weight(1f))
                // 热区只盖文字到开关这一段 (与类型下拉之间的留白不算)
                Row(
                    Modifier.toggleable(
                        value = autoUpdate,
                        role = Role.Switch,
                        onValueChange = { autoUpdate = it },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.rule_sub_auto_update),
                        color = colors.primaryText,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(DesignTokens.spacingDefault))
                    AppSwitch(checked = autoUpdate, onCheckedChange = null)
                }
            }
            AppUnderlineTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppUnderlineTextField(
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
