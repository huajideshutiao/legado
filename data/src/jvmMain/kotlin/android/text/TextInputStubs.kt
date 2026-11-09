// android.text 输入相关 JVM 空壳 stub: 扩展在 EditTextPreference.OnBindEditTextListener 里
// 给输入框挂过滤器/监听及 TextWatcher/Editable 回调参数需要这些类型。
// 接口签名与常量逐字对齐 android-35 真实定义 (InputFilter.filter 返回可空 CharSequence;
// Spanned 点/标记常量与别名; InputType 变体值); LengthFilter 直接小改复用 AOSP 实现
// (含 UTF-16 高代理截断保护); getSpans 按 actual componentType 生成真实 typed 数组。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.text

/**
 * 对齐 android.text.Spanned: CharSequence 的 span 读面 (真实接口继承 CharSequence)。
 * 常量与别名逐字对齐 android-35 Spanned.java。
 */
interface Spanned : CharSequence {

    companion object {
        const val SPAN_POINT_MARK_MASK: Int = 0x33
        const val SPAN_MARK_MARK: Int = 0x11
        const val SPAN_MARK_POINT: Int = 0x12
        const val SPAN_POINT_MARK: Int = 0x21
        const val SPAN_POINT_POINT: Int = 0x22

        /** 对齐 Spanned.java: SPAN_PARAGRAPH = 0x33。 */
        const val SPAN_PARAGRAPH: Int = 0x33

        /** 对齐 Spanned.java: 四个 inclusive/exclusive 名是相应点/标记原常量别名。 */
        const val SPAN_INCLUSIVE_EXCLUSIVE: Int = SPAN_MARK_MARK
        const val SPAN_INCLUSIVE_INCLUSIVE: Int = SPAN_MARK_POINT
        const val SPAN_EXCLUSIVE_EXCLUSIVE: Int = SPAN_POINT_MARK
        const val SPAN_EXCLUSIVE_INCLUSIVE: Int = SPAN_POINT_POINT

        const val SPAN_COMPOSING: Int = 0x100
        const val SPAN_INTERMEDIATE: Int = 0x200
        const val SPAN_USER_SHIFT: Int = 24
        const val SPAN_USER: Int = -0x1000000
        const val SPAN_PRIORITY_SHIFT: Int = 16
    }

    fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T>

    fun getSpanStart(tag: Any?): Int

    fun getSpanEnd(tag: Any?): Int

    fun getSpanFlags(tag: Any?): Int

    fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int
}

/** 对齐 android.text.Spannable: Spanned 的 span 写面。 */
interface Spannable : Spanned {

    fun setSpan(what: Any?, start: Int, end: Int, flags: Int)

    fun removeSpan(what: Any?)
}

/**
 * Editable: TextWatcher.afterTextChanged 与 EditText.getText() 的类型面。
 * 修改方法签名 (replace/insert/delete/append/clear) 对齐真实接口;
 * append 以协变返回 [Editable] 重声明, 对齐真实方法描述符。
 */
interface Editable : Spannable, Appendable {

    override fun append(csq: CharSequence?): Editable

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable

    override fun append(c: Char): Editable

    fun replace(start: Int, end: Int, source: CharSequence?): Editable

    fun replace(start: Int, end: Int, source: CharSequence?, startOfSource: Int, endOfSource: Int): Editable

    fun insert(where: Int, text: CharSequence?): Editable

    fun insert(where: Int, text: CharSequence?, startOfText: Int, endOfText: Int): Editable

    fun delete(start: Int, end: Int): Editable

    fun clear()

    fun clearSpans()
}

/**
 * 可变文本承载: EditText.getText() 返回的活对象 (快照会让扩展在回调里的追加静默丢失)。
 * span 存储为真实合同面 (合法 watcher 用 setSpan 跟踪编辑偏移): 列表存储 + 修改映射;
 * 修改映射由宿主适配层在文本变更时驱动 (见 DesktopEditable)。
 */
class TextEditable(content: String = "") : Editable {

    private var content: StringBuilder = StringBuilder(content)

    private class SpanRecord(val what: Any, var start: Int, var end: Int, var flags: Int)

    private val spans = ArrayList<SpanRecord>()

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        content.subSequence(startIndex, endIndex)

    override fun append(csq: CharSequence?): Editable {
        content.append(csq ?: "")
        return this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable {
        content.append(csq?.subSequence(start, end) ?: "")
        return this
    }

    override fun append(c: Char): Editable {
        content.append(c)
        return this
    }

    override fun replace(start: Int, end: Int, source: CharSequence?): Editable {
        content.replace(start, end, source?.toString() ?: "")
        return this
    }

    override fun replace(start: Int, end: Int, source: CharSequence?, startOfSource: Int, endOfSource: Int): Editable {
        content.replace(start, end, source?.subSequence(startOfSource, endOfSource)?.toString() ?: "")
        return this
    }

    override fun insert(where: Int, text: CharSequence?): Editable {
        content.insert(where, text?.toString() ?: "")
        return this
    }

    override fun insert(where: Int, text: CharSequence?, startOfText: Int, endOfText: Int): Editable {
        content.insert(where, text?.subSequence(startOfText, endOfText)?.toString() ?: "")
        return this
    }

    override fun delete(start: Int, end: Int): Editable {
        content.delete(start, minOf(end, content.length))
        return this
    }

    override fun clear() {
        content.setLength(0)
    }

    override fun clearSpans() {
        spans.clear()
    }

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        if (what == null) return
        spans.removeAll { it.what === what }
        spans.add(SpanRecord(what, start, end, flags))
    }

