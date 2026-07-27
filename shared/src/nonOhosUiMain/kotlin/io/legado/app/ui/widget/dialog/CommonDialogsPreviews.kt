package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [WaitDialog] / [CodeDialog] / [VariableDialog] 的 @Preview。
 *
 * 均取 *Content 版本 (无 Dialog 包装), Preview 中 Popup 渲染不稳定;
 * VariableDialog 无 Content 拆分, 直接预览带 Dialog 版本。
 */

// ---- WaitDialogContent ----

@AppPreview
@Composable
fun WaitDialogContentPreview() = LegadoThemePreview {
    WaitDialogContent(message = "正在加载...")
}

@AppPreview
@Composable
fun WaitDialogContentLongMessagePreview() = LegadoThemePreview {
    WaitDialogContent(message = "正在校验书源可用性, 请稍候, 这可能需要几分钟")
}

@AppPreview
@Composable
fun WaitDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    WaitDialogContent(message = "正在加载...")
}

// ---- CodeDialogContent ----

private val previewJsCode = """
// 书源正文净化脚本
function purify(text) {
    return text
        .replace(/^.*最新章节.*$/gm, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}
purify(result);
""".trimIndent()

@AppPreview
@Composable
fun CodeDialogContentEditablePreview() = LegadoThemePreview {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

@AppPreview
@Composable
fun CodeDialogContentReadOnlyPreview() = LegadoThemePreview {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = true,
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun CodeDialogContentEmptyPreview() = LegadoThemePreview {
    CodeDialogContent(
        code = "",
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

@AppPreview
@Composable
fun CodeDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

// ---- VariableDialog ----

private val previewSourceVariables = mapOf(
    "cookie" to "session=abc123; uid=88888",
    "token" to "eyJhbGciOiJIUzI1NiJ9.preview",
    "baseUrl" to "https://preview.invalid",
)

private val previewBookVariables = mapOf(
    "lastReadTime" to "1700000000000",
    "chapterOffset" to "12",
)

@AppPreview
@Composable
fun VariableDialogPreview() = LegadoThemePreview {
    VariableDialog(
        sourceVariables = previewSourceVariables,
        bookVariables = previewBookVariables,
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun VariableDialogEmptyPreview() = LegadoThemePreview {
    VariableDialog(
        sourceVariables = emptyMap(),
        bookVariables = emptyMap(),
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun VariableDialogDarkPreview() = LegadoThemePreview(dark = true) {
    VariableDialog(
        sourceVariables = previewSourceVariables,
        bookVariables = previewBookVariables,
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}
