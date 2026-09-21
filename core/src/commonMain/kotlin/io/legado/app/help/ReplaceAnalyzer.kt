package io.legado.app.help

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.parseJsonElement
import io.legado.app.utils.readBool
import io.legado.app.utils.readInt
import io.legado.app.utils.readLong
import io.legado.app.utils.readString
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object ReplaceAnalyzer {

    fun jsonToReplaceRules(json: String): Result<MutableList<ReplaceRule>> {
        return kotlin.runCatching {
            val replaceRules = mutableListOf<ReplaceRule>()
            val jsonElement = parseJsonElement(json)
            val items: JsonArray = jsonElement.jsonArray
            for (item in items) {
                val jsonItem = item.jsonObject
                jsonToReplaceRule(jsonItem.toString()).getOrThrow().let {
                    if (it.isValid()) {
                        replaceRules.add(it)
                    }
                }
            }
            replaceRules
        }
    }

    fun jsonToReplaceRule(json: String): Result<ReplaceRule> {
        return runCatching {
            val replaceRule: ReplaceRule? =
                GSON.fromJsonObject<ReplaceRule>(json.trim()).getOrNull()
            if (replaceRule == null || replaceRule.pattern.isEmpty()) {
                val jsonItem = parseJsonElement(json.trim())
                val rule = ReplaceRule()
                rule.id = jsonItem.readLong("$.id") ?: systemCurrentTimeMillis()
                rule.pattern = jsonItem.readString("$.regex") ?: ""
                if (rule.pattern.isEmpty()) throw NoStackTraceException("格式不对")
                rule.isRegex = jsonItem.readBool("$.isRegex") ?: false
                rule.replacement = jsonItem.readString("$.replacement") ?: ""
                //兼容旧字段: name/replaceSummary, scope/useTo, isEnabled/enable, order/serialNumber
                rule.name = jsonItem.readString("$.name")
                    ?: jsonItem.readString("$.replaceSummary") ?: ""
                rule.isEnabled = jsonItem.readBool("$.isEnabled")
                    ?: jsonItem.readBool("$.enable") ?: true
                rule.order = jsonItem.readInt("$.order")
                    ?: jsonItem.readInt("$.serialNumber") ?: 0
                rule.scope = jsonItem.readString("$.scope")
                    ?: jsonItem.readString("$.useTo")
                rule.group = jsonItem.readString("$.group") ?: ""
                rule
            } else {
                replaceRule
            }
        }
    }

}
