package io.legado.app.utils

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.select.Elements

fun Element.textArray(): Array<String> {
    val sb = StringBuilder()
    traverse(object : com.fleeksoft.ksoup.select.NodeVisitor {
        override fun head(node: Node, depth: Int) {
            if (node is TextNode) {
                appendNormalisedText(sb, node)
            } else if (node is Element) {
                if (sb.isNotEmpty() &&
                    (node.isBlock() || node.tagName() == "br") &&
                    !lastCharIsWhitespace(sb)
                ) sb.append("\n")
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element) {
                if (node.isBlock() && node.nextSibling() is TextNode
                    && !lastCharIsWhitespace(sb)
                ) {
                    sb.append("\n")
                }
            }
        }
    })
    val text = sb.toString().trim()
    return text.splitNotBlank("\n")
}

fun Element.findNS(tag: String, namespace: HashSet<String>): Elements {
    return select("*|$tag").filter { el ->
        namespace.contains(el.tagName().substringBefore(":"))
    }.toElements()
}

fun Element.findNSPrefix(namespaceURI: String): HashSet<String> {
    return select("[^xmlns:]").map { element ->
        element.attributes().filter { it.value == namespaceURI }.map { it.key.substring(6) }
    }.flatten().toHashSet()
}

fun List<Element>.toElements() = Elements(this)

private fun appendNormalisedText(sb: StringBuilder, textNode: TextNode) {
    val text = textNode.getWholeText()
    if (preserveWhitespace(textNode.parentNode()) || textNode is com.fleeksoft.ksoup.nodes.CDataNode)
        sb.append(text)
    else sb.appendNormalisedWhitespace(text, lastCharIsWhitespace(sb))
}

private fun StringBuilder.appendNormalisedWhitespace(string: String, lastCharIsWhitespace: Boolean) {
    val collector = StringBuilder()
    var lastWasWhite = lastCharIsWhitespace
    for (i in string.indices) {
        val c = string[i]
        if (c.isWhitespace()) {
            if (!lastWasWhite) {
                collector.append(' ')
                lastWasWhite = true
            }
        } else {
            collector.append(c)
            lastWasWhite = false
        }
    }
    append(collector.toString())
}

private fun preserveWhitespace(node: Node?): Boolean {
    if (node is Element) {
        var el: Element? = node
        var i = 0
        do {
            if (el!!.tag().preserveWhitespace()) return true
            el = el.parent()
            i++
        } while (i < 6 && el != null)
    }
    return false
}

private fun lastCharIsWhitespace(sb: StringBuilder): Boolean {
    return sb.isNotEmpty() && sb[sb.length - 1] == ' '
}
