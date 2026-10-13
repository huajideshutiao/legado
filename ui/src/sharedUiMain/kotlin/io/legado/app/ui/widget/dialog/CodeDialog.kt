package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.code.CodeEditorSearchTarget
import io.legado.app.ui.compose.component.code.CodeSearchHighlightState
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.KeyboardToolbar
import io.legado.app.ui.compose.component.code.KeyboardToolbarState
import io.legado.app.ui.compose.component.code.rememberCodeEditorState
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.component.code.rememberHighlightedCode
import io.legado.app.ui.compose.theme.AppTheme
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.action_save
import legado.ui.generated.resources.code_view
import legado.ui.generated.resources.ic_save
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 代码查看/编辑对话框 (KMP 共享, desktop / iOS / app 三端同一实现)。
 *
 * 对应 app 端 `io.legado.app.ui.widget.dialog.CodeDialog` 的 UI 结构, 去掉对
 * BaseDialogFragment / IntentData 的依赖, 改为纯 @Composable + 回调形式:
 * - 壳: [AppDialogScaffold]; 顶栏标题 disableEdit=true 时 "code view", 否则空串;
 *   右侧保存按钮仅可编辑模式显示 (图标 ic_save + contentDescription action_save)
 * - 代码区: 可编辑模式用 [CodeTextField] (语法高亮 + 等宽 + 垂直滚动) + [KeyboardToolbar];
 *   只读模式用 [SelectableText] (readOnly BasicTextField, 同样着色, 支持文本选择复制 +
 *   拖选/拖手柄越界自动滚动)
 *
 * 壳体与代码区必须同一函数内构建: 保存按钮要读代码区当前文本, 拆成两层就拿不到。
 *
 * 与 app 端差异 (平台限制, 严禁改变可编辑/只读的交互语义):
 * - app 端通过 Fragment arguments + IntentData 传 code; KMP 版由调用方直接传参。
 * - app 端 onSave 通过 parentFragment/activity 的 Callback 回调; KMP 版用 [onSave] lambda。
 *
 * @param code 初始代码文本
 * @param disableEdit true=只读查看, false=可编辑
 * @param onDismiss 用户点返回键/标题栏返回按钮
 * @param onSave 用户点保存按钮, 参数为当前编辑区文本 (仅 !disableEdit 时触发)
 * @param onShowKeyboardConfig 打开键盘助手设置
 */
@Composable
fun CodeDialog(
    code: String,
    disableEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    onShowKeyboardConfig: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val saveDesc = stringResource(Res.string.action_save)
    // 语法高亮: legado + json + js 三组全开, 对齐 app 端 CodeDialog 的三连 addXxxPattern
    val syntax = rememberFullCodeSyntax()
    val editor = rememberCodeEditorState(code, key = code)
    // 查找高亮状态: CodeTextField 叠加全量黄底 + 当前命中强调色 (对齐原版 CodeView 查找高亮)
    val searchHighlight = remember { CodeSearchHighlightState() }
    val focusManager = LocalFocusManager.current

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        // 标题: disableEdit=true → "code view", 否则空串 (对齐 app 端逻辑)
        title = if (disableEdit) stringResource(Res.string.code_view) else "",
        properties = AppDialogSizes.properties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        contentPadding = PaddingValues(0.dp),
        titleActions = {
            if (!disableEdit && onSave != null) {
                IconButton(onClick = { onSave(editor.value.text) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = saveDesc,
                        tint = colors.primaryText,
                    )
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            // 桌面端 Ctrl+Z/Y 撤销/重做由新版 BasicTextField 原生处理 (KeyCommand.UNDO/REDO →
            // 同一个 undoState), 无需手写按键路由
        ) {
            // 代码区: 等宽字体 + 内部滚动, 高度上限 480dp (避免超长内容撑爆 Dialog)
            if (disableEdit) {
                // SelectableText (readOnly BasicTextField): 语法高亮 span 保留,
                // 长按拖选/拖手柄越界自动滚动 (SelectionContainer 无自动滚动)
                SelectableText(
                    text = rememberHighlightedCode(code, syntax),
                    color = colors.primaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                )
            } else {
                // 无边框无背景 (showIndicator=false), 对齐 app 端 CodeView 内嵌呈现
                CodeTextField(
                    value = editor.textFieldState,
                    inputTransformation = editor.inputTransformation,
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
}
