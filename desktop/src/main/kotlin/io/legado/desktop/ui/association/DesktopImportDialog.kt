package io.legado.desktop.ui.association

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.association.ImportBookSourceItemsVm
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.association.ImportDictRuleItemsVm
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportHttpTtsItemsVm
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.association.ImportItemsDialog
import io.legado.app.ui.association.ImportItemsVm
import io.legado.app.ui.association.ImportReplaceRuleItemsVm
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.association.ImportSourceFilterRuleItemsVm
import io.legado.app.ui.association.ImportSourceFilterRuleViewModelShared
import io.legado.app.ui.association.ImportThemeItemsVm
import io.legado.app.ui.association.ImportThemeViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleItemsVm
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.ui.component.DialogSizes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 桌面端统一导入对话框 (书源/替换规则/TxtToc/Dict/HttpTts/主题/SourceFilterRule)。
 *
 * 与 iOS/鸿蒙不同, 桌面端在对话框内触发下载+解析 (打开即见 loading), 故本壳保留:
 * - **Provider 注入**: Dialog 是独立窗口, 内部注入 [DesktopThemeStoreProvider]/
 *   [DesktopAppConfigProvider]/[DesktopEventBusProvider] 让 [AppTheme]/[rememberString] 正常工作;
 * - **startImport 触发 + 状态机**: [LaunchedEffect] 调 [DesktopImportVm.startImport],
 *   collect errorState/successState 驱动 loading/error (对照 app 端
 *   `viewModel.errorLiveData.observe { error = it }`; error 清理 "ImportError:" 前缀,
 *   size==0 显示 wrong_format);
 * - **尺寸约束**: widthIn/heightIn 走 [DialogSizes] (与 ThemeListDialog 一致)。
 *
 * 列表勾选/全选/单条 CodeDialog 编辑回写/importSelect 入库收敛到共享 [ImportItemsDialog]
 * (适配器复用 shared 的 ImportXxxItemsVm, 由 [DesktopImportVm] 工厂组装)。
 *
 * @param title 对话框标题 (如 "导入书源", 由调用方 rememberString 缓存)
 * @param vm 导入 VM 句柄 (共享勾选适配器 + 底层 VM 状态流, 见 [DesktopImportVm] 工厂)
 * @param initialText 初始导入文本 (URL/JSON), Dialog 显示时由 [LaunchedEffect] 自动触发 startImport
 * @param onDismiss 关闭回调 (取消/导入完成均触发, 调用方清空 vm 引用隐藏 Dialog)
 */
@Composable
fun DesktopImportDialog(
    title: String,
    vm: DesktopImportVm,
    initialText: String,
    onDismiss: () -> Unit,
) {
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            val wrongFormatText = rememberString("wrong_format")

            LaunchedEffect(vm) {
                vm.startImport(initialText)
                launch {
                    vm.errorState.collect { err ->
                        if (err != null) {
                            loading = false
                            error = err.removePrefix("ImportError:")
                        }
                    }
                }
                launch {
                    vm.successState.collect { size ->
                        if (size != null) {
                            // loading 翻转触发重组, 列表重读 itemCount 显示解析结果
                            loading = false
                            if (size == 0) error = wrongFormatText
                        }
                    }
                }
            }

            ImportItemsDialog(
                title = title,
                vm = vm.items,
                onDismiss = onDismiss,
                loading = loading,
                errorText = error,
                surfaceModifier = Modifier
                    .widthIn(max = DialogSizes.dialogMaxWidth())
                    .heightIn(max = DialogSizes.dialogFullHeight()),
            )
        }
    }
}

/**
 * 桌面端导入 VM 句柄: 组合共享勾选适配器 [items] 与底层 VM 的导入触发/状态流。
 *
 * 共享 [ImportItemsVm] 不含 startImport/errorState/successState (iOS/鸿蒙在调用点解析),
 * 桌面端在对话框内解析需要它们, 由本类补齐; 各域入口方法名差异
 * (ReplaceRule/SourceFilterRule 用 `import`, 其余用 `importSource`) 在工厂内抹平。
 */
class DesktopImportVm private constructor(
    val items: ImportItemsVm,
    val errorState: StateFlow<String?>,
    val successState: StateFlow<Int?>,
    private val startImportFn: (String) -> Unit,
) {
    fun startImport(text: String) = startImportFn(text)

    companion object {
        fun bookSource(vm: ImportBookSourceViewModelShared) = DesktopImportVm(
            ImportBookSourceItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
        )

        fun replaceRule(vm: ImportReplaceRuleViewModelShared) = DesktopImportVm(
            ImportReplaceRuleItemsVm(vm), vm.errorState, vm.successState, vm::import,
        )

        fun txtTocRule(vm: ImportTxtTocRuleViewModelShared) = DesktopImportVm(
            ImportTxtTocRuleItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
        )

        fun dictRule(vm: ImportDictRuleViewModelShared) = DesktopImportVm(
            ImportDictRuleItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
        )

        fun httpTts(vm: ImportHttpTtsViewModelShared) = DesktopImportVm(
            ImportHttpTtsItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
        )

        fun theme(vm: ImportThemeViewModelShared) = DesktopImportVm(
            ImportThemeItemsVm(vm), vm.errorState, vm.successState, vm::importSource,
        )

        fun sourceFilterRule(vm: ImportSourceFilterRuleViewModelShared) = DesktopImportVm(
            ImportSourceFilterRuleItemsVm(vm), vm.errorState, vm.successState, vm::import,
        )
    }
}
