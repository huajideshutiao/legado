package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * `match()` 函数的正则匹配能力 (由宿主注入)。
 *
 * rjpath 是内嵌第三方模块, 不依赖本项目其它模块; 而 JSONPath 的 `match(值, 模式)` 里的模式串
 * 来自书源规则, 在 native 端 `kotlin.text.Regex` 的匹配按迭代次数递归, 病态规则可把线程栈写穿
 * 直接闪退且无法中止。故匹配动作改为可注入 (宿主注册 `RuleRegexes` 实现, 带时间上限),
 * 未注入时退回 `Regex` 直调 (与本参数引入前行为一致)。
 *
 * 是普通 interface 而非 `fun interface`: 两种匹配模式 (ENTIRE/CONTAINS) 语义不同,
 * 各需一个抽象成员, 而 `fun interface` 只允许一个。
 */
interface RegexMatcher {
    /** 全量匹配 (`RegexMatchMode.ENTIRE`)。 */
    fun matchesEntire(input: String, pattern: String): Boolean

    /** 包含匹配 (`RegexMatchMode.CONTAINS`)。 */
    fun containsMatch(input: String, pattern: String): Boolean
}

/**
 * Function registry for JSONPath
 */
class FunctionRegistry(options: RJPathOptions) {
    private val functions = mutableMapOf<String, FunctionExtension>()

    init {
        register(LengthFunction())
        register(CountFunction())
        register(MatchFunction(options))
        register(SearchFunction())
        register(ValueFunction())
    }

    fun register(function: FunctionExtension) {
        functions[function.name] = function
    }

    fun get(name: String): FunctionExtension? = functions[name]
}

/**
 * Function for getting length of string or array
 */
class LengthFunction : FunctionExtension {
    override val name: String = "length"

    override fun evaluate(arguments: List<JsonElement>): JsonElement {
        require(arguments.size == 1) { "length() expects exactly one argument" }
        val value = arguments[0]
        val result = when (value) {
            is JsonPrimitive -> {
                if (value.isString) {
                    JsonPrimitive(value.content.length.toDouble())
                } else {
                    JsonPrimitive(0.0)
                }
            }
            is JsonArray -> {
                JsonPrimitive(value.size.toDouble())
            }
            is JsonObject -> {
                JsonPrimitive(value.size.toDouble())
            }
        }
        return result
    }
}

/**
 * Function for counting elements in array
 */
class CountFunction : FunctionExtension {
    override val name: String = "count"

    override fun evaluate(arguments: List<JsonElement>): JsonElement {
        require(arguments.size == 1) { "count() expects exactly one argument" }
        val value = arguments[0]
        return when (value) {
            is JsonArray -> JsonPrimitive(value.size.toDouble())
            is JsonObject -> JsonPrimitive(value.size.toDouble())
            else -> JsonPrimitive(1.0)
        }
    }
}

/**
 * Function for checking regex pattern match
 */
class MatchFunction(private val options: RJPathOptions) : FunctionExtension {
    override val name: String = "match"

    override fun evaluate(arguments: List<JsonElement>): JsonElement {
        require(arguments.size == 2) { "match() expects exactly two arguments" }
        val (str, pattern) = arguments.map { it.jsonPrimitive.content }
        return JsonPrimitive(options.regexMatchMode.matches(RegexMatchers.get(), pattern, str))
    }
}

/**
 * Function for searching substring
 */
class SearchFunction : FunctionExtension {
    override val name: String = "search"

    override fun evaluate(arguments: List<JsonElement>): JsonElement {
        require(arguments.size == 2) { "search() expects exactly two arguments" }
        val (str, substr) = arguments.map { it.jsonPrimitive.content }
        return JsonPrimitive(str.contains(substr))
    }
}

/**
 * Function for getting value
 */
class ValueFunction : FunctionExtension {
    override val name: String = "value"

    override fun evaluate(arguments: List<JsonElement>): JsonElement {
        require(arguments.size == 1) { "value() expects exactly one argument" }
        return arguments[0]
    }
} 