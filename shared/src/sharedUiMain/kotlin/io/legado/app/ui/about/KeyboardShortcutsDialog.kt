package io.legado.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.commandKeyLabel
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.back
import legado.shared.generated.resources.close
import legado.shared.generated.resources.exit
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.keyboard_shortcuts
import legado.shared.generated.resources.keyboard_shortcuts_global
import legado.shared.generated.resources.next_page
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.prev_page
import legado.shared.generated.resources.reading
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.search
import legado.shared.generated.resources.setting
import legado.shared.generated.resources.text_size
import org.jetbrains.compose.resources.stringResource

/**
 * 快捷键一览对话框, 键位与 [io.legado.app.ui.root.GlobalShortcuts] /
 * ReaderRoute 的注册项一一对应; 主修饰键名按平台显示 Cmd 或 Ctrl。
 */
@Composable
fun KeyboardShortcutsDialog(onDismiss: () -> Unit) {
    val cmd = commandKeyLabel
    val globalRows = listOf(
        "$cmd+F" to stringResource(Res.string.search),
        "$cmd+," to stringResource(Res.string.setting),
        "$cmd+W" to stringResource(Res.string.close),
        "$cmd+Q" to stringResource(Res.string.exit),
        "$cmd+R / F5" to stringResource(Res.string.refresh),
        "F11" to stringResource(Res.string.full_screen),
        "Esc" to stringResource(Res.string.back),
    )
    val readerRows = listOf(
        "PageUp / ← / ↑" to stringResource(Res.string.prev_page),
        "PageDown / → / ↓ / Space" to stringResource(Res.string.next_page),
        "$cmd+= / $cmd+-" to stringResource(Res.string.text_size),
    )

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.keyboard_shortcuts),
        okButton = AlertButton(stringResource(Res.string.ok)),
        widthFraction = 0.8f,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            ShortcutCategory(stringResource(Res.string.keyboard_shortcuts_global))
            globalRows.forEach { (keys, action) -> ShortcutRow(keys, action) }
            ShortcutCategory(stringResource(Res.string.reading))
            readerRows.forEach { (keys, action) -> ShortcutRow(keys, action) }
        }
    }
}

@Composable
private fun ShortcutCategory(title: String) {
    Text(
        text = title,
        color = AppTheme.colors.accent,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ShortcutRow(keys: String, action: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = keys,
            color = AppTheme.colors.primaryText,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(text = action, color = AppTheme.colors.secondaryText, fontSize = 14.sp)
    }
}
