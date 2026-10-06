package io.legado.app.model.manga

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.manga.SharedMangaExtensionPlatform.MangaExtensionConfig
import io.legado.app.ui.book.manga.extension.MangaPrefAction
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import kotlinx.coroutines.withContext

/**
 * 插件自带配置的平台桥 (JVM+Android 共用): 构造 shim `PreferenceScreen`、调
 * `setupPreferenceScreen`、按插件自身 SharedPreferences (`source_<sourceId>`) 回填当前值。
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
            // 对齐 keiyoushi stub: 插件 setupPreferenceScreen 首句即 screen.context, shim 必须能提供
            val screen = PreferenceScreen(appContext)
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
            val prefs = sourcePrefsOf(source) ?: return@withContext emptyList()
            // shim 的 getPreferences() 是 Kotlin 函数 (非 Java getter), 不合成 `.preferences` 属性,
            // 且其后备字段为 private, 必须走函数调用。
            val preferences = screen.getPreferences()
            // 动作通道依赖活 Preference 实例 (无 key 项只能按序号定位), 缓存本次构建结果,
            // 与 [runPreferenceAction] 共享; 下次构建/写入自动覆盖。
            prefCache[pkgName] = preferences
            preferences
                .filter { it.visible }
                .mapIndexed { index, it -> it.toPrefItem(prefs, index) }
        }

    override suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue) {
        withContext(IoDispatcher) {
            val source = sourceOf(pkgName) ?: return@withContext
            val prefs = sourcePrefsOf(source) ?: return@withContext
            val editor = prefs.edit()
            when (value) {
                is MangaPrefValue.Text -> editor.putString(key, value.value)
                is MangaPrefValue.Flag -> editor.putBoolean(key, value.value)
                is MangaPrefValue.Choice -> editor.putString(key, value.value)
                is MangaPrefValue.MultiChoice -> editor.putStringSet(key, value.values)
            }
            editor.apply()
        }
    }

    /** 最近一次构建的 shim Preference 列表 (按 pkgName; 供动作通道按序号定位无 key 项)。 */
    private val prefCache = HashMap<String, List<Preference>>()

    /**
     * 执行偏好动作 (对齐 androidx.preference 语义): 按 [index] 定位到上次构建缓存的 shim
     * Preference, 触发扩展挂的监听回调。
     *
     * - [MangaPrefAction.Click]: 调 `onPreferenceClickListener` (返回值约定为已消费, 忽略);
     * - [MangaPrefAction.Toggle]: 调 `onPreferenceChangeListener(preference, newValue)`,
     *   返回 true 才按 [MangaPrefValue] 落值并同步 shim 的 `isChecked` (无 key 的项无法
     *   持久化, 仅同步内存态供重读回显); 返回 false 表示扩展已拦截 (如"立即签到"触发
     *   一次请求但不改状态), UI 保持原值。
     */
    override suspend fun runPreferenceAction(
        pkgName: String,
        index: Int,
        action: MangaPrefAction,
    ) {
        withContext(IoDispatcher) {
            val source = sourceOf(pkgName) ?: return@withContext
            val prefs = sourcePrefsOf(source) ?: return@withContext
            val preference = prefCache[pkgName]?.getOrNull(index) ?: return@withContext
            when (action) {
                is MangaPrefAction.Click -> {
                    preference.onPreferenceClickListener?.onPreferenceClick(preference)
                }

                is MangaPrefAction.Toggle -> {
                    val accepted = preference.onPreferenceChangeListener
                        ?.onPreferenceChange(preference, action.value) == true
                    if (accepted) {
                        (preference as? TwoStatePreference)?.isChecked = action.value
                        preference.key?.let { key ->
                            val editor = prefs.edit()
                            editor.putBoolean(key, action.value)
                            editor.apply()
                        }
                    }
                }
            }
        }
    }

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
     * shim Preference → 跨层 [MangaPrefItem]。当前值优先读插件偏好, 缺失回落到 shim 上的
     * 声明值 (setDefaultValue/构造时 setChecked 等)。shim 的 getter 在未设置时为 null,
     * 故所有读取都带默认值。
     */
    private fun Preference.toPrefItem(prefs: SharedPreferences, index: Int): MangaPrefItem {
        // 动作通道: 挂了点击/变更监听即带出 (有 key 的普通开关也可能带联动 listener,
        // 点击统一经 runPreferenceAction 执行, 返回 true 才落值, 与 androidx 语义一致)
        val action = when {
            onPreferenceClickListener != null -> MangaPrefAction.Click
            onPreferenceChangeListener != null && this is TwoStatePreference ->
                MangaPrefAction.Toggle(value = isChecked)

            else -> null
        }
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
        // 回落值优先取 setDefaultValue 的声明值 (对齐 androidx.preference: 多数插件只声明
        // 默认值不赋现值, 漏声明值会把默认开的开关误显示为关)
        val value: MangaPrefValue = when (this) {
            is ListPreference -> {
                val current = key?.let { prefs.getString(it, null) }
                    ?: defaultValue?.toString() ?: this.value
                MangaPrefValue.Choice(current ?: entryValues.firstOrNull().orEmpty())
            }

            is MultiSelectListPreference -> {
                val declared = (defaultValue as? Set<*>)?.map { it.toString() }?.toSet()
                val current = key?.let { prefs.getStringSet(it, null) } ?: declared ?: this.values
                MangaPrefValue.MultiChoice(current.toSet())
            }

            is TwoStatePreference -> {
                val declared = (defaultValue as? Boolean) ?: this.isChecked
                val current = key?.let { prefs.getBoolean(it, declared) } ?: declared
                MangaPrefValue.Flag(current)
            }

            is EditTextPreference -> {
                val current = key?.let { prefs.getString(it, null) }
                    ?: defaultValue?.toString() ?: this.text
                MangaPrefValue.Text(current.orEmpty())
            }

            else -> {
                val current = key?.let { prefs.getString(it, defaultValue?.toString()) }
                    ?: defaultValue?.toString()
                MangaPrefValue.Text(current.orEmpty())
            }
        }
        return MangaPrefItem(
            index = index,
            key = key,
            title = title?.toString().orEmpty(),
            summary = summary?.toString(),
            value = value,
            entries = entries,
            entryValues = entryValues,
            action = action,
        )
    }
}
