package io.legado.app.model.analyzeRule

import com.script.jsdispatch.JsValueConverter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [JsonElement] → JS 原生值的转换器。
 *
 * JsonPath 层 (AnalyzeByJSonPath) 保持返回原生 JsonElement 不丢类型;
 * 只有对象真正跨入 JS 边界时, 才由本转换器递归映射为 JS 值模型:
 *
 * | JsonElement          | JS 值      |
 * |----------------------|------------|
 * | JsonNull             | null       |
 * | JsonPrimitive string | String     |
 * | JsonPrimitive number | Long/Double|
 * | JsonPrimitive bool   | Boolean    |
 * | JsonArray            | List       |
 * | JsonObject           | LinkedHashMap (保序) |
 *
 * 注册由 quickjs 引擎在入 JS 边界统一调用 (bindings 注入 + Java 方法返回)。
 */
object JsonElementJsConverter : JsValueConverter {

    override fun convert(value: Any): Any? = value.toJsValueIfNeeded().value

    /**
     * `getObject()` 顶层会直接返回 JsonElement；`getList()` 则返回包含 JsonElement 的 List。
     * 两条路径都必须在同一个 JS 边界完成深度转换。普通业务 List/Map 若不含 JsonElement
     * 则原样返回，避免把既有 Java 集合桥接语义误改成 JS plain object/array。
     */
    private fun Any?.toJsValueIfNeeded(): Conversion = when (this) {
        is JsonElement -> Conversion(toJsValue(), true)
        is List<*> -> {
            var changed = false
            val converted = map { item ->
                item.toJsValueIfNeeded().also { changed = changed || it.changed }.value
            }
            if (changed) Conversion(converted, true) else Conversion(this, false)
        }

        is Map<*, *> -> {
            var changed = false
            val converted = LinkedHashMap<Any?, Any?>(size)
            for ((key, item) in this) {
                val itemConversion = item.toJsValueIfNeeded()
                changed = changed || itemConversion.changed
                converted[key] = itemConversion.value
            }
            if (changed) Conversion(converted, true) else Conversion(this, false)
        }

        else -> Conversion(this, false)
    }

    private fun JsonElement.toJsValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            content == "true" -> true
            content == "false" -> false
            else -> content.toLongOrNull()
                ?: content.toDoubleOrNull()
                ?: content
        }

        is JsonArray -> map { it.toJsValue() }
        is JsonObject -> entries.associateTo(LinkedHashMap()) { (k, v) -> k to v.toJsValue() }
    }

    private data class Conversion(val value: Any?, val changed: Boolean)
}
