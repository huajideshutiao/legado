package io.legado.app.model.analyzeRule

import com.github.jershell.rjpath.RJPath
import io.legado.app.help.coroutine.printStackTraceOnDebug
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonParser = Json { ignoreUnknownKeys = true }

@Suppress("RegExpRedundantEscape")
// @Keep 移除：shared 无 androidx.annotation，反射保活改由 consumer-rules.pro -keep 登记（照 AnalyzeByXPath 先例）。
class AnalyzeByJSonPath(json: Any) {

    companion object {

        fun parse(json: Any): JsonElement {
            return when (json) {
                is JsonElement -> json
                is String -> jsonParser.parseToJsonElement(json)
                // JS 引擎返回的普通 Map/List 回流(@js→$. 链)时重建。
                // JsonPath 自己的结果保持 JsonElement, 不在此处提前解包。
                is Map<*, *>, is List<*> -> anyToElement(json)
                else -> jsonParser.parseToJsonElement(json.toString())
            }
        }

        /**
         * JSONPath 规则有时会作用于登录页/防爬页等 HTML 响应。
         * 这类输入不是 JSON，应按“无匹配”处理，不能在构造解析器时把整条请求协程打崩。
         */
        private fun parseOrNull(json: Any): JsonElement? {
            return try {
                parse(json)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Map/List/基本类型 递归重建为 JsonElement, 供解包结果回流再查询。
         */
        private fun anyToElement(value: Any?): JsonElement {
            return when (value) {
                null -> JsonNull
                is JsonElement -> value
                is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
                    k.toString() to anyToElement(v)
                })
                is List<*> -> JsonArray(value.map { anyToElement(it) })
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> jsonParser.parseToJsonElement(value.toString())
            }
        }
    }

    // HTML 错误页/登录页误套 JSONPath 时，按空结果处理；合法 JSON 的行为不变。
    private val parsedElement: JsonElement? = parseOrNull(json)

    /**
     * 改进解析方法
     * 解决阅读"&&"、"||"与jsonPath支持的"&&"、"||"之间的冲突
     * 解决{$.rule}形式规则可能匹配错误的问题，旧规则用正则解析内容含'}'的json文本时，用规则中的字段去匹配这种内容会匹配错误.现改用平衡嵌套方法解决这个问题
     * */
    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        val element = parsedElement ?: return null
        var result: String
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||")

        if (rules.size == 1) {

            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器

            result = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}

            if (result.isEmpty()) { //st为空，表明无成功替换的内嵌规则
                try {
                    //保持 jayway read 语义: 单值直接返回, 数组才 joinToString
                    val results = RJPath.selector(rule).getAll(element)
                    result = when {
                        results.isEmpty() -> ""
                        //单值: 不强制套数组, 直接取该元素
                        results.size == 1 -> elementToString(results[0])
                        //多值: 各自转换后 joinToString
                        else -> results.joinToString("\n") { elementToString(it) }
                    }
                } catch (e: Exception) {
                    e.printStackTraceOnDebug()
                }
            }
            return result
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    /**
     * 将 JsonElement 转为字符串, 保持 jayway read 行为:
     * 基本类型取字面值, 数组展开元素后 joinToString, 对象取 JSON 字符串
     */
    private fun elementToString(element: JsonElement): String {
        return when (element) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString("\n") { item ->
                (item as? JsonPrimitive)?.content ?: item.toString()
            }
            else -> element.toString()
        }
    }

    fun getStringList(rule: String): List<String> {
        val result = ArrayList<String>()
        if (rule.isEmpty()) return result
        val element = parsedElement ?: return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器
            val st = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}
            if (st.isEmpty()) { //st为空，表明无成功替换的内嵌规则
                try {
                    //保持 jayway read 语义: 单值作为整体加入, 数组才展开逐个加入
                    val results = RJPath.selector(rule).getAll(element)
                    for (r in results) {
                        when (r) {
                            is JsonPrimitive -> result.add(r.content)
                            is JsonArray -> {
                                for (item in r) {
                                    result.add((item as? JsonPrimitive)?.content ?: item.toString())
                                }
                            }
                            else -> result.add(r.toString())
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTraceOnDebug()
                }
            } else {
                result.add(st)
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, result)
            }
            return result
        }
    }

    fun getObject(rule: String): Any? {
        val element = parsedElement ?: return null
        return try {
            // 对齐 jayway: ctx.read(rule) 直接返回, null 就是 null。
            // 结果保持 JsonElement, 类型转换延迟到 JS 胶水层。
            RJPath.selector(rule).read(element)
        } catch (e: Exception) {
            e.printStackTraceOnDebug()
            null
        }
    }

    fun getList(rule: String): ArrayList<Any> {
        val result = ArrayList<Any>()
        if (rule.isEmpty()) return result
        val element = parsedElement ?: return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            try {
                //使用 rules[0] 而非 rule, 避免规则含分隔符残留导致解析失败
                val elements = RJPath.selector(rules[0]).getAll(element)
                val resultList = ArrayList<Any?>()
                for (item in elements) {
                    when (item) {
                        is JsonArray -> {
                            //路径直接指向数组时展开元素, 与 jayway read 行为一致
                            resultList.addAll(item)
                        }
                        // JsonNull/JsonPrimitive/JsonObject 均保持原生 JsonElement。
                        // 进入 JS 时再由 JsonElementJsConverter 转成 JS 原生值。
                        else -> resultList.add(item)
                    }
                }
                @Suppress("UNCHECKED_CAST")
                return resultList as ArrayList<Any>
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }
        } else {
            val results = ArrayList<ArrayList<*>>()
            for (rl in rules) {
                val temp = getList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineNullableResults(results, ruleAnalyzes.elementsType, result)
            }
        }
        return result
    }

}
