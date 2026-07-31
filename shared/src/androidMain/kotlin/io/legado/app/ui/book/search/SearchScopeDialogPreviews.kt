package io.legado.app.ui.book.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.preview.LegadoThemePreview

private val previewGroups = listOf(
    "默认分组",
    "精校书源",
    "网络书源",
    "音频书源",
    "图片书源",
)

private val previewSources = listOf(
    BookSourcePart(
        bookSourceUrl = "https://example.com/source-1",
        bookSourceName = "示例书源一",
        bookSourceGroup = "默认分组",
    ),
    BookSourcePart(
        bookSourceUrl = "https://example.com/source-2",
        bookSourceName = "示例书源二",
        bookSourceGroup = "网络书源",
    ),
)

@Preview
@Composable
fun SearchScopeDialogPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        sources = previewSources,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogEmptySelectionPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        sources = previewSources,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogSingleSourcePreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        sources = previewSources,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogNoGroupsPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = emptyList(),
        sources = emptyList(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SearchScopeDialog(
        groups = previewGroups,
        sources = previewSources,
        onConfirm = {},
        onDismiss = {},
    )
}
