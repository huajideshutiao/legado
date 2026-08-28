package io.legado.app.ui.root

import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportDictRuleItemsVm
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportItemsVm
import io.legado.app.ui.association.ImportSourceFilterRuleItemsVm
import io.legado.app.ui.association.ImportSourceFilterRuleViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleItemsVm
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.book.filter.SourceFilterEditDialog
import io.legado.app.ui.book.filter.SourceFilterRuleListDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dict.rule.DictRuleEditDialog
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.export_success
import legado.shared.generated.resources.import_dict_rule
import legado.shared.generated.resources.import_source_filter_rule
import legado.shared.generated.resources.import_txt_toc_rule
import legado.shared.generated.resources.wrong_format
import org.jetbrains.compose.resources.stringResource

/**
 * 规则类 Overlay Dialog 的渲染实现 (字典规则 / TXT 目录规则 / 屏蔽规则的编辑、导入、导出、帮助)。
 *
 * 由 LegadoApp 的 DialogOverlayContent 按 key 分流调用。落库后各页 ScreenModel 订阅的
 * DAO flow 自动回推, 故无需经 overlayResults 回传结果。
 */

/**
 * 编辑类对话框的浮层容器。三个 Edit 对话框原是 BaseComposeDialogFragment 的 Content,
 * 只画 Surface 不含窗口, 走 Overlay 渲染时需要在此补 [Dialog] 外壳。
 */
@Composable
internal fun EditDialogHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties()
    ) {
        // 不能套 fillMaxSize: 撑满窗口会让整窗都算"框内", 点外部永远关不掉; 居中由 RootMeasurePolicy 负责。
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
        ) {
            content()
        }
    }
}

// 字典规则编辑 (key="dictRuleEdit", payload=规则 name, null=新增)
@Composable
internal fun DictRuleEditDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    var rule by remember(overlay.payload) { mutableStateOf<DictRule?>(null) }
    var loaded by remember(overlay.payload) { mutableStateOf(overlay.payload == null) }
    // 对照 app 端 DictRuleEditDialog.initData: 按 name 到达端重查最新 DB 行
    LaunchedEffect(overlay.payload) {
        val name = overlay.payload ?: return@LaunchedEffect
        rule = withContext(IoDispatcher) { AppDbProviders.get().dictRuleDao.getByName(name) }
        loaded = true
    }
    if (!loaded) return
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        DictRuleEditDialog(
            // save 由 DictRuleEditViewModelShared 内部完成 (delete 旧 + insert 新), 无需 onConfirm 落库
            rule = rule,
            onConfirm = {},
            onDismiss = { navigator.dismissOverlay(overlay.key) },
            clipTextProvider = { PlatformCapabilityProviders.getOrNull()?.getClipboardText() },
            clipTextSink = { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(it) },
        )
    }
}

// TXT 目录规则编辑 (key="txtTocRuleEdit", payload=规则 id, null=新增)
@Composable
internal fun TxtTocRuleEditDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    var rule by remember(overlay.payload) { mutableStateOf<TxtTocRule?>(null) }
    var loaded by remember(overlay.payload) { mutableStateOf(overlay.payload == null) }
    // 对照 app 端 TxtTocRuleEditDialog.initData: 按 id 到达端重查最新 DB 行
    LaunchedEffect(overlay.payload) {
        val id = overlay.payload?.toLongOrNull() ?: return@LaunchedEffect
        rule = withContext(IoDispatcher) { AppDbProviders.get().txtTocRuleDao.get(id) }
        loaded = true
    }
    if (!loaded) return
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        TxtTocRuleEditDialog(
            rule = rule,
            // 对照 app 端 TxtTocRuleActivity.saveTxtTocRule → viewModel.save (insert)
            onConfirm = { saved ->
                scope.launch(IoDispatcher) { AppDbProviders.get().txtTocRuleDao.insert(saved) }
            },
            onDismiss = { navigator.dismissOverlay(overlay.key) },
            clipTextProvider = { PlatformCapabilityProviders.getOrNull()?.getClipboardText() },
            clipTextSink = { PlatformCapabilityProviders.getOrNull()?.copyToClipboard(it) },
        )
    }
}

