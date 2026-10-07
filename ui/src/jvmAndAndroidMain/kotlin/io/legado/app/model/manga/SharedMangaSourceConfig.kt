package io.legado.app.model.manga

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SharedPreferencesDataStore
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.manga.SharedMangaExtensionPlatform.MangaExtensionConfig
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import kotlinx.coroutines.withContext

/**
 * 插件自带配置的平台桥 (JVM+Android 共用): 构造 shim `PreferenceScreen`、把扩展偏好
 * (`source_<sourceId>`) 设为 PreferenceDataStore 并回填当前值; 交互事件按 androidx 语义转发到
 * shim 实例 (点击 → `performClick()`; 开关切换 → `callChangeListener` + `setChecked`;
 * 输入框 → `OnBindEditTextListener`), 与 Mihon 的 SourcePreferencesFragment 同口径。
 * app 端经 AndroidMangaExtensionPlatform、desktop 端经 DesktopCore 启动序列注入;
 * 未注入端 (iOS/鸿蒙) 「设置」入口隐藏。
 */
class SharedMangaSourceConfig(
    private val appContext: Context,
) : MangaExtensionConfig {

    /** 按 pkgName 取已装载源实例 (漫画源优先, 视频源次之); 未装载出源返回 null。 */
    private fun sourceOf(pkgName: String): Any? = MangaExtensionManager.firstSourceOf(pkgName)

    override fun isConfigurable(pkgName: String): Boolean = MangaExtensionManager.isPkgConfigurable(pkgName)

    override suspend fun buildPreferenceItems(pkgName: String): List<MangaPrefItem> =
        withContext(IoDispatcher) {
            val source = sourceOf(pkgName) ?: return@withContext emptyList()
            val prefs = sourcePrefsOf(source) ?: return@withContext emptyList()
            // 对齐 keiyoushi stub: 插件 setupPreferenceScreen 首句即 screen.context, shim 必须能提供
            val screen = PreferenceScreen(appContext)
            // 对齐 Mihon: 扩展偏好文件即值存储, 读写全走 shim 的 persist/getter
            screen.setPreferenceDataStore(SharedPreferencesDataStore(prefs))
            // 插件 setupPreferenceScreen 抛错 (shim 缺 API/扩展内 NPE 等) 如实上抛:
            // 吞成空表会把"读取失败"伪装成"没有可配置项"
            try {
                when (source) {
                    is ConfigurableSource -> source.setupPreferenceScreen(screen)
                    is ConfigurableAnimeSource -> source.setupPreferenceScreen(screen)
                    else -> Unit
                }
            } catch (e: Throwable) {
                AppLog.put("插件配置读取失败 $pkgName\n${e.message}", e)
                throw e
            }
            // shim 的 getPreferences() 是 Kotlin 函数 (非 Java getter), 不合成 `.preferences` 属性,
            // 且其后备字段为 private, 必须走函数调用。
            val preferences = screen.getPreferences()
            // 对齐 androidx PreferenceManager.dispatchSetInitialValue: 已持久化的值读回 Preference 实例
            preferences.forEach { it.dispatchSetInitialValue(prefs) }
            // 事件通道依赖活 Preference 实例 (无 key 项只能按序号定位), 缓存本次构建结果,
            // 下次构建/写入自动覆盖。
            prefCache[pkgName] = preferences
            // 交互按下标回传并打在全量表 (prefCache) 上, 故 index 必须是全量序号;
            // visible 只决定渲染哪些项, 不改变序号。
            preferences.mapIndexedNotNull { index, item ->
                if (item.visible) item.toPrefItem(index) else null
            }
        }

    override suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue) {
        withContext(IoDispatcher) {
            val preference = prefCache[pkgName]?.firstOrNull { it.key == key } ?: return@withContext
            // 经 shim 的 setter 落值: 持久化规则 (key/持久化开关) 与 androidx 一致
            when (value) {
                is MangaPrefValue.Text -> (preference as? EditTextPreference)?.setText(value.value)
                is MangaPrefValue.Flag -> (preference as? TwoStatePreference)?.setChecked(value.value)
                is MangaPrefValue.Choice -> (preference as? ListPreference)?.setValue(value.value)
                is MangaPrefValue.MultiChoice -> (preference as? MultiSelectListPreference)?.setValues(value.values)
            }
        }
    }

    override suspend fun performPreferenceClick(pkgName: String, index: Int) {
        withContext(IoDispatcher) {
            prefCache[pkgName]?.getOrNull(index)?.performClick()
        }
    }

    override suspend fun applyPreferenceChange(pkgName: String, index: Int, newValue: Boolean) {
        withContext(IoDispatcher) {
            val preference = prefCache[pkgName]?.getOrNull(index) as? TwoStatePreference
                ?: return@withContext
            // 对齐 androidx: callChangeListener 通过才落值 (未挂监听视为通过)
            if (preference.callChangeListener(newValue)) preference.setChecked(newValue)
        }
    }

    override suspend fun bindEditTextPreference(pkgName: String, index: Int) {
        withContext(IoDispatcher) {
            val preference = prefCache[pkgName]?.getOrNull(index) as? EditTextPreference
                ?: return@withContext
            preference.onBindEditTextListener?.onBindEditText(EditText(appContext))
        }
    }

    /** 最近一次构建的 shim Preference 列表 (按 pkgName; 交互事件按序号定位到它)。 */
    private val prefCache = HashMap<String, List<Preference>>()

    /**
     * 扩展自身偏好文件: `source_<sourceId>` (对齐 keiyoushi.utils.getPreferencesLazy /
     * Aniyomi sourcePreferences 的 `source_$id` 契约)。
     */
    private fun sourcePrefsOf(source: Any): SharedPreferences? {
        val id = when (source) {
            is Source -> source.id
            is AnimeSource -> source.id
            else -> return null
        }
        return appContext.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    /**
     * 对齐 androidx PreferenceManager.dispatchSetInitialValue(): 把已持久化的值读回 Preference
     * 实例 (只回填有 key 且持久化的项)。值缺失时沿用扩展声明的默认值。
     */
    private fun Preference.dispatchSetInitialValue(prefs: SharedPreferences) {
        val preferenceKey = key?.takeIf { isPersistent && it.isNotEmpty() } ?: return
        when (this) {
            is TwoStatePreference ->
                setChecked(prefs.getBoolean(preferenceKey, defaultValue as? Boolean ?: false))

            is ListPreference ->
                setValue(prefs.getString(preferenceKey, defaultValue?.toString()))

            is MultiSelectListPreference -> {
                val stored = prefs.getStringSet(preferenceKey, null)
                    ?: (defaultValue as? Set<*>)?.map { it.toString() }?.toSet()
                if (stored != null) setValues(stored)
            }

            is EditTextPreference ->
                setText(prefs.getString(preferenceKey, defaultValue?.toString()))

            else -> Unit
        }
    }

    /**
     * shim Preference → 跨层 [MangaPrefItem] (当前值已在回填阶段从扩展偏好读入 shim,
     * 未持久化的项沿用扩展声明值)。列表项的 entries/entryValues 供 UI 渲染选择器。
     */
    private fun Preference.toPrefItem(index: Int): MangaPrefItem {
        val entries = when (this) {
            is ListPreference -> this.entries?.map { it.toString() }.orEmpty()
            is MultiSelectListPreference -> this.entries?.map { it.toString() }.orEmpty()
            else -> emptyList()
        }
        val entryValues = when (this) {
            is ListPreference -> this.entryValues?.map { it.toString() }.orEmpty()
            is MultiSelectListPreference -> this.entryValues?.map { it.toString() }.orEmpty()
            else -> emptyList()
        }
        val value: MangaPrefValue = when (this) {
            is ListPreference ->
                MangaPrefValue.Choice(getValue() ?: entryValues.firstOrNull().orEmpty())

            is MultiSelectListPreference ->
                MangaPrefValue.MultiChoice(getValues())

            is TwoStatePreference ->
                MangaPrefValue.Flag(isChecked())

            is EditTextPreference ->
                MangaPrefValue.Text(getText().orEmpty())

            else ->
                MangaPrefValue.Text(defaultValue?.toString().orEmpty())
        }
        return MangaPrefItem(
            index = index,
            key = key,
            title = title?.toString().orEmpty(),
            summary = summary?.toString(),
            value = value,
            entries = entries,
            entryValues = entryValues,
            enabled = enabled,
        )
    }
}
