package io.legado.app.data.entities

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * 让 BookSource 把 6 个规则字段存成原始 JSON 字符串、走 lazy 反序列化，
 * 同时保持导入导出 JSON 时字段仍是嵌套对象的形状，跨设备书源兼容不破。
 *
 * - write：字段已经是 JSON 字符串，用 jsonValue 原样灌进流，避免 parse+re-serialize。
 * - read：兼容嵌套对象、字符串两种形态，统一规整成 JSON 字符串入库。
 */
class RuleStringAdapter : TypeAdapter<String?>() {

    override fun write(out: JsonWriter, value: String?) {
        if (value.isNullOrEmpty()) {
            out.nullValue()
            return
        }
        out.jsonValue(value)
    }

    override fun read(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }

            JsonToken.STRING -> reader.nextString().takeIf { it.isNotEmpty() }
            else -> {
                val element = JsonParser.parseReader(reader)
                if (element.isJsonNull) null else element.toString()
            }
        }
    }
}
