package io.legado.app.utils

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
import java.util.LinkedList
import java.util.regex.Pattern

@Suppress("RegExpRedundantEscape")
object HtmlFormatter {
    private val nbspRegex = "(&nbsp;)+".toRegex()
    private val espRegex = "(&ensp;|&emsp;)".toRegex()
    private val noPrintRegex = "(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)".toRegex()
    private val wrapHtmlRegex = "</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex()
    private val commentRegex = "<!--[^>]*-->".toRegex() //注释
    private val otherHtmlRegex = "</?[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    val formatImagePattern = Pattern.compile(
        "<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>|<img[^>]*\\s(?:data-src|src)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>|<img[^>]*\\sdata-[^=>]*=\\s*['\"]([^'\">]*)['\"][^>]*>",
        Pattern.CASE_INSENSITIVE
    )
    private val indent1Regex = "\\s*\\n+\\s*".toRegex()
    private val indent2Regex = "^[\\n\\s]+".toRegex()
    private val lastRegex = "[\\n\\s]+$".toRegex()

    fun format(html: String?, otherRegex: Regex = otherHtmlRegex): String {
        html ?: return ""
        return html.replace(nbspRegex, " ")
            .replace(espRegex, " ")
            .replace(noPrintRegex, "")
            .replace(wrapHtmlRegex, "\n")
            .replace(commentRegex, "")
            .replace(otherRegex, "")
            .replace(indent1Regex, "\n　　")
            .replace(indent2Regex, "　　")
            .replace(lastRegex, "")
    }

    fun formatKeepImg(html: String, redirectUrl: String? = null): String {
        val str = StringBuilder()
        val tmp = html.indexOf("<")
        if (tmp == -1 || html.indexOf(">", tmp) == -1) {
            html.lines().forEach {
                val oo = it.trim()
                if (oo == "") return@forEach
                if (str.isNotEmpty()) str.append("\n")
                str.append("　　")
                str.append(oo)
            }
        } else {
            val content = Jsoup.parse(html, redirectUrl ?: "").body()
            val nodes = LinkedList<Node>()
            nodes.add(content)

            var lastIsBlock = true // 初始视为块开始，触发首行缩进

            while (nodes.isNotEmpty()) {
                val node = nodes.pollFirst() ?: continue
                when (node) {
                    is TextNode -> {
                        val text = node.wholeText.trim()
                        if (text.isNotEmpty()) {
                            if (lastIsBlock) {
                                if (str.isNotEmpty()) str.append("\n")
                                str.append("　　")
                            }
                            str.append(text)
                            lastIsBlock = false
                        }
                    }

                    is Element -> {
                        val tagName = node.tagName()
                        if (tagName == "img") {
                            val img = Element(Tag.valueOf("img"), redirectUrl ?: "")
                            val src = when {
                                node.hasAttr("data-src") -> node.absUrl("data-src")
                                    .ifEmpty { node.attr("data-src") }

                                node.hasAttr("data-original") -> node.absUrl("data-original")
                                    .ifEmpty { node.attr("data-original") }

                                else -> node.absUrl("src").ifEmpty { node.attr("src") }
                            }
                            img.attr("src", src)
                            node.attribute("style")?.let { img.attr("style", it.value) }
                            node.attribute("onclick")?.let { img.attr("onclick", it.value) }
                            str.append(img.outerHtml())
                            lastIsBlock = false
                        } else {
                            // 块级标签处理
                            val isBlock = node.isBlock || tagName == "br"
                            if (isBlock && !lastIsBlock) {
                                lastIsBlock = true
                            }
                            // 将子节点逆序放入队列前端，实现深度优先遍历
                            val childNodes = node.childNodes()
                            for (i in childNodes.indices.reversed()) {
                                nodes.addFirst(childNodes[i])
                            }
                        }
                    }
                }
            }
        }
        return str.toString()
    }
}
