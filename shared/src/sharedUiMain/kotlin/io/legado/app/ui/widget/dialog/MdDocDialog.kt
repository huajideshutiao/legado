package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * 内置 Markdown 文档对话框 (四端唯一实现)。
 *
 * 文档统一放 shared 的 `commonMain/composeResources/files/`, 经 [Res.readBytes] 通用读取
 * (四端直接走 composeResources, 单一数据源), 不依赖 WebAssetSources 等 Web 静态服务抽象。
 *
 * 布局与 [TextDialog] 同一套 AppDialog + Surface + Column (标题固定 / 正文
 * weight+内部滚动 / 按钮钉底), 不用 M2 AlertDialog (桌面端其 BaselineLayout
 * 汇报高度未钳制, 长文本滚动错位/按钮被推出屏幕外, 用户多轮实测复现)。
 *
 * @param title    标题 (关于页传条目名, 帮助文档传"帮助")
 * @param assetPath composeResources `files/` 下的相对路径 (如 `md/LICENSE.md`)
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun MdDocDialog(title: String, assetPath: String, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var content by remember(assetPath) { mutableStateOf("") }
    LaunchedEffect(assetPath) {
        // 同步读文件不能卡主线程 (用户反馈点击"文档"卡一下): 切 IO 线程读,
        // 完成回主线程更新状态 (LaunchedEffect 协程体默认跑主线程, 读 IO 必须显式切)
        content = withContext(IoDispatcher) {
            runCatching {
                val fullPath = if (assetPath.startsWith("files/")) assetPath else "files/$assetPath"
                Res.readBytes(fullPath).decodeToString()
            }.getOrElse {
                AppLog.put("读取内置文档失败 $assetPath", it)
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
            Column(
                Modifier.padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp,
                )
            ) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(DesignTokens.spacingDefault),
                )
                // 正文区: weight 占对话框剩余空间 (视口恒定), 超长滚动, 按钮恒可见
                // 滚动由 MarkdownContent 内部分支承担 (短文档 Column 自带 / 长文档 LazyColumn 虚拟化)
                Box(Modifier.weight(1f, fill = false)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp),
                    ) {
                        MarkdownContentSelectable(content)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
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
