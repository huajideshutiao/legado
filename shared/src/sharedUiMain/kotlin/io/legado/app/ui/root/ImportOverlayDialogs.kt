package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.help.IntentData
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.ImportBookSourceItemsDialog
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.association.ImportDictRuleItemsVm
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportHttpTtsItemsVm
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportReplaceRuleItemsDialog
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.association.ImportThemeItemsVm
import io.legado.app.ui.association.ImportThemeViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleItemsVm
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.widget.dialog.CodeDialog
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.import_dict_rule
import legado.shared.generated.resources.import_theme
import legado.shared.generated.resources.import_tts
import legado.shared.generated.resources.import_txt_toc_rule
import legado.shared.generated.resources.wrong_format
import org.jetbrains.compose.resources.stringResource

/**
 * 6 个 Import 对话框 + CodeDialog 的 Overlay 渲染实现。
 *
 * 对照 app 端原 BaseComposeDialogFragment 子类 (ImportBookSourceDialog 等):
 * - source 文本经 [IntentData] 侧信道传递 (可能为大段 JSON, 不直接走 payload 字符串);
 * - VM 创建后立即 startImport, loading 态覆盖到 success/error 到达;
 * - success(count=0) 显示"格式不对"错误 (对照 app 端 `error = getString(R.string.wrong_format)`)。
 */

// 通用导入 Overlay: 6 个 Import 对话框共用 (key="*Import", payload=IntentData key)
@Composable
internal fun ImportSourceOverlayContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    type: DeepLinkImportType,
) {
    // 侧信道取 source 文本 (对照 SourceLoginContext.put/take 模式)
    val sourceText = remember(overlay.payload) { IntentData.get<String>(overlay.payload) }
    if (sourceText.isNullOrEmpty()) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }

    val scope = rememberCoroutineScope()
    var loading by remember(overlay.key) { mutableStateOf(true) }
    var error by remember(overlay.key) { mutableStateOf<String?>(null) }
    val wrongFormatText = stringResource(Res.string.wrong_format)
    val onDismiss: () -> Unit = { navigator.dismissOverlay(overlay.key) }

    when (type) {
        DeepLinkImportType.BOOK_SOURCE -> {
            val vm = remember(overlay.key) { ImportBookSourceViewModelShared(scope) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.importSource(sourceText)
            }
            ImportBookSourceItemsDialog(
                vm = vm, onDismiss = onDismiss, loading = loading, errorText = error,
            )
        }

        DeepLinkImportType.REPLACE_RULE -> {
            val vm = remember(overlay.key) { ImportReplaceRuleViewModelShared(scope) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.import(sourceText)
            }
            ImportReplaceRuleItemsDialog(
                vm = vm, onDismiss = onDismiss, loading = loading, errorText = error,
            )
        }

        DeepLinkImportType.TXT_TOC_RULE -> {
            val vm = remember(overlay.key) { ImportTxtTocRuleViewModelShared(scope) }
            val adapter = remember(vm) { ImportTxtTocRuleItemsVm(vm) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.importSource(sourceText)
            }
            ImportItemsDialog(
                title = stringResource(Res.string.import_txt_toc_rule),
                vm = adapter, onDismiss = onDismiss,
                loading = loading, errorText = error,
            )
        }

        DeepLinkImportType.HTTP_TTS -> {
            val vm = remember(overlay.key) { ImportHttpTtsViewModelShared(scope) }
            val adapter = remember(vm) { ImportHttpTtsItemsVm(vm) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.importSource(sourceText)
            }
            ImportItemsDialog(
                title = stringResource(Res.string.import_tts),
                vm = adapter, onDismiss = onDismiss,
                loading = loading, errorText = error,
            )
        }

        DeepLinkImportType.DICT_RULE -> {
            val vm = remember(overlay.key) { ImportDictRuleViewModelShared(scope) }
            val adapter = remember(vm) { ImportDictRuleItemsVm(vm) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.importSource(sourceText)
            }
            ImportItemsDialog(
                title = stringResource(Res.string.import_dict_rule),
                vm = adapter, onDismiss = onDismiss,
                loading = loading, errorText = error,
            )
        }

        DeepLinkImportType.THEME -> {
            val vm = remember(overlay.key) { ImportThemeViewModelShared(scope) }
            val adapter = remember(vm) { ImportThemeItemsVm(vm) }
            LaunchedEffect(vm) {
                launch {
                    vm.errorState.collect {
                        loading = false; error = it.substringAfter("ImportError:")
                    }
                }
                launch {
                    vm.successState.collect { count ->
                        loading = false; if (count == 0) error = wrongFormatText
                    }
                }
                vm.importSource(sourceText)
            }
            ImportItemsDialog(
                title = stringResource(Res.string.import_theme),
                vm = adapter, onDismiss = onDismiss,
                loading = loading, errorText = error,
            )
        }

        // ADD_TO_BOOKSHELF / READ_CONFIG / UNKNOWN 不走导入对话框, 无 Overlay 分支
        else -> LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
    }
}

// 代码查看对话框 (key="codeDialog", payload=IntentData key for code text)
@Composable
internal fun CodeDialogOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val code = remember(overlay.payload) { IntentData.get<String>(overlay.payload).orEmpty() }
    CodeDialog(
        code = code,
        disableEdit = true,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
