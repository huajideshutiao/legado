package io.legado.app.model.analyzeRule

import android.text.TextUtils
import androidx.annotation.Keep
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

@Keep
class AnalyzeByXPath(doc: Any) {
    private var baseElement: Element = parse(doc)

    private fun parse(doc: Any): Element {
        return when (doc) {
            is Document -> doc
            is Element -> doc
            is Elements -> doc.first() ?: Jsoup.parse("")
            is Node -> Jsoup.parse(doc.toString())
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
                return Jsoup.parse(html1, Parser.xmlParser())
            }
        }
        return Jsoup.parse(html1)
    }

    private fun getResult(xPath: String): List<Node>? {
        return try {
            if (xPath.contains("/@")) {
                val parts = xPath.split("/@", limit = 2)
                val elementPath = parts[0].ifEmpty { "." }
                val attrName = parts[1]
                val elements = baseElement.selectXpath(elementPath, Element::class.java)
                elements.map { TextNode(it.attr(attrName)) }
            } else {
                baseElement.selectXpath(xPath, Node::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun getElements(xPath: String): List<Node>? {
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
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                nodes.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        nodes.addAll(temp)
                    }
                }
            }
        }
        return nodes
    }

    internal fun getStringList(xPath: String): List<String> {
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
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                result.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
        }
        return result
    }

    fun getString(rule: String): String? {
        val ruleAnalyzes = RuleAnalyzer(rule)
        val rules = ruleAnalyzes.splitRule("&&", "||")
        if (rules.size == 1) {
            getResult(rule)?.let {
                return TextUtils.join("\n", it.map { node -> getNodeText(node) })
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
