package com.github.jershell.rjpath

import kotlinx.serialization.json.*
import kotlin.collections.iterator

/**
 * Root selector
 */
object RootSelector : Selector {
    override fun select(node: Node): List<Node> = listOf(node)
}

/**
 * Represents a slice operation on an array.
 *
 * @property start The starting index of the slice (inclusive). Defaults to 0.
 * @property end The ending index of the slice (exclusive). Defaults to the end of the array.
 * @property step The step size for the slice. Defaults to 1.
 */
class SliceSelector(private val start: Int?, private val end: Int?, private val step: Int? = 1) : Selector {
    override fun select(node: Node): List<Node> {
        val value = node.value
        if (value !is JsonArray) return emptyList()

        val actualStep = step ?: 1
        if (actualStep == 0) {
            throw IllegalArgumentException("Step cannot be zero")
        }

        val size = value.size
        var actualStart = start ?: if (actualStep > 0) 0 else size - 1
        var actualEnd = end ?: if (actualStep > 0) size else -1 // Exclusive end for positive step, inclusive for negative

        // Handle negative indices
        if (actualStart < 0) actualStart += size
        if (actualEnd < 0) actualEnd += size

        val result = mutableListOf<Node>()
        var i = actualStart

        if (actualStep > 0) {
            while (i < actualEnd && i < size) {
                 if (i >= 0) { // Ensure index is within bounds after potential negative start calculation
                     result.add(Node(value[i], Location.Index(i, node.location)))
                 }
                i += actualStep
            }
        } else { // Negative step
             // Adjust end condition for negative step
             val negativeEndCondition = end ?: -1 // if end was null, we iterate down to index 0
             while (i > negativeEndCondition && i >= 0) {
                 if (i < size) { // Ensure index is within bounds
                     result.add(Node(value[i], Location.Index(i, node.location)))
                 }
                 i += actualStep
            }
        }

        return result
    }
}

/**
 * Filter selector for elements
 */
class FilterSelector(
    private val expression: FilterExpression
) : Selector {
    override fun select(node: Node): List<Node> {
        val value = node.value
        return when (value) {
            is JsonArray -> value.mapIndexed { index, element ->
                Node(element, Location.Index(index, node.location))
            }.filter { expression.evaluate(it) }
            is JsonObject -> value.entries.map { (name, element) ->
                Node(element, Location.Property(name, node.location))
            }.filter { 
                // check only objects
                it.value is JsonObject && expression.evaluate(it)
            }
            is JsonPrimitive -> emptyList()
            is JsonNull -> emptyList()
            else -> emptyList()
        }
    }
}

/**
 * Selector for all elements
 */
object WildcardSelector : Selector {
    override fun select(node: Node): List<Node> {
        val value = node.value
        return when (value) {
            is JsonArray -> value.mapIndexed { index, element ->
                Node(element, Location.Index(index, node.location))
            }
            is JsonObject -> value.entries.map { (name, element) ->
                Node(element, Location.Property(name, node.location))
            }
            is JsonPrimitive -> emptyList()
            is JsonNull -> emptyList()
            else -> emptyList()
        }
    }
}

/**
 * Base class for composite selectors
 */
abstract class CompositeSelector : Selector {
    internal val selectors = mutableListOf<Selector>() // Changed to internal

    fun addSelector(selector: Selector) {
        selectors.add(selector)
    }

    override fun select(node: Node): List<Node> {
        var currentNodes = listOf(node)
        for (selector in selectors) {
            currentNodes = if (selector is RecursiveSelector) {
                // For RecursiveSelector, we collect all descendants AND the nodes themselves
                // to which it applies, and apply the next selector to all of them.
                val descendants = currentNodes.flatMap { collectDescendantsAndSelf(it) }
                descendants
                // The next selector will be applied to these descendants in the next iteration
            } else {
                // For other selectors, just apply to current nodes
                currentNodes.flatMap { selector.select(it) }.distinct()
            }
        }
        return currentNodes
    }

    // Helper function for collecting all descendants, including the node itself
    private fun collectDescendantsAndSelf(node: Node): List<Node> {
        val result = mutableListOf(node) // Start with the node itself
        collectDescendantsRecursive(node, result)
        return result
    }

    private fun collectDescendantsRecursive(node: Node, result: MutableList<Node>) {
        val value = node.value
        when (value) {
            is JsonObject -> {
                for ((key, child) in value) {
                    val childNode = Node(child, Location.Property(key, node.location))
                    if (!result.contains(childNode)) { // Avoid cycles/duplicates
                        result.add(childNode)
                        collectDescendantsRecursive(childNode, result)
                    }
                }
            }
            is JsonArray -> {
                for ((index, child) in value.withIndex()) {
                    val childNode = Node(child, Location.Index(index, node.location))
                    if (!result.contains(childNode)) { // Avoid cycles/duplicates
                        result.add(childNode)
                        collectDescendantsRecursive(childNode, result)
                    }
                }
            }
            else -> {}
        }
    }
}

/**
 * Implementation of composite selector
 */
class CompositeSelectorImpl : CompositeSelector()

class ArraySelector(private val index: Int) : Selector {
    override fun select(node: Node): List<Node> {
        val value = node.value
        if (value !is JsonArray) return emptyList()
        // Handle negative indices
        val actualIndex = if (index >= 0) index else value.size + index
        if (actualIndex < 0 || actualIndex >= value.size) return emptyList()
        return listOf(Node(value[actualIndex], Location.Index(actualIndex, node.location)))
    }
}

class PropertySelector(private val name: String) : Selector {
    override fun select(node: Node): List<Node> {
        val value = node.value
        if (value !is JsonObject) return emptyList()
        val property = value[name] ?: return emptyList()
        return listOf(Node(property, Location.Property(name, node.location)))
    }
}

// Recursive descent selector. By itself it doesn't select anything,
// but modifies the behavior of CompositeSelector.
class RecursiveSelector : Selector {
    override fun select(node: Node): List<Node> {
        // Should not be called directly via flatMap in CompositeSelector
        // Its presence is handled specially.
        // Return the node itself so the next selector applies to it,
        // and CompositeSelector will take care of descendants.
        return listOf(node)
    }
}

// Union selector for combining results
class UnionSelector(private val selectors: List<Selector>) : Selector {
    override fun select(node: Node): List<Node> {
        // Apply each inner selector to the SAME input node
        return selectors.flatMap { it.select(node) }.distinct() // distinct is important
    }
}

// Filter that is always true (for [@] expression)
internal object TrueFilterExpression : FilterExpression {
    override fun evaluate(node: Node): Boolean = true
} 