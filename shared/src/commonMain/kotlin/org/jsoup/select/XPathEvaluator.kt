package org.jsoup.select

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.select.Elements
import io.legado.app.utils.scan.BalanceScan
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
                filterByPredicate(elements, firstPart.predicates)
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
                    val (tagName, predicates) = parsePredicates(segment)
                    parts.add(PathPart.TagWithPredicate(tagName, predicates))
                }
                else -> parts.add(PathPart.Tag(segment))
            }
        }

        return parts
    }

    /**
     * 切出标签名与它的**全部**顶层谓词。
     *
     * 谓词边界用计数式 [BalanceScan.chomp] 逐个拉出，并把连续的多个谓词（`div[a][b]`）全部
     * 收集后求交——这才是 archive 原版（`org.jsoup.select.selectXpath`，jsoup 原生 XPath）的语义。
     * 本文件原先用 `indexOf('[')` + `lastIndexOf(']')` 只取一段，`div[a][b]` 会切成垃圾谓词
     * `a][b`，一条正则都不命中 → 落到 [evaluatePredicate] 末尾的 `return true` 兜底 →
     * 两个谓词**静默失效**、元素全量返回。
     *
     * 残缺（有 `[` 但无配对 `]`）时与原实现同形：整段当标签名、不带谓词，
     * 于是 `getElementsByTag("div[")` 取不到任何东西（宁可漏选，也不静默多选）。
     */
    private fun parsePredicates(segment: String): Pair<String, List<String>> {
        val first = segment.indexOf('[')
        if (first == -1) {
            return segment to emptyList()
        }

        val predicates = ArrayList<String>(2)
        var pos = first
        while (pos < segment.length && segment[pos] == '[') {
            //谓词内允许带引号的字面量（`@id='x'`），引号内符号不计层；引号内反斜杠无效
            val end = BalanceScan.chomp(
                segment, pos, '[', ']',
                quote = true, escape = BalanceScan.Escape.OUTSIDE_QUOTES
            )
            if (end < 0) {
                return segment to emptyList()
            }
            predicates.add(segment.substring(pos + 1, end - 1))
            pos = end
            while (pos < segment.length && segment[pos] == ' ') pos++ //谓词间空白容忍
        }

        return segment.substring(0, first) to predicates
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
                filterByPredicate(elements, part.predicates)
            }
        }
    }

    /** 多个谓词求交：对齐 jsoup 原生 XPath 的 `div[a][b]` 语义。 */
    private fun filterByPredicate(elements: List<Element>, predicates: List<String>): List<Element> {
        if (predicates.isEmpty()) return elements

        return elements.filter { element ->
            predicates.all { evaluatePredicate(element, it) }
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
            return siblingPositionAndTotal(element).first == pos
        }

        if (predicate == "last()" || predicate == "position()=last()") {
            val (position, total) = siblingPositionAndTotal(element)
            return position == total
        }

        // Handle numeric index [n]
        val indexPattern = Regex("""^\d+$""")
        if (indexPattern.matches(predicate)) {
            val n = predicate.toIntOrNull() ?: return false
            //XPath 位置谓词是 1 基的，且按“同一父节点下同名兄弟”计数（节点先经节点测试过滤）：
            //`//div[1]` = 每个父节点下的第一个 div，而不是“全部子元素里下标 1 的那个”。
            //原实现用 `siblings[index] == element` 既走了 0 基、又未按标签名过滤，
            //与同文件 `position()=n` 的 1 基算法互相矛盾，现两者共用同一个序号口径。
            return n >= 1 && siblingPositionAndTotal(element).first == n
        }

        return true
    }

    /**
     * 元素在其父节点**同名**子元素序列中的 1 基序号，与同名子元素总数。
     *
     * 基准为 W3C XPath 1.0 的谓词位置语义（节点先经节点测试过滤、位置从 1 开始），而不是 archive 行为：
     * 本件是 KMP 新增的重写件（archive 直接调 jsoup 原生 `selectXpath`），没有“与原版一致”的包袱，
     * 只能按规范定对错。原实现三处互相矛盾：`[n]` 走 0 基且未按标签过滤、`position()=n` 走 1 基全兄弟、
     * `last()` 比的是全部子元素的末尾，导致 `//div[1]` 与 `//div[position()=1]`、`//div[last()]` 结果不一致。
     */
    private fun siblingPositionAndTotal(element: Element): Pair<Int, Int> {
        val parent = element.parent() ?: return 1 to 1
        val tag = element.tagName()
        var position = 0
        var total = 0
        for (child in parent.children()) {
            if (child.tagName() == tag) {
                total++
                if (child === element) position = total
            }
        }
        return (if (position == 0) 1 else position) to total
    }
}

sealed class PathPart {
    object Parent : PathPart()
    object Current : PathPart()
    object AllChildren : PathPart()
    data class Tag(val name: String) : PathPart()
    data class TagWithPredicate(val tag: String, val predicates: List<String>) : PathPart()
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
