package io.legado.app.ui.book.filter

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.legado.app.ui.compose.component.Md2TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportSourceFilterRuleViewModelShared
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端书源过滤规则管理 Screen 入口 (包装 shared/sharedUiMain 的 [SourceFilterRuleScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [SourceFilterRuleActivity] 的薄壳模式, iOS 端在 [io.legado.app.ui.IosNavHost]
 * 的 SOURCE_FILTER_RULE 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **数据订阅**: `LaunchedEffect` 订阅 `appDb.sourceFilterRuleDao.flowAll/flowSearch`,
 *   持有内存列表供拖拽排序交换;
 * - **VM**: [SourceFilterRuleViewModelShared] (commonMain, scope) 转发增删改;
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [SourceFilterEditDialog];
 * - **导入**: [ImportSourceFilterRuleViewModelShared] 处理解析, 本地走 [pickDocuments],
 *   在线走 AlertDialog;
 * - **导出**: 选中项序列化为 JSON 复制到剪贴板 (iOS 端无文件保存面板)。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 */
@Composable
fun IosSourceFilterRuleScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope) { SourceFilterRuleViewModelShared(scope) }

    // 数据列表 (订阅 flowAll/flowSearch, 持有内存副本供拖拽排序交换)
    var rules by remember { mutableStateOf<List<SourceFilterRule>>(emptyList()) }
    // 选中集合 (rule.id 为主键, String 类型)
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 搜索关键词 (空串走 flowAll, 非空走 flowSearch)
    var searchKey by remember { mutableStateOf("") }

    LaunchedEffect(searchKey) {
        val dao = AppDbProviders.get().sourceFilterRuleDao
        val flow = if (searchKey.isBlank()) dao.flowAll() else dao.flowSearch("%$searchKey%")
        flow.flowOn(Dispatchers.Default).collectLatest { rules = it }
    }

    // 编辑对话框状态 (onAddRule/onEditRule 触发; null=新增, 非空=编辑)
    var editTarget by remember { mutableStateOf<SourceFilterRule?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    val importVm = remember(scope) { ImportSourceFilterRuleViewModelShared(scope) }
    // 文案模板 (LaunchedEffect / onExportSelection lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val copiedRulesTemplate = rememberString("copied_rules_to_clipboard_count")

    // 收集导入成功/失败信号 (与 IosDictRuleScreen 模式一致)
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

    val state = remember(rules, selected, searchKey) {
        SourceFilterRuleUiState(rules = rules, selected = selected, searchKey = searchKey)
    }

    SourceFilterRuleScreen(
        state = state,
        actions = object : SourceFilterRuleUiActions {
            override fun onBack() = onBack()
            override fun onSearchKeyChange(key: String) { searchKey = key }
            override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {
                selected = if (checked) selected + item.id else selected - item.id
            }
            override fun onSelectAll(all: Boolean) {
                selected = if (all) rules.map { it.id }.toSet() else emptySet()
            }
            override fun onRevertSelection() {
                selected = rules.map { it.id }.toSet() - selected
            }
            override fun onMoveItem(from: Int, to: Int) {
                // 即时交换内存列表供 UI 显示, 松手落库由 onPersistOrder 触发
                rules = rules.toMutableList().apply { add(to, removeAt(from)) }
            }
            override fun onPersistOrder() = viewModel.upOrder()
            override fun onDeleteSelection() {
                val sel = rules.filter { it.id in selected }
                viewModel.delSelection(sel)
                selected = emptySet()
            }
            override fun onDeleteRule(rule: SourceFilterRule) = viewModel.delete(rule)
            override fun onDeleteAll() {
                scope.launch(Dispatchers.Default) {
                    AppDbProviders.get().sourceFilterRuleDao.deleteAll()
                }
            }
            override fun onEnableSelection() {
                val sel = rules.filter { it.id in selected }
                viewModel.enableSelection(sel)
            }
            override fun onDisableSelection() {
                val sel = rules.filter { it.id in selected }
                viewModel.disableSelection(sel)
            }
            override fun onTopSelect() {
                val sel = rules.filter { it.id in selected }
                viewModel.topSelect(sel)
            }
            override fun onBottomSelect() {
                val sel = rules.filter { it.id in selected }
                viewModel.bottomSelect(sel)
            }
            override fun onExportSelection() {
                val sel = rules.filter { it.id in selected }
                copyToClipboard(GSON.toJson(sel))
                Toasters.get().toast(String.format(copiedRulesTemplate, sel.size))
            }
            override fun onEditRule(rule: SourceFilterRule) {
                editTarget = rule
                showEditDialog = true
            }
            override fun onToTop(rule: SourceFilterRule) = viewModel.toTop(rule)
            override fun onToBottom(rule: SourceFilterRule) = viewModel.toBottom(rule)
            override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {
                viewModel.update(rule.copy(enabled = enabled))
            }
            override fun onAddRule() {
                editTarget = null
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
                    importVm.import(bytes.toString(Charsets.UTF_8).trim())
                }
            }
            override fun onImportOnline() {
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            }
        },
    )

    // 编辑对话框 (复用 shared/sharedUiMain 的 SourceFilterEditDialog)
    if (showEditDialog) {
        SourceFilterEditDialog(
            rule = editTarget,
            onConfirm = { rule ->
                // 新增 insert / 编辑 update; VM 层均走 update (REPLACE 冲突策略等价)
                scope.launch(Dispatchers.Default) {
                    AppDbProviders.get().sourceFilterRuleDao.insert(rule)
                }
                editTarget = null
                showEditDialog = false
            },
            onDismiss = {
                editTarget = null
                showEditDialog = false
            },
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
                    if (url.isNotEmpty()) importVm.import(url)
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
