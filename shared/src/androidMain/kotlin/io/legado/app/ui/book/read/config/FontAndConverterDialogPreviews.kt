package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ChineseConverterSelectorDialog] / [FontSelectDialog] 的 @Preview。
 *
 * 字体列表用内存 [FontItem], 不扫描真实字体目录。
 */

// ---- ChineseConverterSelectorDialog ----

@Preview
@Composable
fun ChineseConverterSelectorDialogOffPreview() = LegadoThemePreview {
    ChineseConverterSelectorDialog(
        currentType = 0,
        onChanged = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ChineseConverterSelectorDialogT2sPreview() = LegadoThemePreview {
    ChineseConverterSelectorDialog(
        currentType = 1,
        onChanged = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ChineseConverterSelectorDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ChineseConverterSelectorDialog(
        currentType = 2,
        onChanged = {},
        onDismiss = {},
    )
}

// ---- FontSelectDialog ----

private val previewFontItems = listOf(
    FontItem(path = "/fonts/SourceHanSerif.otf", name = "思源宋体"),
    FontItem(path = "/fonts/SourceHanSans.otf", name = "思源黑体"),
    FontItem(path = "/fonts/FZKai.ttf", name = "方正楷体"),
    FontItem(path = "/fonts/LXGWWenKai.ttf", name = "霞鹜文楷"),
)

@Preview
@Composable
fun FontSelectDialogPreview() = LegadoThemePreview {
    FontSelectDialog(
        fontItems = previewFontItems,
        curFontPath = previewFontItems[1].path,
        curFontName = previewFontItems[1].name,
        onSelectFont = {},
        onSelectDefault = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun FontSelectDialogEmptyPreview() = LegadoThemePreview {
    FontSelectDialog(
        fontItems = emptyList(),
        curFontPath = "",
        curFontName = "",
        onSelectFont = {},
        onSelectDefault = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun FontSelectDialogDarkPreview() = LegadoThemePreview(dark = true) {
    FontSelectDialog(
        fontItems = previewFontItems,
        curFontPath = previewFontItems[1].path,
        curFontName = previewFontItems[1].name,
        onSelectFont = {},
        onSelectDefault = {},
        onDismiss = {},
    )
}
