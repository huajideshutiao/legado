package io.legado.app.ui.route

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportReplaceRuleItemsVm
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.replace.ReplaceRuleListScreen
import io.legado.app.ui.replace.ReplaceRuleListViewModel
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.group_manage
import legado.shared.generated.resources.group_name
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.import_replace_rule
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 替换规则管理 shared 路由入口。
 *
 * [ReplaceRuleListViewModel] 非 [io.legado.app.ui.root.ScreenModel] (自管 scope),
 * 故用 [remember] 创建, [DisposableEffect] 在离开时调 [ReplaceRuleListViewModel.onCleared]
 * 取消内部数据流订阅防泄漏。平台专属能力 (导入/帮助/分组管理/导出) 通过
 * [PlatformServiceProviders] + shared 对话框组件实现。
 */
@Composable
fun ReplaceRuleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val viewModel = remember { ReplaceRuleListViewModel() }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onCleared()
            // 对照 Activity onDestroy: 刷新替换规则缓存 (fire-and-forget)
            runCatching { ContentProcessorProviders.get() }.getOrNull()?.let {
                Coroutine.async { it.upReplaceRules() }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val groups by viewModel.groups.collectAsState()

    // 对话框状态
    var showHelp by remember { mutableStateOf(false) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var importVm by remember { mutableStateOf<ImportReplaceRuleViewModelShared?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // 监听导入 VM 的成功/错误状态
    LaunchedEffect(importVm) {
        val vm = importVm ?: return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                vm.successState.collect { count -> if (count != null) showImportDialog = true }
            }
            launch {
                vm.errorState.collect { err -> if (err != null) importError = err }
            }
        }
    }
    LaunchedEffect(importError) {
        importError?.let {
            Toasters.get().toast(it)
            importError = null
        }
    }

    ReplaceRuleListScreen(
        viewModel = viewModel,
        onBack = { navigator.pop() },
        onAddRule = { navigator.push(AppRoute.ReplaceEdit()) },
        onEditRule = { id -> navigator.push(AppRoute.ReplaceEdit(id)) },
        onImportLocal = {
            val services = PlatformServiceProviders.getOrNull() ?: return@ReplaceRuleListScreen
            scope.launch {
                val path = withContext(IoDispatcher) {
                    services.files.pickFile(FileFilter.Text)
                } ?: return@launch
                val text = withContext(IoDispatcher) { BackupFileOps.readText(path) }
                val vm = ImportReplaceRuleViewModelShared(scope)
                importVm = vm
                vm.import(text)
            }
        },
        onImportOnline = { showUrlInput = true },
        onHelp = { showHelp = true },
        onGroupManage = { showGroupManage = true },
        onExport = { rules ->
            val services = PlatformServiceProviders.getOrNull() ?: return@ReplaceRuleListScreen
            scope.launch {
                val path = withContext(IoDispatcher) {
                    services.files.saveFile("exportReplaceRule.json")
                } ?: return@launch
                withContext(IoDispatcher) {
                    BackupFileOps.writeText(path, GSON.toJson(rules))
                }
                Toasters.get().toast("导出成功")
            }
        },
    )

    // 帮助对话框
    if (showHelp) {
        HelpDialog("replaceRuleHelp") { showHelp = false }
    }

    // 分组管理对话框 (替换规则分组为字符串标签, 非 BookGroup)
    if (showGroupManage) {
        ReplaceGroupManageDialog(
            groups = groups,
            onAddGroup = { name -> viewModel.addGroup(name) },
            onRenameGroup = { old, new -> viewModel.upGroup(old, new) },
            onDeleteGroup = { name -> viewModel.delGroup(name) },
            onDismiss = { showGroupManage = false },
        )
    }

    // 在线导入 URL 输入对话框 (含 ACache 历史记录, 对照 Activity showImportDialog)
    if (showUrlInput) {
        OnlineImportUrlDialog(
            recordKey = "replaceRuleRecordKey",
            onConfirm = { url ->
                showUrlInput = false
                val vm = ImportReplaceRuleViewModelShared(scope)
                importVm = vm
                vm.import(url)
            },
            onDismiss = { showUrlInput = false },
        )
    }

    // 导入勾选对话框
    importVm?.let { vm ->
        if (showImportDialog) {
            ImportItemsDialog(
                title = stringResource(Res.string.import_replace_rule),
                vm = remember(vm) { ImportReplaceRuleItemsVm(vm) },
                onDismiss = {
                    showImportDialog = false
                    importVm = null
                },
                onImported = {
                    showImportDialog = false
                    importVm = null
                },
            )
        }
    }
}

/**
 * 替换规则分组管理对话框 (替换规则分组为字符串标签, 与书架 BookGroup 不同)。
 */
@Composable
private fun ReplaceGroupManageDialog(
    groups: List<String>,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (oldName: String, newName: String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // null=列表态, (null, "")=新建, (oldName, value)=编辑
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = stringResource(Res.string.group_manage),
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = { editing = null to "" }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = stringResource(Res.string.add),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                if (editing != null) {
                    Column(Modifier.padding(16.dp)) {
                        AppOutlinedTextField(
                            value = editing!!.second,
                            onValueChange = { editing = editing!!.first to it },
                            label = stringResource(Res.string.group_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AppTextButton(text = stringResource(Res.string.cancel)) {
                                editing = null
                            }
                            AppTextButton(
                                text = stringResource(Res.string.ok),
                                enabled = editing!!.second.isNotBlank(),
                            ) {
                                val (old, new) = editing!!
                                val name = new.trim()
                                if (old == null) onAddGroup(name) else onRenameGroup(old, name)
                                editing = null
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(items = groups, key = { it }) { group ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = group,
                                    color = colors.primaryText,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(Res.string.edit),
                                    color = colors.secondaryText,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { editing = group to group }
                                        .padding(8.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.delete),
                                    color = colors.secondaryText,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { onDeleteGroup(group) }
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppTextButton(text = stringResource(Res.string.ok), onClick = onDismiss)
                    }
                }
            }
        }
    }
}
