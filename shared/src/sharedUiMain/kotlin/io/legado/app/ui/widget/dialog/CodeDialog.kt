package io.legado.app.ui.widget.dialog

// I18N KEYS (need to register in ResourceProvider.jvm.kt / Localizable.strings):
//   "code_view" to "code view"
//   "action_save" to "保存" (已注册)

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.component.code.CodeEditorSearchTarget
import io.legado.app.ui.compose.component.code.CodeSearchHighlightState
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.rememberCodeEditorState
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.component.code.rememberHighlightedCode
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.code_view
import legado.shared.generated.resources.ic_save
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 代码查看/编辑对话框内容 (KMP 共享, desktop / iOS 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.widget.dialog.CodeDialog` 的 UI 结构, 去掉对
 * BaseDialogFragment / IntentData 的依赖, 改为纯 @Composable + 回调形式:
 * - 标题栏: [DialogTitleBar] (disableEdit=true 时标题 "code view", 否则空串), 右侧保存按钮
 *   (仅可编辑模式显示, 图标 ic_save + contentDescription action_save)
 * - 代码区: 可编辑模式用 [CodeTextField] (语法高亮 + 等宽 + 垂直滚动) + [KeyboardToolbar];
 *   只读模式用 [SelectionContainer] + [Text] (同样着色, 支持文本选择复制)
 *
 * 与 app 端差异 (平台限制, 严禁改变可编辑/只读的交互语义):
 * - app 端通过 Fragment arguments + IntentData 传 code; KMP 版由调用方直接传参。
 * - app 端 onSave 通过 parentFragment/activity 的 Callback 回调; KMP 版用 [onSave] lambda。
 *
 * UI 结构对齐 app 端 (严禁改变样式):
 * - Column fillMaxWidth
 * - DialogTitleBar: onBack=onDismiss, actions=保存按钮 (仅 !disableEdit)
 * - 代码区: fillMaxWidth + weight(1f) + 垂直滚动
 *
 * @param code 初始代码文本
 * @param disableEdit true=只读查看, false=可编辑
 * @param onDismiss 用户点返回键/标题栏返回按钮
 * @param onSave 用户点保存按钮, 参数为当前编辑区文本 (仅 !disableEdit 时触发)
 */
@Composable
fun CodeDialogContent(
    code: String,
    disableEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    onShowKeyboardConfig: () -> Unit = {},
) {
    val colors = AppTheme.colors
    // 标题: disableEdit=true → "code view", 否则空串 (对齐 app 端逻辑)
    val title = if (disableEdit) stringResource(Res.string.code_view) else ""
    val saveDesc = stringResource(Res.string.action_save)
    // 语法高亮: legado + json + js 三组全开, 对齐 app 端 CodeDialog 的三连 addXxxPattern
    val syntax = rememberFullCodeSyntax()
    val editor = rememberCodeEditorState(code, key = code)
    // 查找高亮状态: CodeTextField 叠加全量黄底 + 当前命中强调色 (对齐原版 CodeView 查找高亮)
    val searchHighlight = remember { CodeSearchHighlightState() }
    val focusManager = LocalFocusManager.current

    Column(
        Modifier
            .fillMaxWidth()
            // 桌面端键盘监听: Ctrl+Z 撤销 / Ctrl+Shift+Z、Ctrl+Y 重做 (对照 BookSourceEditScreen
            // 根 Column); 消费 Ctrl+Z 压住桌面 BasicTextField 内置撤销栈, 避免与 CodeEditorState
            // 手写撤销栈双重撤销
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Z if event.isShiftPressed -> {
                        editor.redo()
                        true
                    }

                    Key.Z -> {
                        editor.undo()
                        true
                    }

                    Key.Y -> {
                        editor.redo()
                        true
                    }

                    else -> false
                }
            },
    ) {
        DialogTitleBar(
            title = title,
            onBack = onDismiss,
        ) {
            if (!disableEdit && onSave != null) {
                IconButton(onClick = { onSave(editor.value.text) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = saveDesc,
                        tint = colors.primaryText,
                    )
                }
            }
        }
        // 代码区: 等宽字体 + 垂直滚动, 高度上限 480dp (避免超长内容撑爆 Dialog)
        if (disableEdit) {
            SelectionContainer {
                Text(
                    text = rememberHighlightedCode(code, syntax),
                    color = colors.primaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            // 无边框无背景 (showIndicator=false), 对齐 app 端 CodeView 内嵌呈现
            CodeTextField(
                value = editor.value,
                onValueChange = { editor.onValueChange(it) },
                syntax = syntax,
                showIndicator = false,
                fontSize = 13.sp,
                searchHighlight = searchHighlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            )
            val keyboardState = remember { KeyboardToolbarState() }
            KeyboardToolbar(
                state = keyboardState,
                onSendText = { editor.insertAtCursor(it) },
                onUndo = { editor.undo() },
                onRedo = { editor.redo() },
                onShowConfig = onShowKeyboardConfig,
                target = { CodeEditorSearchTarget(editor, searchHighlight) { focusManager.clearFocus() } },
            )
        }
    }
}

/**
 * 代码查看/编辑对话框 (KMP 共享, desktop / iOS 直接复用)。
 *
 * 用 [AppDialog] 包裹 [CodeDialogContent], 提供声明式 API。
 * app 端不使用本函数 (仍走 DialogFragment 版 CodeDialog 以保持 showDialogFragment 调用点); desktop / iOS 端可直接调用。
 *
 * @param code 初始代码文本
 * @param disableEdit true=只读查看, false=可编辑
 * @param onDismiss 用户关闭对话框 (返回键 / 点击外部 / 标题栏返回)
 * @param onSave 保存回调 (仅 !disableEdit 时有效)
 */
@Composable
fun CodeDialog(
    code: String,
    disableEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    onShowKeyboardConfig: () -> Unit = {},
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = AppTheme.colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            CodeDialogContent(
                code = code,
                disableEdit = disableEdit,
                onDismiss = onDismiss,
                onSave = onSave,
                onShowKeyboardConfig = onShowKeyboardConfig,
            )
        }
    }
}

// ===== @Preview 合并自 androidMain 的 widget/dialog/CommonDialogsPreviews.kt (CodeDialogContent) =====

// ---- CodeDialogContent ----

private val previewJsCode = """
// 书源正文净化脚本
function purify(text) {
    return text
        .replace(/^.*最新章节.*$/gm, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}
purify(result);
""".trimIndent()

@Preview
@Composable
fun CodeDialogContentEditablePreview() = LegadoThemePreview {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

@Preview
@Composable
fun CodeDialogContentReadOnlyPreview() = LegadoThemePreview {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = true,
        onDismiss = {},
    )
}

@Preview
@Composable
fun CodeDialogContentEmptyPreview() = LegadoThemePreview {
    CodeDialogContent(
        code = "",
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

@Preview
@Composable
fun CodeDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    CodeDialogContent(
        code = previewJsCode,
        disableEdit = false,
        onDismiss = {},
        onSave = {},
    )
}

