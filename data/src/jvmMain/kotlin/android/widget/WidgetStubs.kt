// android.widget JVM 空壳 stub: 满足扩展 setupPreferenceScreen 与 OnBindEditTextListener 的控件引用面
// (EditTextPreference.OnBindEditTextListener 拿到 EditText, Kotlin 编译的扩展把 setInputType/
// setFilters/addTextChangedListener 的 owner 落在 TextView 上, 类层次或方法缺面即
// NoClassDefFoundError / NoSuchMethodError)。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.widget

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextEditable
import android.text.TextWatcher

/**
 * TextView 空壳: 仅保证类解析与常见控件调用可链接, 不复刻真实行为; 缺口按运行期报错按需补。
 */
open class TextView(private val context: Context? = null) {

    /** 活文本对象: [getText] 与 EditText 覆写返回的是同一实例, setText 就地重置内容。 */
    protected val editable: TextEditable = TextEditable()

    open fun setInputType(type: Int) {
    }

    open fun setText(value: CharSequence?) {
        editable.setContent(value?.toString().orEmpty())
    }

    open fun setText(resId: Int) {
    }

    open fun getText(): CharSequence = editable

    open fun length(): Int = editable.length

    open fun setTextSize(size: Float) {
    }

    open fun setTextColor(color: Int) {
    }

    open fun setHint(hint: CharSequence?) {
    }

    open fun setHint(resId: Int) {
    }

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
    }

    open fun setSingleLine() {
    }

    open fun setSingleLine(singleLine: Boolean) {
    }

    open fun setMaxLines(maxLines: Int) {
    }

    open fun setLines(lines: Int) {
    }

    open fun setSelection(index: Int) {
    }

    open fun setSelection(start: Int, stop: Int) {
    }

    open fun setError(error: CharSequence?) {
    }

    open fun setImeOptions(imeOptions: Int) {
    }

    open fun setFilters(filters: Array<out InputFilter>?) {
    }

    open fun addTextChangedListener(watcher: TextWatcher?) {
    }

    open fun removeTextChangedListener(watcher: TextWatcher?) {
    }

    open fun setEnabled(enabled: Boolean) {
    }

    open fun setVisibility(visibility: Int) {
    }

    open fun getContext(): Context? = context
}

/**
 * EditText 是 TextView 子类 (扩展把 `EditText.setInputType` 解析到 TextView 声明处,
 * 层次不符时 JVM 校验器直接拒绝整个扩展类); getText 覆写返回 Editable 并自动生成
 * 返回 CharSequence 的桥方法, 两种调用口径都能解析。
 */
class EditText(context: Context? = null) : TextView(context) {

    override fun getText(): Editable = editable
}
