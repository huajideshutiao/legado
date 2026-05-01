package io.legado.app.ui.book.read.page.provider

import io.legado.app.help.book.BookContent
import org.apache.commons.text.StringEscapeUtils

object ChapterContentParser {

    class Image(
        val src: String,
        val style: String?,
        val onclick: String?
    )

    class ParsedLine(
        val text: String,
        val images: List<Image>
    )

    /**
     * 轻量级解析内容中的图片和文字
     * 针对 HtmlFormatter.formatKeepImg 产出的标准化内容进行了优化
     */
    fun parse(bookContent: BookContent): List<ParsedLine> {
        return bookContent.textList.map { content ->
            if (content.isEmpty()) return@map ParsedLine("", emptyList())

            // 如果完全没有标签，直接解码返回
            if (content.indexOf('<') == -1) {
                return@map ParsedLine(decodeHtml(content), emptyList())
            }

            val images = mutableListOf<Image>()
            val textBuilder = StringBuilder(content.length)
            var i = 0
            val len = content.length

            while (i < len) {
                val tagStart = content.indexOf('<', i)
                if (tagStart == -1) {
                    textBuilder.append(content.substring(i))
                    break
                }

                if (tagStart > i) {
                    textBuilder.append(content.substring(i, tagStart))
                }

                val tagEnd = content.indexOf('>', tagStart)
                if (tagEnd == -1) {
                    // 残缺标签，作为普通文本处理
                    textBuilder.append(content.substring(tagStart))
                    break
                }

                val tagContent = content.substring(tagStart + 1, tagEnd)
                if (tagContent.startsWith("img", ignoreCase = true)) {
                    val fullTag = content.substring(tagStart, tagEnd + 1)
                    val src = getAttr(fullTag, "src") ?: ""
                    val style = getAttr(fullTag, "style")
                    val onclick = getAttr(fullTag, "onclick")
                    images.add(Image(src, style, onclick))
                    textBuilder.append(ChapterProvider.srcReplaceChar)
                } else if (tagContent.equals("br", ignoreCase = true) || tagContent.equals(
                        "br/",
                        ignoreCase = true
                    )
                ) {
                    textBuilder.append("\n")
                }
                // 忽略其他经过 formatKeepImg 过滤后理论上不应存在的标签

                i = tagEnd + 1
            }

            ParsedLine(decodeHtml(textBuilder.toString()), images)
        }
    }

    private fun decodeHtml(html: String): String {
        return if (html.contains('&')) {
            StringEscapeUtils.unescapeHtml4(html)
        } else {
            html
        }
    }

    /**
     * 高性能提取所有图片信息
     */
    fun extractImages(content: String): List<Image> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val images = mutableListOf<Image>()
        var i = 0
        val len = content.length
        while (i < len) {
            val tagStart = content.indexOf("<img", i, ignoreCase = true)
            if (tagStart == -1) break
            val tagEnd = content.indexOf('>', tagStart)
            if (tagEnd == -1) break
            val fullTag = content.substring(tagStart, tagEnd + 1)
            val src = getAttr(fullTag, "src")
            if (src != null) {
                val style = getAttr(fullTag, "style")
                val onclick = getAttr(fullTag, "onclick")
                images.add(Image(src, style, onclick))
            }
            i = tagEnd + 1
        }
        return images
    }

    /**
     * 高性能属性提取器
     */
    fun getAttr(tag: String, attrName: String): String? {
        val search = "$attrName="
        val index = tag.indexOf(search, ignoreCase = true)
        if (index == -1) return null

        val valueStart = index + search.length
        if (valueStart >= tag.length) return null

        val quote = tag[valueStart]
        return if (quote == '"' || quote == '\'') {
            val endQuote = tag.indexOf(quote, valueStart + 1)
            if (endQuote == -1) null else tag.substring(valueStart + 1, endQuote)
        } else {
            // 处理无引号属性
            var end = valueStart
            while (end < tag.length && tag[end] != ' ' && tag[end] != '>' && tag[end] != '/') {
                end++
            }
            if (end > valueStart) tag.substring(valueStart, end) else null
        }
    }
}
