package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.drop

/** 回车自动缩进触发字符集 (对齐原版 CodeView mIndentCharacterList) */
private val IndentCharacterList = setOf('{', '(', '[', '+', '-', '*', '/', '=')

/** 闭合配对字符集 (对齐原版 CodeView mClosePairMap) */
private val ClosePairSet = setOf('}', ')', ']')

/**
 * 代码编辑状态 (新版 TextFieldState API)。
 *
 * 文本/选区/撤销历史全部由框架的 [TextFieldState] 持有 (UndoState 原生撤销/重做);
 * 输入修正 (回车自动缩进 / 退格整段删缩进 / "#in" 剔除) 作为 [inputTransformation]
 * 在用户编辑时对缓冲区同步生效 —— 无旧版 TextFieldValue 同步回路的一帧滞后。
 * 供 [CodeTextField] 与 [KeyboardToolbar] 的 ↩️/↪️/辅助键共用 (对齐原 CodeView 自带 undo/redo)。
 */
@Stable
class CodeEditorState(initial: String) {

    /** 单一数据源: 文本 + 选区 + 撤销历史 (用户输入由字段直接写入) */
    val textFieldState = TextFieldState(initial)

    /**
     * 输入修正, 对齐原版 CodeView 的 InputFilter + onKeyDown:
     * 1. 回车自动缩进 (autoIndent: 继承行首缩进, 遇开括号多加 4 空格, 闭合对自动换行/减缩进)
     * 2. 退格连删整段缩进空格 (KEYCODE_DEL 处理)
     * 3. 剔除输入中的 "#in" 占位符并定位光标
     *
     * 先用 changes 区间 O(1) 判定本次编辑是否命中上述场景, 未命中 (常规打字/粘贴/IME 组词/
     * 纯选区变化) 直接跳过 —— 不做每次按键 O(n) 的字符串拷贝 + 全量 diff (大文件输入卡顿
     * 的根源)。命中才复用纯函数 [adjustInput]: 以缓冲区修改前状态 (originalText/originalSelection)
     * 为 old、当前建议值为 new, 需要调整时改写缓冲区并校正光标。回车插入点用 [newlineInsertPos]
     * (来自 changes 区间, 等价原版 InputFilter 的 dStart), 不依赖 selection 反推。
     * 只处理纯插入/删除且改动落在光标处的场景, IME 组词/粘贴多字符不受影响 (同旧逻辑)。
     */
    @OptIn(ExperimentalFoundationApi::class)
    val inputTransformation: InputTransformation = InputTransformation {
        // 纯选区/光标变化 (鼠标拖选/点击逐事件触发): changes 无文本改动时直接跳过。
        // 否则大文件下每次做 O(n) 字符串拷贝 + 全量 diff, 拖选逐事件执行 → 卡顿且
        // 拖垮手势 (表现为鼠标无法选择文本)。
        if (changes.changeCount == 0) return@InputTransformation
        // 常规打字/粘贴/IME 组词 (非修正场景) 直接跳过: 不再每次按键做 O(n) 字符串拷贝 +
        // 全量 diff (大文件输入卡顿的根源)。只有命中修正场景 (回车自动缩进/退格删缩进/
        // "#in" 剔除) 才走完整 adjustInput —— 用 changes 区间 O(1) 判定, 不物化全文。
        val original = changes.getOriginalRange(0)
        val range = changes.getRange(0)
        val hit = when {
            // 回车自动缩进: 单字符插入 '\n' (等价 adjustInput 的 removed 为空 + inserted=="\n")
            original.collapsed && range.length == 1 &&
                asCharSequence().getOrNull(range.min) == '\n' -> true
            // 退格删缩进空格: 单字符删除 ' ' 且改动前光标 collapsed (对齐 adjustInput 的
            // old.selection.collapsed: 整段选中删除不触发)
            !original.collapsed && original.length == 1 && range.collapsed &&
                originalSelection.collapsed && originalText.getOrNull(original.min) == ' ' -> true
            // "#in" 剔除: 本次插入/替换文本含占位符 (subSequence 只取改动区间, O(改动长度))
            changes.changeCount == 1 &&
                asCharSequence().subSequence(range.min, range.max).contains("#in") -> true
            else -> false
        }
        if (!hit) return@InputTransformation
        val oldValue = TextFieldValue(originalText.toString(), originalSelection)
        val newValue = TextFieldValue(asCharSequence().toString(), selection)
        val adjusted = adjustInput(oldValue, newValue, newlineInsertPos())
        if (adjusted.text != asCharSequence().toString() || adjusted.selection != selection) {
            replace(0, length, adjusted.text)
            selection = adjusted.selection
        }
    }

    /**
     * 当前编辑是否是一次"单字符插入", 返回新文本中该字符的下标 (等价原版 InputFilter
     * 的 dStart); 非单字符插入 (删除/替换/多字符/IME 组词) 返回 -1。
     * 用 changes 区间精确定位, 不受 diff 公共前缀影响 (在 '\n' 前插 '\n' 时 prefix 会
     * 越过真实插入点, selection 反推也会失效的场景之一)。
     */
    @OptIn(ExperimentalFoundationApi::class)
    private fun TextFieldBuffer.newlineInsertPos(): Int =
        if (changes.changeCount == 1) {
            val original = changes.getOriginalRange(0)
            val range = changes.getRange(0)
            if (original.collapsed && range.length == 1) range.min else -1
        } else {
            -1
        }

