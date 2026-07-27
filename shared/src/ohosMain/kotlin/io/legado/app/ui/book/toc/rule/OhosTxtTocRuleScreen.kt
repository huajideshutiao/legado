package io.legado.app.ui.book.toc.rule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.saveDocument
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportTxtTocRuleItemsVm
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.formatNative
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
 * - **数据订阅**: `LaunchedEffect` 订阅 `appDb.txtTocRuleDao.observeAll()`, 持有内存列表供拖拽排序;
 * - **VM**: [TxtTocRuleViewModelShared] (commonMain, scope + importDefaultRules lambda);
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [TxtTocRuleEditDialog];
 * - **导入**: [ImportTxtTocRuleViewModelShared] 处理解析, 本地走 [pickDocuments], 在线走 [OnlineImportUrlDialog];
 * - **导出**: 选中项序列化为 JSON, [saveDocument] 弹系统保存器写真文件 (对照 app 端
 *   HandleFileContract.EXPORT "exportTxtTocRule.json"); 桥未就绪/保存失败/取消降级复制到剪贴板;
 * - **帮助**: HelpDialog 渲染 txtTocRuleHelp.md (对照 app 端 showHelp);
 * - **导入默认规则**: 调 [DefaultDataShared.importDefaultTocRules] (与 desktop 端一致)。
 *
 * @param onBack 返回回调 (切回调用方路由)
 */
@Composable
fun OhosTxtTocRuleScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope) {
        TxtTocRuleViewModelShared(
            scope = scope,
            // 默认规则走 composeResources 单一数据源 (与 desktop 端一致)
            importDefaultRules = {
                runCatching { DefaultDataShared.importDefaultTocRules() }
                    .onFailure { AppLog.put("导入默认目录规则失败", it) }
            },
        )
    }

    // 数据列表 (订阅 observeAll, 持有内存副本供拖拽排序交换)
    var tocRules by remember { mutableStateOf<List<TxtTocRule>>(emptyList()) }
    // 选中集合 (rule.id 为主键)
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(Unit) {
        AppDbProviders.get().txtTocRuleDao.observeAll().flowOn(Dispatchers.Default).collectLatest {
            tocRules = it
        }
    }

    // 编辑对话框状态 (onAddRule/onEditRule 触发; null=新增, 非空=编辑)
    var editTarget by remember { mutableStateOf<TxtTocRule?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    // 在线导入 URL 输入 Dialog 状态
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    // 帮助文档对话框状态 (onHelp 触发)
    var showHelpDialog by remember { mutableStateOf(false) }

    // 导入 VM: 每次导入新建 (与 app 端每次弹窗新建 VM 一致), 避免列表跨次导入累积错位
    var importVm by remember { mutableStateOf<ImportTxtTocRuleViewModelShared?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 文案模板 (LaunchedEffect / onExportSelection lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val copiedRulesTemplate = rememberString("copied_rules_to_clipboard_count")
    val wrongFormatText = rememberString("wrong_format")
    val exportSuccessText = rememberString("export_success")

    // 解析成功 → 弹勾选对话框 (对照 app 端 ImportTxtTocRuleDialog); 失败 → toast
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
                    val text = bytes.decodeToString().trim()
                    importVm = ImportTxtTocRuleViewModelShared(scope).also { it.importSource(text) }
                }
            }
            override fun onImportOnline() {
                showImportOnlineDialog = true
            }
            override fun onImportDefault() = viewModel.importDefault()
            override fun onHelp() {
                // 对应 app 端 showHelp("txtTocRuleHelp")
                showHelpDialog = true
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
                scope.launch {
                    val json = GSON.toJson(rules)
                    // 真文件导出: DocumentViewPicker.save (文件名对照 app 端 HandleFileContract.EXPORT);
                    // saveDocument 桥未就绪/保存失败抛异常, 与用户取消一并降级复制到剪贴板
                    val saved = withContext(Dispatchers.Default) {
                        runCatching { saveDocument("exportTxtTocRule.json", json.encodeToByteArray()) }
                            .onFailure { AppLog.put("导出 TXT 目录规则失败", it) }
                            .getOrDefault(false)
                    }
                    if (saved) {
                        Toasters.get().toast(exportSuccessText)
                    } else {
                        copyToClipboard(json)
                        Toasters.get().toast(copiedRulesTemplate.formatNative(rules.size))
                    }
                }
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

    // 编辑对话框 (复用 shared/sharedUiMain 的 TxtTocRuleEditDialog, Dialog 外壳补遮罩/居中)
    if (showEditDialog) {
        Dialog(onDismissRequest = {
            editTarget = null
            showEditDialog = false
        }) {
            TxtTocRuleEditDialog(
                rule = editTarget,
                onConfirm = { rule ->
                    // insert onConflict=REPLACE, 新增/编辑同路径落库
                    scope.launch { AppDbProviders.get().txtTocRuleDao.insert(rule) }
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
    }

    // 导入勾选对话框 (确认后仅入库勾选项)
    if (showImportDialog) importVm?.let { vm ->
        ImportItemsDialog(
            title = rememberString("import_txt_toc_rule"),
            vm = remember(vm) { ImportTxtTocRuleItemsVm(vm) },
            onDismiss = {
                showImportDialog = false
                importVm = null
            },
            onImported = { count ->
                Toasters.get().toast(importCompleteTemplate.formatNative(count))
            },
        )
    }

    // 在线导入 URL 输入 Dialog (带历史下拉, 对照 app 端 TxtTocRuleActivity.showImportDialog)
    if (showImportOnlineDialog) {
        OnlineImportUrlDialog(
            recordKey = "tocRuleUrl",
            defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json",
            onConfirm = { url ->
                importVm = ImportTxtTocRuleViewModelShared(scope).also { it.importSource(url) }
            },
            onDismiss = { showImportOnlineDialog = false },
        )
    }

    // 帮助文档对话框 (onHelp 触发, 渲染 txtTocRuleHelp.md)
    if (showHelpDialog) {
        HelpDialog(fileName = "txtTocRuleHelp", onDismiss = { showHelpDialog = false })
    }
}
