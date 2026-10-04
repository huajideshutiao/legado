// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 由 app 模块同名 Java shim 下沉 (ConfigurableSource/ConfigurableAnimeSource 随兼容层落 :data,
// 依赖面必须同行); 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference

import android.content.Context
import android.widget.EditText

@SuppressWarnings("unused")
open class Preference {

    var title: CharSequence? = null
    var summary: CharSequence? = null
    var key: String? = null
    var enabled: Boolean = true
    var visible: Boolean = true
    var defaultValue: Any? = null

    fun setOnPreferenceChangeListener(onPreferenceChangeListener: OnPreferenceChangeListener) {
    }

    fun setOnPreferenceClickListener(onPreferenceClickListener: OnPreferenceClickListener) {
    }

    interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any): Boolean
    }

    interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }
}

@SuppressWarnings("unused")
// context 对齐 keiyoushi stub 的 getContext(): 插件 setupPreferenceScreen 首句即 screen.context
// (扩展构建时 extensions-lib 为 compileOnly, 运行时解析到本 shim)
class PreferenceScreen(val context: Context) {

    private val preferences = ArrayList<Preference>()

    fun addPreference(preference: Preference): Boolean {
        return preferences.add(preference)
    }

    fun getPreferences(): List<Preference> {
        return preferences
    }
}

@SuppressWarnings("unused")
open class TwoStatePreference : Preference() {

    var isChecked: Boolean = false
    var summaryOn: CharSequence? = null
    var summaryOff: CharSequence? = null
    var disableDependentsState: Boolean = false
}

@SuppressWarnings("unused")
class CheckBoxPreference(context: Context) : TwoStatePreference()

@SuppressWarnings("unused")
class SwitchPreferenceCompat(context: Context) : TwoStatePreference()

@SuppressWarnings("unused")
abstract class DialogPreference : Preference() {

    var dialogTitle: CharSequence? = null
    var dialogMessage: CharSequence? = null
}

@SuppressWarnings("unused")
class ListPreference(context: Context) : Preference() {

    var entries: Array<out CharSequence>? = null
    var entryValues: Array<out CharSequence>? = null
    var value: String? = null

    fun findIndexOfValue(value: String?): Int {
        if (value == null || entryValues == null) {
            return -1
        }
        for (i in entryValues!!.indices.reversed()) {
            if (value.contentEquals(entryValues!![i])) {
                return i
            }
        }
        return -1
    }

    fun setValueIndex(index: Int) {
        val entryValues = entryValues
        if (entryValues != null && index >= 0 && index < entryValues.size) {
            value = entryValues[index].toString()
        }
    }
}

@SuppressWarnings("unused")
class MultiSelectListPreference(context: Context) : DialogPreference() {

    var entries: Array<out CharSequence>? = null
    var entryValues: Array<out CharSequence>? = null
    var values: Set<String> = HashSet()

    fun findIndexOfValue(value: String?): Int {
        if (value == null || entryValues == null) {
            return -1
        }
        for (i in entryValues!!.indices.reversed()) {
            if (value.contentEquals(entryValues!![i])) {
                return i
            }
        }
        return -1
    }
}

@SuppressWarnings("unused")
class EditTextPreference(context: Context) : DialogPreference() {

    var text: String? = null

    fun setOnBindEditTextListener(onBindEditTextListener: OnBindEditTextListener?) {
    }

    interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }
}
