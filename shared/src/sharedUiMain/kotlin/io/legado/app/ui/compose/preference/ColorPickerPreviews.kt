package io.legado.app.ui.compose.preference

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ColorPicker.kt] 中 [ColorPickerDialog] 的 @Preview。
 *
 * ColorPickerDialog 内部用 Dialog 窗口包装, Preview 中可渲染但交互受限;
 * 主要预览 SV 面板 / Hue 滑条 / hex 输入 / 预设色格的视觉效果。
 */

@Preview
@Composable
fun ColorPickerDialogPreview() = LegadoThemePreview {
    ColorPickerDialog(
        initColor = 0xFF165DFF.toInt(),
        title = "选择颜色",
        onDismissRequest = {},
        onConfirm = {},
    )
}

@Preview
@Composable
fun ColorPickerDialogWithAlphaPreview() = LegadoThemePreview {
    ColorPickerDialog(
        initColor = 0x80FF5722.toInt(),
        title = "带透明度颜色",
        showAlphaSlider = true,
        onDismissRequest = {},
        onConfirm = {},
    )
}

@Preview
@Composable
fun ColorPickerDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ColorPickerDialog(
        initColor = 0xFFF44336.toInt(),
        title = "深色主题取色",
        onDismissRequest = {},
        onConfirm = {},
    )
}

/**
 * colorPreference 的 @Preview: 行尾颜色格子 + 点击弹取色盘。
 *
 * colorPreference 是 LazyListScope 扩展, 需包在 [PreferenceScreen] 中。
 * 点击交互在 Preview 中受限, 但可预览行尾颜色格子的视觉。
 */
@Preview
@Composable
fun ColorPreferencePreview() = LegadoThemePreview {
    PreferenceScreen(modifier = Modifier.fillMaxWidth()) {
        colorPreference(
            prefKey = "preview_color",
            title = "颜色项",
            summary = "点击选择颜色",
            defaultValue = 0xFF165DFF.toInt(),
        )
    }
}

/**
 * ColorPickerDialogContent 的 @Preview: 取色盘正文 (不含 Dialog 窗口)。
 *
 * 直接 Preview Content 可避免 Dialog 窗口在 IDE 中的渲染限制,
 * 更清晰地预览 SV 面板 / Hue 滑条 / hex 输入 / 预设色格。
 */
@Preview
@Composable
fun ColorPickerDialogContentPreview() = LegadoThemePreview {
    ColorPickerDialogContent(
        initColor = 0xFF165DFF.toInt(),
        title = "选择颜色",
        onDismissRequest = {},
        onConfirm = {},
    )
}
