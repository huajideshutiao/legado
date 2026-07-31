package io.legado.app.ui.book.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewGroupSample

/** [GroupEditDialog] / [GroupManageDialog] 的 @Preview (新增态/编辑态/空列表)。 */

private val previewGroups = listOf(
    previewGroupSample,
    BookGroup(groupId = 2, groupName = "已完结", order = 1),
    BookGroup(groupId = 4, groupName = "待读", order = 2, enableRefresh = false),
)

// ---- GroupEditDialog ----

@Preview
@Composable
fun GroupEditDialogNewPreview() = LegadoThemePreview {
    GroupEditDialog(
        group = null,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun GroupEditDialogEditPreview() = LegadoThemePreview {
    GroupEditDialog(
        group = previewGroupSample,
        onConfirm = {},
        onDismiss = {},
        onDelete = {},
    )
}

@Preview
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

@Preview
@Composable
fun GroupManageDialogPreview() = LegadoThemePreview {
    GroupManageDialog(
        groups = previewGroups,
        onAddGroup = {},
        onEditGroup = {},
        onUpdateGroup = {},
        onPersistOrder = {},
        onDismiss = {},
        canAddGroup = { true },
    )
}

@Preview
@Composable
fun GroupManageDialogEmptyPreview() = LegadoThemePreview {
    GroupManageDialog(
        groups = emptyList(),
        onAddGroup = {},
        onEditGroup = {},
        onUpdateGroup = {},
        onPersistOrder = {},
        onDismiss = {},
        canAddGroup = { true },
    )
}

@Preview
@Composable
fun GroupManageDialogDarkPreview() = LegadoThemePreview(dark = true) {
    GroupManageDialog(
        groups = previewGroups,
        onAddGroup = {},
        onEditGroup = {},
        onUpdateGroup = {},
        onPersistOrder = {},
        onDismiss = {},
        canAddGroup = { true },
    )
}
