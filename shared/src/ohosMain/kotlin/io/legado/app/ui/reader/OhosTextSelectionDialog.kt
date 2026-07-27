package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard

/**
 * 鸿蒙端"文字选择"对话框薄壳: 委托 sharedUiMain [TextSelectionDialog],
 * 注入 pasteboard napi 桥剪贴板 ([copyToClipboard]/[readFromClipboard]) 与 [openURL]。
 */
@Composable
fun OhosTextSelectionDialog(
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
