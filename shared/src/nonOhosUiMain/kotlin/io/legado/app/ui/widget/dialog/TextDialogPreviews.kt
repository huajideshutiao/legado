package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [TextDialog.kt] 中 [TextDialog] 的 @Preview。
 */

@AppPreview
@Composable
fun TextDialogPreview() = LegadoThemePreview {
    TextDialog(
        title = "错误信息",
        content = "这是一段错误信息的正文, 用于展示给用户。\n第二行内容。\n第三行内容。",
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun TextDialogLongContentPreview() = LegadoThemePreview {
    TextDialog(
        title = "长文本展示",
        content = buildString {
            repeat(50) { i ->
                appendLine("第 ${i + 1} 行: 这是一段用于测试长文本滚动展示效果的内容。")
            }
        },
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun TextDialogWithNeutralPreview() = LegadoThemePreview {
    TextDialog(
        title = "确认操作",
        content = "请确认是否执行此操作, 操作不可撤销。",
        onConfirm = {},
        onDismiss = {},
        neutralButtonText = "默认",
        onNeutral = {},
    )
}

@AppPreview
@Composable
fun TextDialogDarkPreview() = LegadoThemePreview(dark = true) {
    TextDialog(
        title = "深色主题",
        content = "深色主题下的文本对话框内容展示。",
        onConfirm = {},
        onDismiss = {},
    )
}
