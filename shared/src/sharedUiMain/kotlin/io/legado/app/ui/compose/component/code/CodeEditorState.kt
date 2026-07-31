package io.legado.app.ui.compose.component.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

private const val MaxHistory = 100

/**
 * 代码编辑状态: TextFieldValue + 撤销/重做历史 + 光标处插入,
 * 供 [CodeTextField] 与 [KeyboardToolbar] 的 ↩️/↪️/辅助键共用 (对齐原 CodeView 自带 undo/redo)。
 */
@Stable
class CodeEditorState(initial: String) {

    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    /** 仅文本变化才入历史, 纯移动光标/改选区不占栈 */
    fun onValueChange(new: TextFieldValue) {
        if (new.text != value.text) {
            undoStack.addLast(value)
            if (undoStack.size > MaxHistory) undoStack.removeFirst()
            redoStack.clear()
        }
        value = new
    }

    /** 外部重新载入文本 (打开文件/切换条目), 不入历史 */
    fun setText(text: String) {
        if (text == value.text) return
        undoStack.clear()
        redoStack.clear()
        value = TextFieldValue(text, TextRange(text.length))
    }

    fun insertAtCursor(insert: String) = onValueChange(value.insertAtCursor(insert))

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = prev
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
    }
}

/** [key] 变化时重建状态 (对话框按传入 code 重开); 默认不重建, 避免受控回写把光标顶回末尾 */
@Composable
fun rememberCodeEditorState(initial: String, key: Any? = Unit): CodeEditorState =
    remember(key) { CodeEditorState(initial) }
