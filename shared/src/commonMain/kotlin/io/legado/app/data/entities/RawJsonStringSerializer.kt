package io.legado.app.data.entities

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * kotlinx-serialization 版本的 GSON `StringJsonDeserializer`（原 GsonExtensions 里
 * `registerTypeAdapter(String::class.java, StringJsonDeserializer())` 的全局注册）。
 *
 * 原 GSON 对所有 String 字段做: primitive → asString, 其余(对象/数组) → toString()。
 * 下沉到 KS 后没有全局 String 适配器, loginUi 这类"值可能是 JSON 数组"的字段会直接
 * 反序列化失败, 需要在字段上显式挂本序列化器补回 (等价原 `readJsonString("$.loginUi")`)。
 */
object RawJsonStringSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.legado.app.data.entities.RawJsonString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        require(decoder is JsonDecoder) {
            "RawJsonStringSerializer 仅支持 JSON 格式，当前 Decoder 非 JsonDecoder"
        }
        return when (val element = decoder.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }
}
