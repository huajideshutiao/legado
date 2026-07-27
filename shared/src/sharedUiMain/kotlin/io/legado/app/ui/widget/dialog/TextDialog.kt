package io.legado.app.ui.widget.dialog

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "text_too_large" to "数据太大，无法全部显示…"

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/** 超长文本截断阈值, 对齐 app 端原 TextDialog 的 32KB 上限。 */
private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 通用文本展示对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.widget.dialog.TextDialog`, 但去掉对 Android Fragment /
 * IntentData / Markwon / AndroidView 的依赖, 改为纯 @Composable + 回调形式:
 * - 展示标题 + 长文本内容 (支持垂直滚动 + 文本选择)
 * - 底部按钮: 可选中性按钮 + 复制按钮 + 取消 + 确定
 * - 超过 32KB 的内容截断并追加提示 (对齐原 TextDialog 的截断逻辑)
 *
 * 原 app 端支持 MD/HTML/TEXT 三种模式, KMP 版仅保留 TEXT 模式 (MD 依赖 Markwon+Glide,
 * HTML 依赖 AnnotatedString.fromHtml, 均为平台相关; 桌面端需要时由调用方预处理后再传入纯文本)。
 *
 * @param title 对话框标题
 * @param content 要展示的文本内容
 * @param onConfirm 用户点击确定按钮
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部 / 取消按钮)
 * @param neutralButtonText 可选的中性按钮文案 (如 "默认")
 * @param onNeutral 可选的中性按钮回调
 */
@Composable
fun TextDialog(
    title: String,
    content: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    neutralButtonText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val okText = rememberString("ok")
    val cancelText = rememberString("cancel")
    val copyText = rememberString("copy")
    val tooLargeText = rememberString("text_too_large")
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 18.sp,
            )
        },
        text = {
            // 超长内容截断 (对齐 app 端原 TextDialog 的 32KB 截断逻辑)
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
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = okText, color = DesignTokens.arcoBlue6)
            }
        },
        dismissButton = {
            Row {
                // 中性按钮 (可选, 靠左)
                if (neutralButtonText != null && onNeutral != null) {
                    TextButton(onClick = onNeutral) {
                        Text(text = neutralButtonText, color = DesignTokens.arcoBlue6)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 复制按钮 (长文本场景)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(content))
                }) {
                    Text(text = copyText, color = DesignTokens.arcoBlue6)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = cancelText, color = colors.secondaryText)
                }
            }
        },
        // 圆角/底色对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        shape = DesignTokens.shapeDefault,
        backgroundColor = colors.fillet,
    )
}
