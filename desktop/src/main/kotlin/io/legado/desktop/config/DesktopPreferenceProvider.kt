package io.legado.desktop.config

import io.legado.app.help.config.PreferenceProvider
import java.util.prefs.PreferenceChangeListener
import java.util.prefs.Preferences

/**
 * [PreferenceProvider] 桌面端实现。
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
        flushAfterWrite()
    }

    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
        flushAfterWrite()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
        flushAfterWrite()
    }

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
        flushAfterWrite()
    }

    override fun putFloat(key: String, value: Float) {
        prefs.putFloat(key, value)
        flushAfterWrite()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        flushAfterWrite()
    }

    /**
     * java.util.prefs 持久化语义: Windows (WindowsPreferences) 同步写注册表,
     * flush 为 no-op; Linux/macOS (FileSystemPreferences) put 只写内存缓存,
     * 后台约 30s 才落盘且无 shutdown hook——写后立即退出/崩溃 (如重启应用) 会丢配置,
     * 故每次写入后显式 flush (Windows 幂等零成本, Linux/macOS 立即落盘)。
     * flush 失败 (BackingStoreException) 如实记录, 不吞。
     */
    private fun flushAfterWrite() {
        try {
            prefs.flush()
        } catch (e: Exception) {
            io.legado.app.constant.AppLog.put("配置持久化失败 (prefs.flush)", e)
        }
    }

    override fun contains(key: String): Boolean =
        prefs.get(key, null) != null

    override fun getAll(): Map<String, *> =
        prefs.keys().associateWith { prefs.get(it, "") }

    override fun addPreferenceChangeListener(listener: (key: String) -> Unit): () -> Unit {
        val prefsListener = PreferenceChangeListener { event -> listener(event.key) }
        prefs.addPreferenceChangeListener(prefsListener)
        return { prefs.removePreferenceChangeListener(prefsListener) }
    }
}
