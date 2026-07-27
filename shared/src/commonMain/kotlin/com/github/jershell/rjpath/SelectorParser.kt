package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonPrimitive

/**
 * JSONPath expression parser according to RFC9535
 */
class SelectorParser(private val functionRegistry: FunctionRegistry = FunctionRegistry(RJPathOptions.Default)) {

    /**
     * Parses JSONPath expression and creates corresponding selector
     */
    fun parse(path: String): Selector {
        val tokens = tokenize(path)
        return parseTokens(tokens)
    }

    private fun tokenize(path: String): List<String> {
        val tokens = mutableListOf<String>()
        var current = StringBuilder()
        var inBrackets = false
        var inQuotes = false
        var escape = false
        var depth = 0 // Track bracket nesting depth for ..[?()]..

        // Initial point - always root selector
        if (path.startsWith("$")) {
            tokens.add("$")
        } else {
            // If path does not start with $, assume it
            tokens.add("$")
            // And immediately add the first token if it exists
        }

        var i = if (path.startsWith("$")) 1 else 0
        while (i < path.length) {
            val char = path[i]
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
                char == '[' && !inQuotes -> {
                    if (current.isNotEmpty() && depth == 0) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                    current.append(char)
                    inBrackets = true
                    depth++
                }
                char == ']' && !inQuotes -> {
                    current.append(char)
                    depth--
                    if (depth == 0) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                        inBrackets = false
                    }
                }
                char == '.' && !inBrackets && !inQuotes -> {
                    // Handling ".."
                    if (i + 1 < path.length && path[i+1] == '.') {
                         if (current.isNotEmpty()) {
                             tokens.add(current.toString())
                             current = StringBuilder()
                         }
                         tokens.add("..")
                         i++ // Skip second dot
                    } else {
                        // Regular dot as separator
                        if (current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current = StringBuilder()
                        }
                        // Don't add the dot as a token
                    }
                }
                 char == '*' && !inBrackets && !inQuotes -> {
                     if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                     }
                     tokens.add("*") // Add * as separate token
                 }
                else -> {
                    current.append(char)
                }
            }
            i++
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        // Remove initial empty token if path did not start with $ and first character was separator
        if (!path.startsWith("$") && tokens.size > 1 && tokens[1] == "") {
            tokens.removeAt(1)
        }
        // Clean up from possible empty tokens due to dots
        return tokens.filter { it.isNotEmpty() }
    }

    private fun parseTokens(tokens: List<String>): Selector {
        val root = CompositeSelectorImpl()
        var currentSelectorChain: CompositeSelector = root

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "$" -> {
                    if (i == 0) {
                         currentSelectorChain.addSelector(RootSelector) 
                    } else {
                         throw IllegalArgumentException("'$' can only appear at the beginning of the path")
                    }
                }
                token.startsWith('[') -> {
                    val content = token.substring(1, token.length - 1).trim()
                    when {
                        content.startsWith('?') -> {
                            // Filter: ?(expression)
                            val filterExpression = content.substring(1, content.length)
                            val filter = parseFilter(filterExpression)
                            currentSelectorChain.addSelector(filter)
                        }
                        content == "*" -> {
                            currentSelectorChain.addSelector(WildcardSelector)
                        }
                        content.contains(":") -> {
                            val parts = content.split(":").map { it.trim() }
                            val start = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val step = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            currentSelectorChain.addSelector(SliceSelector(start, end, step))
                        }
                        content.contains(",") -> {
                             // List of indices or properties
                             val elements = content.split(",").map { it.trim() }
                             val selectors = elements.mapNotNull {
                                 it.toIntOrNull()?.let { index -> ArraySelector(index) } ?: 
                                 if (it.startsWith("'") && it.endsWith("'") || it.startsWith("\"") && it.endsWith("\"")) {
                                     PropertySelector(it.substring(1, it.length - 1))
                                 } else {
                                     PropertySelector(it)
                                 }
                             }
                             currentSelectorChain.addSelector(UnionSelector(selectors))
                        }
                        content.toIntOrNull() != null -> {
                            currentSelectorChain.addSelector(ArraySelector(content.toInt()))
                        }
                         content.startsWith("'") && content.endsWith("'") || content.startsWith("\"") && content.endsWith("\"") -> {
                             currentSelectorChain.addSelector(PropertySelector(content.substring(1, content.length - 1)))
                         }
                        else -> {
                             currentSelectorChain.addSelector(PropertySelector(content))
                        }
                    }
                }
                token == ".." -> {
                    currentSelectorChain.addSelector(RecursiveSelector())
                }
                token == "*" -> {
                    currentSelectorChain.addSelector(WildcardSelector)
                }
                else -> {
                    currentSelectorChain.addSelector(PropertySelector(token))
                }
            }
            i++
        }

        // If after parsing in the root selector there is only one RootSelector, 
        // then the path was simply "$", return the RootSelector itself
        if (root.selectors.size == 1 && root.selectors[0] is RootSelector) {
            return RootSelector
        }

        return root
    }

    private fun parseFilter(filter: String): FilterSelector {
        val expression = parseLogicalOrExpression(filter.trim())
        return FilterSelector(expression)
    }

    // --- Recursive parser for expressions with priority --- 

    // ||
    private fun parseLogicalOrExpression(expression: String): FilterExpression {
        val parts = splitByTopLevelOperator(expression, "||")
        if (parts.size > 1) {
            return parts.map { parseLogicalAndExpression(it) }
                        .reduce { acc, expr -> LogicalExpression.Or(acc, expr) }
        }
        return parseLogicalAndExpression(expression)
    }

    // &&
    private fun parseLogicalAndExpression(expression: String): FilterExpression {
        val parts = splitByTopLevelOperator(expression, "&&")
        if (parts.size > 1) {
            return parts.map { parseComparisonExpression(it) }
                        .reduce { acc, expr -> LogicalExpression.And(acc, expr) }
        }
        return parseComparisonExpression(expression)
    }

    // ==, !=, <, <=, >, >=
    private fun parseComparisonExpression(expression: String): FilterExpression {
        val trimmed = expression.trim()
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return parseLogicalOrExpression(trimmed.substring(1, trimmed.length - 1))
        }
        val operatorMatch = findTopLevelComparisonOperator(trimmed)
        if (operatorMatch != null) {
            val (operator, index) = operatorMatch
            val left = trimmed.substring(0, index).trim()
            val right = trimmed.substring(index + operator.length).trim()
            val leftExpr = parseValueExpression(left)
            val rightExpr = parseValueExpression(right)
            return when (operator) {
                "==" -> ComparisonExpression.Equals(leftExpr, rightExpr)
                "!=" -> ComparisonExpression.NotEquals(leftExpr, rightExpr)
                "<=" -> ComparisonExpression.LessThanOrEquals(leftExpr, rightExpr)
                ">=" -> ComparisonExpression.GreaterThanOrEquals(leftExpr, rightExpr)
                "<" -> ComparisonExpression.LessThan(leftExpr, rightExpr)
                ">" -> ComparisonExpression.GreaterThan(leftExpr, rightExpr)
                else -> throw IllegalArgumentException("Unknown comparison operator: $operator")
            }
        }
        if (trimmed.startsWith("@.")) {
             return PropertyFilterExpression(trimmed.substring(2))
        }
        if (trimmed == "@") {
            return TrueFilterExpression 
        }
        if (trimmed.contains("(") && trimmed.endsWith(")")) {
            val functionName = trimmed.substring(0, trimmed.indexOf("("))
            val args = trimmed.substring(trimmed.indexOf("(") + 1, trimmed.length - 1)
            val function = functionRegistry.get(functionName)
            if (function != null) {
                return FunctionFilterExpression(function, args, this)
            }
        }
        // If this is a string in quotes, remove quotes
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || 
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return StringValueExpression(trimmed.substring(1, trimmed.length - 1))
        }
        // In all other cases, leave as is
        return StringValueExpression(trimmed)
    }

    fun parseValueExpression(expression: String): ValueExpression {
        val trimmed = expression.trim()
        if (trimmed.startsWith("@.")) {
            return PropertyValueExpression(trimmed.substring(2))
        }
        if (trimmed == "@") {
            return NodeValueExpression()
        }
        if (trimmed.contains("(") && trimmed.endsWith(")")) {
            val functionName = trimmed.substring(0, trimmed.indexOf("("))
            val args = trimmed.substring(trimmed.indexOf("(") + 1, trimmed.length - 1)
            val function = functionRegistry.get(functionName)
            if (function != null) {
                return FunctionValueExpression(function, args, this)
            }
        }
        
        // Handling literals
        if (trimmed == "true" || trimmed == "false") {
            return LiteralValueExpression(JsonPrimitive(trimmed.toBoolean()))
        }
        
        // Try to convert to number
        trimmed.toDoubleOrNull()?.let {
            return LiteralValueExpression(JsonPrimitive(it))
        }
        
        // If this is a string in quotes, remove quotes
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || 
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return LiteralValueExpression(JsonPrimitive(trimmed.substring(1, trimmed.length - 1)))
        }
        
        // In all other cases, leave as is
        return LiteralValueExpression(JsonPrimitive(trimmed))
    }

    private fun splitByTopLevelOperator(expression: String, operator: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var inBrackets = 0
        var escape = false
        var i = 0

        while (i < expression.length) {
            val char = expression[i]
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
                !inQuotes && inBrackets == 0 && i + operator.length <= expression.length && 
                expression.substring(i, i + operator.length) == operator -> {
                    result.add(current.toString())
                    current = StringBuilder()
                    i += operator.length - 1
                }
                else -> {
                    current.append(char)
                }
            }
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    public fun findTopLevelComparisonOperator(expression: String): Pair<String, Int>? {
        val operators = listOf("==", "!=", "<=", ">=", "<", ">")
        var inQuotes = false
        var inBrackets = 0
        var escape = false
        var i = 0
        val trimmed = expression.trim()

        // If expression is in brackets, remove them
        val expr = if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            inBrackets = 0 // Reset bracket counter
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }

        while (i < expr.length) {
            val char = expr[i]
            when {
                escape -> {
                    escape = false
                }
                char == '\\' -> {
                    escape = true
                }
                char == '"' -> {
                    inQuotes = !inQuotes
                }
                char == '(' && !inQuotes -> {
                    inBrackets++
                }
                char == ')' && !inQuotes -> {
                    inBrackets--
                }
                !inQuotes && inBrackets == 0 -> {
                    for (op in operators) {
                        if (i + op.length <= expr.length && 
                            expr.substring(i, i + op.length) == op) {
                            // If expression was in brackets, add 1 to index
                            val offset = if (trimmed != expr) 1 else 0
                            return Pair(op, i + offset)
                        }
                    }
                }
            }
            i++
        }
        return null
    }
} 