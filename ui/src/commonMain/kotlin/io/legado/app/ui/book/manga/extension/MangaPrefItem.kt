package io.legado.app.ui.book.manga.extension

/**
 * 插件自带配置项 (跨层数据类, 对齐 keiyoushi extensions-lib 的 androidx.preference shim 面)。
 *
 * 为什么转数据类而不是直传 `androidx.preference.PreferenceScreen`:
 * - sharedUiMain 是四端共享源码, 鸿蒙/iOS 无该 shim (shim 类型只存在于 :data 的
 *   JVM+Android 平台变体), 直传会让这两端编译不过 (:data 依赖已是 api, 依赖可见性不是
 *   约束);
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
 * 配置项实例句柄: 平台侧构建快照时生成, 指向本次构建的实际配置对象 (平台内部实例)。
 * 仅作交互与文本编辑会话定位的身份引用 — 不可序列化、不持久化, UI 层原样透传不解析。
 */
interface MangaPrefItemRef

/**
 * 一个可渲染的配置项。
 *
 * @param index 在 `PreferenceScreen.getPreferences()` 列表中的序号
 * @param key 插件偏好键 (`Preference.key`); 为空表示扩展不持久化该项
 * @param title 标题 (`Preference.title`)
 * @param summary 摘要 (`Preference.summary`)
 * @param summaryOn/summaryOff 开关项按勾选状态显示的摘要 (`TwoStatePreference.summaryOn/summaryOff`);
 *              非空时优先于 [summary]
 * @param entryText 单选题当前值对应的 entry 文案 (`ListPreference.getEntry()`); 当前值不在
 *              entryValues 中时为 null
 * @param entries/entryValues 列表项的展示文案与取值 (`ListPreference.entries/entryValues`)
 * @param enabled `Preference.isEnabled` (禁用项按 androidx 语义不响应交互)
 * @param editableText 该项是否为真实的 `EditTextPreference` (平台构建快照时按实际类型判定):
 *              只有真实文本项走插件编辑会话, 其余项保持既有点击交互
 * @param ref 实际配置对象句柄: 交互与文本编辑会话按它定位本快照对应的实际对象
 *              (序号在交互重建后可能失配); 共享数据构造可省略, 平台消费自身快照时必有值
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
    val editableText: Boolean = false,
    val ref: MangaPrefItemRef? = null,
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

/**
 * 插件文本项活编辑会话 (shared 面): 平台在文本项点击路径里取得, 持打开时定位到的实际配置
 * 对象 —— 绑定/动态校验/提交全程针对同一对象 (对齐 androidx 编辑对话框的同一对象语义,
 * 不经序号/键从重建缓存重取)。
 *
 * 会话对象不持 View、不注册进任何进程级缓存; 编辑控件由平台槽实现创建, 控件绑定面
 * (EditText) 留在平台层 ([MangaPrefTextEditorSession]), 弹窗关闭控件与会话即弃。
 */
interface MangaPrefTextSession {

    /** 实际对象当前文本 (对齐 androidx 库弹窗的 `setText(preference.getText())` 填充)。 */
    fun initialText(): String

    /**
     * 提交: `callChangeListener(text)` 通过才落值持久化, 返回是否落值 (被插件拦截 = false,
     * 与 androidx/Mihon 的确认路径同口径)。控件与模型操作在所属主线程执行。
     */
    suspend fun commit(text: String): Boolean
}
