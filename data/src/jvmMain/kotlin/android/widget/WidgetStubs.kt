// android.widget JVM 空壳 stub: 满足扩展 setupPreferenceScreen 的控件引用面
// (EditTextPreference.OnBindEditTextListener 拿到 EditText, Kotlin 编译的扩展把
// setInputType 的 owner 落到 TextView 上, 类层次缺 TextView 即 NoClassDefFoundError)。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.widget

import android.content.Context

/**
 * TextView 空壳: 仅保证类解析与常见控件调用可链接, 不复刻真实行为; 缺口按运行期报错按需补。
 */
open class TextView(private val context: Context? = null) {

    private var text: CharSequence = ""

    open fun setInputType(type: Int) {
    }

    open fun setText(value: CharSequence?) {
        text = value ?: ""
    }

    open fun setText(resId: Int) {
    }

    open fun getText(): CharSequence = text

    open fun setTextSize(size: Float) {
    }

    open fun setTextColor(color: Int) {
    }

    open fun setHint(hint: CharSequence?) {
    }

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
    }

    open fun setSingleLine(singleLine: Boolean) {
    }

    open fun setEnabled(enabled: Boolean) {
    }

    open fun setVisibility(visibility: Int) {
    }

    open fun getContext(): Context? = context
}

/**
 * EditText 是 TextView 子类 (扩展把 `EditText.setInputType` 解析到 TextView 声明处,
 * 层次不符时 JVM 校验器直接拒绝整个扩展类)。
 */
class EditText(context: Context? = null) : TextView(context)
