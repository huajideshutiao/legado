package io.legado.desktop.config

import io.legado.app.help.config.PreferenceProvider
import java.util.prefs.Preferences

/**
 * [PreferenceProvider] 桌面端实现（KP1.4）。
 *
 * 内部委托 `java.util.prefs.Preferences.userNodeForPackage(PreferenceProvider::class.java)`,
 * 配置存储在用户级注册表 (Windows) / ~/.java/.userPrefs (Linux/macOS)。
 *
 * java.util.prefs.Preferences 只支持 String/Int/Boolean/Long/ByteArray, 刚好覆盖本接口
 * 所需的 4 种类型。注意：putString 的 value 为 null 时等价于 remove
 * (与 SharedPreferences 行为一致)。
 *
 * getAll() 实现: 遍历 prefs.keys() 返回 Map<String, String>
 * (java.util.prefs 不保留原始类型信息, 统一以 String 读取)。
 */
class DesktopPreferenceProvider : PreferenceProvider {

    private val prefs: Preferences =
        Preferences.userNodeForPackage(io.legado.app.help.config.PreferenceProvider::class.java)

    override fun getString(key: String, default: String): String =
        prefs.get(key, default)

    override fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    override fun getFloat(key: String, default: Float): Float =
        prefs.getFloat(key, default)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.remove(key)
        } else {
            prefs.put(key, value)
        }
    }

    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }

    override fun putFloat(key: String, value: Float) {
        prefs.putFloat(key, value)
    }

    override fun remove(key: String) {
        prefs.remove(key)
    }

    override fun contains(key: String): Boolean =
        prefs.get(key, null) != null

    override fun getAll(): Map<String, *> =
        prefs.keys().associateWith { prefs.get(it, "") }
}
