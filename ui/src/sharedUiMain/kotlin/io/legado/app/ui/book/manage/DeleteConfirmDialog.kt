package io.legado.app.ui.book.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.delete_book_file
import org.jetbrains.compose.resources.stringResource

/**
 * 删除确认对话框 (对照 app 端 alert + DeleteFileCheckbox)。
 *
 * 书架删除与书籍详情页下架共用: 勾选框决定是否连带删除本地书源文件。
 *
 * @param showCheckbox 是否显示"删除源文件"勾选 (对照 app: 单项删除仅本地书显示, 批量删除始终显示)
 * @param initialDeleteOriginal 勾选初始值 (对照 app: 取 LocalConfig.deleteBookOriginal)
 * @param onConfirm 确认时回传勾选值
 */
@Composable
fun DeleteConfirmDialog(
    showCheckbox: Boolean,
    initialDeleteOriginal: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 对照 app: 初始值取 LocalConfig.deleteBookOriginal
    val deleteFile = remember { mutableStateOf(initialDeleteOriginal) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.draw),
        message = stringResource(Res.string.sure_del),
        okButton = AlertButton(stringResource(Res.string.ok)) {
            onConfirm(deleteFile.value)
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)) { onDismiss() },
        content = if (showCheckbox) {
            {
                // "删除源文件"复选框 (对照 app 端 DeleteFileCheckbox)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = deleteFile.value,
                            onValueChange = { deleteFile.value = it },
                        )
                        .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingDefault),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingMd),
                ) {
                    AppCheckbox(checked = deleteFile.value, onCheckedChange = null)
                    Text(
                        text = stringResource(Res.string.delete_book_file),
                        color = AppTheme.colors.primaryText,
                    )
                }
            }
        } else null,
    )
}
