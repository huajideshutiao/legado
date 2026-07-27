package org.jsoup.select

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.select.Elements
import kotlin.reflect.KClass

/**
 * Lightweight XPath evaluator for ksoup
 * Supports basic XPath 1.0 patterns used in legado book sources
 */
class XPathEvaluator {

    companion object {
        fun evaluate(xpath: String, element: Element): List<Node> {
            val evaluator = XPathEvaluator()
            return evaluator.eval(xpath, element)
        }

        fun evaluateElements(xpath: String, element: Element): Elements {
            val result = evaluate(xpath, element)
            return Elements(result.filterIsInstance<Element>())
        }
    }

    fun eval(xpath: String, element: Element): List<Node> {
        val trimmed = xpath.trim()

        // Handle attribute selection: path/@attr
        if (trimmed.contains("/@")) {
            val parts = trimmed.split("/@", limit = 2)
            val elementPath = parts[0].ifEmpty { "." }
            val attrName = parts[1]

            val elements = if (elementPath == ".") {
                listOf(element)
            } else {
                selectByPath(elementPath, element)
            }

            return elements.mapNotNull { el ->
                val attrValue = el.attr(attrName)
                if (attrValue.isNotEmpty()) {
                    TextNode(attrValue)
                } else {
                    null
                }
            }
        }

        // Handle regular element selection
        return selectByPath(trimmed, element)
    }

