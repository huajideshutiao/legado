package io.legado.desktop.config

import io.legado.app.constant.AppLog
import io.legado.app.help.config.PreferenceChangeNotifier
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.help.storage.FilesJsonStore
import io.legado.app.utils.decodeAnyMapOrNull
import io.legado.app.utils.encodeAnyMap

/**
 * [PreferenceProvider] 桌面端实现。
 *
 * 存储是 filesDir 下的单份键值 JSON ([PREF_CONFIG_FILE_NAME], [FilesJsonStore] 原子落盘)
 * 加全量内存缓存: 读全部命中内存, 写先改内存再整份回写。Boolean / Int / Long / Float /
 * Double / String 的值类型由此完整保留 —— 这也是跨端备份恢复的类型基线: 备份导出
 * ([BackupShared] 经 [PreferenceProvider.getAll]) 拿到的就是真实类型, 其他端按类型写回
 * 自己的强类型存储, 不会再把配置位污染成字符串。
 *
 * 取值语义（宽容段）：值类型与写入类型同形；仅当值以字符串形态写入（putString）而读位
 * 是数字/布尔时按"宽容解析"收录：收得上就收，getBoolean 只认 true/false 两字面量，
 * 收不上回落调用方默认值。
 *
 * 同步落盘节奏: 每次 put / remove 后整份回写一次 (临时件 + 原子改名, 见
 * [FilesJsonStore.writeText]); 失败只记日志 (内存值继续生效, 进程存续期内配置不丢,
 * 下一次写入会再落一次盘)。
 *
 * 变更通知: 进程内写入走 [PreferenceChangeNotifier] 同步自通知; 跨进程直写无监听
 * (桌面应用单实例运行, JSON 文件没有跨进程通知通道)。
 */
class DesktopPreferenceProvider : PreferenceProvider {

    private val lock = Any()

    /** 全量配置的内存缓存: 首次访问时整读文件 (按 [decodeAnyMapOrNull] 还原真实类型)。 */
    private val cachedValues: MutableMap<String, Any?> by lazy { loadValues() }

    override fun getString(key: String, default: String): String =
        asStringValue(key) ?: default

    override fun getStringOrNull(key: String): String? = asStringValue(key)

    override fun getInt(key: String, default: Int): Int {
        val value = valueOf(key)
        return when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else default
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean {
        val value = valueOf(key)
        return when (value) {
            is Boolean -> value
            is String -> when (value) {
                "true" -> true
                "false" -> false
                else -> default
            }
            else -> default
        }
    }

    override fun getLong(key: String, default: Long): Long {
        val value = valueOf(key)
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    override fun getFloat(key: String, default: Float): Float {
        val value = valueOf(key)
        return when {
            value is Float -> value
            value is Double -> value.toFloat()
            value is Int -> value.toFloat()
            value is Long -> value.toFloat()
            value is String -> value.toFloatOrNull() ?: default
            else -> default
        }
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        synchronized(lock) {
            cachedValues[key] = value
            persistLocked()
        }
        notifyChanged(key)
    }

    override fun putInt(key: String, value: Int) = putValue(key, value)

    override fun putBoolean(key: String, value: Boolean) = putValue(key, value)

    override fun putLong(key: String, value: Long) = putValue(key, value)

    override fun putFloat(key: String, value: Float) = putValue(key, value)

    override fun remove(key: String) {
        synchronized(lock) {
            if (cachedValues.remove(key) != null) {
                persistLocked()
            }
        }
        notifyChanged(key)
    }

    override fun contains(key: String): Boolean =
        valueOf(key) != null

    override fun getAll(): Map<String, *> = synchronized(lock) {
        cachedValues.toMap()
    }

    override fun addPreferenceChangeListener(listener: (key: String) -> Unit): () -> Unit {
        notifier.add(listener)
        return { notifier.remove(listener) }
    }

    private val notifier = PreferenceChangeNotifier()

    private fun notifyChanged(key: String) = notifier.notifyChanged(key)

    private fun putValue(key: String, value: Any) {
        synchronized(lock) {
            cachedValues[key] = value
            persistLocked()
        }
        notifyChanged(key)
    }

    private fun valueOf(key: String): Any? = synchronized(lock) {
        cachedValues[key]
    }

    /** 值的非字符串形态取字符串形 (数字/布尔 toString 即字面内容)。 */
    private fun asStringValue(key: String): String? {
        val value = valueOf(key) ?: return null
        return when (value) {
            is String -> value
            is Boolean, is Number -> value.toString()
            else -> null
        }
    }

    /** 键值文件不存在/读不出即空表启动 (首次运行本就无内容, 非异常状态)。 */
    private fun loadValues(): MutableMap<String, Any?> {
        val json = FilesJsonStore.readText(PREF_CONFIG_FILE_NAME) ?: return linkedMapOf()
        val loaded = decodeAnyMapOrNull(json)
        if (loaded == null) {
            // 解析失败说明文件内容已损 (原子写不会留半截, 恶意改动除外): 记日志后按空表走,
            // 下一次写入会以内存值整份覆盖回去
            AppLog.put("配置文件解析失败, 按空配置启动: $PREF_CONFIG_FILE_NAME")
            return linkedMapOf()
        }
        return loaded.filterValues { it != null }.toMutableMap()
    }

    /** 整份回写键值 JSON (调用方已持 [lock]; 失败由 FilesJsonStore 记日志, 此处不再抛)。 */
    private fun persistLocked() {
        runCatching { FilesJsonStore.writeText(PREF_CONFIG_FILE_NAME, encodeAnyMap(cachedValues)) }
            .onFailure {
                AppLog.put("配置写入失败: $PREF_CONFIG_FILE_NAME\n${it.message}", it)
            }
    }

    companion object {
        const val PREF_CONFIG_FILE_NAME = "pref_config.json"
    }
}
