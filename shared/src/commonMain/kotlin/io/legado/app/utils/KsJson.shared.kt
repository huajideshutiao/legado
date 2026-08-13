package io.legado.app.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

// 宽松 JSON 解析（对应原 GSON，容错降级用）
// allowComments 补齐 Gson lenient 的注释容忍度（实测 Gson.fromJson 恒 lenient，收 // 与 /* */）；
// allowTrailingComma 收手写书源常见的尾逗号（Gson 对象内其实不收，此处放宽一档）。
@OptIn(ExperimentalSerializationApi::class)
val KS_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    coerceInputValues = true
    allowComments = true
    allowTrailingComma = true
}

// 严格 JSON 解析（对应原 GSONStrict，先尝试严格解析）
val KS_JSON_STRICT: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    encodeDefaults = false
}

// 容错降级解析（先 strict 失败则用宽松重试）
inline fun <reified T> decodeFromStringWithFallback(json: String): T {
    return try {
        KS_JSON_STRICT.decodeFromString(json)
    } catch (e: Exception) {
        KS_JSON.decodeFromString(json)
    }
}

/**
 * Map<String, String> 专用序列化器, 用于 Book/BookChapter/SearchBook 的 variableMap 等 HashMap<String, String> 字段。
 *
 * 替代原 `GSON.toJson(variableMap)` / `GSON.fromJsonObject<HashMap<String, String>>(variable)` 路径,
 * 行为对齐: 容错降级 (KS_JSON 宽松策略), 解析失败返回 null 由调用方回落到默认空 map。
 *
 * 返回 HashMap 以匹配 [io.legado.app.model.analyzeRule.RuleDataInterface.variableMap] 的声明类型。
 */
private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

fun encodeStringMap(map: Map<String, String>): String =
    KS_JSON.encodeToString(stringMapSerializer, map)

fun decodeStringMapOrNull(json: String?): HashMap<String, String>? {
    if (json.isNullOrEmpty()) return null
    return try {
        // KS_JSON.decodeFromString 返回 Map<String, String>, 转 HashMap 匹配 variableMap 声明类型
        HashMap(KS_JSON.decodeFromString(stringMapSerializer, json))
    } catch (_: Exception) {
        null
    }
}

/**
 * kotlinx-serialization 版本的 MapDeserializerDoubleAsIntFix (KMP 化)。
 *
 * 复刻原 Gson MapDeserializer (注册在 INITIAL_GSON 中) 的语义:
 * - JsonObject → Map<String, Any?> (LinkedTreeMap, 保序)
 * - JsonArray → List<Any?>
 * - JsonPrimitive(boolean) → Boolean
 * - JsonPrimitive(string) → String
 * - JsonPrimitive(number) → 若 ceil(toDouble) == toLong 则 Long, 否则 Double (修复 Int 变 Double 的问题)
 * - JsonNull → null
 *
 * 用于替代 `GSON.fromJsonObject<Map<String, Any?>>(...)` 路径, 供 CustomUrl/Restore 等场景使用。
 *
 * 仅支持 JSON 格式 (JsonDecoder/JsonEncoder), 与原 Gson JsonDeserializer 假设 JsonElement 一致。
 */
