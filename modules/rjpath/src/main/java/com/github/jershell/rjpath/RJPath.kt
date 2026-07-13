package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Path-value pair for getAllWithPath result
 */
data class PathValuePair(
    val path: String,
    val value: JsonElement
)

/**
 * JSONPath implementation according to RFC9535
 */
class RJPath private constructor(
    private val selector: Selector,
    private val options: RJPathOptions = RJPathOptions.Default
) {
    companion object {
        /**
         * Creates JSONPath selector according to RFC9535
         */
        fun selector(path: String, options: RJPathOptions = RJPathOptions.Default): RJPath {
            val selector = SelectorParser(FunctionRegistry(options)).parse(path)
            return RJPath(selector, options)
        }
    }

    /**
     * Finds the first element matching the selector
     */
    fun getFirst(element: JsonElement): JsonElement {
        return getAll(element).firstOrNull() 
            ?: throw NoSuchElementException("No element found for path")
    }

    /**
     * Finds the first element matching the selector, or null
     */
    fun getFirstOrNull(element: JsonElement): JsonElement? {
        return getAll(element).firstOrNull()
    }

    /**
     * Finds all elements matching the selector
     */
    fun getAll(element: JsonElement): List<JsonElement> {
        val rootNode = Node(element, Location.Root)
        return selector.select(rootNode).map { it.value }
    }

    /**
     * Finds all elements matching the selector and returns a list of elements with paths
     */
    fun getAllWithPath(element: JsonElement): List<PathValuePair> {
        val rootNode = Node(element, Location.Root)
        return selector.select(rootNode).map { node ->
            PathValuePair(
                path = node.location.toFullPath(),
                value = node.value
            )
        }
    }

    /**
     * 模拟 jayway json-path read<Any> 语义, 保持向下兼容:
     * - 无匹配返回 null
     * - 单值匹配返回该 JsonElement (不套 List)
     * - 路径直接指向数组时返回数组元素列表 (展开)
     * - 多值匹配返回 List<JsonElement>
     */
    fun read(element: JsonElement): Any? {
        val results = getAll(element)
        return when {
            results.isEmpty() -> null
            results.size == 1 -> {
                val r = results[0]
                //路径直接指向数组: 展开为 List, 与 jayway 自动展开数组的行为一致
                if (r is JsonArray) r.toList() else r
            }
            else -> results
        }
    }
}