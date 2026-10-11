package io.legado.app.utils

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import io.legado.app.constant.AppPattern

object HtmlFormatter {
    private val nbspRegex = "(&nbsp;)+".toRegex()
    private val espRegex = "(&ensp;|&emsp;)".toRegex()
    private val noPrintRegex = "(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)".toRegex()
    private val wrapHtmlRegex = "</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex()
    private val commentRegex = "<!--[^>]*-->".toRegex() //注释
    private val otherHtmlRegex = "</?[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()

    // 与 otherHtmlRegex 同，但放行 <button>/<img>（含其闭合形式）
    private val otherHtmlRegexKeepRich =
        "</?(?!(?:button|img)\\b)[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    // 空白显式写 [AppPattern.spaceClass] (含 U+3000): 各端 `\s` 定义不同,
    // native stdlib 的 `\s` 不含 U+3000 全角空格, 而正文缩进大量用它
    private val indent1Regex = "${AppPattern.spaceClass}*\n+${AppPattern.spaceClass}*".toRegex()
    private val indent2Regex = "^[\n${AppPattern.spaceClassBody}]+".toRegex()
    private val lastRegex = "[\n${AppPattern.spaceClassBody}]+$".toRegex()

    fun formatKeepRichTags(html: String?): String = format(html, otherHtmlRegexKeepRich)

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
            EscapeUtils.unescapeHtml(html).lines().forEach {
                val oo = it.trim()
                if (oo == "") return@forEach
                if (str.isNotEmpty()) str.append("\n")
                str.append("　　")
                str.append(oo)
            }
        } else {
            val content = Ksoup.parse(html, redirectUrl ?: "").body()
            val nodes = ArrayDeque<Node>()
            nodes.add(content)

            var lastIsBlock = true // 初始视为块开始，触发首行缩进

            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirstOrNull() ?: continue
                when (node) {
                    is TextNode -> {
                        val text = node.getWholeText().trim()
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
                            val src = when {
                                node.hasAttr("data-src") -> node.absUrl("data-src")
                                    .ifEmpty { node.attr("data-src") }

                                node.hasAttr("data-original") -> node.absUrl("data-original")
                                    .ifEmpty { node.attr("data-original") }

                                else -> node.absUrl("src").ifEmpty { node.attr("src") }
                            }
                            str.append("<img src=\"$src\"")
                            node.attribute("style")?.let { str.append(" style=\"${it.value}\"") }
                            node.attribute("onclick")
                                ?.let { str.append(" onclick=\"${it.value}\"") }
                            str.append(">")
                            lastIsBlock = false
                        } else {
                            // 块级标签处理
                            val isBlock = node.isBlock() || tagName == "br"
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
