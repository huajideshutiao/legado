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
import io.legado.app.help.coroutine.PlatformDispatchers
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.manga.SharedMangaExtensionPlatform.MangaExtensionConfig
import io.legado.app.ui.book.manga.extension.MangaPrefCacheOwner
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefItemRef
import io.legado.app.ui.book.manga.extension.MangaPrefTextEditorSession
import io.legado.app.ui.book.manga.extension.MangaPrefTextSession
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件自带配置的平台桥 (JVM+Android 共用): 构造 shim `PreferenceScreen`、把扩展偏好
 * (`source_<sourceId>`) 设为 PreferenceDataStore 并回填当前值; 交互事件按 androidx 语义
 * 转发到 shim 实例 (点击 → `performClick()`; 开关切换 → `callChangeListener` + `setChecked`;
 * 输入框 → `OnBindEditTextListener`), 与 Mihon 的 SourcePreferencesFragment 同口径。
 * 实际配置对象身份经快照句柄 ([MangaPrefItem.ref]) 直达, 无序号/键查找缓存。
 * app 端经 AndroidMangaExtensionPlatform、desktop 端经 DesktopCore 启动序列注入;
 * 未注入端 (iOS/鸿蒙) 「设置」入口隐藏。
 */
class SharedMangaSourceConfig(
    private val appContext: Context,
) : MangaExtensionConfig {

    /** 按 pkgName 取已装载源实例 (漫画源优先, 视频源次之); 未装载出源返回 null。 */
    private fun sourceOf(pkgName: String): Any? = MangaExtensionManager.firstSourceOf(pkgName)

    override fun isConfigurable(pkgName: String): Boolean = MangaExtensionManager.isPkgConfigurable(pkgName)

    override suspend fun buildPreferenceItems(pkgName: String, owner: MangaPrefCacheOwner): List<MangaPrefItem> =
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
            // 构建归属登记: 入口关闭时按归属定点释放, 不清另一入口的构建
            activeScreens[owner] = screen
            // 实际对象身份随快照句柄直达; visible 只决定渲染哪些项。
            preferences.mapIndexedNotNull { index, item ->
                if (item.visible) item.toPrefItem(index) else null
            }
        }

    override fun releasePreferenceCache(owner: MangaPrefCacheOwner) {
        // 仅清本归属; 另一入口的构建不受影响
        activeScreens.remove(owner)
    }

    override suspend fun setPreferenceValue(item: MangaPrefItem, value: MangaPrefValue) {
        withContext(PlatformDispatchers.mainDispatcher) {
            val preference = item.ref.ownerAsPreference()
            // 经 shim 的 setter 落值: 持久化规则 (key/持久化开关) 与 androidx 一致
            when (value) {
                is MangaPrefValue.Text -> (preference as? EditTextPreference)?.setText(value.value)
                is MangaPrefValue.Choice -> (preference as? ListPreference)?.setValue(value.value)
                is MangaPrefValue.MultiChoice -> (preference as? MultiSelectListPreference)?.setValues(value.values)
                is MangaPrefValue.Flag -> (preference as? TwoStatePreference)?.setChecked(value.value)
            }
        }
    }

    override suspend fun performPreferenceClick(item: MangaPrefItem) {
        withContext(PlatformDispatchers.mainDispatcher) {
            item.ref.ownerAsPreference().performClick()
        }
    }

    override suspend fun applyPreferenceChange(item: MangaPrefItem, newValue: Boolean) {
        withContext(PlatformDispatchers.mainDispatcher) {
            val preference = item.ref.ownerAsPreference() as? TwoStatePreference
                ?: return@withContext
            // 对齐 androidx: callChangeListener 通过才落值 (未挂监听视为通过)
            if (preference.callChangeListener(newValue)) preference.setChecked(newValue)
        }
    }

    override suspend fun clickTextEditor(item: MangaPrefItem): MangaPrefTextSession? {
        return withContext(PlatformDispatchers.mainDispatcher) {
            val preference = item.ref.ownerAsEditText() ?: return@withContext null
            // 对齐 androidx performClick 合同: 插件监听消费即结束; 否则 EditTextPreference
            // 默认路径 = 请求宿主显示编辑对话框 (shim onClick 经 onShowEditDialog 交还)
            var session: MangaPrefTextEditorSession? = null
            try {
                preference.onShowEditDialog = { opened -> session = sessionOf(opened) }
                preference.performClick()
            } finally {
                preference.onShowEditDialog = null
            }
            session
        }
    }

    /** 会话持本次定位到的实际对象: 后续绑定/提交全针对它, 与交互重建后的缓存替换无关。 */
    private fun sessionOf(preference: EditTextPreference): MangaPrefTextEditorSession {
        return object : MangaPrefTextEditorSession {
            override fun initialText(): String = preference.getText().orEmpty()

            override fun onBindEditText(editText: EditText) {
                preference.onBindEditTextListener?.onBindEditText(editText)
            }

            override suspend fun commit(text: String): Boolean {
                // View/模型操作 (插件 changeListener 可引用实际 EditText) 在主线程;
                // 真实平台 SharedPreferences.apply 本就异步落盘, 磁盘持久化边界待另批
                return withContext(PlatformDispatchers.mainDispatcher) {
                    // 对齐 androidx/Mihon 确认路径: callChangeListener 通过才落值
                    if (preference.callChangeListener(text)) {
                        preference.setText(text)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    /** 构建归属登记 (入口关闭按归属定点释放; 实际对象由快照句柄持有, 随弃即回收)。 */
    private val activeScreens = ConcurrentHashMap<MangaPrefCacheOwner, PreferenceScreen>()

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
     * 未持久化的项沿用扩展声明值)。列表项的 entries/entryValues 供 UI 渲染选择器;
     * 摘要所需信息 (ListPreference 的当前 entry 文案、TwoStatePreference 的 summaryOn/Off)
     * 一并下沉, UI 层不再自行映射取值。
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
            // 无值即未选中: 不借用首个 entryValue, 对齐 androidx ListPreference 的空 mValue
            is ListPreference ->
                MangaPrefValue.Choice(getValue().orEmpty())

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
            summaryOn = (this as? TwoStatePreference)?.summaryOn?.toString(),
            summaryOff = (this as? TwoStatePreference)?.summaryOff?.toString(),
            entryText = (this as? ListPreference)?.getEntry()?.toString(),
            entries = entries,
            entryValues = entryValues,
            enabled = enabled,
            // 全部项带实际对象句柄: 交互与文本会话按它直达实际对象
            ref = SharedPrefItemRef(this),
        )
    }
}

/** 平台私有句柄实现: 持确切配置对象, 不经 Any 万能盒。 */
private class SharedPrefItemRef(
    val preference: Preference,
) : MangaPrefItemRef

/** 句柄消费 (平台内): 取确切 Preference / EditTextPreference。 */
private fun MangaPrefItemRef.ownerAsPreference(): Preference =
    when (this) {
        is SharedPrefItemRef -> preference
        else -> error("未知配置句柄实现: $this")
    }

private fun MangaPrefItemRef.ownerAsEditText(): EditTextPreference? =
    ownerAsPreference() as? EditTextPreference
