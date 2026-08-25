package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.toHtmlAnnotatedString
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.text_too_large
import org.jetbrains.compose.resources.stringResource

private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 通用文本展示对话框，保留旧调用方的确定/中性按钮契约，同时支持 Markdown、HTML 和纯文本模式。
 *
 * 布局 = AppDialog + Surface + Column(标题固定 / 正文 weight(1f)+[SelectableText] / 按钮行钉底)。
 * 不用 M2 AlertDialog: 其 BaselineLayout 在 CMP 桌面按"未钳制的标题+正文高"汇报, 长文本时
 * 对话框超 Surface 封顶, 滚动视口 > 可视区, 滚动错位 (内容下移/顶部空白/按钮被推出屏幕外,
 * 用户多轮实测复现)。weight+内部滚动方案视口恒定 (正文区 = 对话框剩余空间), 与
 * AppAlertDialogContent/AppLogDialog 同一已验证模式。正文选择用 [SelectableText]
 * (readOnly BasicTextField, 拖选/拖手柄越界自动滚动, 对齐 master 原生 TextView)。
 */
@Composable
fun TextDialog(
    title: String,
    content: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    neutralButtonText: String? = null,
    onNeutral: (() -> Unit)? = null,
    mode: TextDialogMode = TextDialogMode.TEXT,
) {
    val colors = AppTheme.colors
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)
    val copyText = stringResource(Res.string.copy)
    val tooLargeText = stringResource(Res.string.text_too_large)
    val clipboard = LocalClipboardManager.current

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.padding(vertical = DesignTokens.spacingDefault)) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                // 正文区: weight 占对话框剩余空间 (视口恒定), 超长滚动, 按钮行恒可见
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    when (mode) {
                        TextDialogMode.MD -> MarkdownContentSelectable(
                            content = content,
                            // 滚动由 MarkdownContent 内部分支承担 (短文档 Column 自带 / 长文档 LazyColumn 虚拟化)
                            modifier = Modifier,
                        )

                        TextDialogMode.HTML -> {
                            // 对齐原版 binding.textView.setHtml(content) / app 端 Compose 版
                            // AnnotatedString.fromHtml: HTML 走富文本渲染, 不是纯文本
                            // (remember: Ksoup 解析不随重组重跑)
                            val html = remember(content) { content.toHtmlAnnotatedString() }
                            SelectableText(
                                text = html,
                                color = colors.secondaryText,
                                fontSize = 15.sp,
                            )
                        }

                        TextDialogMode.TEXT -> {
                            val displayText = if (content.length >= MAX_TEXT_LENGTH) {
                                content.take(MAX_TEXT_LENGTH) + "\n\n" + tooLargeText
                            } else {
                                content
                            }
                            // SelectableText (readOnly BasicTextField): 长按拖选/拖手柄越界自动滚动,
                            // 对齐 master 分支原生 TextView (SelectionContainer 无自动滚动)
                            SelectableText(
                                text = displayText,
                                color = colors.secondaryText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
                // 按钮行钉底 (对照 AppAlertDialogContent: neutral 靠左, copy/取消/确定 靠右)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (neutralButtonText != null && onNeutral != null) {
                        TextButton(onClick = onNeutral) {
                            Text(text = neutralButtonText, color = DesignTokens.arcoBlue6)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }) {
                        Text(text = copyText, color = DesignTokens.arcoBlue6)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text(text = cancelText, color = colors.secondaryText)
                    }
                    TextButton(onClick = onConfirm) {
                        Text(text = okText, color = DesignTokens.arcoBlue6)
                    }
                }
            }
        }
    }
}

enum class TextDialogMode {
    MD, HTML, TEXT,
}
