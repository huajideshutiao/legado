package io.legado.app.ui.replace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
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
 *   [ImportReplaceRuleViewModelShared.import] 解析; 在线导入用 [AlertDialog] 输入 URL;
 * - **分组管理**: 复用 shared/sharedUiMain 的 [GroupManageDialog];
 * - **帮助**: 调 [openURL] 跳转正则教程;
 * - **导出**: 选中项序列化为 JSON 复制到剪贴板。
 *
 * 平台专属对话框/输入/文本/按钮改用 MD3 组件
 * ([AlertDialog]/[OutlinedTextField]/[Text]/[TextButton])。
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
    var importOnlineUrlText by remember { mutableStateOf("") }

    val importVm = remember(scope) { ImportReplaceRuleViewModelShared(scope) }

    // 文案模板 (LaunchedEffect / lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val copiedRulesTemplate = rememberString("copied_rules_to_clipboard_count")

    // 收集导入成功/失败信号
    LaunchedEffect(importVm) {
        importVm.successState.collectLatest { count ->
            if (count != null) {
                importVm.importSelect { Toasters.get().toast(String.format(importCompleteTemplate, count)) }
            }
        }
    }
    LaunchedEffect(importVm) {
        importVm.errorState.collectLatest { err ->
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
                val text = bytes.toString(Charsets.UTF_8).trim()
                importVm.import(text)
            }
        },
        onImportOnline = {
            importOnlineUrlText = ""
            showImportOnlineDialog = true
        },
        onHelp = {
            openURL("https://www.runoob.com/regexp/regexp-tutorial.html")
        },
        onGroupManage = { showGroupManage = true },
        onExport = { rules ->
            val json = GSON.toJson(rules)
            copyToClipboard(json)
            Toasters.get().toast(String.format(copiedRulesTemplate, rules.size))
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

    // 在线导入 URL 输入 Dialog (MD3 AlertDialog + OutlinedTextField)
    if (showImportOnlineDialog) {
        AlertDialog(
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(rememberString("import_on_line")) },
            text = {
                OutlinedTextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = importOnlineUrlText.trim()
                    if (url.isNotEmpty()) {
                        importVm.import(url)
                    }
                    showImportOnlineDialog = false
                }) { Text(rememberString("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { showImportOnlineDialog = false }) {
                    Text(rememberString("cancel"))
                }
            },
        )
    }
}
