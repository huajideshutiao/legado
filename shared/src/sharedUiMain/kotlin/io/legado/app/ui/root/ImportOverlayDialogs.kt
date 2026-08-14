package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.help.IntentData
import io.legado.app.ui.association.DeepLinkImportTarget
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.ImportTargetDialog
import io.legado.app.ui.widget.dialog.CodeDialog

/**
 * 6 个 Import 对话框 + CodeDialog 的 Overlay 渲染实现。
 *
 * 对照 app 端原 BaseComposeDialogFragment 子类 (ImportBookSourceDialog 等):
 * - source 文本经 [IntentData] 侧信道传递 (可能为大段 JSON, 不直接走 payload 字符串);
 * - VM 创建/下载/解析/勾选渲染统一走 [DeepLinkImportTarget] + [ImportTargetDialog]
 *   (与 deep link 宿主、规则订阅同一条链, 不再各自内联构造 VM 与 collect success/error)。
 */

// 通用导入 Overlay: 6 个 Import 对话框共用 (key="*Import:<DeepLinkImportType.name>", payload=IntentData key)
// 由 LegadoApp DialogOverlayContent 按类型分发; 调用方 (app 端 FileAssociationFragment / 其他端深链) 用
// AppOverlay.Dialog(key = "*Import:" + type.name, payload = IntentData.put(sourceText)) 弹窗。
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
    val target = remember(overlay.key) { DeepLinkImportTarget.of(type, scope) }
    if (target == null) {
        // ADD_TO_BOOKSHELF / READ_CONFIG / READ_BOOK / UNKNOWN 不走导入对话框, 无 Overlay 分支
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    LaunchedEffect(target) { target.startImport(sourceText) }
    ImportTargetDialog(
        target = target,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
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
