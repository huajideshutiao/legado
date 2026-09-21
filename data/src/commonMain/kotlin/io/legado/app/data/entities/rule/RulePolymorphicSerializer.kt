package io.legado.app.data.entities.rule

import io.legado.app.utils.KS_JSON
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通用 kotlinx-serialization KSerializer，复刻 6 个 rule（SearchRule/BookInfoRule/ContentRule/
 * ExploreRule/ReviewRule/TocRule）的 Gson JsonDeserializer 双形态反序列化语义。
 *
 * 原 Gson 语义（6 个 rule 文件中复制的同款 jsonDeserializer）：
 * ```
 * val jsonDeserializer = JsonDeserializer<XxxRule?> { json, _, _ ->
 *     when {
 *         json.isJsonObject -> INITIAL_GSON.fromJson(json, XxxRule::class.java)
 *         json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, XxxRule::class.java)
 *         else -> null
 *     }
 * }
 * ```
 *
 * 读时（deserialize）：
 * - JsonNull → null（对应原 Gson else 分支对 JsonNull 的处理）
 * - JsonObject → 用 [tSerializer] 反序列化为 T（对应原 Gson isJsonObject 分支）
 * - JsonPrimitive(string) → 先把字符串 parse 成 JsonElement（对应原 Gson json.asString 再 fromJson），
 *   若 parse 结果是 JsonObject 则用 [tSerializer] 反序列化，否则返回 null
 *   （原 Gson 对非对象字符串会抛或返回 null；此处统一返回 null，更健壮，实际输入均为对象字符串）
 * - 其他（JsonArray 等）→ null（对应原 Gson else 分支）
 *
 * 写时（serialize）：null → encodeNull；非空 → 用 [tSerializer] encode
 * （输出嵌套对象形状，与原 Gson 默认序列化一致）。
 *
 * 仅支持 JSON 格式（JsonDecoder），与原 Gson JsonDeserializer 假设 JsonElement 一致。
 * KS_JSON 对应原 INITIAL_GSON（宽松 JSON，忽略未知键），rule 字段全为 String? 不涉及数字策略差异。
 */
@OptIn(ExperimentalSerializationApi::class)
class RulePolymorphicSerializer<T : Any>(
    private val tSerializer: KSerializer<T>
) : KSerializer<T?> {

    override val descriptor: SerialDescriptor = tSerializer.descriptor

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        encoder.encodeSerializableValue(tSerializer, value)
    }

    override fun deserialize(decoder: Decoder): T? {
        require(decoder is JsonDecoder) {
            "RulePolymorphicSerializer 仅支持 JSON 格式，当前 Decoder 非 JsonDecoder"
        }
        val element = decoder.decodeJsonElement()
        return when (element) {
            is JsonNull -> null
            is JsonObject -> KS_JSON.decodeFromJsonElement(tSerializer, element)
            is JsonPrimitive -> {
                // 字符串形态：parse 字符串为 JsonElement，若为 JsonObject 则反序列化，否则返回 null
                val parsed = try {
                    KS_JSON.parseToJsonElement(element.content)
                } catch (_: Exception) {
                    null
                }
                if (parsed is JsonObject) {
                    KS_JSON.decodeFromJsonElement(tSerializer, parsed)
                } else {
                    null
                }
            }
            else -> null  // JsonArray 等不匹配任何 rule 形态，返回 null（对应原 Gson else 分支）
        }
    }
}
