package io.legado.app.ui.book.manga.extension

/**
 * 插件自带配置项 (跨层数据类, 对齐 keiyoushi extensions-lib 的 androidx.preference shim 面)。
 *
 * 为什么转数据类而不是直传 `androidx.preference.PreferenceScreen`:
 * - shim 只在 `:data` 的 jvmAndAndroidMain 源集, UI 层要引用它必须把 :data 依赖改成 `api`
 *   (现为 `implementation`), 且 sharedUiMain 是四端共享源码, 鸿蒙/iOS 无该 shim 会编译不过;
 * - 直传 shim 还得把 `Context` (只存在于 Android) 一并跨层, 破坏「接口只依赖 shared 类型」约定。
 * 故平台侧读完 shim 立即降级成以下纯数据, UI 侧只渲染数据 + 回调。
 */

/** 单个配置项的值 (按控件类型分派, 与 shim 的 String/Boolean/Set&lt;String&gt; 取值面一一对应)。 */
sealed interface MangaPrefValue {
    data class Text(val value: String) : MangaPrefValue
    data class Flag(val value: Boolean) : MangaPrefValue
    data class Choice(val value: String) : MangaPrefValue
    data class MultiChoice(val values: Set<String>) : MangaPrefValue
}

/**
 * 配置项的宿主侧动作通道: 扩展在 preference 上挂了
 * `setOnPreferenceChangeListener` / `setOnPreferenceClickListener` 的回调
 * (动作型偏好, 典型如「立即签到」: 点击触发一次动作、不持久化状态)。
 * 宿主快照式偏好无法透传 listener, 故把「有动作」这一事实带进 UI 数据层,
 * 点击时经平台 [io.legado.app.model.manga.SharedMangaSourceConfig.runPreferenceAction]
 * 执行扩展回调 (对齐 androidx.preference 语义: change 回调返回 true 才落值)。
 */
sealed interface MangaPrefAction {
    /** 点击触发 `OnPreferenceClickListener` (非开关类动作行)。 */
    data object Click : MangaPrefAction

    /**
     * 开关切换触发 `OnPreferenceChangeListener`, [value] 为点击后的候选值
     * (对齐 androidx 的 `newValue`; 回调返回 true 才持久化/更新, false 则 UI 不动)。
     */
    data class Toggle(val value: Boolean) : MangaPrefAction
}

/**
 * 一个可渲染的配置项。
 *
 * @param index 在 `PreferenceScreen.getPreferences()` 列表中的序号; 无 key 的动作项
 *               靠它定位到 shim Preference 实例执行回调, 其余类型仅作展示。
 * @param key 插件偏好键 (`Preference.key`); 为空但有 [action] 时该项仍可点 (动作项),
 *               无 key 且无动作才不可写 (见 [writable])
 * @param title 标题 (`Preference.title`)
 * @param summary 摘要 (`Preference.summary`); 含 `%s` 占位时由 UI 侧按当前 entry 文本替换
 *                (对齐 shim 里 `summary = "%s"` 的 ListPreference 用法)
 * @param entries/entryValues 列表项的展示文案与取值 (`ListPreference.entries/entryValues`)
 * @param action 扩展挂的回调动作通道 (null=纯数据项, 写入走 [key])
 */
data class MangaPrefItem(
    val index: Int,
    val key: String?,
    val title: String,
    val summary: String? = null,
    val value: MangaPrefValue,
    val entries: List<String> = emptyList(),
    val entryValues: List<String> = emptyList(),
    val action: MangaPrefAction? = null,
) {
    /**
     * 是否可交互: 有 key 可写值, 或有动作通道可触发回调。
     * 动作型偏好 (典型「立即签到」) 无 key 但可点, 不再置灰。
     */
    val writable: Boolean get() = !key.isNullOrBlank() || action != null

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
