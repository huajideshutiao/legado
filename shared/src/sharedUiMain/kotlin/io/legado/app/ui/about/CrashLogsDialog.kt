package io.legado.app.ui.about

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "crash_log" to "崩溃日志",
//   "clear" to "清空"

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.TextDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.crash_log
import org.jetbrains.compose.resources.stringResource

/**
 * 崩溃日志条目 (KMP 共享 UI 契约)。仅承载展示所需的文件名；
 * 读取内容 / 分享 / 清空等平台相关逻辑由宿主经回调注入，避免依赖 app 端 FileDoc。
 */
data class CrashLogItem(val name: String)

/**
 * 崩溃日志列表内容 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.about.CrashLogsDialog` 的 Content，去掉对 BaseComposeDialogFragment /
 * FileDoc / requireContext().share 的依赖，改为纯 @Composable + 回调形式:
 * - 标题栏: 返回 + "崩溃日志" + 清空按钮
 * - 列表: LazyColumn，行=文件名，点击读内容弹内容查看对话框，长按分享
 *
 * 宿主 (app DialogFragment / desktop Composable) 负责提供 [logs] 与各回调的具体平台实现。
 *
 * @param logs 崩溃日志条目列表
 * @param onDismiss 用户取消 (返回按钮)
 * @param onClear 清空全部崩溃日志
 * @param onReadFile 读取某条日志内容，读取完成后回调返回内容字符串
 * @param onShare 分享某条日志 (长按触发)
 */
@Composable
fun CrashLogsDialogContent(
    logs: List<CrashLogItem>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onReadFile: (CrashLogItem, (String) -> Unit) -> Unit,
    onShare: (CrashLogItem) -> Unit,
) {
    val colors = AppTheme.colors
    // 选中的日志文件内容 (非 null 时弹出内容对话框)
    var fileContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = stringResource(Res.string.crash_log),
            onBack = onDismiss,
            actions = {
                AppTextButton(text = stringResource(Res.string.clear)) { onClear() }
            },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(logs, key = { it.name }) { item ->
                Text(
                    text = item.name,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                onReadFile(item) { content ->
                                    fileContent = item.name to content
                                }
                            },
                            onLongClick = { onShare(item) },
                        )
                        .padding(8.dp),
                )
            }
        }
    }

    // 文件内容弹窗 (点击某条日志触发) —— 与日志堆栈详情同一个 TextDialog, 对齐原版
    // (原 CrashLogViewDialog 是 KMP 化时分裂出的第二份实现, 已收敛删除)
    fileContent?.let { (name, content) ->
        TextDialog(
            title = name,
            content = content,
            onDismiss = { fileContent = null },
        )
    }
}

/**
 * 崩溃日志对话框 (带 Dialog 窗口, 供桌面 / iOS 端直接使用)。
 *
 * 四端唯一入口 (app 端原 CrashLogsDialog Fragment 已删, 统一走 crash_logs Overlay)。
 *
 * @see CrashLogsDialogContent
 */
@Composable
fun CrashLogsDialog(
    logs: List<CrashLogItem>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onReadFile: (CrashLogItem, (String) -> Unit) -> Unit,
    onShare: (CrashLogItem) -> Unit,
) {
    val colors = AppTheme.colors
    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        // 圆角/底色对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        Surface(
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            CrashLogsDialogContent(logs, onDismiss, onClear, onReadFile, onShare)
        }
    }
}