// 屏蔽规则编辑 (key="sourceFilterRuleEdit", payload=规则 id, null=新增)
@Composable
internal fun SourceFilterEditDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    var rule by remember(overlay.payload) { mutableStateOf<SourceFilterRule?>(null) }
    var loaded by remember(overlay.payload) { mutableStateOf(overlay.payload == null) }
    // 对照 app 端 SourceFilterEditDialog.onCreate: 传主键到达端重查最新 DB 行
    LaunchedEffect(overlay.payload) {
        val id = overlay.payload ?: return@LaunchedEffect
        rule = withContext(IoDispatcher) { AppDbProviders.get().sourceFilterRuleDao.get(id) }
        loaded = true
    }
    if (!loaded) return
    val isNew = overlay.payload == null
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        SourceFilterEditDialog(
            rule = rule,
            // 对照 app 端 SourceFilterRuleActivity.onSourceFilterRuleSave
            onConfirm = { saved ->
                scope.launch(IoDispatcher) { SearchBookFilter.save(saved, isNew) }
            },
            onDismiss = { navigator.dismissOverlay(overlay.key) },
        )
    }
}

// 屏蔽规则列表 (key="sourceFilterRuleList", payload=SearchScope 字符串)
@Composable
internal fun SourceFilterRuleListDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    SourceFilterRuleListDialog(
        scope = overlay.payload,
        onManageAll = {
            navigator.dismissOverlay(overlay.key)
            navigator.push(AppRoute.SourceFilterRule)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 帮助文档 (key="help" 时 payload 为 md 文件名; key="dictRuleHelp" 为固定文档)
@Composable
internal fun HelpDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    fileName: String,
) {
    if (fileName.isEmpty()) {
        LaunchedEffect(overlay.key) { navigator.dismissOverlay(overlay.key) }
        return
    }
    HelpDialog(fileName) { navigator.dismissOverlay(overlay.key) }
}

/** 规则导入类型: 归一化三种 Import*ViewModelShared 的入口方法与列表适配器差异。 */
internal enum class RuleImportKind { DICT, TXT_TOC, SOURCE_FILTER }

/**
 * 本地文件导入 (key="*ImportLocal"): 选文件 → 读文本 → 解析比对 → 勾选导入对话框。
 * 对照 app 端 `importDoc.launch { mode = FILE }` + Import*Dialog(uri)。
 */
@Composable
internal fun RuleImportLocalDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    kind: RuleImportKind,
) {
    RuleImportDialogContent(overlay, navigator, kind, source = null)
}

/**
 * 在线导入 (key="*ImportOnline"): 先弹 URL 输入框 (带 ACache 历史), 确认后走同一条解析链。
 * 对照 app 端 showImportDialog() + Import*Dialog(url)。
 */
@Composable
internal fun RuleImportOnlineDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    kind: RuleImportKind,
) {
    var url by remember(overlay.key) { mutableStateOf<String?>(null) }
    var dismissRequested by remember(overlay.key) { mutableStateOf(false) }
    if (url == null) {
        if (!dismissRequested) {
            OnlineImportUrlDialog(
                recordKey = kind.importRecordKey,
                defaultUrl = kind.defaultImportUrl,
                onConfirm = { url = it },
                onDismiss = { dismissRequested = true },
            )
        }
        // OnlineImportUrlDialog 确定路径也会先调 onDismiss 再调 onConfirm,
        // 故延到重组后判断 url 是否已填, 只有真取消才关 overlay
        LaunchedEffect(dismissRequested) {
            if (dismissRequested && url == null) navigator.dismissOverlay(overlay.key)
        }
        return
    }
    RuleImportDialogContent(overlay, navigator, kind, source = url)
}

/**
 * 规则导入通用链路: [source] 为 null 时先走文件选择器取文本, 否则直接用 URL;
 * 交对应 Import*ViewModelShared 解析比对, 解析出结果或失败都弹 [ImportItemsDialog] (失败时窗内显示错误)。
 */
