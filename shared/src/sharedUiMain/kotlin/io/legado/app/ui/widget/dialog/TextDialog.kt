package io.legado.app.ui.widget.dialog

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
import io.legado.app.ui.compose.MarkdownContentSelectable
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.text_too_large
import org.jetbrains.compose.resources.stringResource

private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 通用文本展示对话框，保留旧调用方的确定/中性按钮契约，同时支持 Markdown、HTML 和纯文本模式。
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

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogSize(),
        properties = AppDialogSizes.properties(),
        title = {
            Text(
                text = title,
                color = colors.primaryText,
                fontSize = 18.sp,
            )
        },
        text = {
            when (mode) {
                TextDialogMode.MD -> MarkdownContentSelectable(
                    content = content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = AppDialogSizes.fullHeight())
                        .verticalScroll(rememberScrollState()),
                )

                TextDialogMode.HTML, TextDialogMode.TEXT -> {
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
                                .heightIn(max = AppDialogSizes.fullHeight())
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = okText, color = DesignTokens.arcoBlue6)
            }
        },
        dismissButton = {
            Row {
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
            }
        },
        shape = DesignTokens.shapeDefault,
        backgroundColor = colors.fillet,
    )
}

enum class TextDialogMode {
    MD, HTML, TEXT,
}