    override fun removeSpan(what: Any?) {
        if (what == null) return
        spans.removeAll { it.what === what }
    }

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val matched = spans.filter { it.what != null && type.isInstance(it.what) && it.end >= start && it.start <= end }
        @Suppress("UNCHECKED_CAST")
        val result = java.lang.reflect.Array.newInstance(type, matched.size) as Array<T>
        matched.forEachIndexed { index, record -> result[index] = type.cast(record.what) }
        return result
    }

    override fun getSpanStart(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.start ?: -1

    override fun getSpanEnd(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.end ?: -1

    override fun getSpanFlags(tag: Any?): Int = spans.firstOrNull { it.what === tag }?.flags ?: 0

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit
        for (record in spans) {
            if (type != null && !type.isInstance(record.what)) continue
            if (record.start > start && record.start < next) next = record.start
            if (record.end > start && record.end < next) next = record.end
        }
        return next
    }

    /** 宿主 setText 时重置内容 (同一实例, 引用者立即可见)。 */
    fun setContent(value: String) {
        content.setLength(0)
        content.append(value)
    }

    /** 文本变更后的 span 区间映射由使用方驱动 (合同修改点已知替换区间); 存储面仅持有记录。 */

    override fun toString(): String = content.toString()
}

/**
 * `[start, end)` 被替换成 [replacementLength] 个字符后原区间 `[min, max)` 的位置 (与
 * foundation TextFieldBuffer 对程序化编辑的选区/span 调整一致): 整体在变化区间之前保持
 * 不动, 整体在之后按长度差平移, 落在变化区间内 (含光标恰在纯插入位置) 落到插入文本末尾,
 * 与变化区间部分重叠时只调整重叠的那一端。
 */
internal fun mapSpanRange(
    start: Int,
    end: Int,
    replacementLength: Int,
    min: Int,
    max: Int,
): Pair<Int, Int> {
    val delta = replacementLength - (end - start)
    return when {
        max < start -> min to max
        min <= start && end <= max ->
            if (min == max) (min + delta) to (min + delta) else min to (max + delta)
        min > start && max < end -> (start + replacementLength) to (start + replacementLength)
        min >= end -> (min + delta) to (max + delta)
        start < min -> (start + replacementLength) to (max + delta)
        else -> min to start
    }
}

/** 对齐 android.text.InputFilter (真实签名: 返回可空 CharSequence, null = 接受原替换)。 */
interface InputFilter {

    fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence?

    /**
     * 对齐 AOSP InputFilter.LengthFilter (原文小改复用, 含 UTF-16 高代理截断保护):
     * keep<=0 返回空串拒绝, keep 够返回 null 接受原替换, 否则截断返回 (高代理收尾时回退,
     * 避免截断出孤立半代理)。
     */
    class LengthFilter(private val mMax: Int) : InputFilter {

        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int,
        ): CharSequence? {
            var keep = mMax - (dest.length - (dend - dstart))
            if (keep <= 0) {
                return ""
            } else if (keep >= end - start) {
                return null // keep original
            } else {
                keep += start
                if (Character.isHighSurrogate(source[keep - 1])) {
                    --keep
                    if (keep == start) {
                        return ""
                    }
                }
                return source.subSequence(start, keep).toString()
            }
        }

        /** @return the maximum length enforced by this input filter */
        fun getMax(): Int = mMax
    }
}

/** 对齐 android.text.TextWatcher (含 NoCopySpan 标记父类型)。 */
interface TextWatcher : NoCopySpan {

    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)

    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)

    fun afterTextChanged(s: Editable?)
}

/** 对齐 android.text.InputType, 常量值逐字对齐 android-35 InputType.java。 */
class InputType private constructor() {

    companion object {
        const val TYPE_NULL: Int = 0x00000000
        const val TYPE_CLASS_TEXT: Int = 0x00000001
        const val TYPE_CLASS_NUMBER: Int = 0x00000002
        const val TYPE_CLASS_DATETIME: Int = 0x00000004
        const val TYPE_MASK_CLASS: Int = 0x0000000f
        const val TYPE_MASK_VARIATION: Int = 0x0000fff0
        const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
        const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
        const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0
        const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010
        const val TYPE_TEXT_FLAG_MULTI_LINE: Int = 0x00020000
    }
}
