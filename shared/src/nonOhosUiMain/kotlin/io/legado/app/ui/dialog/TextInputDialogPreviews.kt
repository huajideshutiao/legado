package io.legado.app.ui.dialog

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [TextInputDialog] 的 @Preview。
 *
 * 覆盖 title-only / 带 message / 带初始值 / 带 hint 四种组合。
 */

@AppPreview
@Composable
fun TextInputDialogPreview() = LegadoThemePreview {
    TextInputDialog(
        title = "新建分组",
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun TextInputDialogWithMessagePreview() = LegadoThemePreview {
    TextInputDialog(
        title = "导入书源",
        message = "粘贴书源 URL 或 JSON 内容",
        hint = "https://example.com/source.json",
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun TextInputDialogWithInitialValuePreview() = LegadoThemePreview {
    TextInputDialog(
        title = "重命名分组",
        initialValue = "正在追",
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun TextInputDialogDarkPreview() = LegadoThemePreview(dark = true) {
    TextInputDialog(
        title = "导入书源",
        message = "粘贴书源 URL 或 JSON 内容",
        hint = "https://example.com/source.json",
        onConfirm = {},
        onDismiss = {},
    )
}
