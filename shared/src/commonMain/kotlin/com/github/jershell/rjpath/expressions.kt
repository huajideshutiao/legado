package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.text.iterator

// Helper function for comparing JsonElement
private fun compareJsonElements(left: JsonElement, right: JsonElement): Int? {
    // Handle JsonNull
    if (left is JsonNull || right is JsonNull) {
        return if (left is JsonNull && right is JsonNull) 0 else null // null == null, otherwise incomparable
    }

    if (left is JsonPrimitive && right is JsonPrimitive) {
        // First try to compare as numbers
        val leftDouble = left.content.toDoubleOrNull()
        val rightDouble = right.content.toDoubleOrNull()
        if (leftDouble != null || rightDouble != null) {
            // If one of the values is a number, try to convert both to numbers
            val leftNum = leftDouble ?: 0.0
            val rightNum = rightDouble ?: 0.0
            return leftNum.compareTo(rightNum)
        }

        // Then as boolean values
        val leftBoolean = left.booleanOrNull
        val rightBoolean = right.booleanOrNull
        if (leftBoolean != null && rightBoolean != null) {
            return leftBoolean.compareTo(rightBoolean)
        }

        // Otherwise as strings
        return left.content.compareTo(right.content)
    }

    // Compare arrays and objects
    return when {
        left is JsonArray && right is JsonArray -> {
            if (left.size != right.size) {
                left.size.compareTo(right.size)
            } else {
                // Compare array elements
                for (i in left.indices) {
                    val comparison = compareJsonElements(left[i], right[i])
                    if (comparison != null && comparison != 0) {
                        return comparison
                    }
                }
                0 // Arrays are equal
            }
        }
        left is JsonObject && right is JsonObject -> {
            if (left.size != right.size) {
                left.size.compareTo(right.size)
            } else {
                // Compare object values by keys
                val leftKeys = left.keys.sorted()
                val rightKeys = right.keys.sorted()
                
                // First compare keys
                for (i in leftKeys.indices) {
                    val keyComparison = leftKeys[i].compareTo(rightKeys[i])
                    if (keyComparison != 0) {
                        return keyComparison
                    }
                }
                
                // Then compare values
                for (key in leftKeys) {
                    val comparison = compareJsonElements(left[key]!!, right[key]!!)
                    if (comparison != null && comparison != 0) {
                        return comparison
                    }
                }
                0 // Objects are equal
            }
        }
        else -> null
    }
}

/**
 * Base interface for expressions in filters
 */
interface FilterExpression {
    fun evaluate(node: Node): Boolean
}

/**
 * Base interface for expressions that return values
 */
interface ValueExpression {
    fun evaluate(node: Node): JsonElement
}

/**
 * Expression for accessing the value of a node
 */
class NodeValueExpression : ValueExpression {
    override fun evaluate(node: Node): JsonElement = node.value
}

/**
 * Expression for literal values
 */
class LiteralValueExpression(private val value: JsonElement) : ValueExpression {
    override fun evaluate(node: Node): JsonElement = value
}

/**
 * Expression for calling functions
 */
class FunctionCallExpression(
    private val function: FunctionExtension,
    private val args: List<ValueExpression>
) : ValueExpression {
    override fun evaluate(node: Node): JsonElement {
        val evaluatedArgs = args.map { it.evaluate(node) }
        return function.evaluate(evaluatedArgs)
    }
}

/**
 * Expression for checking the existence of a property
 */
class PropertyExpression(private val name: String) : FilterExpression {
    override fun evaluate(node: Node): Boolean {
        val value = node.value
        if (value !is JsonObject) return false
        return value[name] != null
    }
}

/**
 * Expression for accessing the value of a node property (for use in comparisons)
 */
class PropertyValueExpression(private val propertyName: String) : ValueExpression {
    override fun evaluate(node: Node): JsonElement {
        val value = node.value
        return when (value) {
            is JsonObject -> {
                value[propertyName] ?: JsonNull
            }
            else -> {
                value
            }
        }
    }
}

/**
 * Expression for comparing a string value of a node (deprecated?)
 */
