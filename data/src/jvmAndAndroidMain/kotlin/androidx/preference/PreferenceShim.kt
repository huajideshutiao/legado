// 兼容 shim: 对齐 androidx.preference 的类面与行为语义 —— performClick/onClick/callChangeListener/
// 持久化规则与真 androidx 一致 (Mihon 的 SourcePreferencesFragment 就是把这套交给 androidx 跑),
// 宿主只负责渲染与事件转发, 不另造动作类型。
// 由 app 模块同名 Java shim 下沉 (ConfigurableSource/ConfigurableAnimeSource 随兼容层落 :data,
// 依赖面必须同行); 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText

/** 值存储后端 (对齐 androidx PreferenceDataStore); 平台层指向扩展自身的 source_$id 偏好。 */
interface PreferenceDataStore {

    fun putString(key: String, value: String?)

    fun getString(key: String, defValue: String?): String?

    fun putBoolean(key: String, value: Boolean)

    fun getBoolean(key: String, defValue: Boolean): Boolean

    fun putStringSet(key: String, value: Set<String>?)

    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
}

/** 对齐 androidx 内建的 SharedPreferences 存储实现。 */
class SharedPreferencesDataStore(private val prefs: SharedPreferences) : PreferenceDataStore {

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    override fun putStringSet(key: String, value: Set<String>?) {
        prefs.edit().putStringSet(key, value).apply()
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        prefs.getStringSet(key, defValues)
}

@SuppressWarnings("unused")
open class Preference {

    open var title: CharSequence? = null
    open var summary: CharSequence? = null
    open var key: String? = null
    open var enabled: Boolean = true
    open var visible: Boolean = true
    open var defaultValue: Any? = null

    /** 对齐 androidx Preference.mPersistent。 */
    open var isPersistent: Boolean = true

    /** 对齐 androidx Preference.preferenceDataStore (由 PreferenceScreen 统一下发)。 */
    open var preferenceDataStore: PreferenceDataStore? = null

    @JvmField
    var onPreferenceChangeListener: OnPreferenceChangeListener? = null

    @JvmField
    var onPreferenceClickListener: OnPreferenceClickListener? = null

    open fun setOnPreferenceChangeListener(onPreferenceChangeListener: OnPreferenceChangeListener) {
        this.onPreferenceChangeListener = onPreferenceChangeListener
    }

    open fun setOnPreferenceClickListener(onPreferenceClickListener: OnPreferenceClickListener) {
        this.onPreferenceClickListener = onPreferenceClickListener
    }

    /**
     * 对齐 androidx Preference.performClick(): 点击监听返回 true 即消费, 否则走默认 [onClick]。
     */
    open fun performClick(): Boolean {
        if (!enabled) return false
        if (onPreferenceClickListener?.onPreferenceClick(this) == true) return true
        onClick()
        return true
    }

    /** 对齐 androidx Preference.onClick(): 基类空实现 (TwoStatePreference 覆写为切换)。 */
    protected open fun onClick() {
    }

    /** 对齐 androidx callChangeListener(): 未挂监听视为接受。 */
    open fun callChangeListener(newValue: Any): Boolean =
        onPreferenceChangeListener?.onPreferenceChange(this, newValue) ?: true

    /** 对齐 androidx shouldPersist(): 持久化开关 + 有 key (无 key 的项只改内存态)。 */
    protected open fun shouldPersist(): Boolean = isPersistent && !key.isNullOrEmpty()

    protected open fun persistBoolean(value: Boolean): Boolean {
        if (!shouldPersist()) return false
        preferenceDataStore?.putBoolean(key!!, value)
        return true
    }

    protected open fun persistString(value: String?): Boolean {
        if (!shouldPersist()) return false
        preferenceDataStore?.putString(key!!, value)
        return true
    }

