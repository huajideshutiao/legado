package io.legado.app.model.analyzeRule

import io.legado.app.utils.AnyMapSerializer
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * AnalyzeUrlCore.UrlOption 的自定义 KSerializer, 复刻原 Gson jsonDeserializer/jsonSerializer 语义。
 *
 * **背景**: UrlOption 字段有自定义 setter (`field = value?.ifBlank { null }`),
 * 与 @Serializable 默认行为冲突, 故不能直接 @Serializable, 需自定义 KSerializer。
 *
 * **读时 (deserialize)**, 复刻原 Gson jsonDeserializer:
 * - 字符串字段 (method/charset/origin/type/webJs/js): 仅接受 JsonPrimitive(string), 空串归 null (对应 setter `ifBlank { null }`)
 * - 数字字段 (retry: Int?/serverID: Long?/webViewDelayTime: Long?): flexNumber 接受 number 或 string(数字字符串),
 *   toLongOrNull 优先, 否则 toDoubleOrNull, 都失败返回 null; .toInt()/.toLong() 由调用方按字段类型转换
 * - useWebView: flexBool 接受 boolean / string("" 或 "false" 视为 false, 其他字符串视为 true) / 其他对象视为 true
 *   (默认 false, 不存在返回 false)
 * - body: JsonNull → null; JsonPrimitive → 字符串内容 (空串归 null); JsonObject/JsonArray → element.toString()
 * - headers: JsonNull → null; JsonObject → 用 [AnyMapSerializer] 解析为 Map<String, Any?> (数字策略 LONG_OR_DOUBLE);
 *   JsonPrimitive(string) → parse 字符串为 JsonElement, 若为 JsonObject 则用 AnyMapSerializer 解析, 否则 null;
 *   其他 → null
 *
 * **写时 (serialize)**, 复刻原 Gson jsonSerializer:
 * - 字符串字段空值省略 (对应原 `src.xxx?.let { addProperty(...) }`)
 * - useWebView 仅 true 时输出 (默认 false 不输出)
 * - body 尝试 parse 为 JsonObject/JsonArray, 成功则还原为容器 (输出嵌套结构), 否则作为字符串
 * - headers 用 [AnyMapSerializer] 序列化为 JsonObject (对齐原 ctx.serialize 行为)
 *
 * **数字策略**: 与原 Gson 一致, 解析时 integer 取 Long, decimal 取 Double (AnyMapSerializer 复刻 MapDeserializerDoubleAsIntFix);
 * 序列化时 Long/Double 直接对应 JsonPrimitive(number), 不做特殊转换。
 *
 * **JSON 字段名**: 与原 Gson 一致 (method/charset/origin/type/webJs/js/retry/serverID/webViewDelayTime/webView/body/headers),
 * 字段名集合改前后零 diff (Room schema 不涉及, UrlOption 不入库)。
 *
 * 仅支持 JSON 格式 (JsonEncoder/JsonDecoder), 与原 Gson TypeAdapter 假设 JsonReader/JsonWriter 一致。
 *
 * **commonMain 下沉说明**: 纯 kotlinx.serialization 实现, 无 JVM 依赖, 直接从 jvmAndAndroidMain 迁移。
 */
