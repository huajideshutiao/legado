package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard

/**
 * iOS 端"文字选择"对话框薄壳: 委托 sharedUiMain [TextSelectionDialog],
 * 注入 UIPasteboard 剪贴板 ([copyToClipboard]/[readFromClipboard]) 与 [openURL]。
 */
@Composable
fun IosTextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
    onDict: (String) -> Unit = {},
) {
    TextSelectionDialog(
        chapterName = chapterName,
        content = content,
        onDismiss = onDismiss,
        clipTextProvider = ::readFromClipboard,
        clipTextSink = ::copyToClipboard,
        openUrl = ::openURL,
        onDict = onDict,
    )
}
