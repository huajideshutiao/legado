// android.widget JVM 兼容面: 满足扩展 setupPreferenceScreen 与 OnBindEditTextListener 的控件引用面
// (EditTextPreference.OnBindEditTextListener 拿到 EditText, Kotlin 编译的扩展把 setInputType/
// setFilters/addTextChangedListener 的 owner 落在 TextView 上, 类层次或方法缺面即
// NoClassDefFoundError / NoSuchMethodError)。
// getter/setter 声明对齐 android-35 真实方法描述符 (getText/getHint/isSingleLine/
// getInputType/getFilters/getMinLines/getMaxLines/getTransformationMethod 及对应 setter);
// 声明面保证扩展链接, 行为状态由宿主桌面适配层以子类私有状态承载。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.widget

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextEditable
import android.text.TextWatcher
import android.text.method.TransformationMethod
import android.view.View
import android.view.ViewGroup

/**
 * TextView 空壳: 仅保证类解析与常见控件调用可链接, 不复刻真实行为; 缺口按运行期报错按需补。
 * 继承 [View] 以获得 id 查找链与 enabled 状态 (动态校验合同)。
 */
open class TextView(private val context: Context? = null) : View() {

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

    /** 对齐 android.view.TextView.getHint() (mHint 可空)。 */
    open fun getHint(): CharSequence? = null

    /** 对齐 android.view.TextView.isSingleLine() (默认多行)。 */
    open fun isSingleLine(): Boolean = false

    /** 对齐 android.view.TextView.getInputType() (未设置 = TYPE_NULL)。 */
    open fun getInputType(): Int = 0

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
    }

    open fun setSingleLine() {
    }

    open fun setSingleLine(singleLine: Boolean) {
    }

    open fun setMaxLines(maxLines: Int) {
    }

    open fun setMinLines(minLines: Int) {
    }

    open fun setLines(lines: Int) {
    }

    /** 对齐 android.view.TextView.getMinLines() (mMinMode = LINES 时返回 mMinimum)。 */
    open fun getMinLines(): Int = 1

    /** 对齐 android.view.TextView.getMaxLines() (mMaxMode = LINES 时返回 mMaximum)。 */
    open fun getMaxLines(): Int = -1

    /** 对齐 android.view.TextView.getSelectionStart() (无选区 = -1)。 */
    open fun getSelectionStart(): Int = -1

    /** 对齐 android.view.TextView.getSelectionEnd() (无选区 = -1)。 */
    open fun getSelectionEnd(): Int = -1

    open fun setError(error: CharSequence?) {
    }

    open fun setImeOptions(imeOptions: Int) {
    }

    open fun setFilters(filters: Array<out InputFilter>?) {
    }

    /** 对齐 android.view.TextView.getFilters()。 */
    open fun getFilters(): Array<InputFilter> = emptyArray()

    open fun setTransformationMethod(method: TransformationMethod?) {
    }

    /** 对齐 android.view.TextView.getTransformationMethod()。 */
    open fun getTransformationMethod(): TransformationMethod? = null

    open fun addTextChangedListener(watcher: TextWatcher?) {
    }

    open fun removeTextChangedListener(watcher: TextWatcher?) {
    }

    open fun getContext(): Context? = context
}

/**
 * EditText 是 TextView 子类 (扩展把 `EditText.setInputType` 解析到 TextView 声明处,
 * 层次不符时 JVM 校验器直接拒绝整个扩展类); getText 覆写返回 Editable 并自动生成
 * 返回 CharSequence 的桥方法, 两种调用口径都能解析。open: 宿主桌面适配层以其为基类
 * 承载声明/过滤/变更合同。
 */
open class EditText(context: Context? = null) : TextView(context) {

    override fun getText(): Editable = editable

    /** 对齐 android.widget.EditText.setSelection (Selection.setSelection(getText(), ...))。 */
    open fun setSelection(start: Int, stop: Int) {
    }

    /** 对齐 android.widget.EditText.setSelection(int)。 */
    open fun setSelection(index: Int) {
    }
}

/** Button 空壳: 插件动态校验把确认键 (android.R.id.button1) 改 enabled 的目标类型。 */
class Button(context: Context? = null) : TextView(context)

/** LinearLayout 空壳: 宿主把假确认键与输入框放进同一可查找树 (rootView 查找合同)。 */
class LinearLayout(context: Context? = null) : ViewGroup()