    /** 兼容读取: 当前文本+选区的 [TextFieldValue] 视图 (外部读 editor.value.text/.selection) */
    val value: TextFieldValue
        get() = TextFieldValue(textFieldState.text.toString(), textFieldState.selection)

    /**
     * 统一变更通知 (对齐原版 CodeView 的 TextWatcher 单一同步点):
     * 用户输入/程序化 edit/undo/redo 的一切变化都会触发 (由 [rememberCodeEditorState]
     * 观察 textFieldState 驱动), 外部 (entity.value 同步/查找高亮刷新) 只挂这里。
     */
    var onChanged: ((TextFieldValue) -> Unit)? = null

    /** 程序化修改 (格式化/查找替换), 撤销历史由 UndoState 自动记录 */
    fun edit(block: TextFieldBuffer.() -> Unit) {
        textFieldState.edit(block)
    }

    /** 光标/选区处插入 (键盘辅助条 sendText), 可撤销 */
    fun insertAtCursor(insert: String) = edit {
        val start = selection.min
        val end = selection.max
        replace(start, end, insert)
        // 光标落到插入文本之后 (对齐旧 insertAtCursor 的 TextRange(start + insert.length))
        selection = TextRange(start + insert.length)
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() = textFieldState.undoState.undo()

    @OptIn(ExperimentalFoundationApi::class)
    fun redo() = textFieldState.undoState.redo()

    /** 外部重新载入文本 (打开文件/切换条目), 清空撤销历史 */
    @OptIn(ExperimentalFoundationApi::class)
    fun setText(text: String) {
        if (text == textFieldState.text.toString()) return
        textFieldState.undoState.clearHistory()
        textFieldState.edit {
            replace(0, length, text)
            selection = TextRange(text.length)
        }
    }

    /**
     * 输入修正纯函数 (原逻辑, 对照原版 CodeView InputFilter): 对 old/new 做 diff 并返回
     * 调整后的值。回车插入点优先用 [newlineInsertPos] (changes 精确定位, 等价原版
     * InputFilter 的 dStart); 传入 -1 时退回 selection 反推 (旧 quickjs 语义, 兜底)。
     * 见 [inputTransformation] 说明。
     */
    private fun adjustInput(
        old: TextFieldValue,
        new: TextFieldValue,
        newlineInsertPos: Int = -1,
    ): TextFieldValue {
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
            // 插入点优先取 changes 精确定位 (等价原版 InputFilter 的 dStart, 见
            // newlineInsertPos); 传 -1 时退回 selection 反推: 纯插入 '\n' 时 selection
            // 恒为 collapsed 且紧贴插入点, new.selection.start - 1 即新文本中该 '\n' 的下标。
            // diff 的公共前缀会把光标前原有的 '\n' 吃进前缀 (prefix 越过真实插入点):
            // lineStart 算到下一行 → indentStr 恒空、行首扫描区间为空 → 自动缩进/闭合对
            // 全失效, selection 被推到下一行行首 —— changes 定位不受此影响。
            val insert =
                newlineInsertPos.takeIf { it >= 0 && newText.getOrNull(it) == '\n' }
                    ?: new.selection.start - 1
            if (insert < 0 || newText.getOrNull(insert) != '\n') {
                // 防御: 未能定位到插入的 '\n' (异常路径), 原样返回字段结果
                return new
            }
            val lineStart = oldText.lastIndexOf('\n', insert - 1) + 1
            var indentEnd = lineStart
            while (indentEnd < insert && oldText[indentEnd] == ' ') indentEnd++
            val indentStr = oldText.substring(lineStart, indentEnd)
            var lastNonSpaceChar: Char? = null
            for (i in insert - 1 downTo lineStart) {
                if (!oldText[i].isWhitespace()) {
                    lastNonSpaceChar = oldText[i]
                    break
                }
            }
            val nextChar = oldText.getOrNull(insert)
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
                newText.substring(0, insert) + sb + newText.substring(insert + 1)
            return TextFieldValue(adjusted, TextRange(insert + cursorOffset))
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
}

/**
 * [key] 变化时重建状态 (对话框按传入 code 重开); 默认不重建, 避免受控回写把光标顶回末尾。
 *
 * 同时挂起对 textFieldState **文本**变化的观察 (只读 text, 选区/光标移动不触发): 文本变化
 * (用户输入/程序化 edit/undo/redo) 触发 [CodeEditorState.onChanged]; drop(1) 跳过初始发射,
 * 对齐旧语义 (onChanged 只在变化后触发)。选区变化不触发, 避免鼠标拖选逐事件写外部状态
 * (entity.value/查找高亮) 造成卡顿。
 */
@Composable
fun rememberCodeEditorState(initial: String, key: Any? = Unit): CodeEditorState {
    val editor = remember(key) { CodeEditorState(initial) }
    LaunchedEffect(editor) {
        snapshotFlow { editor.textFieldState.text.toString() }
            .drop(1)
            .collect { editor.onChanged?.invoke(editor.value) }
    }
    return editor
}
