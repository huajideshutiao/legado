package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端 TXT 目录规则管理 Screen 入口 (包装 shared/sharedUiMain 的 [TxtTocRuleScreen])。
 *
 * 对照 iOS [IosTxtTocRuleScreen] 的包装模式, 鸿蒙端在 OhosNavHost 的 TXT_TOC_RULE
 * 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做鸿蒙平台适配:
 * - **数据订阅**: `LaunchedEffect` 订阅 `appDb.txtTocRuleDao.flowAll()`, 持有内存列表供拖拽排序;
 * - **VM**: [TxtTocRuleViewModelShared] (commonMain, scope + importDefaultRules lambda);
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [TxtTocRuleEditDialog];
 * - **导入**: [ImportTxtTocRuleViewModelShared] 处理解析, 本地走 [pickDocuments], 在线走 [AlertDialog];
 * - **导出**: 选中项序列化为 JSON 复制到剪贴板;
 * - **帮助**: 调 [openURL] 跳转 TXT 目录规则帮助页;
 * - **导入默认规则**: 鸿蒙端无 DefaultData, lambda 内 toast 提示暂不支持。
 *
 * 平台专属对话框/输入/文本/按钮改用 MD3 组件
 * ([AlertDialog]/[OutlinedTextField]/[Text]/[TextButton])。
 *
 * @param onBack 返回回调 (切回调用方路由)
 */
@Composable
fun OhosTxtTocRuleScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 鸿蒙端待接入提示文案 (importDefaultRules lambda 非 @Composable, 需预先缓存)
    val importDefaultTxtTocRuleText = rememberString("ios_import_default_txt_toc_rule_not_supported")
    val viewModel = remember(scope) {
        TxtTocRuleViewModelShared(
            scope = scope,
            // 鸿蒙端无 DefaultData (依赖 Android assets), 暂不支持导入默认规则
            importDefaultRules = {
                Toasters.get().toast(importDefaultTxtTocRuleText)
            },
        )
    }

    // 数据列表 (订阅 flowAll, 持有内存副本供拖拽排序交换)
    var tocRules by remember { mutableStateOf<List<TxtTocRule>>(emptyList()) }
    // 选中集合 (rule.id 为主键)
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(Unit) {
        AppDbProviders.get().txtTocRuleDao.flowAll().flowOn(Dispatchers.Default).collectLatest {
            tocRules = it
        }
    }

    // 编辑对话框状态 (onAddRule/onEditRule 触发; null=新增, 非空=编辑)
    var editTarget by remember { mutableStateOf<TxtTocRule?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    val importVm = remember(scope) { ImportTxtTocRuleViewModelShared(scope) }
    // 文案模板 (LaunchedEffect / onExportSelection lambda 非 @Composable, 预先 remember 模板)
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

    val state = remember(tocRules, selected) { TxtTocRuleUiState(tocRules, selected) }

    TxtTocRuleScreen(
        state = state,
        actions = object : TxtTocRuleUiActions {
            override fun onBack() = onBack()
            override fun onAddRule() {
                editTarget = null
                showEditDialog = true
            }
            override fun onEditRule(item: TxtTocRule) {
                editTarget = item
                showEditDialog = true
            }
            override fun onImportLocal() {
                scope.launch {
                    val urls = pickDocuments(
                        contentTypes = listOf("public.json", "public.text"),
                        allowsMultiple = false,
                    ) ?: return@launch
                    val firstUrl = urls.firstOrNull() ?: return@launch
                    val bytes = withContext(Dispatchers.Default) { pickDocumentContent(firstUrl) }
                        ?: return@launch
                    importVm.importSource(bytes.toString(Charsets.UTF_8).trim())
                }
            }
            override fun onImportOnline() {
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            }
            override fun onImportDefault() = viewModel.importDefault()
            override fun onHelp() {
                openURL("https://github.com/gedoor/legado/wiki/TXT目录规则")
            }
            override fun onToggleSelect(item: TxtTocRule, checked: Boolean) {
                selected = if (checked) selected + item.id else selected - item.id
            }
            override fun onSelectAll(all: Boolean) {
                selected = if (all) tocRules.map { it.id }.toSet() else emptySet()
            }
            override fun onRevertSelection() {
                selected = tocRules.map { it.id }.toSet() - selected
            }
            override fun onDelSelection() {
                val rules = tocRules.filter { it.id in selected }
                viewModel.del(*rules.toTypedArray())
                selected = emptySet()
            }
            override fun onDel(item: TxtTocRule) = viewModel.del(item)
            override fun onEnableSelection(enabled: Boolean) {
                val rules = tocRules.filter { it.id in selected }
                if (enabled) viewModel.enableSelection(*rules.toTypedArray())
                else viewModel.disableSelection(*rules.toTypedArray())
            }
            override fun onExportSelection() {
                val rules = tocRules.filter { it.id in selected }
                copyToClipboard(GSON.toJson(rules))
                Toasters.get().toast(String.format(copiedRulesTemplate, rules.size))
            }
            override fun onMove(from: Int, to: Int) {
                // 即时交换内存列表供 UI 显示, 松手落库由 onPersistOrder 触发
                tocRules = tocRules.toMutableList().apply {
                    add(to, removeAt(from))
                }
            }
            override fun onPersistOrder() = viewModel.upOrder()
            override fun onToTop(item: TxtTocRule) = viewModel.toTop(item)
            override fun onToBottom(item: TxtTocRule) = viewModel.toBottom(item)
            override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {
                viewModel.update(item.copy(enable = enabled))
            }
        },
    )

    // 编辑对话框 (复用 shared/sharedUiMain 的 TxtTocRuleEditDialog)
    if (showEditDialog) {
        TxtTocRuleEditDialog(
            rule = editTarget,
            onConfirm = { rule ->
                // 保存 (新增 insert / 编辑 update; TxtTocRuleEditViewModelShared 内部处理)
                editTarget = null
                showEditDialog = false
            },
            onDismiss = {
                editTarget = null
                showEditDialog = false
            },
            clipTextProvider = { readFromClipboard() },
            clipTextSink = { text -> copyToClipboard(text) },
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
                    if (url.isNotEmpty()) importVm.importSource(url)
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
