package com.github.catvod.utils

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject

/** TVBox 壳 JSON 工具 (API 面对齐 FongMi catvod 模块, 返回 Gson 类型; 宿主须带 Gson 依赖)。 */
object Json {

    @JvmStatic
    fun parse(json: String): JsonElement = JsonParser.parseString(json)

    @JvmStatic
    fun isObj(text: String?): Boolean = try {
        if (text.isNullOrEmpty()) false else {
            JSONObject(text)
            true
        }
    } catch (e: Exception) {
        false
    }

    @JvmStatic
    fun isArray(text: String?): Boolean = try {
        if (text.isNullOrEmpty()) false else {
            JSONArray(text)
            true
        }
    } catch (e: Exception) {
        false
    }

    @JvmStatic
    fun isEmpty(obj: JsonObject, key: String): Boolean {
        if (!obj.has(key)) return true
        val element = obj.get(key)
        if (element.isJsonNull) return true
        if (element.isJsonArray) return element.asJsonArray.isEmpty
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) return element.asString.trim().isEmpty()
        return true
    }

    @JvmStatic
    fun safeString(obj: JsonObject, key: String): String = try {
        obj.getAsJsonPrimitive(key).asString.trim()
    } catch (e: Exception) {
        ""
    }

    @JvmStatic
    fun safeListString(obj: JsonObject, key: String): List<String> {
        val result = ArrayList<String>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(safeString(obj, key))
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asString)
        return result
    }

    @JvmStatic
    fun safeListElement(obj: JsonObject, key: String): List<JsonElement> {
        val result = ArrayList<JsonElement>()
        if (!obj.has(key)) return result
        if (obj.get(key).isJsonObject) result.add(obj.get(key).asJsonObject)
        else for (opt in obj.getAsJsonArray(key)) result.add(opt.asJsonObject)
        return result
    }

    @JvmStatic
    fun safeObject(element: JsonElement): JsonObject {
        var item = element
        return try {
            if (item.isJsonPrimitive) item = parse(item.asJsonPrimitive.asString)
            item.asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
    }

    @JvmStatic
    fun toMap(json: String): Map<String, String>? =
        if (json.isNullOrEmpty()) null else toMap(parse(json))

    @JvmStatic
    fun toMap(element: JsonElement?): Map<String, String> {
        val map = HashMap<String, String>()
        if (element == null || element.isJsonNull) return map
        val `object` = safeObject(element)
        for ((key, _) in `object`.entrySet()) map[key] = safeString(`object`, key)
        return map
    }
}
