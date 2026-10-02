package io.legado.app.ui.compose.component.code

import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import io.legado.treeview.JsonTreeItem
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.serialization.json.JsonPrimitive
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.copied_to_clipboard
import legado.ui.generated.resources.copy_key
import legado.ui.generated.resources.copy_json_path
import legado.ui.generated.resources.copy_key_value
import legado.ui.generated.resources.copy_value
import legado.ui.generated.resources.view_value
import org.jetbrains.compose.resources.stringResource

/**
 * JSON 树条目菜单内容: 查看 / 复制键 / 复制值 / 复制键值对 / 复制 JSONPath, 长按与右键共用。
 * 由 [io.legado.treeview.JsonTree] 的条目行内 DropdownMenu 承载 (本组合只渲染菜单项,
 * 弹出定位与进出场动画是 DropdownMenu 官方实现)。
 *
 * 无键 (根条目) 时"复制键""复制键值对"置灰, 无值 (折叠头行) 时"复制值""复制键值对"置灰,
 * 既无子树也无值时"查看"置灰。
 */
@Composable
fun JsonPathMenu(
    item: JsonTreeItem,
    onDismiss: () -> Unit,
    onViewValue: (JsonTreeItem) -> Unit,
) {
    val copiedText = stringResource(Res.string.copied_to_clipboard)

    fun copy(text: String) {
        PlatformCapabilityProviders.get().copyToClipboard(text)
        Toasters.get().toast(copiedText)
        onDismiss()
    }

    DropdownMenuItem(
        enabled = item.subtreeElement != null || item.value != null,
        onClick = {
            onViewValue(item)
            onDismiss()
        },
    ) {
        Text(stringResource(Res.string.view_value))
    }
    DropdownMenuItem(
        enabled = item.key != null,
        onClick = { copy(item.key.orEmpty()) },
    ) {
        Text(stringResource(Res.string.copy_key))
    }
    DropdownMenuItem(
        enabled = item.value != null,
        onClick = { copy(item.value.orEmpty()) },
    ) {
        Text(stringResource(Res.string.copy_value))
    }
    DropdownMenuItem(
        enabled = item.key != null && item.quotedValue != null,
        onClick = { copy("${JsonPrimitive(item.key.orEmpty())}: ${item.quotedValue}") },
    ) {
        Text(stringResource(Res.string.copy_key_value))
    }
    DropdownMenuItem(
        onClick = { copy(item.path) },
    ) {
        Text(stringResource(Res.string.copy_json_path))
    }
}
