package io.legado.desktop.ui.reader

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.utils.browseUrl
import io.legado.desktop.ui.component.DialogSizes
import io.legado.app.ui.reader.TextSelectionDialog as SharedTextSelectionDialog

/**
 * 桌面端"文字选择"对话框薄壳: 委托 sharedUiMain
 * [io.legado.app.ui.reader.TextSelectionDialog], 注入 [browseUrl] 打开外链,
 * 剪贴板仍由调用方注入 AWT 实现 (与 DictRuleScreen 等同款), Surface 宽度保持
 * [DialogSizes.dialogMaxWidth] 原样式。
 */
@Composable
fun TextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: (String) -> Unit,
    onDict: (String) -> Unit = {},
) {
    SharedTextSelectionDialog(
        chapterName = chapterName,
        content = content,
        onDismiss = onDismiss,
        clipTextProvider = clipTextProvider,
        clipTextSink = clipTextSink,
        openUrl = ::browseUrl,
        onDict = onDict,
        surfaceModifier = Modifier.widthIn(max = DialogSizes.dialogMaxWidth()),
    )
}
