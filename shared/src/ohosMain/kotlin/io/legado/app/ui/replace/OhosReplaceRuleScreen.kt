package io.legado.app.ui.replace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.saveDocument
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportReplaceRuleItemsVm
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.formatNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端替换规则管理 Screen 入口 (包装 shared/sharedUiMain 的 [ReplaceRuleListScreen])。
 *
 * 对照 iOS [IosReplaceRuleScreen] 的包装模式, 鸿蒙端在 OhosNavHost 的 REPLACE_RULE
 * 路由分支调用本入口。
 *
 * 业务展示与交互逻辑全部下沉到 shared/sharedUiMain, 本文件仅做鸿蒙平台适配:
 * - **VM 生命周期**: `remember { ReplaceRuleListViewModel() }` 持有,
 *   退出时 `DisposableEffect.onDispose { viewModel.onCleared() }` 取消内部协程;
 * - **编辑规则**: onAddRule/onEditRule 回调委托 OhosNavHost 切到 REPLACE_EDIT 路由;
 * - **导入**: 本地导入走 [pickDocuments] 选 JSON →
 *   [ImportReplaceRuleViewModelShared.import] 解析; 在线导入用 [OnlineImportUrlDialog] (带历史);
 * - **分组管理**: 复用 shared/sharedUiMain 的 [GroupManageDialog];
 * - **帮助**: HelpDialog 渲染 replaceRuleHelp.md (对照 app 端 showHelp);
 * - **导出**: 选中项序列化为 JSON, [saveDocument] 弹系统保存器写真文件 (对照 app 端
 *   HandleFileContract.EXPORT "exportReplaceRule.json"); 桥未就绪/保存失败/取消降级复制到剪贴板。
 *
 * @param onBack 返回回调 (切回调用方路由)
 * @param onAddRule 新建规则回调 (切到 REPLACE_EDIT 路由, ruleId=-1)
 * @param onEditRule 编辑规则回调 (切到 REPLACE_EDIT 路由, ruleId=id)
 */
@Composable
fun OhosReplaceRuleScreen(
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { ReplaceRuleListViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // 分组管理 Dialog 状态
    var showGroupManage by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    // 帮助文档对话框状态 (onHelp 触发)
    var showHelpDialog by remember { mutableStateOf(false) }

    // 导入 VM: 每次导入新建 (与 app 端每次弹窗新建 VM 一致), 避免列表跨次导入累积错位
    var importVm by remember { mutableStateOf<ImportReplaceRuleViewModelShared?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    // 文案模板 (LaunchedEffect / lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val copiedRulesTemplate = rememberString("copied_rules_to_clipboard_count")
    val wrongFormatText = rememberString("wrong_format")
    val exportSuccessText = rememberString("export_success")

    // 解析成功 → 弹勾选对话框 (对照 app 端 ImportReplaceRuleDialog); 失败 → toast
    LaunchedEffect(importVm) {
        val vm = importVm ?: return@LaunchedEffect
        launch {
            vm.successState.collectLatest { count ->
                if (count != null) {
                    if (count > 0) showImportDialog = true
                    else Toasters.get().toast(wrongFormatText)
                }
            }
        }
        vm.errorState.collectLatest { err ->
            if (err != null) Toasters.get().toast(err.substringAfter("ImportError:"))
        }
    }

    // 分组列表 (订阅 viewModel.groups, 适配 GroupManageDialog 期望的 List<BookGroup>)
    val groupNames by viewModel.groups.collectAsState()
    val groupEntities = remember(groupNames) {
        groupNames.mapIndexed { index, name ->
            BookGroup(groupId = (index + 1).toLong(), groupName = name)
        }
    }

    ReplaceRuleListScreen(
        viewModel = viewModel,
        onBack = onBack,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onImportLocal = {
            scope.launch {
                val urls = pickDocuments(
                    contentTypes = listOf("public.json", "public.text"),
                    allowsMultiple = false,
                ) ?: return@launch
                val firstUrl = urls.firstOrNull() ?: return@launch
                val bytes = withContext(Dispatchers.Default) { pickDocumentContent(firstUrl) }
                    ?: return@launch
                val text = bytes.decodeToString().trim()
                importVm = ImportReplaceRuleViewModelShared(scope).also { it.import(text) }
            }
        },
        onImportOnline = {
            showImportOnlineDialog = true
        },
        onHelp = {
            // 对应 app 端 showHelp("replaceRuleHelp")
            showHelpDialog = true
        },
        onGroupManage = { showGroupManage = true },
        onExport = { rules ->
            scope.launch {
                val json = GSON.toJson(rules)
                // 真文件导出: DocumentViewPicker.save (文件名对照 app 端 HandleFileContract.EXPORT);
                // saveDocument 桥未就绪/保存失败抛异常, 与用户取消一并降级复制到剪贴板
                val saved = withContext(Dispatchers.Default) {
                    runCatching { saveDocument("exportReplaceRule.json", json.encodeToByteArray()) }
                        .onFailure { AppLog.put("导出替换规则失败", it) }
                        .getOrDefault(false)
                }
                if (saved) {
                    Toasters.get().toast(exportSuccessText)
                } else {
                    copyToClipboard(json)
                    Toasters.get().toast(copiedRulesTemplate.formatNative(rules.size))
                }
            }
        },
    )

    // 分组管理 Dialog (复用 shared/sharedUiMain 的 GroupManageDialog)
    if (showGroupManage) {
        GroupManageDialog(
            groups = groupEntities,
            onAddGroup = { name -> viewModel.addGroup(name) },
            onRenameGroup = { groupId, newName ->
                groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { oldName ->
                    viewModel.upGroup(oldName, newName)
                }
            },
            onDeleteGroup = { groupId ->
                groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { name ->
                    viewModel.delGroup(name)
                }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // 导入勾选对话框 (确认后仅入库勾选项)
    if (showImportDialog) importVm?.let { vm ->
        ImportItemsDialog(
            title = rememberString("import_replace_rule"),
            vm = remember(vm) { ImportReplaceRuleItemsVm(vm) },
            onDismiss = {
                showImportDialog = false
                importVm = null
            },
            onImported = { count ->
                Toasters.get().toast(importCompleteTemplate.formatNative(count))
            },
        )
    }

    // 在线导入 URL 输入 Dialog (带历史下拉, 对照 app 端 ReplaceRuleActivity.showImportDialog)
    if (showImportOnlineDialog) {
        OnlineImportUrlDialog(
            recordKey = "replaceRuleRecordKey",
            onConfirm = { url ->
                importVm = ImportReplaceRuleViewModelShared(scope).also { it.import(url) }
            },
            onDismiss = { showImportOnlineDialog = false },
        )
    }

    // 帮助文档对话框 (onHelp 触发, 渲染 replaceRuleHelp.md)
    if (showHelpDialog) {
        HelpDialog(fileName = "replaceRuleHelp", onDismiss = { showHelpDialog = false })
    }
}