    private fun selectByPath(path: String, element: Element): List<Element> {
        val trimmed = path.trim()

        //处理开头的 / 或 //
        val isAbsolute = trimmed.startsWith("/")
        val isRecursiveRoot = trimmed.startsWith("//")
        val relativePath = when {
            isRecursiveRoot -> trimmed.substring(2)
            isAbsolute -> trimmed.substring(1)
            else -> trimmed
        }

        val root = if (isAbsolute) element.ownerDocument() ?: element else element

        //按 // 分割路径, 每段是相对路径, 段间为后代递归关系
        //如 "div//a/b" → ["div", "a/b"]
        val segments = relativePath.split("//").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return listOf(root)

        //第一段: 若路径以 // 开头则递归搜索, 否则相对搜索
        var currentNodes = if (isRecursiveRoot) {
            selectDescendants(segments[0], root)
        } else {
            selectRelative(segments[0], root)
        }

        //后续每段都是后代递归搜索
        for (i in 1 until segments.size) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectDescendants(segments[i], node))
            }
            currentNodes = nextNodes
        }

        return currentNodes
    }

    /**
     * 后代递归搜索: 在 element 的所有后代中匹配 segment (segment 可含多级相对路径)
     */
    private fun selectDescendants(segment: String, element: Element): List<Element> {
        val parts = parsePathParts(segment)
        if (parts.isEmpty()) return listOf(element)

        //对第一段做递归后代搜索
        val firstPart = parts[0]
        val matched = when (firstPart) {
            is PathPart.Tag -> element.getElementsByTag(firstPart.name)
            is PathPart.AllChildren -> collectAllDescendants(element)
            is PathPart.TagWithPredicate -> {
                val elements = if (firstPart.tag.isEmpty()) {
                    collectAllDescendants(element)
                } else {
                    element.getElementsByTag(firstPart.tag)
                }
                filterByPredicate(elements, firstPart.predicate)
            }
            is PathPart.Current -> listOf(element)
            is PathPart.Parent -> {
                val parent = element.parent()
                if (parent is Element) listOf(parent) else emptyList()
            }
        }

        //segment 内剩余路径段用相对路径处理 (如 "div/a" 中的 "/a")
        if (parts.size == 1) return matched

        var currentNodes = matched
        for (part in parts.drop(1)) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectPart(part, node))
            }
            currentNodes = nextNodes
        }
        return currentNodes
    }

    /**
     * 递归收集所有后代元素 (不含自身)
     */
    private fun collectAllDescendants(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        for (child in element.children()) {
            result.add(child)
            result.addAll(collectAllDescendants(child))
        }
        return result
    }

    private fun selectRelative(path: String, element: Element): List<Element> {
        if (path.isEmpty()) return listOf(element)

        val parts = parsePathParts(path)
        var currentNodes = listOf(element)

        for (part in parts) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectPart(part, node))
            }
            currentNodes = nextNodes
        }

        return currentNodes
    }

    private fun parsePathParts(path: String): List<PathPart> {
        val parts = mutableListOf<PathPart>()
        val segments = path.split("/").filter { it.isNotEmpty() }

        for (segment in segments) {
            when {
                segment == ".." -> parts.add(PathPart.Parent)
                segment == "." -> parts.add(PathPart.Current)
                segment == "*" -> parts.add(PathPart.AllChildren)
                segment.contains("[") -> {
                    val (tagName, predicate) = parsePredicate(segment)
                    parts.add(PathPart.TagWithPredicate(tagName, predicate))
                }
                else -> parts.add(PathPart.Tag(segment))
            }
        }

        return parts
    }

    private fun parsePredicate(segment: String): Pair<String, String> {
        val bracketStart = segment.indexOf('[')
        val bracketEnd = segment.lastIndexOf(']')

        if (bracketStart == -1 || bracketEnd == -1) {
            return segment to ""
        }

        val tag = segment.substring(0, bracketStart)
        val predicate = segment.substring(bracketStart + 1, bracketEnd)

        return tag to predicate
    }

    private fun selectPart(part: PathPart, element: Element): List<Element> {
        return when (part) {
            is PathPart.Parent -> {
                val parent = element.parent()
                if (parent is Element) listOf(parent) else emptyList()
            }
            is PathPart.Current -> listOf(element)
            is PathPart.AllChildren -> element.children()
            is PathPart.Tag -> element.getElementsByTag(part.name)
            is PathPart.TagWithPredicate -> {
                val elements = if (part.tag.isEmpty()) {
                    element.children()
                } else {
                    element.getElementsByTag(part.tag)
                }
                filterByPredicate(elements, part.predicate)
            }
        }
    }

    private fun filterByPredicate(elements: List<Element>, predicate: String): List<Element> {
        if (predicate.isEmpty()) return elements

        return elements.filter { element ->
            evaluatePredicate(element, predicate)
        }
    }

    private fun evaluatePredicate(element: Element, predicate: String): Boolean {
        // Handle @attr='value' or @attr="value"
        val attrPattern = Regex("""@(\w+)\s*=\s*['"](.+?)['"]""")
        val attrMatch = attrPattern.find(predicate)
        if (attrMatch != null) {
            val attrName = attrMatch.groupValues[1]
            val expectedValue = attrMatch.groupValues[2]
            return element.attr(attrName) == expectedValue
        }

        // Handle @attr (attribute exists)
        val existsPattern = Regex("""@(\w+)""")
        val existsMatch = existsPattern.find(predicate)
        if (existsMatch != null && !predicate.contains("=")) {
            val attrName = existsMatch.groupValues[1]
            return element.hasAttr(attrName)
        }

        // Handle contains(@attr, 'value')
        val containsPattern = Regex("""contains\(@(\w+)\s*,\s*['"](.+?)['"]\)""")
        val containsMatch = containsPattern.find(predicate)
        if (containsMatch != null) {
            val attrName = containsMatch.groupValues[1]
            val expectedValue = containsMatch.groupValues[2]
            return element.attr(attrName).contains(expectedValue)
        }

        // Handle position()=n or last()
        val posPattern = Regex("""position\(\)\s*=\s*(\d+)""")
        val posMatch = posPattern.find(predicate)
        if (posMatch != null) {
            val pos = posMatch.groupValues[1].toIntOrNull() ?: return false
            val index = element.elementSiblingIndex() + 1
            return index == pos
        }

        if (predicate == "last()") {
            val parent = element.parent() ?: return false
            val siblings = parent.children()
            return element == siblings.lastOrNull()
        }

        // Handle numeric index [n]
        val indexPattern = Regex("""^\d+$""")
        if (indexPattern.matches(predicate)) {
            val index = predicate.toIntOrNull() ?: return false
            val parent = element.parent() ?: return false
            val siblings = parent.children()
            return index in siblings.indices && siblings[index] == element
        }

        return true
    }
}

sealed class PathPart {
    object Parent : PathPart()
    object Current : PathPart()
    object AllChildren : PathPart()
    data class Tag(val name: String) : PathPart()
    data class TagWithPredicate(val tag: String, val predicate: String) : PathPart()
}

// Extension function for Element to support selectXpath
fun Element.selectXpath(xpath: String): Elements {
    return XPathEvaluator.evaluateElements(xpath, this)
}

// KClass 版（原 java.lang.Class 版泄漏 JVM 类型, commonMain 不可用）。
// type 仅用于筛节点种类, 现仅 AnalyzeByXPath 以 Element/Node 调用, 结果最终只保留 Element。
fun <T : Node> Element.selectXpath(xpath: String, type: KClass<T>): Elements {
    val result = XPathEvaluator.evaluate(xpath, this)
    val elements = Elements()
    result.forEach { node ->
        if (node is Element && type.isInstance(node)) {
            elements.add(node)
        }
    }
    return elements
}