    open fun persistStringSet(value: Set<String>?): Boolean {
        if (!shouldPersist()) return false
        preferenceDataStore?.putStringSet(key!!, value)
        return true
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

    private var dataStore: PreferenceDataStore? = null

    fun addPreference(preference: Preference): Boolean {
        preference.preferenceDataStore = dataStore
        return preferences.add(preference)
    }

    fun getPreferences(): List<Preference> = preferences

    /** 对齐 androidx PreferenceManager.preferenceDataStore: 统一指定各项的持久化后端。 */
    fun setPreferenceDataStore(dataStore: PreferenceDataStore?) {
        this.dataStore = dataStore
        preferences.forEach { it.preferenceDataStore = dataStore }
    }
}

@SuppressWarnings("unused")
abstract class TwoStatePreference : Preference() {

    private var checkedState: Boolean = false

    private var checkedSet: Boolean = false

    open var summaryOn: CharSequence? = null
    open var summaryOff: CharSequence? = null
    open var disableDependentsState: Boolean = false

    /** 对齐 androidx TwoStatePreference.isChecked()。 */
    open fun isChecked(): Boolean = checkedState

    /** 对齐 androidx setChecked(): 值变化 (或首次) 才持久化。 */
    open fun setChecked(checked: Boolean) {
        val changed = checkedState != checked
        if (changed || !checkedSet) {
            checkedState = checked
            checkedSet = true
            persistBoolean(checked)
        }
    }

    /** 对齐 androidx TwoStatePreference.onClick(): 取反 → callChangeListener 通过才落值。 */
    override fun onClick() {
        val newValue = !checkedState
        if (callChangeListener(newValue)) setChecked(newValue)
    }
}

@SuppressWarnings("unused")
open class CheckBoxPreference(context: Context) : TwoStatePreference()

@SuppressWarnings("unused")
open class SwitchPreferenceCompat(context: Context) : TwoStatePreference()

@SuppressWarnings("unused")
abstract class DialogPreference : Preference() {

    open var dialogTitle: CharSequence? = null
    open var dialogMessage: CharSequence? = null
}

@SuppressWarnings("unused")
open class ListPreference(context: Context) : DialogPreference() {

    open var entries: Array<out CharSequence>? = null
    open var entryValues: Array<out CharSequence>? = null

    private var valueState: String? = null

    /** 对齐 androidx ListPreference.getValue()/setValue(): set 时持久化。 */
    open fun getValue(): String? = valueState

    open fun setValue(value: String?) {
        valueState = value
        persistString(value)
    }

    open fun getEntry(): CharSequence? {
        val index = findIndexOfValue(valueState)
        return entries?.getOrNull(index)
    }

    open fun findIndexOfValue(value: String?): Int {
        val entryValues = entryValues ?: return -1
        if (value == null) return -1
        for (i in entryValues.indices.reversed()) {
            if (value.contentEquals(entryValues[i])) return i
        }
        return -1
    }

    open fun setValueIndex(index: Int) {
        val entryValues = entryValues ?: return
        if (index in entryValues.indices) setValue(entryValues[index].toString())
    }
}

@SuppressWarnings("unused")
open class MultiSelectListPreference(context: Context) : DialogPreference() {

    open var entries: Array<out CharSequence>? = null
    open var entryValues: Array<out CharSequence>? = null

    private var valuesState: Set<String> = HashSet()

    /** 对齐 androidx MultiSelectListPreference.getValues()/setValues(): set 时持久化。 */
    open fun getValues(): Set<String> = valuesState

    open fun setValues(values: Set<String>) {
        valuesState = values
        persistStringSet(values)
    }

    open fun findIndexOfValue(value: String?): Int {
        val entryValues = entryValues ?: return -1
        if (value == null) return -1
        for (i in entryValues.indices.reversed()) {
            if (value.contentEquals(entryValues[i])) return i
        }
        return -1
    }
}

@SuppressWarnings("unused")
open class EditTextPreference(context: Context) : DialogPreference() {

    private var textState: String? = null

    /** 宿主侧存下的输入框绑定回调 (扩展在此配置 EditText)。 */
    @JvmField
    var onBindEditTextListener: OnBindEditTextListener? = null

    /** 对齐 androidx EditTextPreference.getText()/setText(): set 时持久化。 */
    open fun getText(): String? = textState

    open fun setText(text: String?) {
        textState = text
        persistString(text)
    }

    open fun setOnBindEditTextListener(onBindEditTextListener: OnBindEditTextListener?) {
        this.onBindEditTextListener = onBindEditTextListener
    }

    interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }
}
