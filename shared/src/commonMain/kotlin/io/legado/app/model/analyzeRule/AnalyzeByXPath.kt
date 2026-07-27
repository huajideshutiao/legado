package io.legado.app.model.analyzeRule

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser
import com.fleeksoft.ksoup.select.Elements
import org.jsoup.select.selectXpath

// 书源 XPath 规则解析。@Keep 移除：commonMain 无 androidx.annotation，
// 反射保活改由 shared consumer-rules.pro -keep 登记（照 QueryTTF 先例）。
class AnalyzeByXPath(doc: Any) {
    private var baseElement: Element = parse(doc)

    private fun parse(doc: Any): Element {
        return when (doc) {
            is Document -> doc
            is Element -> doc
            is Elements -> doc.first() ?: Ksoup.parse("")
            is Node -> Ksoup.parse(doc.toString())
            else -> strToElement(doc.toString())
        }
    }

    private fun strToElement(html: String): Element {
        var html1 = html
        if (html1.endsWith("</td>")) {
            html1 = "<tr>${html1}</tr>"
        }
        if (html1.endsWith("</tr>") || html1.endsWith("</tbody>")) {
            html1 = "<table>${html1}</table>"
        }
        kotlin.runCatching {
            if (html1.trim().startsWith("<?xml", true)) {
                return Ksoup.parse(html1, parser = Parser.xmlParser())
            }
        }
        return Ksoup.parse(html1)
    }

    private fun getResult(xPath: String): List<Node>? {
        return try {
            if (xPath.contains("/@")) {
                val parts = xPath.split("/@", limit = 2)
                val elementPath = parts[0].ifEmpty { "." }
                val attrName = parts[1]
                val elements = baseElement.selectXpath(elementPath, Element::class)
                elements.map { TextNode(it.attr(attrName)) }
            } else {
                baseElement.selectXpath(xPath, Node::class)
            }
        } catch (_: Exception) {
            null
        }
    }

    // internal→public: 消费者 AnalyzeRule 仍在 app 模块，跨模块 internal 不可见
    fun getElements(xPath: String): List<Node>? {
        if (xPath.isEmpty()) return null

        val nodes = ArrayList<Node>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            return getResult(rules[0])
        } else {
            val results = ArrayList<List<Node>>()
            for (rl in rules) {
                val temp = getElements(rl)
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, nodes)
            }
        }
        return nodes
    }

    fun getStringList(xPath: String): List<String> {
        val result = ArrayList<String>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            getResult(xPath)?.map {
                result.add(getNodeText(it))
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
        }
        return result
    }

    fun getString(rule: String): String? {
        val ruleAnalyzes = RuleAnalyzer(rule)
        val rules = ruleAnalyzes.splitRule("&&", "||")
        if (rules.size == 1) {
            getResult(rule)?.let {
                return it.joinToString("\n") { node -> getNodeText(node) }
            }
            return null
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

    private fun getNodeText(node: Node): String {
        return when (node) {
            is Element -> node.text()
            is TextNode -> node.text()
            else -> node.toString()
        }
    }
}
