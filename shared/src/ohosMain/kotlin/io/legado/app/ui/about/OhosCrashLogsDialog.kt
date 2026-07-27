package io.legado.app.ui.about

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.File

/** 超长崩溃日志截断阈值, 对齐 TextDialog 的 32KB 上限。 */
private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 鸿蒙端崩溃日志对话框 (对照 app 端 `CrashLogsDialog` + sharedUiMain `CrashLogsDialogContent`)。
 *
 * ohosMain 已继承 sharedUiMain, 但因主题/图标依赖差异 (MaterialTheme vs AppTheme, materialIconsExtended),
 * 此处单独实现等价 UI, 行为与 app/desktop 端一致:
 * - 标题栏: 返回 + "崩溃日志" + 清空按钮
 * - 列表: LazyColumn, 行=文件名, 点击读内容弹内容查看对话框, 长按分享(toast)
 * - 内容查看对话框: 标题栏 + 可选择滚动正文 + 底部 复制/取消/确定 按钮
 * - 超长内容截断 (32KB, 对齐 TextDialog)
 *
 * 文件读取路径: `{AppFilesDirs.cacheDir}/crash/` (对齐 app 端 externalCacheDir/crash 回退逻辑,
 * 鸿蒙端无 externalCacheDir, 直接用 cacheDir)。
 *
 * @param onDismiss 用户取消 (返回按钮)
 */
@Composable
fun OhosCrashLogsDialog(
    onDismiss: () -> Unit,
) {
    // 崩溃日志文件列表 (cacheDir/crash/*.log)
    var logs by remember { mutableStateOf<List<File>>(emptyList()) }
    // 选中的日志文件内容 (非 null 时弹出内容查看对话框)
    var fileContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 首次进入读取崩溃日志目录
    LaunchedEffect(Unit) {
        logs = loadCrashLogs()
    }

    Dialog(onDismissRequest = onDismiss) {
        // 圆角对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        Surface(
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 标题栏: 返回 + "崩溃日志" + 清空
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ohosMain 未声明 materialIconsExtended, 用 TextButton 替代 Icon(返回箭头)
                    TextButton(onClick = onDismiss) {
                        Text("←")
                    }
                    Text(
                        text = "崩溃日志",
                        fontSize = 18.sp,
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = {
                        clearCrashLogs()
                        logs = loadCrashLogs()
                    }) {
                        Text("清空")
                    }
                }
                // 日志列表
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(logs, key = { it.name }) { file ->
                        CrashLogRow(
                            fileName = file.name,
                            onClick = {
                                val content = readCrashLog(file)
                                fileContent = file.name to content
                            },
                            onLongClick = {
                                // 鸿蒙端无系统分享 Intent, toast 提示
                                Toasters.get().toast("长按: ${file.name}")
                            },
                        )
                    }
                }
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

/** 崩溃日志列表行: 文件名, 点击读内容, 长按分享。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrashLogRow(
    fileName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Text(
        text = fileName,
        color = AppTheme.colors.primaryText,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * 单条崩溃日志内容查看对话框。
 *
 * 对齐 sharedUiMain `CrashLogViewDialog`: 标题栏 + 可选择滚动正文 + 底部 复制/取消/确定 按钮。
 * 超长内容截断 (32KB)。
 */
@Composable
private fun CrashLogViewDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // 超长内容截断 (对齐 TextDialog 的 32KB 截断逻辑)
    val displayText = if (content.length >= MAX_TEXT_LENGTH) {
        content.take(MAX_TEXT_LENGTH) + "\n\n内容过长, 已截断"
    } else {
        content
    }

    Dialog(onDismissRequest = onDismiss) {
        // 圆角对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        Surface(
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ohosMain 未声明 materialIconsExtended, 用 TextButton 替代 Icon(返回箭头)
                    TextButton(onClick = onDismiss) {
                        Text("←")
                    }
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 正文 (可选择 + 可滚动)
                SelectionContainer {
                    Text(
                        text = displayText,
                        color = AppTheme.colors.secondaryText,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                // 底部按钮栏: 复制 + 取消 + 确定
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(content))
                    }) {
                        Text("复制")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

/** 读取崩溃日志目录 (cacheDir/crash/) 下所有文件, 按文件名降序排列。 */
private fun loadCrashLogs(): List<File> {
    val crashDir = File(AppFilesDirs.get().cacheDir + "/crash")
    if (!crashDir.exists() || !crashDir.isDirectory) return emptyList()
    return runCatching {
        crashDir.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.name }
            ?.distinctBy { it.name }
            ?: emptyList()
    }.getOrDefault(emptyList())
}

/** 读取单条崩溃日志文件内容。 */
private fun readCrashLog(file: File): String {
    return runCatching { file.readText() }
        .getOrElse { "读取失败: ${it.localizedMessage}" }
}

/** 清空崩溃日志目录 (删除 cacheDir/crash/ 下所有文件)。 */
private fun clearCrashLogs() {
    val crashDir = File(AppFilesDirs.get().cacheDir + "/crash")
    if (!crashDir.exists() || !crashDir.isDirectory) return
    runCatching {
        crashDir.listFiles { f -> f.isFile }?.forEach { it.delete() }
    }.onFailure { e ->
        Toasters.get().toast("清空失败: ${e.localizedMessage}")
    }
}
