package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.App
import io.legado.app.utils.defaultSharedPreferences

/**
 * [PreferenceProvider] 安卓端实现。
 *
 * 委托 [defaultSharedPreferences]（`<packageName>_preferences`）, 与 [AppConfig] / 设置界面
 * 同一份 SP 文件: shared 侧读 webPort / webDav* 等配置时必须看到用户在设置界面改的值,
 * 且 config.json 备份（app 端 dump default SP）与本 provider 的 [getAll] 需一致。
 *
 * 早期实现用独立的 `legado_config` 文件, 造成设置界面写、shared 读不到的割裂,
 * 由 [migrateLegacyConfigOnce] 一次性迁移旧值。
 *
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl]。
 */
class AndroidPreferenceProvider : PreferenceProvider {

    private val prefs = App.instance.defaultSharedPreferences.also {
        migrateLegacyConfigOnce(it)
    }

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    override fun getFloat(key: String, default: Float): Float =
        prefs.getFloat(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean =
        prefs.contains(key)

    override fun getAll(): Map<String, *> =
        prefs.all
}

/** 迁移完成标记, 放在 default SP 里, 避免每次启动都去加载旧文件。 */
private const val LEGACY_MIGRATED_KEY = "legadoConfigSpMigrated"

/**
 * 把旧 `legado_config` SP 文件中的配置一次性并入 default SP。
 *
 * default SP 已有的 key 一律保留（那才是用户在设置界面改的值）, 只补旧文件独有的 key。
 * 旧文件不删, 留作回滚保险。
 */
@Suppress("UNCHECKED_CAST")
private fun migrateLegacyConfigOnce(target: SharedPreferences) {
    if (target.getBoolean(LEGACY_MIGRATED_KEY, false)) return
    val legacy = App.instance.getSharedPreferences("legado_config", Context.MODE_PRIVATE)
    target.edit {
        legacy.all.forEach { (key, value) ->
            if (value == null || target.contains(key)) return@forEach
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }
        putBoolean(LEGACY_MIGRATED_KEY, true)
    }
}

/**
 * 安卓宿主启动早期注册 [PreferenceProvider]。
 *
 * 调用时机: App.onCreate, 任何 shared 调用之前。
 * 模式参考 `registerAndroidWebBookProviders` / `registerAndroidPasswordProvider`。
 */
fun registerAndroidPreferenceProvider() {
    PreferenceProviders.register(AndroidPreferenceProvider())
}
