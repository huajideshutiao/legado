package io.legado.app.ui.book.group

import androidx.compose.runtime.Composable
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewGroupSample

/** [GroupEditDialog] / [GroupManageDialog] 的 @Preview (新增态/编辑态/空列表)。 */

private val previewGroups = listOf(
    previewGroupSample,
    BookGroup(groupId = 2, groupName = "已完结", order = 1),
    BookGroup(groupId = 4, groupName = "待读", order = 2, enableRefresh = false),
)

// ---- GroupEditDialog ----

@AppPreview
@Composable
fun GroupEditDialogNewPreview() = LegadoThemePreview {
    GroupEditDialog(
        group = null,
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun GroupEditDialogEditPreview() = LegadoThemePreview {
    GroupEditDialog(
        group = previewGroupSample,
        onConfirm = {},
        onDismiss = {},
        onDelete = {},
    )
}

@AppPreview
@Composable
fun GroupEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    GroupEditDialog(
        group = previewGroupSample,
        onConfirm = {},
        onDismiss = {},
        onDelete = {},
    )
}

// ---- GroupManageDialog ----

@AppPreview
@Composable
fun GroupManageDialogPreview() = LegadoThemePreview {
    GroupManageDialog(
        groups = previewGroups,
        onAddGroup = {},
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun GroupManageDialogEmptyPreview() = LegadoThemePreview {
    GroupManageDialog(
        groups = emptyList(),
        onAddGroup = {},
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun GroupManageDialogDarkPreview() = LegadoThemePreview(dark = true) {
    GroupManageDialog(
        groups = previewGroups,
        onAddGroup = {},
        onRenameGroup = { _, _ -> },
        onDeleteGroup = {},
        onDismiss = {},
    )
}
