package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.web.utils.WebAssetSources
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 帮助文档对话框 (KMP 共享, desktop/iOS/鸿蒙复用)。
 *
 * 对应 app 端 `showHelp(fileName)`: 读 composeResources 的 `web/help/md/<fileName>.md`,
 * 用 [MarkdownContentSelectable] 渲染 (替代 TextDialog Mode.MD 的 Markwon)。
 *
 * @param fileName 帮助文档文件名 (不含 .md 后缀, 如 "dictRuleHelp")
 */
@Composable
fun HelpDialog(fileName: String, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var content by remember(fileName) { mutableStateOf("") }
    LaunchedEffect(fileName) {
        content = runCatching {
            WebAssetSources.get().read("web/help/md/$fileName.md").decodeToString()
        }.getOrElse {
            AppLog.put("读取帮助文档失败 $fileName", it)
            it.message.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.help),
                color = colors.primaryText,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownContentSelectable(content)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.ok), color = DesignTokens.arcoBlue6)
            }
        },
        shape = DesignTokens.dialogShape,
        backgroundColor = MaterialTheme.colors.surface,
    )
}
