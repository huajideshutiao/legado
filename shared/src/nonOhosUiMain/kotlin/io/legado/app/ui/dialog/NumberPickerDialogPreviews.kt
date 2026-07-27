package io.legado.app.ui.dialog

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [NumberPickerDialog.kt] 中 [NumberPickerDialog] 的 @Preview。
 */

@AppPreview
@Composable
fun NumberPickerDialogPreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "字号",
        value = 18,
        range = 12..36,
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun NumberPickerDialogLargeRangePreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "换源延迟(ms)",
        value = 500,
        range = 0..3000,
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun NumberPickerDialogWithNeutralPreview() = LegadoThemePreview {
    NumberPickerDialog(
        title = "端口",
        value = 8080,
        range = 1024..65535,
        onConfirm = {},
        onDismiss = {},
        neutralButtonText = "默认",
        onNeutral = {},
    )
}

@AppPreview
@Composable
fun NumberPickerDialogDarkPreview() = LegadoThemePreview(dark = true) {
    NumberPickerDialog(
        title = "字号",
        value = 16,
        range = 12..36,
        onConfirm = {},
        onDismiss = {},
    )
}