class StringValueExpression(private val value: String) : FilterExpression {
    override fun evaluate(node: Node): Boolean {
        val nodeValue = node.value
        if (nodeValue !is JsonPrimitive) return false
        return nodeValue.jsonPrimitive.content == value
    }
}

/**
 * Comparison expressions
 */
sealed class ComparisonExpression : FilterExpression {
    protected abstract val left: ValueExpression
    protected abstract val right: ValueExpression

    // Calculate operands once
    fun evaluateOperands(node: Node): Pair<JsonElement, JsonElement> {
        return Pair(left.evaluate(node), right.evaluate(node))
    }

    data class Equals(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)
            
            // If both operands are JsonPrimitive
            if (l is JsonPrimitive && r is JsonPrimitive) {
                // First try to compare as numbers
                val leftDouble = l.content.toDoubleOrNull()
                val rightDouble = r.content.toDoubleOrNull()
                if (leftDouble != null && rightDouble != null) {
                    return leftDouble == rightDouble
                }
                
                // Then try to compare as boolean values
                val leftBoolean = l.booleanOrNull
                val rightBoolean = r.booleanOrNull
                if (leftBoolean != null && rightBoolean != null) {
                    return leftBoolean == rightBoolean
                }
                
                // Compare as strings
                return l.content == r.content
            }
            
            // For other types, use standard comparison
            return l == r
        }
    }

    data class NotEquals(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)
            return l != r
        }
    }

    data class LessThan(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)
            // If both operands are JsonPrimitive

            if (l is JsonPrimitive && r is JsonPrimitive) {
                // First try to compare as numbers
                val leftDouble = l.content.toDoubleOrNull()
                val rightDouble = r.content.toDoubleOrNull()
                if (leftDouble != null && rightDouble != null) {
                    return leftDouble < rightDouble
                }
                
                // Then as boolean values
                val leftBoolean = l.booleanOrNull
                val rightBoolean = r.booleanOrNull
                if (leftBoolean != null && rightBoolean != null) {
                    return leftBoolean.compareTo(rightBoolean) < 0
                }
                
                // Otherwise as strings
                return l.content < r.content
            }
            
            // For other types, use compareJsonElements
            val result = compareJsonElements(l, r)?.let { it < 0 } ?: false
            return result
        }
    }

    data class GreaterThan(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)

            // If both operands are JsonPrimitive
            if (l is JsonPrimitive && r is JsonPrimitive) {
                // First try to compare as numbers
                val leftDouble = l.content.toDoubleOrNull()
                val rightDouble = r.content.toDoubleOrNull()
                if (leftDouble != null && rightDouble != null) {
                    return leftDouble > rightDouble
                }
                
                // Then as boolean values
                val leftBoolean = l.booleanOrNull
                val rightBoolean = r.booleanOrNull
                if (leftBoolean != null && rightBoolean != null) {
                    return leftBoolean.compareTo(rightBoolean) > 0
                }
                
                // Otherwise as strings
                return l.content > r.content
            }
            
            // For other types, use compareJsonElements
            val result = compareJsonElements(l, r)?.let { it > 0 } ?: false
            return result
        }
    }

    data class LessThanOrEquals(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)
            // Check equality separately, since compareJsonElements may return null for incomparable but equal (e.g., two empty objects)
            if (l == r) return true
            return compareJsonElements(l, r)?.let { it <= 0 } ?: false
        }
    }

    data class GreaterThanOrEquals(override val left: ValueExpression, override val right: ValueExpression) : ComparisonExpression() {
        override fun evaluate(node: Node): Boolean {
            val (l, r) = evaluateOperands(node)
            if (l == r) return true
            return compareJsonElements(l, r)?.let { it >= 0 } ?: false
        }
    }
}

/**
 * Logical expressions
 */
sealed class LogicalExpression : FilterExpression {
    protected abstract val left: FilterExpression
    protected abstract val right: FilterExpression

    data class And(override val left: FilterExpression, override val right: FilterExpression) : LogicalExpression() {
        override fun evaluate(node: Node): Boolean = left.evaluate(node) && right.evaluate(node)
    }

    data class Or(override val left: FilterExpression, override val right: FilterExpression) : LogicalExpression() {
        override fun evaluate(node: Node): Boolean = left.evaluate(node) || right.evaluate(node)
    }

