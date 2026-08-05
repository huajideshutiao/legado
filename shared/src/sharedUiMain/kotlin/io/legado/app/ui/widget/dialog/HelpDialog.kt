package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
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
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.web.utils.WebAssetSources
import kotlinx.coroutines.withContext
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
 * 布局与 [TextDialog] 同一套 AppDialog + Surface + Column (标题固定 / 正文
 * weight+verticalScroll / 按钮钉底), 不用 M2 AlertDialog (桌面端其 BaselineLayout
 * 汇报高度未钳制, 长文本滚动错位/按钮被推出屏幕外, 用户多轮实测复现)。
 *
 * @param fileName 帮助文档文件名 (不含 .md 后缀, 如 "dictRuleHelp")
 */
@Composable
fun HelpDialog(fileName: String, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var content by remember(fileName) {
        mutableStateOf("")
    }
    LaunchedEffect(fileName) {
        // 同步读文件不能卡主线程 (用户反馈点击"文档"卡一下): 切 IO 线程读,
        // 完成回主线程更新状态 (LaunchedEffect 协程体默认跑主线程, 读 IO 必须显式切)
        content = withContext(IoDispatcher) {
            runCatching {
                WebAssetSources.get().read("web/help/md/$fileName.md").decodeToString()
            }.getOrElse {
                AppLog.put("读取帮助文档失败 $fileName", it)
                it.message.orEmpty()
            }
        }
    }
    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = stringResource(Res.string.help),
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                // 正文区: weight 占对话框剩余空间 (视口恒定), 超长滚动, 按钮恒可见
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MarkdownContentSelectable(content)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(Res.string.ok), color = DesignTokens.arcoBlue6)
                    }
                }
            }
        }
    }
}
