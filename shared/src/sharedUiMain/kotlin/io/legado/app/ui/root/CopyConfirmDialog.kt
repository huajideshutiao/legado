package io.legado.app.ui.root

// java.copy 确认对话框: 完整文本存 IntentData (one-shot), payload 只带 key,
// 超长文本只显示前面一截 (截断预览), 确认后才写入系统剪贴板。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.help.IntentData
import io.legado.app.help.JsExtensionsCommon.Companion.COPY_CONFIRM_KEY
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.copy_to_clipboard
import org.jetbrains.compose.resources.stringResource

/** 预览截断长度: 超长文本只显示前面一截。 */
private const val PREVIEW_MAX_LEN = 200

/**
 * java.copy 确认对话框 Overlay 内容 (key="copy_confirm")。
 *
 * - 完整文本从 [IntentData] 取出 (one-shot, 取走即删), 对话框只显示截断预览;
 * - 确定 → 写入系统剪贴板 ([PlatformCapabilityProviders.copyToClipboard]) 并关闭;
 * - 取消/关闭 → 不复制, 直接关闭。
 */
@Composable
fun CopyConfirmOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val text = remember { IntentData.get<String>(overlay.payload ?: COPY_CONFIRM_KEY).orEmpty() }
    val onDismiss: () -> Unit = { navigator.dismissOverlay(overlay.key) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.copy_to_clipboard),
        message = text.take(PREVIEW_MAX_LEN) + if (text.length > PREVIEW_MAX_LEN) "…" else "",
        okButton = AlertButton(stringResource(Res.string.copy)) {
            PlatformCapabilityProviders.getOrNull()?.copyToClipboard(text)
            onDismiss()
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
    )
}
