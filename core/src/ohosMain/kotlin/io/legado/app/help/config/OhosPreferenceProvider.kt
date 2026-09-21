package io.legado.app.help.config

import kotlin.concurrent.Volatile

import io.legado.app.help.file.AppFilesDirs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.legado.app.utils.File

/**
 * [PreferenceProvider] 的鸿蒙 (OHOS) 文件持久化 stub 实现。
 *
 * 鸿蒙平台没有 SharedPreferences, 当前用 kotlin.io.File + kotlinx-serialization.json
 * 在 [AppFilesDirs.filesDir] 下持久化为 `legado_config.json`:
 * - 读: 文件存在则解析为 JsonObject, 否则空 Map
 * - 写: 全量序列化为 JSON 文本回写 (配置项数量少, 全量写可接受)
 *
 * 模式参考桌面端 `DesktopPreferenceProvider` (java.util.prefs 持久化):
 * 鸿蒙端额外做文件持久化, 适合最小可运行骨架; 后续接入鸿蒙原生
 * `@ohos.data.preferences` 时, 通过 napi 桥接替换本 stub。
 *
 * 注: [AppFilesDirs] 在鸿蒙端默认注册为空串 ([OhosAppFilesDir]),
 * 此处对空串 filesDir 退化为内存 Map (不持久化, 进程退出即丢失),
 * 让单元测试与早期骨架可运行; 真实接入时 [registerOhosAppFilesDir] 应先注入有效路径。
 *
 * 注: kotlin.io.File 在 Kotlin/Native linuxArm64 上由标准库支持 (基于 POSIX fs),
 * 行为与 JVM java.io.File 等价, 可直接使用。
 */
class OhosPreferenceProvider(
    private val fileName: String = "legado_config.json",
) : PreferenceProvider {

    // kotlinx-serialization Json 配置: 容忍未知键 (向前兼容), 不输出类名
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // 内存缓存: lazy 加载, 写入时同步回写
    @Volatile
    private var cache: JsonObject = loadFromFile()

    private fun resolveFile(): File? {
        val dir = AppFilesDirs.get().filesDir
        // 空串 (OhosAppFilesDir stub 默认值) 退化为内存模式, 避免抛异常
        if (dir.isEmpty()) return null
        return File(dir, fileName)
    }

    private fun loadFromFile(): JsonObject {
        val file = resolveFile() ?: return JsonObject(emptyMap())
        if (!file.exists()) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(file.readText()) as? JsonObject ?: JsonObject(emptyMap())
        }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun persist() {
        val file = resolveFile() ?: return  // 内存模式, 不持久化
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(JsonObject.serializer(), cache))
        }
    }

    private fun getPrim(key: String): JsonPrimitive? =
        (cache[key] as? JsonPrimitive)

    override fun getString(key: String, default: String): String =
        getPrim(key)?.content ?: default

    override fun getStringOrNull(key: String): String? =
        getPrim(key)?.content

    override fun getInt(key: String, default: Int): Int =
        getPrim(key)?.content?.toIntOrNull() ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        getPrim(key)?.content?.toBooleanStrictOrNull() ?: default

    override fun getLong(key: String, default: Long): Long =
        getPrim(key)?.content?.toLongOrNull() ?: default

    override fun getFloat(key: String, default: Float): Float =
        getPrim(key)?.content?.toFloatOrNull() ?: default

    override fun putString(key: String, value: String?) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> put(k, v) }
            if (value != null) {
                put(key, value)
            }
            // null 等价于移除 (与 SharedPreferences.Editor.remove 行为一致)
        }
        persist()
        notifyChanged(key)
    }

    override fun putInt(key: String, value: Int) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        persist()
        notifyChanged(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        persist()
        notifyChanged(key)
    }

    override fun putLong(key: String, value: Long) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        persist()
        notifyChanged(key)
    }

    override fun putFloat(key: String, value: Float) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        persist()
        notifyChanged(key)
    }

    override fun remove(key: String) {
        cache = buildJsonObject {
            cache.forEach { (k, v) -> if (k != key) put(k, v) }
        }
        persist()
        notifyChanged(key)
    }

    override fun contains(key: String): Boolean = cache.containsKey(key)

    override fun getAll(): Map<String, *> = cache

    // 变更监听: 鸿蒙端所有写入都经本实例 (设置界面等 UI 层统一取 PreferenceProviders 单例即本实例),
    // 写入自通知即可覆盖, 无平台异步通道 (见 [PreferenceChangeNotifier])。
    private val notifier = PreferenceChangeNotifier()

    override fun addPreferenceChangeListener(listener: (key: String) -> Unit): () -> Unit {
        notifier.add(listener)
        return { notifier.remove(listener) }
    }

    private fun notifyChanged(key: String) = notifier.notifyChanged(key)
}

/**
 * 鸿蒙宿主启动早期注册 [PreferenceProvider] 的文件持久化实现。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码访问 `PreferenceProviders.get()` 之前。
 *
 * 模式参考 `registerOhosAppFilesDir`。
 */
fun registerOhosPreferenceProvider() {
    PreferenceProviders.register(OhosPreferenceProvider())
}
