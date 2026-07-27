package io.legado.app.ui.book.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [SearchScopeDialog] 的 @Preview。
 *
 * 假数据: [BookGroup] 列表用纯内存对象构造, 覆盖空选/单选/多选场景。
 */

private val previewGroups = listOf(
    BookGroup(groupId = 1, groupName = "默认分组"),
    BookGroup(groupId = 2, groupName = "精校书源"),
    BookGroup(groupId = 3, groupName = "网络书源"),
    BookGroup(groupId = 4, groupName = "音频书源"),
    BookGroup(groupId = 5, groupName = "图片书源"),
)

@Preview
@Composable
fun SearchScopeDialogPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        selectedGroupIds = setOf(1L, 3L),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogEmptySelectionPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        selectedGroupIds = emptySet(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogSingleSelectionPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = previewGroups,
        selectedGroupIds = setOf(2L),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogNoGroupsPreview() = LegadoThemePreview {
    SearchScopeDialog(
        groups = emptyList(),
        selectedGroupIds = emptySet(),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun SearchScopeDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SearchScopeDialog(
        groups = previewGroups,
        selectedGroupIds = setOf(1L, 3L),
        onConfirm = {},
        onDismiss = {},
    )
}
