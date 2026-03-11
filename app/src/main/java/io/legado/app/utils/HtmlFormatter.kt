package io.legado.app.utils

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
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

    fun formatKeepImg(html: String, redirectUrl: String? = null, needSave: Boolean = true): String {
        val content = Jsoup.parse(html, redirectUrl ?: "").body()
        val str = StringBuilder()
        fun extractFromNode(node: Node) {
            for (child in node.childNodes()) {
                when (child) {
                    is TextNode -> {
                        val text = child.wholeText
                        if (text.isNotEmpty()) str.apply {
                            text.lines().forEach {
                                val oo = it.trim()
                                if (oo == "") return@forEach
                                if (str.isNotEmpty()) append("\n")
                                if (needSave) append("　　")
                                append(oo)
                            }
                        }
                    }

                    is Element if child.tagName().equals("img", ignoreCase = true) -> {
                        val img = Element(Tag.valueOf("img"), redirectUrl ?: "")
                        img.attr(
                            "src",
                            when {
                                child.hasAttr("data-src") -> child.absUrl("data-src")
                                child.hasAttr("data-original") -> child.absUrl("data-original")
                                else -> child.absUrl("src")
                            }
                        )
                        img.attr("style", child.attr("style"))
                        img.attr("onclick", child.attr("onclick"))
                        str.append(img.outerHtml())
                    }

                    is Element -> extractFromNode(child)
                }
            }
        }
        extractFromNode(content)
        return str.toString()
    }
}
