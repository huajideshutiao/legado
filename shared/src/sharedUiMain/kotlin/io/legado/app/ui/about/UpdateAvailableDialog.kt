package io.legado.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.legado.app.help.update.UpdateAction
import io.legado.app.help.update.UpdateCheckInfo
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_download
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.download_now
import legado.shared.generated.resources.found_new_version
import org.jetbrains.compose.resources.stringResource

/**
 * 发现新版本对话框 (desktop/iOS/鸿蒙共用; app 端仍用自己的 UpdateDialog Fragment)。
 *
 * 正文走 [MarkdownContentSelectable], 与 app 端 UpdateDialog 同一渲染路径 (multiplatformMarkdown)。
 * 确认按钮文案随 [action] 变化: 直装/下载 vs 打开页面。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateCheckInfo,
    action: UpdateAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogSize(),
        properties = AppDialogSizes.properties(),
        title = {
            Text(
                text = "${stringResource(Res.string.found_new_version)} ${info.versionName}",
                color = colors.primaryText,
                fontSize = 18.sp,
            )
        },
        text = {
            // LazyMarkdown 自带滚动, 不套 verticalScroll (嵌套滚动会失效虚拟化)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = AppDialogSizes.textAreaMaxHeight()),
            ) {
                MarkdownContentSelectable(info.releaseNote)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel(action), color = DesignTokens.arcoBlue6)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cancel), color = colors.secondaryText)
            }
        },
        shape = DesignTokens.dialogShape,
        backgroundColor = colors.fillet,
    )
}

@Composable
private fun confirmLabel(action: UpdateAction): String = when (action) {
    UpdateAction.DIRECT_INSTALL,
    UpdateAction.DOWNLOAD_AND_PROMPT,
    UpdateAction.SIDELOAD_DEEP_LINK -> stringResource(Res.string.action_download)

    UpdateAction.OPEN_DOWNLOAD_PAGE,
    UpdateAction.OPEN_STORE -> stringResource(Res.string.download_now)
}
