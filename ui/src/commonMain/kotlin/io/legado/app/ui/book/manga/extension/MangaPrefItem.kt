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
 * @param summary 摘要 (`Preference.summary`)
 * @param summaryOn/summaryOff 开关项按勾选状态显示的摘要 (`TwoStatePreference.summaryOn/summaryOff`);
 *              非空时优先于 [summary]
 * @param entryText 单选题当前值对应的 entry 文案 (`ListPreference.getEntry()`); 当前值不在
 *              entryValues 中时为 null
 * @param entries/entryValues 列表项的展示文案与取值 (`ListPreference.entries/entryValues`)
 * @param enabled `Preference.isEnabled` (禁用项按 androidx 语义不响应交互)
 */
data class MangaPrefItem(
    val index: Int,
    val key: String?,
    val title: String,
    val summary: String? = null,
    val value: MangaPrefValue,
    val summaryOn: String? = null,
    val summaryOff: String? = null,
    val entryText: String? = null,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    /**
     * 行内摘要, 按值类型对齐 androidx `Preference.getSummary()`:
     * 开关项取当前勾选状态对应的 `summaryOn`/`summaryOff` (为空则回退 [summary]);
     * 单选题 (ListPreference) 把 [summary] 里的 `%s` 换成 [entryText] (取不到换空串);
     * 其余类型 (MultiSelectListPreference/EditTextPreference 无摘要格式化) 直接返回 [summary]。
     */
    fun displaySummary(): String? = when (val v = value) {
        is MangaPrefValue.Flag ->
            (if (v.value) summaryOn else summaryOff).takeUnless { it.isNullOrEmpty() } ?: summary

        is MangaPrefValue.Choice -> summary?.replace("%s", entryText ?: "")

        is MangaPrefValue.MultiChoice, is MangaPrefValue.Text -> summary
    }
}