@OptIn(ExperimentalSerializationApi::class)
object UrlOptionSerializer : KSerializer<AnalyzeUrlCore.UrlOption> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.legado.app.model.analyzeRule.UrlOption")

    override fun serialize(encoder: Encoder, value: AnalyzeUrlCore.UrlOption) {
        require(encoder is JsonEncoder) {
            "UrlOptionSerializer 仅支持 JSON 格式, 当前 Encoder 非 JsonEncoder"
        }
        val obj = buildJsonObject {
            value.method?.let { put("method", it) }
            value.charset?.let { put("charset", it) }
            value.origin?.let { put("origin", it) }
            value.type?.let { put("type", it) }
            value.webJs?.let { put("webJs", it) }
            value.js?.let { put("js", it) }
            value.retry?.let { put("retry", it) }
            value.serverID?.let { put("serverID", it) }
            value.webViewDelayTime?.let { put("webViewDelayTime", it) }
            // useWebView 仅 true 时输出 (默认 false 不输出, 对齐原 Gson jsonSerializer)
            if (value.useWebView) put("webView", true)
            value.body?.let { b ->
                // body 可解析为 JSON 对象/数组时还原为容器 (对应原 parseAsJsonContainer)
                val parsed = try {
                    KS_JSON.parseToJsonElement(b)
                } catch (_: Exception) {
                    null
                }
                when (parsed) {
                    is JsonObject, is JsonArray -> put("body", parsed)
                    else -> put("body", b)
                }
            }
            value.headers?.let { h ->
                // headers 用 AnyMapSerializer 序列化为 JsonObject (对齐原 ctx.serialize 行为)
                val headersElement = KS_JSON.encodeToJsonElement(AnyMapSerializer, h)
                put("headers", headersElement)
            }
        }
        encoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): AnalyzeUrlCore.UrlOption {
        require(decoder is JsonDecoder) {
            "UrlOptionSerializer 仅支持 JSON 格式, 当前 Decoder 非 JsonDecoder"
        }
        val element = decoder.decodeJsonElement()
        if (element !is JsonObject) {
            throw SerializationException("UrlOption 期望 JsonObject, 实际: ${element::class}")
        }
        val obj = element
        return AnalyzeUrlCore.UrlOption().apply {
            method = obj.flexString("method")
            charset = obj.flexString("charset")
            origin = obj.flexString("origin")
            type = obj.flexString("type")
            webJs = obj.flexString("webJs")
            js = obj.flexString("js")
            retry = obj.flexNumber("retry")?.toInt()
            serverID = obj.flexNumber("serverID")?.toLong()
            webViewDelayTime = obj.flexNumber("webViewDelayTime")?.toLong()
            useWebView = obj.flexBool("webView")
            body = obj["body"]?.let { el ->
                when (el) {
                    is JsonNull -> null
                    is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotEmpty() }
                    // JsonObject / JsonArray → element.toString() (对应原 Gson el.toString())
                    else -> el.toString()
                }
            }
            headers = obj["headers"]?.let { el ->
                when (el) {
                    is JsonNull -> null
                    is JsonObject -> KS_JSON.decodeFromJsonElement(AnyMapSerializer, el)
                    is JsonPrimitive -> {
                        // 字符串形态: parse 字符串为 JsonElement, 若为 JsonObject 则解析, 否则 null
                        if (el.isString) {
                            val parsed = try {
                                KS_JSON.parseToJsonElement(el.content)
                            } catch (_: Exception) {
                                null
                            }
                            if (parsed is JsonObject) {
                                KS_JSON.decodeFromJsonElement(AnyMapSerializer, parsed)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    // JsonArray 等不匹配 headers 形态, 返回 null (对应原 Gson else 分支)
                    else -> null
                }
            }
        }
    }

    // 复刻原 Gson jsonDeserializer 中的 flexString/flexNumber/flexBool helper
    private val falseStrings = setOf("", "false")

    /**
     * 仅接受 JsonPrimitive(string), 取字符串内容, 空串归 null (对应 setter `ifBlank { null }`)。
     */
    private fun JsonObject.flexString(key: String): String? {
        val el = this[key] ?: return null
        if (el !is JsonPrimitive || !el.isString) return null
        return el.content.ifBlank { null }
    }

    /**
     * 接受 number 或 string(数字字符串), toLongOrNull 优先, 否则 toDoubleOrNull, 都失败返回 null。
     */
    private fun JsonObject.flexNumber(key: String): Number? {
        val el = this[key] ?: return null
        if (el !is JsonPrimitive) return null
        return when {
            el.isString -> el.content.toLongOrNull() ?: el.content.toDoubleOrNull()
            // number 类型: longOrNull 优先 (整数), 否则 doubleOrNull (小数)
            else -> el.longOrNull ?: el.doubleOrNull
        }
    }

    /**
     * 接受 boolean / string("" 或 "false" 视为 false, 其他字符串视为 true) / 其他对象视为 true。
     * 不存在或 JsonNull 返回 false。
     */
    private fun JsonObject.flexBool(key: String): Boolean {
        val el = this[key] ?: return false
        return when (el) {
            is JsonNull -> false
            is JsonPrimitive -> {
                val bool = el.booleanOrNull
                when {
                    bool != null -> bool
                    el.isString -> el.content.lowercase() !in falseStrings
                    // number 等其他 primitive 视为 true (对齐原 Gson `else -> true`)
                    else -> true
                }
            }
            // object/array 视为 true (对齐原 Gson `else -> true`)
            else -> true
        }
    }
}
