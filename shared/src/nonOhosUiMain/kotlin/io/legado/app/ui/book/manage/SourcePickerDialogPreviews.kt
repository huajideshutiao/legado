package io.legado.app.ui.book.manage

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [SourcePickerDialog.kt] 中 [SourcePickerDialog] 的 @Preview。
 *
 * 假数据: Book/BookSource 用纯内存对象构造, 不依赖 DB/网络。
 */

private val previewSources = listOf(
    BookSource(
        bookSourceUrl = "https://source1.com",
        bookSourceName = "测试书源1",
        bookSourceGroup = "默认",
        respondTime = 200L,
    ),
    BookSource(
        bookSourceUrl = "https://source2.com",
        bookSourceName = "测试书源2",
        bookSourceGroup = "默认",
        respondTime = 350L,
    ),
    BookSource(
        bookSourceUrl = "https://source3.com",
        bookSourceName = "测试书源3(慢)",
        bookSourceGroup = "备用",
        respondTime = 1200L,
    ),
)

@AppPreview
@Composable
fun SourcePickerDialogPreview() = LegadoThemePreview {
    SourcePickerDialog(
        sources = previewSources,
        initialDelay = 0,
        onSourceSelected = {},
        onDelayChange = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun SourcePickerDialogEmptyPreview() = LegadoThemePreview {
    SourcePickerDialog(
        sources = emptyList(),
        initialDelay = 0,
        onSourceSelected = {},
        onDelayChange = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun SourcePickerDialogMultiSelectedPreview() = LegadoThemePreview {
    SourcePickerDialog(
        sources = previewSources,
        initialDelay = 2,
        onSourceSelected = {},
        onDelayChange = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun SourcePickerDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SourcePickerDialog(
        sources = previewSources,
        initialDelay = 0,
        onSourceSelected = {},
        onDelayChange = {},
        onDismiss = {},
    )
}
