package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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

    override fun evaluate(args: List<JsonElement>): JsonElement {
        require(args.size == 1) { "length() expects exactly one argument" }
        val value = args[0]
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
            else -> {
                JsonPrimitive(0.0)
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

    override fun evaluate(args: List<JsonElement>): JsonElement {
        require(args.size == 1) { "count() expects exactly one argument" }
        val value = args[0]
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

    override fun evaluate(args: List<JsonElement>): JsonElement {
        require(args.size == 2) { "match() expects exactly two arguments" }
        val (str, pattern) = args.map { it.jsonPrimitive.content }
        return JsonPrimitive(options.regexMatchMode.matches(Regex(pattern), str))
    }
}

/**
 * Function for searching substring
 */
class SearchFunction : FunctionExtension {
    override val name: String = "search"

    override fun evaluate(args: List<JsonElement>): JsonElement {
        require(args.size == 2) { "search() expects exactly two arguments" }
        val (str, substr) = args.map { it.jsonPrimitive.content }
        return JsonPrimitive(str.contains(substr))
    }
}

/**
 * Function for getting value
 */
class ValueFunction : FunctionExtension {
    override val name: String = "value"

    override fun evaluate(args: List<JsonElement>): JsonElement {
        require(args.size == 1) { "value() expects exactly one argument" }
        return args[0]
    }
} 