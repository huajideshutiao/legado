package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.help
import org.jetbrains.compose.resources.stringResource

/**
 * 帮助文档对话框 (KMP 共享, 四端复用)。
 *
 * 对应 app 端 `showHelp(fileName)`: 读 composeResources 的 `web/help/md/<fileName>.md`。
 * 实体是 [MdDocDialog] (内置 Markdown 文档对话框), 本函数只固定标题为"帮助"并拼路径。
 *
 * @param fileName 帮助文档文件名 (不含 .md 后缀, 如 "dictRuleHelp")
 */
@Composable
fun HelpDialog(fileName: String, onDismiss: () -> Unit) {
    MdDocDialog(
        title = stringResource(Res.string.help),
        assetPath = "web/help/md/$fileName.md",
        onDismiss = onDismiss,
    )
}
