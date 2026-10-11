package io.legado.app.ui.compose.component

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 受控值 ↔ [TextFieldState] 的取值/写值适配: String 与 TextFieldValue 两种契约共用同一套同步。
 */
@Stable
internal interface SyncedFieldAdapter<T : Any> {

    /** 用外部初始值构造 state */
    fun initialState(value: T): TextFieldState

    /** state → 外部值 (出向同步的取值与去重口径) */
    fun read(state: TextFieldState): T

    /** 契约携带的文本: 回声判定与入向写回的唯一口径, 同时是装饰盒 (label 浮动/占位符判定) 的显示文本 */
    fun text(value: T): String

    /** 契约携带的选区; 契约不含选区时为 null */
    fun selection(value: T): TextRange?
}

/** String 契约: 只比文本 */
internal object StringFieldAdapter : SyncedFieldAdapter<String> {
    override fun initialState(value: String) = TextFieldState(value)
    override fun read(state: TextFieldState) = state.text.toString()
    override fun text(value: String) = value
    override fun selection(value: String): TextRange? = null
}

/** TextFieldValue 契约: 文本+选区 (IME composition / undo-redo 场景需要保留选区) */
internal object TextFieldValueFieldAdapter : SyncedFieldAdapter<TextFieldValue> {
    override fun initialState(value: TextFieldValue) = TextFieldState(value.text, value.selection)
    override fun read(state: TextFieldState) =
        TextFieldValue(state.text.toString(), state.selection)

    override fun text(value: TextFieldValue) = value.text
    override fun selection(value: TextFieldValue) = value.selection
}

/**
 * 把 String 形态的受控字段桥到 [TextFieldState]: 用户编辑回推 [onValueChange],
 * 外部 [value] 变化改写 state (清除按钮/粘贴源等程序化修改)。
 *
 * state 必须用初始值构造: 用空串构造会让首帧同步把空串推给 [onValueChange], 清空调用方的初始值。
 */
@Composable
fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState = rememberSyncedTextFieldState(value, onValueChange, StringFieldAdapter)

/** [rememberSyncedTextFieldState] 的 TextFieldValue 契约版本 (需要保留选区的字段) */
@Composable
fun rememberSyncedTextFieldValueState(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
): TextFieldState =
    rememberSyncedTextFieldState(value, onValueChange, TextFieldValueFieldAdapter)

/**
 * 双向同步的唯一实现 (String / TextFieldValue 经 [adapter] 注入差异)。
 *
 * 出向 (state → 外部) 与入向 (外部 → state) 合并在**同一个** [snapshotFlow] 收集体里, 按
 * "本次回调由哪一侧变化触发" 决定动作:
 *
 * - state 侧变化 → 只推给 [onValueChange], 并把文本登记为在途推送, 绝不回写外部值;
 * - 外部侧变化 → 命中在途推送即判为回声丢弃, 否则才写回 state。
 *
 * # 调用方契约与回声判定
 *
 * 调用方把 [onValueChange] 收到的值回写进自己的 [value]; 回写可能滞后于输入框, 也可能先下发较早
 * 的一次 (DB/网络回写后再刷新界面), 故回声判定只以文本为准: 外部文本与本字段推送过的文本相同即
 * 视为回声不回写, 外部文本追上输入框当前文本时在途推送全部了结。判定不依赖"外部至多落后几次推送"
 * 这类次数假设, 也不存在因丢弃在途推送而把回声当成程序化改值的路径。
 *
 * # 文本优先, 选区次之
 *
 * 只有外部文本与输入框不同才是程序化改值 (清除按钮/粘贴源/切换条目/DB 回写): 只改写真正变化的
 * 区间, 选区跟随输入框自身的选区映射; 外部选区描述的是它自己那段文本, 只在文本已一致时作为纯选区
 * 变更 (撤销回位/查找定位) 生效。
 */
@Composable
internal fun <T : Any> rememberSyncedTextFieldState(
    value: T,
    onValueChange: (T) -> Unit,
    adapter: SyncedFieldAdapter<T>,
): TextFieldState {
    val state = remember { adapter.initialState(value) }
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) {
        // 上一次观察到的两侧值: 用于判定本次回调由哪一侧变化触发
        var lastState: T? = null
        var lastExternal: T? = null
        // 已推给外部、外部文本尚未追上的文本 (回声判定依据见 KDoc)
        val pushedTexts = mutableSetOf<String>()
        snapshotFlow { adapter.read(state) to currentValue }
            .collect { (current, external) ->
                val stateChanged = current != lastState
                val externalChanged = external != lastExternal
                lastState = current
                lastExternal = external
                val currentText = adapter.text(current)
                val externalText = adapter.text(external)
                if (stateChanged && current != external) {
                    pushedTexts.add(currentText)
                    currentOnValueChange(current)
                }
                if (!externalChanged) return@collect
                if (externalText == currentText) {
                    // 外部文本已追上输入框: 在途推送全部了结
                    pushedTexts.clear()
                } else if (externalText in pushedTexts) {
                    // 回声 (外部文本落后于输入框), 不回写
                    return@collect
                }
                state.writeExternal(externalText, adapter.selection(external))
            }
    }
    return state
}

/**
 * 入向写回 (两种契约共用的唯一实现, 文本优先、选区次之): 文本不同时只替换公共前缀/后缀之外的区间,
 * 文本相同而选区不同时只改选区。
 */
private fun TextFieldState.writeExternal(newText: String, newSelection: TextRange?) {
    val oldText = text.toString()
    if (oldText == newText) {
        if (newSelection != null && newSelection != selection) {
            edit { selection = newSelection }
        }
        return
    }
    // 最小差异区间: 公共前缀与公共后缀之外才是真正变化的文本 (后缀不与前缀重叠)
    val minLength = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLength && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < minLength - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    val changeStart = prefix
    val changeEnd = oldText.length - suffix
    val replacement = newText.substring(prefix, newText.length - suffix)
    edit {
        val caret = selection.mapAfterChange(changeStart, changeEnd, replacement.length)
        replace(changeStart, changeEnd, replacement)
        selection = caret
    }
}

/**
 * `[start, end)` 被替换成 [replacementLength] 个字符后原选区的位置 (与 foundation TextFieldBuffer
 * 对程序化编辑的选区调整一致): 整体在变化区间之前保持不动, 整体在之后按长度差平移, 落在变化区间内
 * (含光标恰在纯插入位置) 落到插入文本末尾, 与变化区间部分重叠时只调整重叠的那一端。
 */
private fun TextRange.mapAfterChange(start: Int, end: Int, replacementLength: Int): TextRange {
    val delta = replacementLength - (end - start)
    return when {
        max < start -> this
        min <= start && end <= max ->
            if (min == max) TextRange(min + delta) else TextRange(min, max + delta)
        min > start && max < end -> TextRange(start + replacementLength)
        min >= end -> TextRange(min + delta, max + delta)
        start < min -> TextRange(start + replacementLength, max + delta)
        else -> TextRange(min, start)
    }
}
