// android.* JVM stub: SharedPreferences 的持久化实现 (按文件名隔离, 落 filesDir 下 JSON)。
// 语义对齐 Android: 同名多次 getSharedPreferences 返回同一实例、写入可读回、重启保留 ——
// 插件源偏好 (keiyoushi utils getPreferencesLazy / Aniyomi sourcePreferences 的 source_<id>)
// 与 TVBox spider 偏好走这里, 配置页写入必须与插件运行时读取同源。
// AppFilesDirs 未注册 (headless 早期/测试) 时退化为纯内存, 不阻塞扩展装载。
package android.content

import io.legado.app.help.storage.FilesJsonStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

interface SharedPreferences {

    fun getAll(): Map<String, *>

    fun getString(key: String?, defValue: String?): String?

    fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>?

    fun getInt(key: String?, defValue: Int): Int

    fun getLong(key: String?, defValue: Long): Long

    fun getFloat(key: String?, defValue: Float): Float

    fun getBoolean(key: String?, defValue: Boolean): Boolean

    fun contains(key: String?): Boolean

    fun edit(): Editor

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {}

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {}

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }

    interface Editor {
        fun putString(key: String?, value: String?): Editor

        fun putStringSet(key: String?, values: MutableSet<String>?): Editor

        fun putInt(key: String?, value: Int): Editor

        fun putLong(key: String?, value: Long): Editor

        fun putFloat(key: String?, value: Float): Editor

        fun putBoolean(key: String?, value: Boolean): Editor

        fun remove(key: String?): Editor

        fun clear(): Editor

        fun commit(): Boolean

        fun apply()
    }
}

class SharedPrefsStub internal constructor(private val name: String?) : SharedPreferences {

    companion object {

        private val instances = ConcurrentHashMap<String, SharedPrefsStub>()

        /** 按 name 单例 (Android 语义: 同名多次 getSharedPreferences 返回同一实例)。 */
        fun of(name: String?): SharedPrefsStub =
            instances.getOrPut(name.orEmpty()) { SharedPrefsStub(name) }

        /** 偏好文件: filesDir 下 shared-prefs-<name>.json; 路径非法字符替换为下划线。 */
        private fun fileName(name: String?): String =
            "shared-prefs-" + (name.orEmpty().ifEmpty { "default" }).replace(Regex("""[\\/:*?"<>|]"""), "_") + ".json"

        // 值带类型标签编码, 保 Int/Long/Boolean/Float/Set<String> 类型 (JSON 数字读回会丢整型)
        private fun encodeStore(store: Map<String, Any?>): String = buildJsonObject {
            store.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, buildJsonObject { put("t", "s"); put("v", value) })
                    is Boolean -> put(key, buildJsonObject { put("t", "b"); put("v", value) })
                    is Int -> put(key, buildJsonObject { put("t", "i"); put("v", value) })
                    is Long -> put(key, buildJsonObject { put("t", "l"); put("v", value) })
                    is Float -> put(key, buildJsonObject { put("t", "f"); put("v", value.toDouble()) })
                    is Set<*> -> put(key, buildJsonObject {
                        put("t", "ss")
                        put("v", JsonArray(value.map { JsonPrimitive(it.toString()) }))
                    })
                    else -> Unit // 未知类型不入盘 (进程内仍生效)
                }
            }
        }.toString()

        private fun decodeStore(text: String): Map<String, Any?> {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
            val result = HashMap<String, Any?>()
            obj.forEach { (key, element) ->
                val entry = element as? JsonObject ?: return@forEach
                val value = entry["v"]
                result[key] = when (entry["t"]?.jsonPrimitive?.contentOrNull) {
                    "s" -> value?.jsonPrimitive?.contentOrNull
                    "b" -> value?.jsonPrimitive?.booleanOrNull
                    "i" -> value?.jsonPrimitive?.intOrNull
                    "l" -> value?.jsonPrimitive?.longOrNull
                    "f" -> value?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                    "ss" -> value?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toMutableSet()
                    else -> null
                }
            }
            return result
        }
    }

    private val store = HashMap<String, Any?>()

    init {
        runCatching {
            FilesJsonStore.readText(fileName(name))?.let { text ->
                synchronized(store) { store.putAll(decodeStore(text)) }
            }
        }
    }

    override fun getAll(): Map<String, *> = synchronized(store) { store.toMap() }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(store) { store[key] as? String } ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(store) { store[key] as? MutableSet<String> } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(store) { store[key] as? Int } ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(store) { store[key] as? Long } ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(store) { store[key] as? Float } ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(store) { store[key] as? Boolean } ?: defValue

    override fun contains(key: String?): Boolean = synchronized(store) { store.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = StubEditor()

    private fun persist() {
        val snapshot = synchronized(store) { store.toMap() }
        runCatching { FilesJsonStore.writeText(fileName(name), encodeStore(snapshot)) }
    }

    private inner class StubEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { pending[key!!] = value!! }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key!!] = values!! }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply { removals.add(key!!) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(store) {
                if (clearAll) store.clear()
                removals.forEach { store.remove(it) }
                pending.forEach { (k, v) -> store[k] = v }
            }
            persist()
        }
    }
}
