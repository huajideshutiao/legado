// android.text 输入相关 JVM 兼容面: 扩展在 EditTextPreference.OnBindEditTextListener 里
// 给输入框挂过滤/长度限制/变更监听, 以及 TextWatcher/Editable 回调参数需要的类型。
// 接口签名与常量逐字对齐 android-35 定义 (InputFilter.filter 返回可空 CharSequence;
// Spanned 点/标记常量与别名; InputType 变体值); LengthFilter 小改复用 AOSP 实现
// (含 UTF-16 高代理截断保护)。仅存在于 :data 的 jvm 变体 (data/src/jvmMain),
// :app (android 变体) 编译与打包不接触本目录。
package android.text

/**
 * 对齐 android.text.NoCopySpan: 切片/复制时不随文本复制的 span 标记面。
 */
interface NoCopySpan {

    /** 对齐 NoCopySpan.Concrete: 需要一个不可复制的普通对象 span 时直接用它。 */
    class Concrete : NoCopySpan
}

/**
 * 对齐 android.text.GetChars: 一次拷出区间的 CharSequence 扩展面。
 */
interface GetChars : CharSequence {

    fun getChars(start: Int, end: Int, dest: CharArray, destOff: Int)
}

/**
 * 对齐 android.text.Spanned: CharSequence 的 span 读面。
 * 常量与别名逐字对齐 android-35 Spanned.java。
 */
interface Spanned : CharSequence {

    companion object {
        const val SPAN_POINT_MARK_MASK: Int = 0x33
        const val SPAN_MARK_MARK: Int = 0x11
        const val SPAN_MARK_POINT: Int = 0x12
        const val SPAN_POINT_MARK: Int = 0x21
        const val SPAN_POINT_POINT: Int = 0x22

        /** 段落 span: 端点须在段落边界, 端点落在全文末尾时按点处理。 */
        const val SPAN_PARAGRAPH: Int = 0x33

        /** 四个 inclusive/exclusive 名是相应点/标记原常量别名。 */
        const val SPAN_INCLUSIVE_EXCLUSIVE: Int = SPAN_MARK_MARK
        const val SPAN_INCLUSIVE_INCLUSIVE: Int = SPAN_MARK_POINT
        const val SPAN_EXCLUSIVE_EXCLUSIVE: Int = SPAN_POINT_MARK
        const val SPAN_EXCLUSIVE_INCLUSIVE: Int = SPAN_POINT_POINT

        const val SPAN_COMPOSING: Int = 0x100

        /** 输入法中间态标记位 (不参与本文档的 span 区间语义)。 */
        const val SPAN_INTERMEDIATE: Int = 0x200

        const val SPAN_USER_SHIFT: Int = 24
        const val SPAN_USER: Int = -0x1000000
        const val SPAN_PRIORITY_SHIFT: Int = 16

        /** 优先级位段: 同区间命中多个 span 时大者先返回。 */
        const val SPAN_PRIORITY: Int = 0x00FF0000
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
 * 修改方法签名 (replace/insert/delete/append/clear) 对齐真实接口; append 以协变返回
 * [Editable] 重声明, 对齐真实方法描述符。
 */
interface Editable : GetChars, Spannable, Appendable {

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

    fun setFilters(filters: Array<InputFilter>?)

    fun getFilters(): Array<InputFilter>
}

/**
 * 独立可变文本对象 (EditText.getText() 返回活对象; 快照会让扩展在回调里的追加静默丢失)。
 * span 面与文本修改区间调整由 [SpanStore] 承载; 文本修改统一走 replace 合同
 * (过滤 → before → 修改 → on → after)。
 */
class TextEditable(content: String = "") : Editable {

    private var content: StringBuilder = StringBuilder(content)

    private var filters: Array<InputFilter> = emptyArray()

    private val spans: SpanStore = SpanStore { content }

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        spans.slice(startIndex, endIndex)

    override fun getChars(start: Int, end: Int, dest: CharArray, destOff: Int) {
        spans.getChars(start, end, dest, destOff)
    }

    override fun append(csq: CharSequence?): Editable = replace(length, length, csq ?: "")

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable =
        replace(length, length, csq ?: "", start, end)

    override fun append(c: Char): Editable = replace(length, length, c.toString())

    override fun replace(start: Int, end: Int, source: CharSequence?): Editable {
        val text = source ?: ""
        return replace(start, end, text, 0, text.length)
    }

    override fun replace(
        start: Int,
        end: Int,
        source: CharSequence?,
        startOfSource: Int,
        endOfSource: Int,
    ): Editable {
        val originalSource: CharSequence = source ?: ""
        checkTextRange("replace", start, end, content.length)
        checkTextRange("replace source", startOfSource, endOfSource, originalSource.length)
        val filtered = applyInputFilters(
            filters,
            originalSource,
            startOfSource,
            endOfSource,
            this,
            start,
            end,
        )
        // 来源可能引用本文本身: 在调整/改写任何 span 前取下文字与 span
        val replaceSource = ReplaceSource.capture(filtered.text, filtered.start, filtered.end)
        val oldLength = end - start
        val newLength = replaceSource.text.length
        if (oldLength == 0 && newLength == 0 && replaceSource.hasNoExpandableSpan) {
            return this
        }
        val watchers = spans.getSpans(start, start + oldLength, TextWatcher::class.java)
        for (watcher in watchers) watcher.beforeTextChanged(this, start, oldLength, newLength)
        spans.adjustForReplace(start, end, newLength, content.length)
        content.replace(start, end, replaceSource.text)
        spans.copySourceSpans(replaceSource, start)
        for (watcher in watchers) watcher.onTextChanged(this, start, oldLength, newLength)
        for (watcher in watchers) watcher.afterTextChanged(this)
        return this
    }

    override fun insert(where: Int, text: CharSequence?): Editable {
        val value = text ?: ""
        return replace(where, where, value, 0, value.length)
    }

    override fun insert(where: Int, text: CharSequence?, startOfText: Int, endOfText: Int): Editable =
        replace(where, where, text ?: "", startOfText, endOfText)

    override fun delete(start: Int, end: Int): Editable = replace(start, end, "")

    override fun clear() {
        replace(0, length, "")
    }

    override fun clearSpans() {
        spans.clearSpans()
    }

    override fun setFilters(filters: Array<InputFilter>?) {
        this.filters = filters ?: throw IllegalArgumentException()
    }

    override fun getFilters(): Array<InputFilter> = filters

    override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
        spans.setSpan(what, start, end, flags)
    }

    override fun removeSpan(what: Any?) {
        spans.removeSpan(what)
    }

    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> =
        spans.getSpans(start, end, type)

    override fun getSpanStart(tag: Any?): Int = spans.getSpanStart(tag)

    override fun getSpanEnd(tag: Any?): Int = spans.getSpanEnd(tag)

    override fun getSpanFlags(tag: Any?): Int = spans.getSpanFlags(tag)

    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int =
        spans.nextSpanTransition(start, limit, type)

    /** 宿主 setText 时以同一 replace 合同重置内容 (旧 span 随新文本弃置)。 */
    fun setContent(value: String) {
        spans.clearSpans()
        replace(0, length, value)
    }

    override fun toString(): String = content.toString()
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

/**
 * 对齐 android.text.TextWatcher (含 NoCopySpan 标记父类型): before/on 只读,
 * after 允许继续改文本; 需要定位时用 setSpan 记录, 并在 after 里读它调整后的位置。
 */
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