@Composable
private fun RuleImportDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    kind: RuleImportKind,
    source: String?,
) {
    val scope = rememberCoroutineScope()
    val target = remember(overlay.key, source) { RuleImportTarget.of(kind, scope) }
    var showDialog by remember(overlay.key, source) { mutableStateOf(false) }
    // 导入失败 / 解析出 0 条的提示文案, 显示在对话框内且不关窗 (对照原版 Import*Dialog 的 tv_msg)
    var importError by remember(overlay.key, source) { mutableStateOf<String?>(null) }
    val strWrongFormat = stringResource(Res.string.wrong_format)

    LaunchedEffect(target) {
        launch {
            target.successState.collect { count ->
                importError = if (count == 0) strWrongFormat else null
                showDialog = true
            }
        }
        launch {
            target.errorState.collect { err ->
                importError = err
                showDialog = true
            }
        }
        // 文件分支: 平台文件选择器取路径后读文本 (对照 app 端 uri.readText); 取消选择即关闭
        val text = if (source != null) {
            source
        } else {
            val services = PlatformServiceProviders.getOrNull()
            val path = services?.let {
                withContext(IoDispatcher) { it.files.pickFile(FileFilter.Text) }
            }
            if (path == null) {
                navigator.dismissOverlay(overlay.key)
                return@LaunchedEffect
            }
            withContext(IoDispatcher) { BackupFileOps.readText(path) }
        }
        target.startImport(text)
    }

    if (!showDialog) return
    ImportItemsDialog(
        title = kind.importTitle(),
        vm = target.items,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onImported = { navigator.dismissOverlay(overlay.key) },
        errorText = importError,
    )
}

/**
 * 规则导出 (key="*Export", payload=选中规则 JSON): 选保存路径后写文件。
 * 对照 app 端 `exportResult.launch { mode = EXPORT; fileData = ... }` + showExportSuccess。
 */
@Composable
internal fun RuleExportDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    fileName: String,
) {
    val json = overlay.payload.orEmpty()
    val successText = stringResource(Res.string.export_success)
    LaunchedEffect(overlay.key) {
        val services = PlatformServiceProviders.getOrNull()
        val path = services?.let { withContext(IoDispatcher) { it.files.saveFile(fileName) } }
        if (path != null) {
            withContext(IoDispatcher) { BackupFileOps.writeText(path, json) }
            Toasters.get().toast(successText)
        }
        navigator.dismissOverlay(overlay.key)
    }
}

/** 各规则类型的在线导入历史 key (与 app 端 Activity 内常量一致)。 */
private val RuleImportKind.importRecordKey: String
    get() = when (this) {
        RuleImportKind.DICT -> "dictRuleUrls"
        RuleImportKind.TXT_TOC -> "tocRuleUrl"
        RuleImportKind.SOURCE_FILTER -> "sourceFilterRuleRecordKey"
    }

/** TXT 目录规则在线导入带默认 URL (对照 app 端 TxtTocRuleActivity.showImportDialog)。 */
private val RuleImportKind.defaultImportUrl: String?
    get() = when (this) {
        RuleImportKind.TXT_TOC ->
            "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"

        else -> null
    }

@Composable
private fun RuleImportKind.importTitle(): String = when (this) {
    RuleImportKind.DICT -> stringResource(Res.string.import_dict_rule)
    RuleImportKind.TXT_TOC -> stringResource(Res.string.import_txt_toc_rule)
    RuleImportKind.SOURCE_FILTER -> stringResource(Res.string.import_source_filter_rule)
}

/** 归一化三种 Import*ViewModelShared (入口方法名与列表适配器不同), 对照 DeepLinkImportTarget。 */
private class RuleImportTarget(
    val items: ImportItemsVm,
    val errorState: SharedFlow<String>,
    val successState: SharedFlow<Int>,
    private val startImportFn: (String) -> Unit,
) {
    fun startImport(text: String) = startImportFn(text)

    companion object {
        fun of(kind: RuleImportKind, scope: CoroutineScope): RuleImportTarget = when (kind) {
            RuleImportKind.DICT -> ImportDictRuleViewModelShared(scope).let { vm ->
                RuleImportTarget(
                    ImportDictRuleItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
                )
            }

            RuleImportKind.TXT_TOC -> ImportTxtTocRuleViewModelShared(scope).let { vm ->
                RuleImportTarget(
                    ImportTxtTocRuleItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
                )
            }

            RuleImportKind.SOURCE_FILTER -> ImportSourceFilterRuleViewModelShared(scope).let { vm ->
                RuleImportTarget(
                    ImportSourceFilterRuleItemsVm(vm), vm.errorState, vm.successState, vm::import,
                )
            }
        }
    }
}
