package io.legado.app.ui.replace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.legado.app.ui.compose.component.Md2TextField
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
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
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
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端替换规则管理 Screen 入口 (包装 shared/sharedUiMain 的 [ReplaceRuleListScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/replace/ReplaceRuleScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 REPLACE_RULE 路由分支调用本入口。
 *
 * 业务展示与交互逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **VM 生命周期**: `remember { ReplaceRuleListViewModel() }` 持有 (commonMain 无参构造),
 *   退出时 `DisposableEffect.onDispose { viewModel.onCleared() }` 取消内部协程 scope;
 * - **编辑规则**: onAddRule/onEditRule 回调委托 IosNavHost 切到 REPLACE_EDIT 路由
 *   (由 [IosReplaceEditScreen] 包装 shared/sharedUiMain 的 [ReplaceEditScreen]);
 * - **导入**: 本地导入走 [pickDocuments] (UIDocumentPickerViewController) 选 JSON →
 *   [ImportReplaceRuleViewModelShared.import] 解析; 在线导入用 [AlertDialog] 输入 URL;
 * - **分组管理**: 复用 shared/sharedUiMain 的 [GroupManageDialog], 分组名列表来自
 *   `viewModel.groups` (String 逗号分隔, 用 groupEntities 适配 BookGroup);
 * - **帮助**: 调 [openURL] 跳转正则教程;
 * - **导出**: 选中项序列化为 JSON 复制到剪贴板 (iOS 端无文件保存面板, 简化为剪贴板)。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 * @param onAddRule 新建规则回调 (切到 REPLACE_EDIT 路由, ruleId=-1, 由 IosNavHost 注入)
 * @param onEditRule 编辑规则回调 (切到 REPLACE_EDIT 路由, ruleId=id, 由 IosNavHost 注入)
 */
@Composable
fun IosReplaceRuleScreen(
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { ReplaceRuleListViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // 分组管理 Dialog 状态 (onGroupManage 触发)
    var showGroupManage by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态 (onImportOnline 触发)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    // 导入 VM (本地/在线导入共用, import(text) 入口)
    val importVm = remember(scope) { ImportReplaceRuleViewModelShared(scope) }

    // 文案模板 (LaunchedEffect / lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val copiedRulesTemplate = rememberString("copied_rules_to_clipboard_count")

    // 收集导入成功/失败信号 (与 IosBookSourceScreen 模式一致)
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

    // 分组列表 (订阅 viewModel.groups, 用 groupEntities 适配 GroupManageDialog 期望的 List<BookGroup>)
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
            // iOS 端无文件保存面板, 简化为复制 JSON 到剪贴板
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

    // 在线导入 URL 输入 Dialog
    if (showImportOnlineDialog) {
        AlertDialog(
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(rememberString("import_on_line")) },
            text = {
                Md2TextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    label = "URL",
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
