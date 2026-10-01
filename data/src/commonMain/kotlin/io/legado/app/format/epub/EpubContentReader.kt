package io.legado.app.format.epub

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.isDataUrl

/** Native EPUB chapter assembly, following the Android spine and fragment slicing rules. */
internal object EpubContentReader {
    fun read(contents: List<EpubResource>, chapter: BookChapter, deleteTags: Long): String {
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        check(contents.any { it.href == currentChapterFirstResourceHref }) {
            "EPUB 章节资源不在阅读顺序中：${chapter.url}"
        }
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val elements = Elements()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        // 一些书籍依靠href索引的resource会包含多个章节, 需依据fragmentId截取当前章节内容
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(getBody(res, startFragmentId, endFragmentId, deleteTags))
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                elements.add(getBody(res, null, null, deleteTags))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    // 有Fragment 则添加到上一章节
                    elements.add(getBody(res, null, endFragmentId, deleteTags))
                }
                break
            }
        }
        // title标签中的内容不需要显示在正文中, 去除
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        elements.select("img").forEach {
            if (it.attributesSize() <= 1) {
                return@forEach
            }
            val src = it.attr("src")
            it.clearAttributes()
            it.attr("src", src)
        }
        val tag = Book.rubyTag
        if (deleteTags and tag == tag) {
            elements.select("rp, rt").remove()
        }
        val html = elements.outerHtml()
        return HtmlFormatter.formatKeepImg(html)
    }

    private fun getBody(res: EpubResource, startFragmentId: String?, endFragmentId: String?, deleteTags: Long): Element {
        // Ksoup 可能会修复不规范的 xhtml 文件, 解析处理后再获取
        var bodyElement = Ksoup.parse(res.data.decodeToString()).body()
        bodyElement.children().run {
            select("script").remove()
            select("style").remove()
        }
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        // 某些 xhtml 文件章节标题和内容不在一个节点或不是兄弟节点, 用 FragmentId 截取
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        // 截取过再重新解析
        if (bodyString != originBodyString) {
            bodyElement = Ksoup.parse(bodyString).body()
        }
        // 去除正文中的 H 标签 (部分书籍标题与阅读标题重复)
        val tag = Book.hTag
        if (deleteTags and tag == tag) {
            bodyElement.run {
                select("h1, h2, h3, h4, h5, h6").remove()
            }
        }
        // SVG <image> 转 <img>
        bodyElement.select("image").forEach {
            it.tagName("img")
            it.attr("src", it.attr("xlink:href"))
        }
        // 解析 img src 为 zip 内绝对路径 (供 getImage 查找)
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim()
            if (src.isDataUrl()) {
                it.attr("src", src)
            } else {
                // 用 EpubParser.resolvePath 规范化相对路径为 zip 内绝对路径
                val resolvedHref = EpubParser.resolvePath(res.href, src)
                it.attr("src", resolvedHref)
            }
        }
        return bodyElement
    }

}
