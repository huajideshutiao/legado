// android.* JVM stub: SharedPreferences 的持久化实现 (按文件名隔离, 落 filesDir 下 JSON)。
// 语义对齐 Android: 同名多次 getSharedPreferences 返回同一实例、写入可读回、重启保留 ——
// 插件源偏好 (keiyoushi utils getPreferencesLazy / Aniyomi sourcePreferences 的 source_<id>)
// 与 TVBox spider 偏好走这里, 配置页写入必须与插件运行时读取同源。
// AppFilesDirs 未注册 (headless 早期/测试) 时退化为纯内存; 注册后首次访问会补读磁盘,
// 不会终生停在内存态。
package android.content

import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs
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
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface SharedPreferences {

    fun getAll(): Map<String, *>

    fun getString(key: String?, defValue: String?): String?

    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>?

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

        fun putStringSet(key: String?, values: Set<String>?): Editor

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
            instances.computeIfAbsent(name.orEmpty()) { SharedPrefsStub(name) }

        /**
         * 偏好文件: filesDir 下 `shared-prefs-<净化名>-<名字摘要>.json`。
         *
         * 净化非法字符后再附原始名字摘要: 否则 `a/b` 与 `a_b` 映到同一文件,
         * Windows 大小写不敏感文件系统上 `Name`/`name` 也会同文件 ——
         * 不同偏好桶共享落盘文件会互相覆写。
         */
        private fun fileName(name: String?): String {
            val raw = name.orEmpty().ifEmpty { "default" }
            val safe = raw.replace(Regex("""[\\/:*?"<>|]"""), "_")
            return "shared-prefs-$safe-${hexDigest(raw)}.json"
        }

        private fun hexDigest(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        // 值带类型标签编码, 保 Int/Long/Boolean/Float/Set<String> 类型 (JSON 数字读回会丢整型)
        private fun encodeStore(store: Map<String, Any?>): String = buildJsonObject {
            store.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, buildJsonObject { put("t", "s"); put("v", value) })
                    is Boolean -> put(key, buildJsonObject { put("t", "b"); put("v", value) })
                    is Int -> put(key, buildJsonObject { put("t", "i"); put("v", value) })
                    is Long -> put(key, buildJsonObject { put("t", "l"); put("v", value) })
                    is Float -> put(key, buildJsonObject {
                        put("t", "f")
                        // 非有限浮点在 JSON 里无字面量 (写成 NaN/Infinity 会让整表解析失败): 以字符串落盘
                        put(
                            "v",
                            if (value.isFinite()) JsonPrimitive(value.toDouble())
                            else JsonPrimitive(value.toString()),
                        )
                    })
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
                // 单条坏值跳过: 一格结构损坏不得毒化整表 (其余键必须保留)
                runCatching { decodeValue(entry) }.onSuccess { result[key] = it }
            }
            return result
        }

        private fun decodeValue(entry: JsonObject): Any? {
            val value = entry["v"]
            return when (entry["t"]?.jsonPrimitive?.contentOrNull) {
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
    }

    private val store = HashMap<String, Any?>()

    /** 落盘文件名 (名字→文件名不可变, 只算一次)。 */
    private val prefFile: String = fileName(name)

    /** 是否已从磁盘装载; AppFilesDirs 未注册时保持 false, 注册后首次访问补读。 */
    @Volatile
    private var loaded = false

    /** 惰性装载 (一次): 目录未注册/读取失败时不置位, 留给下次访问重试。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(store) {
            if (loaded) return
            if (runCatching { AppFilesDirs.get() }.isFailure) return
            runCatching { FilesJsonStore.readText(prefFile) }
                .onSuccess { text ->
                    loaded = true
                    text?.let { store.putAll(decodeStore(it)) }
                }
        }
    }

    override fun getAll(): Map<String, *> {
        ensureLoaded()
        return synchronized(store) { store.toMap() }
    }

    override fun getString(key: String?, defValue: String?): String? {
        ensureLoaded()
        return synchronized(store) { store[key] as? String } ?: defValue
    }

    // [decodeValue] 的 "ss" 分支恒产出 MutableSet<String> (类型标签编码, 见 [encodeStore]);
    // 泛型擦除下这里只能做运行时断言
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        ensureLoaded()
        return synchronized(store) { store[key] as? Set<String> } ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        ensureLoaded()
        return synchronized(store) { store[key] as? Int } ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        ensureLoaded()
        return synchronized(store) { store[key] as? Long } ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        ensureLoaded()
        return synchronized(store) { store[key] as? Float } ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        ensureLoaded()
        return synchronized(store) { store[key] as? Boolean } ?: defValue
    }

    override fun contains(key: String?): Boolean {
        ensureLoaded()
        return synchronized(store) { store.containsKey(key) }
    }

    override fun edit(): SharedPreferences.Editor {
        ensureLoaded()
        return StubEditor()
    }

    /**
     * 锁内落盘 (调用方持 store 锁): 临时文件 + 原子重命名, 写盘失败返回 false。
     * 锁内持久化保证并发 apply 不会用旧快照覆掉后写者。
     */
    private fun persistLocked(): Boolean = runCatching {
        val target = File(FilesJsonStore.path(prefFile))
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(encodeStore(store))
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        true
    }.onFailure {
        AppLog.put("共享偏好写入失败: $name\n${it.message}", it)
    }.getOrDefault(false)

    private inner class StubEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearAll = false

        private fun set(key: String, value: Any?) {
            removals.remove(key)
            pending[key] = value
        }

        private fun removeKey(key: String) {
            pending.remove(key)
            removals.add(key)
        }

        // Android 契约: 值为 null 等价 remove(key); key 为 null 抛 NPE (与 Android 一致)
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (value == null) removeKey(key!!) else set(key!!, value)
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = apply {
            if (values == null) removeKey(key!!) else set(key!!, values)
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { set(key!!, value) }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { set(key!!, value) }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { set(key!!, value) }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { set(key!!, value) }

        override fun remove(key: String?): SharedPreferences.Editor = apply { removeKey(key!!) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean = applyLocked()

        override fun apply() {
            applyLocked()
        }

        /** 锁内应用并落盘; 返回值即 commit() 的结果 (Android 写盘失败应返 false)。 */
        private fun applyLocked(): Boolean {
            ensureLoaded()
            synchronized(store) {
                if (clearAll) store.clear()
                removals.forEach { store.remove(it) }
                pending.forEach { (k, v) -> store[k] = v }
                return persistLocked()
            }
        }
    }
}
