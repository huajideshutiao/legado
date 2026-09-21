package io.legado.app.utils

import com.github.jershell.rjpath.RJPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

val jsonPath: Json
    get() = json

fun parseJsonElement(text: String): JsonElement = json.parseToJsonElement(text)

fun JsonElement.readString(path: String): String? {
    return try {
        val result = RJPath.selector(path).getAll(this)
        val textList = ArrayList<String>()
        for (r in result) {
            when (r) {
                is JsonPrimitive -> textList.add(r.content)
                is JsonArray -> {
                    for (item in r) {
                        textList.add((item as? JsonPrimitive)?.content ?: item.toString())
                    }
                }
                else -> textList.add(r.toString())
            }
        }
        when {
            textList.isEmpty() -> null
            textList.size == 1 -> textList[0]
            else -> textList.joinToString("\n")
        }
    } catch (_: Exception) {
        null
    }
}

fun JsonElement.readBool(path: String): Boolean? {
    return try {
        val result = RJPath.selector(path).getFirstOrNull(this)
        (result as? JsonPrimitive)?.let { primitive ->
            //兼容 jayway 类型转换: 数字 1/0 转 true/false, 字符串 "true"/"false" 直取
            primitive.booleanOrNull ?: when (primitive.content) {
                "1" -> true
                "0" -> false
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }
}

fun JsonElement.readInt(path: String): Int? {
    return try {
        val result = RJPath.selector(path).getFirstOrNull(this)
        (result as? JsonPrimitive)?.intOrNull
    } catch (_: Exception) {
        null
    }
}

fun JsonElement.readLong(path: String): Long? {
    return try {
        val result = RJPath.selector(path).getFirstOrNull(this)
        (result as? JsonPrimitive)?.longOrNull
    } catch (_: Exception) {
        null
    }
}

fun JsonElement.readList(path: String): List<JsonElement> {
    return try {
        RJPath.selector(path).getAll(this)
    } catch (_: Exception) {
        emptyList()
    }
}

fun JsonElement.readJsonString(path: String): String? {
    return try {
        val result = RJPath.selector(path).getFirstOrNull(this)
        result?.toString()
    } catch (_: Exception) {
        null
    }
}
