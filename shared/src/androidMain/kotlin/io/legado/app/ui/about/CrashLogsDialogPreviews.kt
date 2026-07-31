package io.legado.app.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [CrashLogsDialogContent] 的 @Preview。
 *
 * 取 Content 版本 (无 Dialog 包装); onReadFile 回调直接回填假堆栈文本。
 */

private val previewCrashLogs = listOf(
    CrashLogItem(name = "crash-2026-07-25-21-30-18.txt"),
    CrashLogItem(name = "crash-2026-07-20-08-12-55.txt"),
    CrashLogItem(name = "crash-2026-07-01-23-59-01.txt"),
)

@Preview
@Composable
fun CrashLogsDialogContentPreview() = LegadoThemePreview {
    CrashLogsDialogContent(
        logs = previewCrashLogs,
        onDismiss = {},
        onClear = {},
        onReadFile = { _, callback -> callback("java.lang.RuntimeException: preview stack trace\n\tat io.legado.app.PreviewKt.main(Preview.kt:1)") },
        onShare = {},
    )
}

@Preview
@Composable
fun CrashLogsDialogContentEmptyPreview() = LegadoThemePreview {
    CrashLogsDialogContent(
        logs = emptyList(),
        onDismiss = {},
        onClear = {},
        onReadFile = { _, _ -> },
        onShare = {},
    )
}

@Preview
@Composable
fun CrashLogsDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    CrashLogsDialogContent(
        logs = previewCrashLogs,
        onDismiss = {},
        onClear = {},
        onReadFile = { _, _ -> },
        onShare = {},
    )
}
