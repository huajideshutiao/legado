package io.legado.app.ui.book.manga.extension

/**
 * 插件自带配置项 (跨层数据类, 对齐 keiyoushi extensions-lib 的 androidx.preference shim 面)。
 *
 * 为什么转数据类而不是直传 `androidx.preference.PreferenceScreen`:
 * - shim 只在 `:data` 的 jvmAndAndroidMain 源集, UI 层要引用它必须把 :data 依赖改成 `api`
 *   (现为 `implementation`), 且 sharedUiMain 是四端共享源码, 鸿蒙/iOS 无该 shim 会编译不过;
 * - 直传 shim 还得把 `Context` (只存在于 Android) 一并跨层, 破坏「接口只依赖 shared 类型」约定。
 * 故平台侧读完 shim 立即降级成以下纯数据, UI 侧只渲染数据 + 上报交互。
 *
 * 交互语义全部按 androidx (与 Mihon 的 SourcePreferencesFragment 同源): 点击 → `Preference.performClick()`;
 * 开关切换 → `callChangeListener(newValue)` 通过才 `setChecked`; 输入框 → 打开对话框时触发
 * `OnBindEditTextListener`。宿主不另造动作类型。
 */

/** 单个配置项的值 (按控件类型分派, 与 shim 的 String/Boolean/Set&lt;String&gt; 取值面一一对应)。 */
sealed interface MangaPrefValue {
    data class Text(val value: String) : MangaPrefValue
    data class Flag(val value: Boolean) : MangaPrefValue
    data class Choice(val value: String) : MangaPrefValue
    data class MultiChoice(val values: Set<String>) : MangaPrefValue
}

/**
 * 一个可渲染的配置项。
 *
 * @param index 在 `PreferenceScreen.getPreferences()` 列表中的序号; 交互按它回传定位 shim 实例
 * @param key 插件偏好键 (`Preference.key`); 为空表示扩展不持久化该项
 * @param title 标题 (`Preference.title`)
 * @param summary 摘要 (`Preference.summary`); 含 `%s` 占位时由 UI 侧按当前 entry 文本替换
 *                (对齐 shim 里 `summary = "%s"` 的 ListPreference 用法)
 * @param entries/entryValues 列表项的展示文案与取值 (`ListPreference.entries/entryValues`)
 * @param enabled `Preference.isEnabled` (禁用项按 androidx 语义不响应交互)
 */
data class MangaPrefItem(
    val index: Int,
    val key: String?,
    val title: String,
    val summary: String? = null,
    val value: MangaPrefValue,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    /**
     * 摘要文案: `%s` 占位替换为当前选中项的 entry 文本 (取不到则替换为当前值),
     * 无占位原样返回。对齐 androidx.preference ListPreference 的 `summary = "%s"` 语义。
     */
    fun displaySummary(): String? {
        val raw = summary ?: return null
        if (!raw.contains("%s")) return raw
        return raw.replace("%s", selectedEntryText() ?: "")
    }

    /** 当前选中项对应的 entry 展示文案 (ListPreference/MultiSelectListPreference 语义)。 */
    fun selectedEntryText(): String? = when (val v = value) {
        is MangaPrefValue.Choice -> {
            val index = entryValues.indexOf(v.value)
            entries.getOrNull(index) ?: v.value
        }

        is MangaPrefValue.MultiChoice -> v.values.joinToString(", ") { selected ->
            val index = entryValues.indexOf(selected)
            entries.getOrNull(index) ?: selected
        }

        else -> null
    }
}