object AnyMapSerializer : KSerializer<Map<String, Any?>> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.legado.app.utils.AnyMapSerializer")

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
        require(encoder is JsonEncoder) {
            "AnyMapSerializer 仅支持 JSON 格式, 当前 Encoder 非 JsonEncoder"
        }
        encoder.encodeJsonElement(valueToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> {
        require(decoder is JsonDecoder) {
            "AnyMapSerializer 仅支持 JSON 格式, 当前 Decoder 非 JsonDecoder"
        }
        val element = decoder.decodeJsonElement()
        return element.toObjectMap()
    }

    private fun elementToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            valueToJsonElement(value as Map<String, Any?>)
        }
        is List<*> -> JsonArray(value.map { elementToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun valueToJsonElement(map: Map<String, Any?>): JsonObject =
        JsonObject(map.mapValues { elementToJsonElement(it.value) })

    private fun elementToObjectMap(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> {
            val p = element.jsonPrimitive
            when {
                p.isString -> p.content
                p.booleanOrNull != null -> p.booleanOrNull!!
                p.contentOrNull != null -> {
                    // 复刻原 MapDeserializerDoubleAsIntFix: 数字统一先取 double,
                    // toLong 截断后若 ceil(double) == long 视为整值 → Long, 否则保留 Double
                    // (3.0 → 3L, 3.5 → 3.5, 10 → 10L)。longOrNull 对 "3.0" 解析不出 Long,
                    // 不能作为整值判据
                    val double = p.doubleOrNull
                    if (double != null) {
                        val long = double.toLong()
                        if (ceil(double) == long.toDouble()) long else double
                    } else {
                        p.content
                    }
                }
                else -> null
            }
        }
        is JsonArray -> element.map { elementToObjectMap(it) }
        is JsonObject -> {
            // 保序 Map (对应原 LinkedTreeMap)
            linkedMapOf<String, Any?>().apply {
                element.forEach { (k, v) -> put(k, elementToObjectMap(v)) }
            }
        }
    }

    private fun JsonElement.toObjectMap(): Map<String, Any?> =
        if (this is JsonObject) {
            linkedMapOf<String, Any?>().apply {
                // 显式 this@toObjectMap 引用 JsonObject (保序 LinkedTreeMap 语义),
                // 否则 apply 块内 this 是 LinkedHashMap, forEach 的 v 是 Any? 类型不匹配
                this@toObjectMap.forEach { (k, v) -> put(k, elementToObjectMap(v)) }
            }
        } else {
            emptyMap()
        }
}

/**
 * 解析 JSON 字符串为 Map<String, Any?>, 复刻 `GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()` 语义。
 *
 * 数字策略对齐原 MapDeserializerDoubleAsIntFix: 整数变 Long, 小数变 Double。
 * 解析失败返回 null, 调用方回落到默认值。
 */
fun decodeAnyMapOrNull(json: String?): Map<String, Any?>? {
    if (json.isNullOrEmpty()) return null
    return try {
        KS_JSON.decodeFromString(AnyMapSerializer, json)
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析 JSON 字符串为 List<T>, 复刻 `GSON.fromJsonArray<T>(json).getOrNull()` 语义。
 *
 * 用 [KS_JSON] 宽松策略 (ignoreUnknownKeys + isLenient + coerceInputValues),
 * 对齐原 GsonExtensions.shared.kt 中 `GSON.fromJsonArray(json)` 行为。
 * 解析失败返回 null, 调用方回落到默认值。
 */
inline fun <reified T> decodeListOrNull(json: String?): List<T>? {
    if (json.isNullOrEmpty()) return null
    return try {
        KS_JSON.decodeFromString<List<T>>(json)
    } catch (_: Exception) {
        null
    }
}

/**
 * 严格/宽松双栈 List 解析, 复刻 `GSONStrict.fromJsonArray<T>(json).getOrNull() ?: GSON.fromJsonArray<T>(json).getOrNull()` 语义。
 *
 * 先用 [KS_JSON_STRICT] 严格解析, 失败则降级到 [KS_JSON] 宽松解析,
 * 对齐原 AnalyzeUrlCore/BookList 中 `GSONStrict.fromJsonObject(...).getOrNull() ?: GSON.fromJsonObject(...).getOrNull()` 双栈调用模式。
 *
 * @param logFallbackToLenient 严格失败但宽松成功时的日志回调 (宽松也失败说明 JSON 根本无效, 不提示"格式不规范")
 */
inline fun <reified T> decodeListWithFallbackOrNull(
    json: String?,
    noinline logFallbackToLenient: (() -> Unit)? = null
): List<T>? {
    if (json.isNullOrEmpty()) return null
    // 先严格解析
    val strict = try {
        KS_JSON_STRICT.decodeFromString<List<T>>(json)
    } catch (_: Exception) {
        null
    }
    if (strict != null) return strict
    // 降级到宽松解析, 仅宽松成功才提示格式不规范
    val lenient = try {
        KS_JSON.decodeFromString<List<T>>(json)
    } catch (_: Exception) {
        null
    }
    if (lenient != null) logFallbackToLenient?.invoke()
    return lenient
}

/**
 * 解析 JSON 字符串为 T, 复刻 `GSON.fromJsonObject<T>(json).getOrNull()` 语义。
 *
 * 用 [KS_JSON] 宽松策略, 解析失败返回 null。
 */
inline fun <reified T> decodeOrNull(json: String?): T? {
    if (json.isNullOrEmpty()) return null
    return try {
        KS_JSON.decodeFromString<T>(json)
    } catch (_: Exception) {
        null
    }
}

/**
 * 严格/宽松双栈对象解析, 复刻 `GSONStrict.fromJsonObject<T>(json).getOrNull() ?: GSON.fromJsonObject<T>(json).getOrNull()` 语义。
 *
 * 先严格, 失败降级到宽松 (并触发 [logFallbackToLenient]), 对齐原 AnalyzeUrlCore 中 UrlOption 解析模式。
 */
inline fun <reified T> decodeWithFallbackOrNull(
    json: String?,
    noinline logFallbackToLenient: (() -> Unit)? = null
): T? {
    if (json.isNullOrEmpty()) return null
    val strict = try {
        KS_JSON_STRICT.decodeFromString<T>(json)
    } catch (_: Exception) {
        null
    }
    if (strict != null) return strict
    logFallbackToLenient?.invoke()
    return try {
        KS_JSON.decodeFromString<T>(json)
    } catch (_: Exception) {
        null
    }
}
