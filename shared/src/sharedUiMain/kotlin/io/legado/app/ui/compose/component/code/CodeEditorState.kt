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

/** 回车自动缩进触发字符集 (对齐原版 CodeView mIndentCharacterList) */
private val IndentCharacterList = setOf('{', '(', '[', '+', '-', '*', '/', '=')

/** 闭合配对字符集 (对齐原版 CodeView mClosePairMap) */
private val ClosePairSet = setOf('}', ')', ']')

/**
 * 代码编辑状态: TextFieldValue + 撤销/重做历史 + 光标处插入 + 输入修正,
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
        val adjusted = adjustInput(value, new)
        if (adjusted.text != value.text) {
            undoStack.addLast(value)
            if (undoStack.size > MaxHistory) undoStack.removeFirst()
            redoStack.clear()
        }
        value = adjusted
    }

    /**
     * 输入修正, 对齐原版 CodeView 的 InputFilter + onKeyDown:
     * 1. 回车自动缩进 (autoIndent: 继承行首缩进, 遇开括号多加 4 空格, 闭合对自动换行/减缩进)
     * 2. 退格连删整段缩进空格 (KEYCODE_DEL 处理)
     * 3. 剔除输入中的 "#in" 占位符并定位光标
     * 只处理纯插入/删除且改动落在光标处的场景, IME 组词/粘贴多字符不受影响。
     */
    private fun adjustInput(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val oldText = old.text
        val newText = new.text
        if (oldText == newText) return new
        val minLen = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < minLen - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++
        val removed = oldText.substring(prefix, oldText.length - suffix)
        val inserted = newText.substring(prefix, newText.length - suffix)

        // 1. 退格: 光标前是缩进空格时一次删掉整段
        if (old.selection.collapsed && removed == " " && inserted.isEmpty()) {
            var i = prefix - 1
            while (i >= 0 && newText[i] == ' ') i--
            if (i != prefix - 1) {
                val adjusted = newText.substring(0, i + 1) + newText.substring(prefix)
                return TextFieldValue(adjusted, TextRange(i + 1))
            }
        }

        // 2. 回车: 自动缩进
        if (removed.isEmpty() && inserted == "\n") {
            val lineStart = oldText.lastIndexOf('\n', prefix - 1) + 1
            var indentEnd = lineStart
            while (indentEnd < prefix && oldText[indentEnd] == ' ') indentEnd++
            val indentStr = oldText.substring(lineStart, indentEnd)
            var lastNonSpaceChar: Char? = null
            for (i in prefix - 1 downTo lineStart) {
                if (!oldText[i].isWhitespace()) {
                    lastNonSpaceChar = oldText[i]
                    break
                }
            }
            val nextChar = oldText.getOrNull(prefix)
            val sb = StringBuilder("\n").append(indentStr)
            var cursorOffset = sb.length
            if (lastNonSpaceChar in IndentCharacterList) {
                sb.append("    ")
                cursorOffset = sb.length
                if (nextChar != null && nextChar in ClosePairSet) {
                    sb.append('\n').append(indentStr)
                }
            } else {
                if (lastNonSpaceChar != null && lastNonSpaceChar == nextChar &&
                    nextChar in ClosePairSet
                ) {
                    sb.setLength(maxOf(0, sb.length - 4))
                    cursorOffset = sb.length
                }
            }
            val adjusted =
                newText.substring(0, prefix) + sb + newText.substring(prefix + 1)
            return TextFieldValue(adjusted, TextRange(prefix + cursorOffset))
        }

        // 3. 剔除 "#in" 占位符, 光标定位到其起始处
        if (inserted.contains("#in")) {
            val first = inserted.indexOf("#in")
            val cleaned = inserted.replace("#in", "")
            val adjusted = newText.substring(0, prefix) + cleaned +
                newText.substring(prefix + inserted.length)
            return TextFieldValue(adjusted, TextRange(prefix + first))
        }
        return new
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
