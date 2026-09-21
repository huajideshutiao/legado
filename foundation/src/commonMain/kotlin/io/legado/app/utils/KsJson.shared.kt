package io.legado.app.utils

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
import kotlinx.serialization.serializer
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
        decodeFromStringLenient(AnyMapSerializer, json)
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
    // 降级到宽松解析, 与对象版 [decodeWithFallbackOrNull] 走同一条链路 ([decodeFromStringLenient]:
    // KS_JSON → 流式宽松解析器)。旧实现只再跑一遍 [KS_JSON].decodeFromString, 于是同一段非法 JSON
    // 对象版能救回、数组版直接判无效。仅宽松成功才提示格式不规范。
    return try {
        decodeFromStringLenient<List<T>>(json).also {
            logFallbackToLenient?.invoke()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 解析 JSON 字符串为 JsonElement, 宽松支持单引号、无引号 key 与多层嵌套 (对齐原版 GSON 解析容错)。
 */
fun parseToJsonElementLenient(json: String): JsonElement {
    if (json.contains('\'')) {
        return try {
            KS_JSON_STRICT.parseToJsonElement(json)
        } catch (_: Exception) {
            LenientJsonParser.parse(json)
        }
    }
    return try {
        KS_JSON.parseToJsonElement(json)
    } catch (_: Exception) {
        LenientJsonParser.parse(json)
    }
}

/**
 * 宽松反序列化 (对应原 GSON.fromJsonObject / GSON.fromJson 默认宽松模式)。
 * 优先标准宽松解析, 失败则降级通过流式状态机解析为 JsonElement 后解码, 支持单引号及非标语法。
 */
fun <T> decodeFromStringLenient(
    deserializer: DeserializationStrategy<T>,
    json: String
): T {
    if (json.contains('\'')) {
        return try {
            KS_JSON_STRICT.decodeFromString(deserializer, json)
        } catch (_: Exception) {
            val element = LenientJsonParser.parse(json)
            KS_JSON.decodeFromJsonElement(deserializer, element)
        }
    }
    return try {
        KS_JSON.decodeFromString(deserializer, json)
    } catch (_: Exception) {
        // [KS_JSON] 的宽松解析已在上一趟对同一文本用过, 这里直接上流式宽松解析器:
        // 再调 [parseToJsonElementLenient] 只会重跑一遍注定失败的解析 (它还多一次 strict 尝试)
        val element = LenientJsonParser.parse(json)
        KS_JSON.decodeFromJsonElement(deserializer, element)
    }
}

inline fun <reified T> decodeFromStringLenient(json: String): T =
    decodeFromStringLenient(serializer<T>(), json)

/**
 * 解析 JSON 字符串为 T, 复刻 `GSON.fromJsonObject<T>(json).getOrNull()` 语义。
 *
 * 用宽松策略 (含单引号容错), 解析失败返回 null。
 */
inline fun <reified T> decodeOrNull(json: String?): T? {
    if (json.isNullOrEmpty()) return null
    return try {
        decodeFromStringLenient(json)
    } catch (_: Exception) {
        null
    }
}

fun <T> decodeOrNull(
    deserializer: DeserializationStrategy<T>,
    json: String?
): T? {
    if (json.isNullOrEmpty()) return null
    return try {
        decodeFromStringLenient(deserializer, json)
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
): T? = decodeWithFallbackOrNull(serializer<T>(), json, logFallbackToLenient)

fun <T> decodeWithFallbackOrNull(
    deserializer: DeserializationStrategy<T>,
    json: String?,
    logFallbackToLenient: (() -> Unit)? = null
): T? {
    if (json.isNullOrEmpty()) return null
    val strict = try {
        KS_JSON_STRICT.decodeFromString(deserializer, json)
    } catch (_: Exception) {
        null
    }
    if (strict != null) return strict
    return try {
        decodeFromStringLenient(deserializer, json).also {
            logFallbackToLenient?.invoke()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 轻量流式非严格 JSON 解析器（基于字符流状态机，对齐原版 Gson JsonReader 核心语义）。
 * 纯私有内部实现，对外通过 [parseToJsonElementLenient] 与 [decodeFromStringLenient] 暴露。
 */
private class LenientJsonParser private constructor(private val src: String) {

    private var pos = 0
    private val len = src.length

    companion object {
        fun parse(json: String): JsonElement {
            val parser = LenientJsonParser(json)
            parser.skipWhitespaceAndComments()
            if (parser.pos >= parser.len) {
                throw SerializationException("JSON 字符串为空")
            }
            val result = parser.parseElement()
            parser.skipWhitespaceAndComments()
            if (parser.pos < parser.len) {
                throw SerializationException("JSON 在字符位置 ${parser.pos} 处有多余内容: '${parser.src.substring(parser.pos)}'")
            }
            return result
        }
    }

    private fun parseElement(): JsonElement {
        skipWhitespaceAndComments()
        if (pos >= len) throw SerializationException("非预期的输入结束")

        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '\'', '"' -> JsonPrimitive(nextQuotedValue(c))
            else -> parseUnquotedValue()
        }
    }

    private fun parseObject(): JsonObject {
        pos++ // 跳过 '{'
        val map = LinkedHashMap<String, JsonElement>()

        while (true) {
            skipWhitespaceAndComments()
            if (pos >= len) throw SerializationException("未闭合的对象: 缺少 '}'")
            if (src[pos] == '}') {
                pos++
                break
            }

            // 读取 Key
            val key = when (val c = src[pos]) {
                '\'', '"' -> nextQuotedValue(c)
                else -> nextUnquotedKey()
            }
            if (key.isEmpty()) throw SerializationException("在位置 $pos 期望属性名")

            skipWhitespaceAndComments()
            if (pos >= len || src[pos] != ':') {
                throw SerializationException("在位置 $pos 的属性 '$key' 后面缺少冒号 ':'")
            }
            pos++ // 跳过 ':'

            // 读取 Value
            val value = parseElement()
            map[key] = value

            skipWhitespaceAndComments()
            if (pos >= len) throw SerializationException("未闭合的对象: 缺少 '}' 或 ','")
            if (src[pos] == ',') {
                pos++
                skipWhitespaceAndComments()
                // 容忍尾逗号
                if (pos < len && src[pos] == '}') {
                    pos++
                    break
                }
            } else if (src[pos] == '}') {
                pos++
                break
            } else {
                throw SerializationException("在位置 $pos 期望 ',' 或 '}'，实际为: '${src[pos]}'")
            }
        }
        return JsonObject(map)
    }

    private fun parseArray(): JsonArray {
        pos++ // 跳过 '['
        val list = ArrayList<JsonElement>()

        while (true) {
            skipWhitespaceAndComments()
            if (pos >= len) throw SerializationException("未闭合的数组: 缺少 ']'")
            if (src[pos] == ']') {
                pos++
                break
            }

            val value = parseElement()
            list.add(value)

            skipWhitespaceAndComments()
            if (pos >= len) throw SerializationException("未闭合的数组: 缺少 ']' 或 ','")
            if (src[pos] == ',') {
                pos++
                skipWhitespaceAndComments()
                // 容忍尾逗号
                if (pos < len && src[pos] == ']') {
                    pos++
                    break
                }
            } else if (src[pos] == ']') {
                pos++
                break
            } else {
                throw SerializationException("在位置 $pos 期望 ',' 或 ']'，实际为: '${src[pos]}'")
            }
        }
        return JsonArray(list)
    }

    /**
     * 消费由 [quote] 包裹的字符串，处理转义字符（对齐 Gson nextQuotedValue 逻辑）。
     */
    private fun nextQuotedValue(quote: Char): String {
        pos++ // 跳过起始 quote
        val sb = StringBuilder()
        while (pos < len) {
            val c = src[pos++]
            if (c == quote) {
                return sb.toString()
            }
            if (c == '\\') {
                if (pos >= len) throw SerializationException("未完成的转义序列")
                val escape = src[pos++]
                when (escape) {
                    'u' -> {
                        if (pos + 4 > len) throw SerializationException("未完成的 \\uXXXX Unicode 转义")
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        val code = hex.toIntOrNull(16)
                            ?: throw SerializationException("非法的 Unicode 转义: \\u$hex")
                        sb.append(code.toChar())
                    }
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(escape) // 容错非标转义
                }
            } else {
                sb.append(c)
            }
        }
        throw SerializationException("未闭合的字符串: 缺少 $quote")
    }

    /**
     * 读取未加引号的 Key。遇到冒号、逗号、大括号或空白结束。
     */
    private fun nextUnquotedKey(): String {
        val start = pos
        while (pos < len) {
            val c = src[pos]
            if (c == ':' || c == ',' || c == '}' || c == '{' || c <= ' ' || c == '/') break
            pos++
        }
        return src.substring(start, pos).trim()
    }

    /**
     * 读取未加引号的字面量（true, false, null, 数字，或字符串）。
     */
    private fun parseUnquotedValue(): JsonElement {
        val start = pos
        while (pos < len) {
            val c = src[pos]
            if (c == ',' || c == '}' || c == ']' || c <= ' ' || c == '/') break
            pos++
        }
        val literal = src.substring(start, pos).trim()
        if (literal.equals("null", ignoreCase = true)) return JsonNull
        if (literal.equals("true", ignoreCase = true)) return JsonPrimitive(true)
        if (literal.equals("false", ignoreCase = true)) return JsonPrimitive(false)

        // 尝试解析为整数或浮点数
        literal.toLongOrNull()?.let { return JsonPrimitive(it) }
        literal.toDoubleOrNull()?.let { return JsonPrimitive(it) }

        return JsonPrimitive(literal)
    }

    /**
     * 跳过空白字符与注释（// 或 /* ... */）。
     */
    private fun skipWhitespaceAndComments() {
        while (pos < len) {
            val c = src[pos]
            if (c <= ' ') {
                pos++
            } else if (c == '/' && pos + 1 < len) {
                val next = src[pos + 1]
                if (next == '/') {
                    // 行注释
                    pos += 2
                    while (pos < len && src[pos] != '\n' && src[pos] != '\r') {
                        pos++
                    }
                } else if (next == '*') {
                    // 块注释
                    pos += 2
                    var closed = false
                    while (pos + 1 < len) {
                        if (src[pos] == '*' && src[pos + 1] == '/') {
                            pos += 2
                            closed = true
                            break
                        }
                        pos++
                    }
                    if (!closed) pos = len
                } else {
                    break
                }
            } else {
                break
            }
        }
    }
}
