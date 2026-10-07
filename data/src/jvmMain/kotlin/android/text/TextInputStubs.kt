// android.text 输入相关 JVM 空壳 stub: 扩展在 EditTextPreference.OnBindEditTextListener 里
// 给输入框挂过滤器/监听 (常见 setFilters(LengthFilter(n)) / addTextChangedListener) 及
// TextWatcher 回调参数 (Editable) 需要这些类型; 仅保证类解析与签名可链接。
package android.text

/** 对齐 android.text.NoCopySpan (纯标记接口, 供 TextWatcher 的父类型面)。 */
interface NoCopySpan

/** 对齐 android.text.InputFilter; 嵌套 LengthFilter 是扩展最常用的输入长度限制器。 */
interface InputFilter {

    fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int,
    ): CharSequence?

    class LengthFilter(private val max: Int) : InputFilter {

        fun getMax(): Int = max

        /** 空壳不裁剪输入, 原样返回 (桌面端输入走宿主 Compose 输入框, 本对象不被宿主使用)。 */
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int,
        ): CharSequence? = source
    }
}

/**
 * Editable: TextWatcher.afterTextChanged 与 EditText.getText() 的类型面。
 * append 以协变返回 [Editable] 重声明, 对齐真实 android.text.Editable 的方法描述符。
 */
interface Editable : CharSequence, Appendable {

    override fun append(csq: CharSequence?): Editable

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable

    override fun append(c: Char): Editable
}

/** 可变文本承载: EditText.getText() 返回的活对象 (快照会让扩展在回调里的追加静默丢失)。 */
class TextEditable(content: String = "") : Editable {

    private var content: String = content

    override val length: Int get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        content.subSequence(startIndex, endIndex)

    override fun append(csq: CharSequence?): Editable {
        content += csq ?: ""
        return this
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Editable {
        content += csq?.subSequence(start, end) ?: ""
        return this
    }

    override fun append(c: Char): Editable {
        content += c
        return this
    }

    /** 宿主 setText 时重置内容 (同一实例, 引用者立即可见)。 */
    fun setContent(value: String) {
        content = value
    }

    override fun toString(): String = content
}

/** 对齐 android.text.TextWatcher (含 NoCopySpan 标记父类型)。 */
interface TextWatcher : NoCopySpan {

    fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)

    fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)

    fun afterTextChanged(s: Editable?)
}
