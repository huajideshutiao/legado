package io.legado.app.ui.about

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "crash_log" to "崩溃日志",
//   "clear" to "清空"

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.crash_log
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.text_too_large
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/** 超长崩溃日志截断阈值, 对齐 TextDialog 的 32KB 上限。 */
private const val MAX_TEXT_LENGTH = 32 * 1024

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

    // 文件内容弹窗 (点击某条日志触发)
    fileContent?.let { (name, content) ->
        CrashLogViewDialog(
            title = name,
            content = content,
            onDismiss = { fileContent = null },
        )
    }
}

/**
 * 单条崩溃日志内容查看对话框 (项目对话框规范: TitleBar + 内容 + 底部按钮栏)。
 *
 * 替代原 [io.legado.app.ui.widget.dialog.TextDialog] (M3 AlertDialog) 调用:
 * - 宽度对齐 [io.legado.app.base.BaseComposeDialogFragment] (0.9 屏宽, 上限 800dp)
 * - 顶部 [DialogTitleBar] (返回 + 文件名)
 * - 底部按钮栏: 复制 + 取消 + 确定 ([AppTextButton], 对齐 BookmarkDialog)
 * - 超长内容截断 (32KB, 对齐原 TextDialog)
 *
 * 不改变读取/分享等宿主回调逻辑, 仅替换展示层。
 */
@Composable
private fun CrashLogViewDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)
    val copyText = stringResource(Res.string.copy)
    val tooLargeText = stringResource(Res.string.text_too_large)
    val clipboard = LocalClipboardManager.current

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        // 圆角/底色对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        Surface(
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = title,
                    onBack = onDismiss,
                )
                // 超长内容截断 (对齐 TextDialog 的 32KB 截断逻辑)
                val displayText = if (content.length >= MAX_TEXT_LENGTH) {
                    content.take(MAX_TEXT_LENGTH) + "\n\n" + tooLargeText
                } else {
                    content
                }
                SelectionContainer {
                    Text(
                        text = displayText,
                        color = colors.secondaryText,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                // 底部按钮栏 (对齐 BookmarkDialog: 左侧 contextual + 右侧 cancel/ok)
                Row(Modifier.fillMaxWidth()) {
                    AppTextButton(text = copyText) {
                        clipboard.setText(AnnotatedString(content))
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText) { onDismiss() }
                    AppTextButton(text = okText) { onDismiss() }
                }
            }
        }
    }
}

/**
 * 崩溃日志对话框 (带 Dialog 窗口, 供桌面 / iOS 端直接使用)。
 *
 * app 端使用 [CrashLogsDialogContent] 嵌入自身 DialogFragment，不调用本函数 (避免双层窗口)。
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

// ===== @Preview 合并自 androidMain 的 about/CrashLogsDialogPreviews.kt =====

/**
 * [CrashLogsDialogContent] 的 @Preview。
 *
 * 取 Content 版本 (无 Dialog 包装); onReadFile 回调直接回填假堆栈文本。
 */

private val previewCrashLogs = listOf(
    CrashLogItem(name = "crash-2026-07-25-21-30-18.txt"),
    CrashLogItem(name = "crash-2026-07-20-08-12-55.txt"),
    CrashLogItem(name = "crash-2026-07-01-23-59-01.txt"),
)

@Preview
@Composable
fun CrashLogsDialogContentPreview() = LegadoThemePreview {
    CrashLogsDialogContent(
        logs = previewCrashLogs,
        onDismiss = {},
        onClear = {},
        onReadFile = { _, callback -> callback("java.lang.RuntimeException: preview stack trace\n\tat io.legado.app.PreviewKt.main(Preview.kt:1)") },
        onShare = {},
    )
}

@Preview
@Composable
fun CrashLogsDialogContentEmptyPreview() = LegadoThemePreview {
    CrashLogsDialogContent(
        logs = emptyList(),
        onDismiss = {},
        onClear = {},
        onReadFile = { _, _ -> },
        onShare = {},
    )
}

@Preview
@Composable
fun CrashLogsDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    CrashLogsDialogContent(
        logs = previewCrashLogs,
        onDismiss = {},
        onClear = {},
        onReadFile = { _, _ -> },
        onShare = {},
    )
}
