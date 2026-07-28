package io.legado.app.ui.book.read.page.provider

import io.legado.app.help.book.BookContent
import io.legado.app.utils.EscapeUtils

/**
 * 章节正文轻量解析器的共享实现。
 *
 * 从 app 端 `ChapterContentParser` 原样下沉：逐项消费 [BookContent.textList]，把 `<img>`
 * 替换为 [srcReplaceChar]，保留 src/style/onclick 顺序，把 `<br>` 转换为换行，并对文本
 * 做 HTML entity 解码。Android 端解析器改为委托本实现，确保下沉前后行为一致。
 */
object ChapterContentParserShared {

    const val srcReplaceChar = "▩"

    fun parse(bookContent: BookContent): List<ParsedParagraph> {
        return bookContent.textList.map { content ->
            if (content.isEmpty()) return@map ParsedParagraph("", emptyList())
            if (content.indexOf('<') == -1) {
                return@map ParsedParagraph(decodeHtml(content), emptyList())
            }

            val images = mutableListOf<ImgData>()
            val textBuilder = StringBuilder(content.length)
            var index = 0
            while (index < content.length) {
                val tagStart = content.indexOf('<', index)
                if (tagStart == -1) {
                    textBuilder.append(content.substring(index))
                    break
                }
                if (tagStart > index) {
                    textBuilder.append(content.substring(index, tagStart))
                }

                val tagEnd = content.indexOf('>', tagStart)
                if (tagEnd == -1) {
                    textBuilder.append(content.substring(tagStart))
                    break
                }

                val tagContent = content.substring(tagStart + 1, tagEnd)
                if (tagContent.startsWith("img", ignoreCase = true)) {
                    val fullTag = content.substring(tagStart, tagEnd + 1)
                    images.add(
                        ImgData(
                            src = getAttr(fullTag, "src") ?: "",
                            style = getAttr(fullTag, "style") ?: "",
                            onclick = getAttr(fullTag, "onclick") ?: "",
                        )
                    )
                    textBuilder.append(srcReplaceChar)
                } else if (
                    tagContent.equals("br", ignoreCase = true) ||
                    tagContent.equals("br/", ignoreCase = true)
                ) {
                    textBuilder.append('\n')
                }
                index = tagEnd + 1
            }

            ParsedParagraph(decodeHtml(textBuilder.toString()), images)
        }
    }

    fun extractImages(content: String): List<ImgData> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val images = mutableListOf<ImgData>()
        var index = 0
        while (index < content.length) {
            val tagStart = content.indexOf("<img", index, ignoreCase = true)
            if (tagStart == -1) break
            val tagEnd = content.indexOf('>', tagStart)
            if (tagEnd == -1) break
            val fullTag = content.substring(tagStart, tagEnd + 1)
            val src = getAttr(fullTag, "src")
            if (src != null) {
                images.add(
                    ImgData(
                        src = src,
                        style = getAttr(fullTag, "style") ?: "",
                        onclick = getAttr(fullTag, "onclick") ?: "",
                    )
                )
            }
            index = tagEnd + 1
        }
        return images
    }

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
            var end = valueStart
            while (end < tag.length && tag[end] != ' ' && tag[end] != '>' && tag[end] != '/') {
                end++
            }
            if (end > valueStart) tag.substring(valueStart, end) else null
        }
    }

    private fun decodeHtml(html: String): String {
        return if (html.contains('&')) EscapeUtils.unescapeHtml(html) else html
    }
}
