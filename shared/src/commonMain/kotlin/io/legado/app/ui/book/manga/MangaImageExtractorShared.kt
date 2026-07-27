package io.legado.app.ui.book.manga

/**
 * 漫画章节图片 URL 提取共享逻辑 (纯字符串扫描, 无平台依赖)。
 * 下沉自 desktop DesktopMangaImageExtractor.extractImageUrls, 供各端 MangaImageExtractor actual 复用。
 */
object MangaImageExtractorShared {
    /**
     * 从 HTML content 提取所有 <img src="..."> 图片 URL。
     * 简化扫描: 仅取 src 属性 (对齐 desktop 实现)。
     */
    fun extractImageUrls(content: String): List<String> {
        if (!content.contains("<img", ignoreCase = true)) return emptyList()
        val images = mutableListOf<String>()
        var i = 0
        val len = content.length
        while (i < len) {
            val tagStart = content.indexOf("<img", i, ignoreCase = true)
            if (tagStart == -1) break
            val tagEnd = content.indexOf('>', tagStart)
            if (tagEnd == -1) break
            val fullTag = content.substring(tagStart, tagEnd + 1)
            val src = getAttr(fullTag, "src")
            if (src != null && src.isNotBlank()) {
                images.add(src)
            }
            i = tagEnd + 1
        }
        return images
    }

    /**
     * 从 HTML 标签字符串取属性值 (支持双引号 / 单引号 / 无引号三种格式)。
     */
    private fun getAttr(tag: String, attrName: String): String? {
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
}
