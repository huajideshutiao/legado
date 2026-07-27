package io.legado.app.data.entities

import io.legado.app.utils.KS_JSON
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * kotlinx-serialization 版本的 RuleStringAdapter（KMP 化）。
 *
 * 让 BookSource 把 6 个规则字段存成原始 JSON 字符串、走 lazy 反序列化，
 * 同时保持导入导出 JSON 时字段仍是嵌套对象的形状，跨设备书源兼容不破。
 *
 * - serialize：字段已是 JSON 字符串，parse 成 JsonElement 后原样 encode（输出嵌套对象形状）。
 *   空值/空字符串 → encodeNull（对应原 Gson write 的 nullValue 分支）。
 *   原 Gson write 用 jsonValue(value) 不 parse 直接灌流；kotlinx-serialization 无等价"原样灌流"API，
 *   parse+encodeJsonElement 是最佳近似，对合法 JSON 输入语义一致（嵌套对象形状）。
 * - deserialize：兼容嵌套对象、字符串两种形态，统一规整成 JSON 字符串入库。
 *   JsonNull → null；JsonPrimitive（含字符串/数字/布尔）→ 字符串内容（空串返回 null，
 *   复刻原 Gson read 的 takeIf { it.isNotEmpty() }）；JsonObject/JsonArray → element.toString()。
 *
 * 仅支持 JSON 格式（JsonEncoder/JsonDecoder），与原 Gson TypeAdapter 假设 JsonReader/JsonWriter 一致。
 */
@OptIn(ExperimentalSerializationApi::class)
object RuleStringSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.legado.app.data.entities.RuleStringSerializer")

    override fun serialize(encoder: Encoder, value: String?) {
        // 空值或空字符串 → null（对应原 Gson write 的 nullValue 分支）
        if (value.isNullOrEmpty()) {
            encoder.encodeNull()
            return
        }
        // 把 JSON 字符串 parse 成 JsonElement 后原样 encode，输出嵌套对象形状
        // （对应原 Gson write 的 jsonValue(value)）
        val element = KS_JSON.parseToJsonElement(value)
        require(encoder is JsonEncoder) {
            "RuleStringSerializer 仅支持 JSON 格式，当前 Encoder 非 JsonEncoder"
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): String? {
        require(decoder is JsonDecoder) {
            "RuleStringSerializer 仅支持 JSON 格式，当前 Decoder 非 JsonDecoder"
        }
        val element = decoder.decodeJsonElement()
        return when (element) {
            is JsonNull -> null
            // JsonPrimitive 覆盖字符串/数字/布尔：原 Gson read 的 STRING 分支 + else 分支对 NUMBER/BOOLEAN 的 parseReader+toString
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }
            // JsonObject / JsonArray → 嵌套对象序列化为 JSON 字符串入库（对应原 Gson read 的 else 分支 element.toString()）
            else -> element.toString()
        }
    }
}
