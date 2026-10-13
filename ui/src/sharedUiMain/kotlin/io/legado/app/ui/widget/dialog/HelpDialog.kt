package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.help
import org.jetbrains.compose.resources.stringResource

/**
 * 帮助文档对话框 (KMP 共享, 四端复用)。
 *
 * 对应原版 `AppCompatActivity.showHelp(fileName)`: 读 assets 的 `web/help/md/<fileName>.md`
 * 后交 [TextDialog] 以 Markdown 渲染。内置文档现放 composeResources, 经
 * [rememberBuiltInDocContent] 读取 (四端同一取数路径)。
 *
 * @param fileName 帮助文档文件名 (不含 .md 后缀, 如 "dictRuleHelp")
 * @param title 对话框标题, 默认"帮助" (升级更新日志弹窗传"更新日志"); 置于 onDismiss 前,
 *   使尾随 lambda 写法 HelpDialog(fileName) { } 直接成立 (onDismiss 为末位参数)
 */
@Composable
fun HelpDialog(
    fileName: String,
    title: String = stringResource(Res.string.help),
    onDismiss: () -> Unit,
) {
    TextDialog(
        title = title,
        content = rememberBuiltInDocContent("web/help/md/$fileName.md"),
        onDismiss = onDismiss,
        markdown = true,
        showActionBar = false,
    )
}
