package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewServers

/** [ServersDialog] / [ServerConfigDialog] 的 @Preview (WebDav 服务器管理)。 */

// ---- ServersDialog ----

@Preview
@Composable
fun ServersDialogPreview() = LegadoThemePreview {
    ServersDialog(
        servers = previewServers,
        initialServerId = previewServers.first().id,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServersDialogEmptyPreview() = LegadoThemePreview {
    ServersDialog(
        servers = emptyList(),
        initialServerId = 0L,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServersDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ServersDialog(
        servers = previewServers,
        initialServerId = previewServers[1].id,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

// ---- ServerConfigDialog ----

@Preview
@Composable
fun ServerConfigDialogPreview() = LegadoThemePreview {
    ServerConfigDialog(
        server = previewServers.first(),
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServerConfigDialogNewPreview() = LegadoThemePreview {
    ServerConfigDialog(
        server = null,
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServerConfigDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ServerConfigDialog(
        server = previewServers.first(),
        onSave = {},
        onDismiss = {},
    )
}
