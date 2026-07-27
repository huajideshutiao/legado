package io.legado.app.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * Gson Phase D 兼容层: 保留 app 模块原有 GSON API 表面, 内部转发到 kotlinx-serialization。
 *
 * - [GSON]/[GSONStrict]: 别名, 复用 shared 模块的 [KS_JSON]/[KS_JSON_STRICT] 实例
 * - [toJson]/[fromJsonObject]/[fromJsonArray]: 扩展函数, 复刻原 GsonExtensions.shared.kt 的语义
 *
 * 注意: 这是过渡兼容层, 新代码应直接使用 KS_JSON.encodeToString / decodeOrNull / decodeListOrNull。
 *
 * 本文件仅含纯 Kotlin / KMP 标准库依赖, 可下沉 commonMain;
 * 涉及 java.io.OutputStream/Reader/Writer 的流式重载在 jvmAndAndroidMain
 * (见 GsonStreamExtensions.kt)。
 */

// GSON 别名, 让 `import io.legado.app.utils.GSON` + `GSON.xxx` 继续工作
val GSON: Json get() = KS_JSON
val GSONStrict: Json get() = KS_JSON_STRICT

/**
 * 复刻 Gson.toJson(obj): String, 处理任意类型 (包括 Any/Any?/List<*>/Map<*,*>/@Serializable 类)。
 *
 * 非 inline: 使用 [Any?.toJsonElement] 运行时查找 serializer, 避免 reified T 推断失败
 * (如 `MutableMap<String, Any>` 场景)。
 */
fun Json.toJson(obj: Any?): String = obj.toJsonElement().toString()

/** 复刻原 GsonExtensions.shared.kt 的 fromJsonObject<T>(json): Result<T>, 接受 String? */
inline fun <reified T> Json.fromJsonObject(json: String?): Result<T> = runCatching {
    if (json.isNullOrEmpty()) throw SerializationException("json is null or empty")
    decodeFromString<T>(json)
}

/** 复刻原 GsonExtensions.shared.kt 的 fromJsonArray<T>(json): Result<List<T>>, 接受 String? */
inline fun <reified T> Json.fromJsonArray(json: String?): Result<List<T>> = runCatching {
    if (json.isNullOrEmpty()) throw SerializationException("json is null or empty")
    decodeFromString<List<T>>(json)
}

/**
 * 任意对象转 [JsonElement], 供 [ReturnData.toJsonString] 等 Gson 反射序列化场景使用。
 *
 * 复刻 Gson.toJson(obj) 对常见类型的处理:
 * - null/JsonElement/String/Boolean/Number: 对应 JsonPrimitive
 * - ByteArray: Base64 编码字符串 (对齐 Gson 默认行为)
 * - List/Map: 递归处理元素
 * - @Serializable 类: 用运行时 [KClass.serializer] 查找编译器生成的 serializer
 * - 其他: 降级为 toString()
 *
 * Base64 编码: 原 android.util.Base64.encodeToString(this, NO_WRAP) 为标准字母表+padding+不换行,
 * 复用 shared 模块 [EncoderUtils.base64Encode] (默认 NO_WRAP flags, 内部走 kotlin.io.encoding.Base64.Default)。
 */
@OptIn(ExperimentalSerializationApi::class, kotlinx.serialization.InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is ByteArray -> JsonPrimitive(EncoderUtils.base64Encode(this))
    is List<*> -> JsonArray(this.map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(this.entries.associate { it.key.toString() to it.value.toJsonElement() })
    else -> try {
        val serializer = this::class.serializer() as KSerializer<Any>
        KS_JSON.encodeToJsonElement(serializer, this)
    } catch (_: SerializationException) {
        JsonPrimitive(this.toString())
    }
}