    data class Not(val expression: FilterExpression) : LogicalExpression() {
        // Not does not have left/right in the traditional sense, but for compatibility with sealed class:
        override val left: FilterExpression get() = expression
        override val right: FilterExpression get() = expression
        override fun evaluate(node: Node): Boolean = !expression.evaluate(node)
    }
}

// Add class FunctionValueExpression
class FunctionValueExpression(private val function: FunctionExtension, private val args: String, private val parser: SelectorParser) : ValueExpression {
    override fun evaluate(node: Node): JsonElement {
        val evaluatedArgs = parseFunctionArgs(args).map { arg ->
            val trimmedArg = arg.trim()
            val expr = if (trimmedArg.startsWith("@.")) {
                parser.parseValueExpression(trimmedArg)
            } else {
                parser.parseValueExpression(trimmedArg)
            }
            expr.evaluate(node)
        }
        return function.evaluate(evaluatedArgs)
    }

    private fun parseFunctionArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var inBrackets = 0
        var escape = false

        for (char in args) {
            when {
                escape -> {
                    current.append(char)
                    escape = false
                }
                char == '\\' -> {
                    current.append(char)
                    escape = true
                }
                char == '"' -> {
                    current.append(char)
                    inQuotes = !inQuotes
                }
                char == '(' && !inQuotes -> {
                    current.append(char)
                    inBrackets++
                }
                char == ')' && !inQuotes -> {
                    current.append(char)
                    inBrackets--
                }
                char == ',' && !inQuotes && inBrackets == 0 -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> {
                    current.append(char)
                }
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }
}

// Add class FunctionFilterExpression
class FunctionFilterExpression(private val function: FunctionExtension, private val args: String, private val parser: SelectorParser) : FilterExpression {
    override fun evaluate(node: Node): Boolean {
        val evaluatedArgs = parseFunctionArgs(args).map { arg ->
            val expr = parser.parseValueExpression(arg.trim())
            val result = expr.evaluate(node)
            result
        }
        val result = function.evaluate(evaluatedArgs)
        val finalResult = when {
            result is JsonPrimitive && result.isString -> {
                val res = result.content.isNotEmpty()
                res
            }
            result is JsonPrimitive && result.booleanOrNull != null -> {
                val res = result.booleanOrNull!!
                res
            }
            result is JsonPrimitive -> {
                // Try to convert to number
                val res = result.content.toDoubleOrNull()?.let { it != 0.0 } ?: false
                res
            }
            result is JsonArray -> {
                val res = result.isNotEmpty()
                res
            }
            result is JsonObject -> {
                val res = result.isNotEmpty()
                res
            }
            else -> {
                false
            }
        }
        return finalResult
    }

    private fun parseFunctionArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var inBrackets = 0
        var escape = false

        for (char in args) {
            when {
                escape -> {
                    current.append(char)
                    escape = false
                }
                char == '\\' -> {
                    current.append(char)
                    escape = true
                }
                char == '"' -> {
                    current.append(char)
                    inQuotes = !inQuotes
                }
                char == '\'' -> {
                    current.append(char)
                    inQuotes = !inQuotes
                }
                char == '(' && !inQuotes -> {
                    current.append(char)
                    inBrackets++
                }
                char == ')' && !inQuotes -> {
                    current.append(char)
                    inBrackets--
                }
                char == ',' && !inQuotes && inBrackets == 0 -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> {
                    current.append(char)
                }
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result
    }
}

/**
 * Expression for filtering by property value
 */
class PropertyFilterExpression(private val propertyName: String) : FilterExpression {
    private val valueExpression = PropertyValueExpression(propertyName)
    
    override fun evaluate(node: Node): Boolean {
        val value = valueExpression.evaluate(node)
        return when {
            value is JsonPrimitive && value.isString -> value.content.isNotEmpty()
            value is JsonPrimitive && value.booleanOrNull != null -> value.booleanOrNull!!
            value is JsonPrimitive -> value.content.toDoubleOrNull()?.let { it != 0.0 } ?: false
            value is JsonArray -> value.isNotEmpty()
            value is JsonObject -> value.isNotEmpty()
            else -> false
        }
    }
} 