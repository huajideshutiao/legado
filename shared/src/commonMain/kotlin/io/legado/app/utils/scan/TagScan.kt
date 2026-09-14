package io.legado.app.utils.scan

/**
 * 正文 `<img>` 标签与属性扫描共用件。
 *
 * 原先「从 `<img ...>` 里取属性」这件事在项目里有 3 份实现：`ChapterContentParserShared.getAttr`
 * （游标扫描）、`ExportBookEpubShared` 两处内联 `Pattern.compile("<img src=\"([^\"]+)\"")`，
 * 外加生产零引用的 `AppPattern.imgPattern`（已删）。本件把前两类收敛成一份，两种口径各自保留、不互相冒充：
 *
 * - [attr]：宽容口径，属性名可出现在标签任意位置（要求处在空白/`<` 边界），值支持双引号、单引号与无引号。
 *   阅读页与漫画链路用它，因为本地 txt/cbz/pdf 正文、视频/音频/RSS 书源与「清洗之后才跑的替换规则」
 *   产出的标签不经过 `HtmlFormatter.formatKeepImg` 那套归一化。
 * - [forEachNormalizedImgSrc]：严格口径，只认 `<img src="` 紧跟非空双引号值，
 *   即 `formatKeepImg` 的输出形态，逐字等价于导出侧原有正则。
 *
 * [forEachTag] 是标签**发现**规则的唯一实现：阅读页正文解析与图片列表提取过去各写一遍、
 * 口径不同（一个先找 `<` 再看标签名、一个直接搜 `<img` 子串），会给出不同的图片集合。
 * 取属性同样只留 [attr] 一份（`ChapterContentParserShared.getAttr` 那个只服务测试的转发已删）。
 */
internal object TagScan {

    private const val IMG = "img"

    /**
     * 从标签串 [tag] 中取属性 [attrName] 的值。
     *
     * 属性名必须处在边界（串首、空白或 `<`）上，故 `data-src` 不会被当成 `src` 取走——
     * archive 原版 `ChapterContentParser.getAttr` 用 `indexOf("$attrName=")` 会误命中，
     * 属原版缺陷，此处不复刻。
     *
     * @return 属性值；没有该属性或值取不到返回 null
     */
    fun attr(tag: String, attrName: String): String? {
        val n = tag.length
        var fromIndex = 0
        while (fromIndex < n) {
            val index = tag.indexOf(attrName, fromIndex, ignoreCase = true)
            if (index == -1) return null
            val isAttrBoundary = index == 0 || tag[index - 1].isWhitespace() || tag[index - 1] == '<'
            if (isAttrBoundary) {
                var eqIndex = index + attrName.length
                while (eqIndex < n && tag[eqIndex].isWhitespace()) {
                    eqIndex++
                }
                if (eqIndex < n && tag[eqIndex] == '=') {
                    var valueStart = eqIndex + 1
                    while (valueStart < n && tag[valueStart].isWhitespace()) {
                        valueStart++
                    }
                    if (valueStart >= n) return null
                    val quote = tag[valueStart]
                    return if (quote == '"' || quote == '\'') {
                        val endQuote = tag.indexOf(quote, valueStart + 1)
                        if (endQuote == -1) null else tag.substring(valueStart + 1, endQuote)
                    } else {
                        var end = valueStart
                        while (end < n && !tag[end].isWhitespace() && tag[end] != '>' && tag[end] != '/') {
                            end++
                        }
                        if (end > valueStart) tag.substring(valueStart, end) else null
                    }
                }
            }
            fromIndex = index + attrName.length
        }
        return null
    }

    /**
     * 按文档序回调 [text] 里每个 `<...>` 标签，返回「剩余原文应从此下标起当文本保留」。
     *
     * 阅读页正文解析与图片列表提取**必须共用这一份发现规则**，否则同一章节会在
     * 「翻页看到的图」与「漫画预加载/EPUB 导出看到的图」之间不一致：`<a<img src="b.jpg">`
     * 这类畸形正文，旧口径一边丢标签、一边取到图。
     *
     * @param onTag 依次拿到 `<` 下标、`>` 下标、标签整名
     * @return 遇到没有引号外 `>` 的残缺标签时返回那个 `<` 的下标；否则返回 [text] 长度
     */
    inline fun forEachTag(text: String, onTag: (tagStart: Int, tagEnd: Int, name: String) -> Unit): Int {
        var index = 0
        while (index < text.length) {
            val tagStart = text.indexOf('<', index)
            if (tagStart == -1) return text.length
            //闭合 `>` 必须取引号外的那个: `<a href="x>y"><img src="b.jpg">` 里引号内的 `>` 不是标签结束，
            //旧实现直接 indexOf('>') 会在此截断，与本件 [attr] 认引号的口径相反（HTML 属性里反斜杠无转义含义，不处理）
            var i = tagStart + 1
            var quote: Char? = null
            var tagEnd = -1
            while (i < text.length) {
                val c = text[i]
                if (quote == null) {
                    if (c == '"' || c == '\'') quote = c
                    else if (c == '>') {
                        tagEnd = i
                        break
                    }
                } else if (c == quote) {
                    quote = null
                }
                i++
            }
            if (tagEnd == -1) return tagStart
            onTag(tagStart, tagEnd, tagName(text, tagStart))
            index = tagEnd + 1
        }
        return text.length
    }

    /**
     * 取 [tagStart] 处 `<` 之后的**完整**标签名：到第一个空白、`>` 或 `/` 为止。
     *
     * 整名比对而非前缀比对：旧实现用 `startsWith("img")`，于是 `<image>`（SVG 内联图）与
     * `<imgx>` 会被当成图片标签、把原文吃掉，属既有缺陷，此处按标签名边界修正。
     */
    fun tagName(text: String, tagStart: Int): String {
        val n = text.length
        var i = tagStart + 1
        while (i < n) {
            val c = text[i]
            if (c == '>' || c == '/' || c.isWhitespace()) break
            i++
        }
        return text.substring(tagStart + 1, i)
    }

    /** 标签整名是否就是 `img`（大小写不敏感）。 */
    fun isImgTag(name: String): Boolean = name.equals(IMG, ignoreCase = true)

    /**
     * 逐个回调 [content] 中归一化形态 img 标签的 src。
     *
     * 语义逐字等价于 `<img src="([^"]+)"`（忽略大小写）的 `Matcher.find()` 循环：
     * 要求 `src="` 紧跟在 `<img ` 之后（恰好一个空格）、值非空且不含双引号；
     * 命中后从右引号之后继续，未命中则只前进一个字符重新找（对齐 find() 的失败回退）。
     */
    inline fun forEachNormalizedImgSrc(content: String, onSrc: (src: String) -> Unit) {
        var index = 0
        while (true) {
            val at = content.indexOf("<img ", index, ignoreCase = true)
            if (at == -1) return
            val attrAt = at + 5 //"<img " 之后
            if (content.regionMatches(attrAt, "src=\"", 0, 5, ignoreCase = true)) {
                val valueStart = attrAt + 5
                val close = content.indexOf('"', valueStart)
                if (close > valueStart) {
                    onSrc(content.substring(valueStart, close))
                    index = close + 1
                    continue
                }
            }
            index = at + 1
        }
    }
}
