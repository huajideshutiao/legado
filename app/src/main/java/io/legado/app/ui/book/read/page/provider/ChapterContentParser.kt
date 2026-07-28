package io.legado.app.ui.book.read.page.provider

import io.legado.app.help.book.BookContent

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
        return ChapterContentParserShared.parse(bookContent).map { parsed ->
            ParsedLine(
                text = parsed.text,
                images = parsed.images.map { Image(it.src, it.style, it.onclick) },
            )
        }
    }

    /**
     * 高性能提取所有图片信息
     */
    fun extractImages(content: String): List<Image> {
        return ChapterContentParserShared.extractImages(content).map {
            Image(it.src, it.style, it.onclick)
        }
    }

    /**
     * 高性能属性提取器
     */
    fun getAttr(tag: String, attrName: String): String? =
        ChapterContentParserShared.getAttr(tag, attrName)
}
