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
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import kotlinx.coroutines.withContext

/**
 * [io.legado.app.ui.book.manga.extension.MangaExtensionService] 安卓注册壳:
 * 状态组装与操作面已下沉 ui 层 [SharedMangaExtensionPlatform] (JVM+Android 共用),
 * 本类只注入安卓专属的插件自带配置桥 (androidx.preference shim + 平台 SharedPreferences)。
 *
 * MainActivity.initializePlatform 一行注册; desktop 等端不注册 → UI 隐藏入口。
 */
class AndroidMangaExtensionPlatform(
    context: Context,
) : SharedMangaExtensionPlatform(AndroidMangaSourceConfig(context.applicationContext))

/**
 * 插件自带配置的平台桥 (安卓): 构造 shim `PreferenceScreen`、调 `setupPreferenceScreen`、
 * 按插件自身 SharedPreferences (`source_<sourceId>`) 回填当前值。
 */
class AndroidMangaSourceConfig(
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
            screen.getPreferences()
                .filter { it.visible }
                .map { it.toPrefItem(prefs) }
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
    private fun Preference.toPrefItem(prefs: SharedPreferences): MangaPrefItem {
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
            key = key,
            title = title?.toString().orEmpty(),
            summary = summary?.toString(),
            value = value,
            entries = entries,
            entryValues = entryValues,
        )
    }
}
