package io.legado.app.ui.compose.component.code

import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import com.sebastianneubauer.jsontree.JsonTreeItem
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.serialization.json.JsonPrimitive
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.copied_to_clipboard
import legado.ui.generated.resources.copy_key
import legado.ui.generated.resources.copy_json_path
import legado.ui.generated.resources.copy_key_value
import legado.ui.generated.resources.copy_value
import org.jetbrains.compose.resources.stringResource

/**
 * JSON 树条目菜单: 复制键 / 复制值 / 复制键值对 / 复制 JSONPath, 长按与右键共用。
 *
 * 挂在与 [com.sebastianneubauer.jsontree.JsonTree][JsonTree] 同一父布局内,
 * [anchorParentOffsetInWindow] 传该父布局的窗口位置 (onGloballyPositioned 取),
 * 用于把条目的窗口坐标换算为相对父布局的偏移。
 * 无键 (根条目) 时"复制键""复制键值对"置灰, 无值 (折叠头行) 时"复制值""复制键值对"置灰。
 */
@Composable
fun JsonPathMenu(
    item: JsonTreeItem?,
    anchorParentOffsetInWindow: IntOffset,
    onDismiss: () -> Unit,
) {
    val menu = item ?: return
    val density = LocalDensity.current
    val copiedText = stringResource(Res.string.copied_to_clipboard)

    fun copy(text: String) {
        PlatformCapabilityProviders.get().copyToClipboard(text)
        Toasters.get().toast(copiedText)
        onDismiss()
    }

    DropdownMenu(
        expanded = true,
        offset = with(density) {
            DpOffset(
                (menu.offsetInWindow.x - anchorParentOffsetInWindow.x).toDp(),
                (menu.offsetInWindow.y - anchorParentOffsetInWindow.y).toDp(),
            )
        },
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            enabled = menu.key != null,
            onClick = { copy(menu.key.orEmpty()) },
        ) {
            Text(stringResource(Res.string.copy_key))
        }
        DropdownMenuItem(
            enabled = menu.value != null,
            onClick = { copy(menu.value.orEmpty()) },
        ) {
            Text(stringResource(Res.string.copy_value))
        }
        DropdownMenuItem(
            enabled = menu.key != null && menu.quotedValue != null,
            onClick = { copy("${JsonPrimitive(menu.key.orEmpty())}: ${menu.quotedValue}") },
        ) {
            Text(stringResource(Res.string.copy_key_value))
        }
        DropdownMenuItem(
            onClick = { copy(menu.path) },
        ) {
            Text(stringResource(Res.string.copy_json_path))
        }
    }
}
