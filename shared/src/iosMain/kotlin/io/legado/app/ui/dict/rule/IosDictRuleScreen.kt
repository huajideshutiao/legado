package io.legado.app.ui.dict.rule

import androidx.compose.foundation.layout.fillMaxSize
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
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端字典规则管理 Screen 入口 (包装 shared/sharedUiMain 的 [DictRuleScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [DictRuleActivity] 的薄壳模式, iOS 端在 [io.legado.app.ui.IosNavHost]
 * 的 DICT_RULE 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **数据订阅**: `LaunchedEffect` 订阅 `appDb.dictRuleDao.flowAll()`, 持有内存列表供拖拽排序;
 * - **VM**: [DictRuleViewModelShared] (commonMain, scope + importDefaultRules lambda);
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [DictRuleEditDialog];
 * - **导入**: [ImportDictRuleViewModelShared] 处理解析, 本地走 [pickDocuments], 在线走 AlertDialog;
 * - **导出**: 选中项序列化为 JSON 复制到剪贴板 (iOS 端无文件保存面板);
 * - **帮助**: 调 [openURL] 跳转字典规则帮助页;
 * - **导入默认规则**: iOS 端无 DefaultData, lambda 内 toast 提示暂不支持。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 */
@Composable
fun IosDictRuleScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // iOS 端待接入提示文案 (importDefaultRules lambda 非 @Composable, 需预先缓存)
    val importDefaultDictRuleText = rememberString("ios_import_default_dict_rule_not_supported")
    val viewModel = remember(scope) {
        DictRuleViewModelShared(
            scope = scope,
            // iOS 端无 DefaultData (依赖 Android assets), 暂不支持导入默认规则
            importDefaultRules = {
                Toasters.get().toast(importDefaultDictRuleText)
            },
        )
    }

    // 数据列表 (订阅 flowAll, 持有内存副本供拖拽排序交换)
    var dictRules by remember { mutableStateOf<List<DictRule>>(emptyList()) }
    // 选中集合 (rule.name 为主键)
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        AppDbProviders.get().dictRuleDao.flowAll().flowOn(Dispatchers.Default).collectLatest {
            dictRules = it
        }
    }

    // 编辑对话框状态 (onAddRule/onEditRule 触发; null=新增, 非空=编辑)
    var editTarget by remember { mutableStateOf<DictRule?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    val importVm = remember(scope) { ImportDictRuleViewModelShared(scope) }
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

    val state = remember(dictRules, selected) { DictRuleUiState(dictRules, selected) }

    DictRuleScreen(
        state = state,
        actions = object : DictRuleUiActions {
            override fun onBack() = onBack()
            override fun onAddRule() {
                editTarget = null
                showEditDialog = true
            }
            override fun onEditRule(name: String) {
                editTarget = dictRules.find { it.name == name }
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
                openURL("https://github.com/gedoor/legado/wiki/字典规则")
            }
            override fun onToggleSelected(item: DictRule, checked: Boolean) {
                selected = if (checked) selected + item.name else selected - item.name
            }
            override fun onSelectAll(all: Boolean) {
                selected = if (all) dictRules.map { it.name }.toSet() else emptySet()
            }
            override fun onRevertSelection() {
                selected = dictRules.map { it.name }.toSet() - selected
            }
            override fun onMoveItem(from: Int, to: Int) {
                // 即时交换内存列表供 UI 显示, 松手落库由 onPersistOrder 触发
                dictRules = dictRules.toMutableList().apply {
                    add(to, removeAt(from))
                }
            }
            override fun onPersistOrder() = viewModel.upSortNumber()
            override fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean) {
                viewModel.update(item.copy(enabled = enabled))
            }
            override fun onDeleteRule(rule: DictRule) = viewModel.delete(rule)
            override fun onDeleteSelection() {
                val rules = dictRules.filter { it.name in selected }
                viewModel.delete(*rules.toTypedArray())
                selected = emptySet()
            }
            override fun onEnableSelection() {
                val rules = dictRules.filter { it.name in selected }
                viewModel.enableSelection(*rules.toTypedArray())
            }
            override fun onDisableSelection() {
                val rules = dictRules.filter { it.name in selected }
                viewModel.disableSelection(*rules.toTypedArray())
            }
            override fun onExportSelection() {
                val rules = dictRules.filter { it.name in selected }
                copyToClipboard(GSON.toJson(rules))
                Toasters.get().toast(String.format(copiedRulesTemplate, rules.size))
            }
        },
    )

    // 编辑对话框 (复用 shared/sharedUiMain 的 DictRuleEditDialog)
    if (showEditDialog) {
        DictRuleEditDialog(
            rule = editTarget,
            onConfirm = { rule ->
                // 保存 (新增 insert / 编辑 update; DictRuleEditViewModelShared 内部 delete 旧 + insert 新)
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
