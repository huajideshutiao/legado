package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.ParagraphLayoutEngine.breakLines
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.fastSum
import kotlin.concurrent.Volatile

/**
 * 段落折行缓存键。
 *
 * 覆盖除「段号 / 段评数」之外的全部排版输入：这两项随段落出现位置变化，命中后由
 * [ParagraphLayoutEngine.layoutParagraph] 就地补齐；其余字段一律进键，
 * 保证命中结果不可能与当前配置不符。
 *
 * 当用户只调节行距/段距/视口高度/边距时，键完全不变，缓存命中率为 100%。
 *
 * @param text 段落完整文本（保证零哈希碰撞）
 * @param fontKey 字体特征键（包含字号、字间距等信息）
 * @param visibleWidth 可视区宽度（px，双页模式为单栏宽）
 * @param isTitle 是否为标题段落
 * @param isFirstLine 是否为首行（影响缩进）
 * @param indentLength 缩进字符数
 * @param textHeight 字体高度（px，烘进 [LineMetrics.textHeight]）
 * @param descent 下行度量（px，烘进 [LineMetrics.descent]）
 * @param indentCharWidth 缩进单字宽（px，决定几何缩进是否生效与 [LineMetrics.indentWidth]）
 * @param centerTitle 标题是否居中（烘进 [LineMetrics.centerTitle]，随 titleMode 变化）
 * @param reviewChar 段评占位字符（烘进 [LineMetrics.reviewChar]）
 */
data class ParagraphCacheKey(
    val text: String,
    val fontKey: String,
    val visibleWidth: Int,
    val isTitle: Boolean = false,
    val isFirstLine: Boolean = true,
    val indentLength: Int = 0,
    val textHeight: Float = 0f,
    val descent: Float = 0f,
    val indentCharWidth: Float = 0f,
    val centerTitle: Boolean = false,
    val reviewChar: String = "",
)

/**
 * 单行纯度量数据（与 Y 轴行距/段距/视口高度完全解耦）。
 *
 * 由 Phase 1（[ParagraphLayoutEngine.layoutParagraph]）产出，
 * 记录该行在 X 轴上的 cluster 切片、字宽、期望宽度、字高与基线等纯度量信息。
 *
 * @param lineIndex 段内行序号（0-based）
 * @param words 该行各字素簇字符串（已扣除几何缩进字符）
 * @param widths 该行各字素簇宽度（px，已含 [PunctuationTrimmer] 阶段 0/2 挤压后的结果）
 * @param text 该行完整文本
 * @param textHeight 字体高度（px，descent - ascent）
 * @param descent 文字下行度量（px）
 * @param desiredWidth 期望宽度（px）= adjustedWidths 之和 **减末簇那份字距**：行末字距不占版面
 *   （[LineBreaker] 装行时同样把它算作余量），不减则两端对齐会把列盒尾对到右边线而墨迹
 *   短一份字距（clreq 6.2.2.1 行尾对齐）；letterSpacing = 0 时二者相等
 * @param indentLength 缩进字符数
 * @param indentWidth 缩进占用宽度（px）
 * @param isFirstLine 是否为段落首行
 * @param isParagraphEnd 是否为段落末行
 * @param isTitle 是否为标题行
 * @param isImage 是否为图片占位行
 * @param imageData 图片元数据（图片行有效）
 * @param imageWidth 图片渲染宽（px）
 * @param imageHeight 图片渲染高（px）
 * @param paragraphNum 逻辑段号
 * @param centerTitle 标题是否居中
 * @param hasReview 是否挂有段评气泡占位
 * @param reviewChar 段评占位字符
 * @param reviewCount 段评数量
 * @param drawOffsets 逐簇绘制 X 偏移（px，与 [words]/[widths] 同长）：裁左半的开始夹注标点为
 *   `-汉字宽/2`，其余为 0；全零时为 null（不占内存）。只影响绘制，不影响列命中盒。
 */
data class LineMetrics(
    val lineIndex: Int,
    val words: List<String>,
    val widths: List<Float>,
    val text: String,
    val textHeight: Float,
    val descent: Float,
    val desiredWidth: Float,
    val indentLength: Int = 0,
    val indentWidth: Float = 0f,
    val isFirstLine: Boolean = false,
    val isParagraphEnd: Boolean = false,
    val isTitle: Boolean = false,
    val isImage: Boolean = false,
    val imageData: ImgData? = null,
    val imageWidth: Float = 0f,
    val imageHeight: Float = 0f,
    val images: List<ImgData> = emptyList(),
    val paragraphNum: Int = 0,
    val centerTitle: Boolean = false,
    val hasReview: Boolean = false,
    val reviewChar: String = "",
    val reviewCount: Int = 0,
    val drawOffsets: List<Float>? = null,
) {
    val charSize: Int get() = text.length
}

/**
 * 段落排版纯度量数据（包含该段所有行在 X 轴上的切片与度量数据）。
 *
 * Phase 1（[ParagraphLayoutEngine.layoutParagraph]）的输出数据类，
 * 存入 [ParagraphLayoutCache] 供 Phase 2（[PaginationEngine.paginate]）反复切片分页。
 *
 * @param lines 该段排出的所有 [LineMetrics] 列表
 * @param isTitle 是否为标题段落
 * @param isImage 是否为块状图片段落
 * @param text 段落原始文本
 * @param paragraphNum 逻辑段号（1..N，0 为标题）
 * @param textHeight 段落字体高度（px）；产出方一律显式传，默认 0 只给空段落 `EMPTY`
 * @param descent 段落下行度量（px）；同上
 */
data class ParagraphLineMetrics(
    val lines: List<LineMetrics>,
    val isTitle: Boolean = false,
    val isImage: Boolean = false,
    val text: String = "",
    val paragraphNum: Int = 0,
    val textHeight: Float = 0f,
    val descent: Float = 0f,
) {
    val lineCount: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()
    val isNotEmpty: Boolean get() = lines.isNotEmpty()

    companion object {
        val EMPTY = ParagraphLineMetrics(emptyList())

        /**
         * 快速构造块状图片段落度量。
         */
        fun createImage(
            img: ImgData,
            width: Float,
            height: Float,
            paragraphNum: Int = 0,
        ): ParagraphLineMetrics {
            val line = LineMetrics(
                lineIndex = 0,
                words = listOf(" "),
                widths = listOf(width),
                text = " ",
                textHeight = height,
                descent = 0f,
                desiredWidth = width,
                isFirstLine = true,
                isParagraphEnd = true,
                isImage = true,
                imageData = img,
                imageWidth = width,
                imageHeight = height,
                paragraphNum = paragraphNum,
            )
            return ParagraphLineMetrics(
                lines = listOf(line),
                isImage = true,
                text = " ",
                paragraphNum = paragraphNum,
                textHeight = height,
                descent = 0f,
            )
        }
    }
}

/**
 * 段落折行缓存。
 *
 * 缓存 Phase 1（[ParagraphLayoutEngine.layoutParagraph]）的断行度量结果 [ParagraphLineMetrics]。
 * 当用户调整行间距、段间距、上下边距或翻页动画参数时，X 轴折行数据完全不变，
 * 缓存命中率达到 100%，直接跳过文字测量与中文避头尾断行计算。
 *
 * 热路径（每段一次 get/put）不进锁：表用 [newConcurrentMap]（jvm/android actual 是
 * ConcurrentHashMap，读无锁）。不做 LRU 淘汰——缓存价值在「同一章重排 100% 命中」，
 * 不在精确的最近最少使用顺序，装满就整表清空。
 *
 * @param maxSize 容量上限（段落数，默认 1000 段），超出即整表清空
 */
class ParagraphLayoutCache(val maxSize: Int = 1000) {

    private val map = newConcurrentMap<ParagraphCacheKey, ParagraphLineMetrics>()

    /** 观测用计数（非精确：并发自增可能少计）。 */
    @Volatile
    var hitCount: Long = 0
        private set

    @Volatile
    var missCount: Long = 0
        private set

    /** 整表清空次数（本缓存无逐条淘汰）。 */
    @Volatile
    var evictionCount: Long = 0
        private set

    val size: Int
        get() = map.size

    val hitRate: Double
        get() {
            val total = hitCount + missCount
            return if (total == 0L) 0.0 else hitCount.toDouble() / total.toDouble()
        }

    fun get(key: ParagraphCacheKey): ParagraphLineMetrics? {
        val value = map[key]
        if (value != null) hitCount++ else missCount++
        return value
    }

    fun put(key: ParagraphCacheKey, value: ParagraphLineMetrics): ParagraphLineMetrics? {
        if (map.size >= maxSize) {
            map.clear()
            evictionCount++
        }
        return map.put(key, value)
    }

    fun getOrPut(key: ParagraphCacheKey, defaultValue: () -> ParagraphLineMetrics): ParagraphLineMetrics {
        get(key)?.let { return it }
        val value = defaultValue()
        put(key, value)
        return value
    }

    fun remove(key: ParagraphCacheKey): ParagraphLineMetrics? = map.remove(key)

    fun snapshot(): Map<ParagraphCacheKey, ParagraphLineMetrics> = map.toMap()

    fun clear() {
        map.clear()
        hitCount = 0
        missCount = 0
        evictionCount = 0
    }
}

/**
 * 段落级排版引擎（Phase 1：纯函数断行与度量）。
 *
 * 核心设计：
 * 1. **纯函数度量**：输入文本、测量器、可视区宽度与缩进参数，输出 [ParagraphLineMetrics]，
 *    与 Y 轴行间距（[lineSpacingExtra]）、段间距（[paragraphSpacing]）、视口高度（[visibleHeight]）完全解耦。
 * 2. **单一断行器**：[breakLines] 封装 [LineBreaker]（平台 ICU 断点 ∩ 中文禁则）。
 * 3. **标点挤压与中西间距先于断行**（无条件生效）：[PunctuationTrimmer.trimAdjacent]（阶段 0）与
 *    [JustifySpacing.insertHanWestSpacing]（clreq 6.3.3）在 [breakLines] 之前定稿宽度，
 *    [PunctuationTrimmer.trimLineEdges]（阶段 2）切行后逐行补做行首行尾。
 * 4. **首行缩进几何度量**：支持字符拼接与等宽几何缩进（[indentCharWidth]）双模式。
 * 5. **段落折行缓存对接**：支持传入 [ParagraphLayoutCache] 实现秒级重排与 100% 缓存命中。
 */
object ParagraphLayoutEngine {

    /**
     * 排版单个段落（纯函数），产出该段落各行的 [LineMetrics] 切片数据。
     *
     * @param text 段落完整文本
     * @param measurer 文字宽度度量器
     * @param visibleWidth 可视区宽度（px，双页模式为单栏宽）
     * @param paragraphIndent 首行缩进字符串（默认全角空格 `　　`）
     * @param indentCharWidth 缩进单字宽（px，>0 启用几何缩进；0 走字符拼接）
     * @param isTitle 是否为章节标题
     * @param isFirstLine 是否为段落首行（影响缩进判定）
     * @param paragraphNum 逻辑段号（0 为标题，>=1 为正文段号）
     * @param centerTitle 标题是否居中
     * @param textHeight 字体高度（px，<=0 时自动取 `descent - ascent + leading`，
     *   与 app 端 `TextPaint.textHeight` 同口径）
     * @param descent 文字下行度量（px，<=0 时自动取 measurer.descent）
     * @param reviewChar 段评占位符（如 `▨`）
     * @param reviewCount 段评数量
     * @param cache 可选的段落折行 LRU 缓存
     * @param fontKey 字体特征键（用于缓存查找）
     * @return 包含该段各行切片与度量数据的 [ParagraphLineMetrics]
     */
    fun layoutParagraph(
        text: String,
        measurer: TextMeasurer,
        visibleWidth: Int,
        paragraphIndent: String = "　　",
        indentCharWidth: Float = 0f,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        paragraphNum: Int = 0,
        centerTitle: Boolean = false,
        textHeight: Float = 0f,
        descent: Float = 0f,
        reviewChar: String = "",
        reviewCount: Int = 0,
        images: List<ImgData> = emptyList(),
        srcReplaceChar: String = ChapterContentParserShared.srcReplaceChar,
        cache: ParagraphLayoutCache? = null,
        fontKey: String = "",
    ): ParagraphLineMetrics {
        if (text.isEmpty()) {
            return ParagraphLineMetrics.EMPTY
        }

        val th = if (textHeight > 0f) {
            textHeight
        } else {
            measurer.descent - measurer.ascent + measurer.leading
        }
        val d = if (descent > 0f) descent else measurer.descent

        val indentLenForCache = if (isFirstLine && !isTitle) paragraphIndent.length else 0
        val cacheKey = if (cache != null && fontKey.isNotEmpty() && images.isEmpty()) {
            ParagraphCacheKey(
                text = text,
                fontKey = fontKey,
                visibleWidth = visibleWidth,
                isTitle = isTitle,
                isFirstLine = isFirstLine,
                indentLength = indentLenForCache,
                textHeight = th,
                descent = d,
                indentCharWidth = indentCharWidth,
                centerTitle = centerTitle,
                reviewChar = reviewChar,
            )
        } else null

        if (cacheKey != null) {
            val cached = cache?.get(cacheKey)
            if (cached != null) {
                return patchOccurrence(cached, paragraphNum, reviewCount)
            }
        }

        val widthsArray = FloatArray(text.length)
        measurer.measureGlyphWidths(text, widthsArray)
        val split = measureTextSplit(text, widthsArray)
        val words = split.words
        if (words.isEmpty()) {
            return ParagraphLineMetrics.EMPTY
        }

        // 阶段 0：相邻标点挤压。必须在 breakLines 之前完成 —— clreq 6.1.1：挤压会影响换行位置，
        // 先挤进才能少退字；放到断行之后就只剩负字距硬塞（迁移前的错路）。
        val cnWidth = cnCharWidth(measurer)
        val squeezed = PunctuationTrimmer.trimAdjacent(words, split.widths, cnWidth)
        // clreq 6.3.3：汉字与西文字母 / 数字之间注入 1/4 汉字宽基础间距。同样改变行宽，
        // 故与挤压并列在断行之前定稿。
        val widths = JustifySpacing.insertHanWestSpacing(words, squeezed.widths, cnWidth)
            ?: squeezed.widths
        val baseOffsets = squeezed.drawOffsets

        val lineRanges = breakLines(
            text = text,
            words = words,
            widths = widths,
            measurer = measurer,
            isFirstLine = isFirstLine && !isTitle,
            indentLength = paragraphIndent.length,
            visibleWidth = visibleWidth,
            cnCharWidth = cnWidth,
        )

        val useGeomIndent = indentCharWidth > 0f
        val lines = ArrayList<LineMetrics>(lineRanges.size)
        var imageOffset = 0

        for ((lineIdx, range) in lineRanges.withIndex()) {
            val lineWords = words.subList(range.first, range.last + 1)
            if (lineWords.isEmpty()) continue
            var lineWidths = widths.subList(range.first, range.last + 1)
            var lineOffsets = baseOffsets?.subList(range.first, range.last + 1)

            // 阶段 2：行尾结束标点裁右半（GB/T 15834「应」）、行首开始夹注裁左半（clreq「可以」）。
            // 在扣缩进之前做：首行行首是缩进字 `　`，不是开始夹注，不会误裁；
            // 缩进后紧跟的开始夹注已由阶段 0 的 U+3000 触发位处理。
            PunctuationTrimmer.trimLineEdges(lineWords, lineWidths, lineOffsets, cnWidth)?.let { edge ->
                lineWidths = edge.widths
                lineOffsets = edge.drawOffsets
            }

            // 若断行恰发生在汉字与西文边界处，移除上一行最后一列残留的中西间距（clreq 6.3.3 行尾不留空白）
            if (range.last < words.size - 1 && cnWidth > 0f &&
                JustifySpacing.isHanWestBoundary(words[range.last], words[range.last + 1])
            ) {
                val lastIdx = lineWidths.lastIndex
                val quarter = cnWidth / 4f
                val trimmed = ArrayList(lineWidths)
                trimmed[lastIdx] = maxOf(0f, trimmed[lastIdx] - quarter)
                lineWidths = trimmed
            }

            val lineImages = if (images.isNotEmpty()) {
                val countInLine = lineWords.count { isImagePlaceholder(it, srcReplaceChar) }
                if (countInLine > 0) {
                    val startImg = imageOffset.coerceAtMost(images.size)
                    val endImg = (imageOffset + countInLine).coerceAtMost(images.size)
                    imageOffset += countInLine
                    images.subList(startImg, endImg)
                } else emptyList()
            } else emptyList()

            val needsIndent = useGeomIndent && isFirstLine && lineIdx == 0 && !isTitle
            val (adjustedWords, adjustedWidths, adjustedOffsets, indentWidth, indentLength) = if (needsIndent) {
                val indentLen = paragraphIndent.length.coerceAtMost(lineWords.size)
                val indentW = indentLen * indentCharWidth
                IndentAdjustment(
                    words = lineWords.subList(indentLen, lineWords.size),
                    widths = lineWidths.subList(indentLen, lineWidths.size),
                    drawOffsets = lineOffsets?.subList(indentLen, lineOffsets.size),
                    indentWidth = indentW,
                    indentLength = indentLen,
                )
            } else {
                IndentAdjustment(
                    words = lineWords,
                    widths = lineWidths,
                    drawOffsets = lineOffsets,
                    indentWidth = 0f,
                    indentLength = 0,
                )
            }

            val desiredWidth = adjustedWidths.fastSum() - trailingSpacingOf(measurer, adjustedWidths)
            val isLastLine = lineIdx == lineRanges.lastIndex
            val lineText = lineWords.joinToString("")

            lines.add(
                LineMetrics(
                    lineIndex = lineIdx,
                    words = adjustedWords,
                    widths = adjustedWidths,
                    text = lineText,
                    textHeight = th,
                    descent = d,
                    desiredWidth = desiredWidth,
                    indentLength = indentLength,
                    indentWidth = indentWidth,
                    isFirstLine = lineIdx == 0,
                    isParagraphEnd = isLastLine,
                    isTitle = isTitle,
                    isImage = false,
                    images = lineImages,
                    paragraphNum = paragraphNum,
                    centerTitle = centerTitle,
                    hasReview = reviewChar.isNotEmpty() && reviewCount > 0 && isLastLine,
                    reviewChar = reviewChar,
                    reviewCount = if (isLastLine) reviewCount else 0,
                    drawOffsets = adjustedOffsets,
                ),
            )
        }

        val result = ParagraphLineMetrics(
            lines = lines,
            isTitle = isTitle,
            isImage = false,
            text = text,
            paragraphNum = paragraphNum,
            textHeight = th,
            descent = d,
        )

        if (cacheKey != null) {
            cache?.put(cacheKey, result)
        }

        return result
    }

    /**
     * 命中缓存后只补随出现位置变化的字段（段号 / 段评数），其余字段由 [ParagraphCacheKey] 保证一致。
     * 段号未变而只有段评数变化时只重建末行，两者都未变直接返回原对象。
     */
    private fun patchOccurrence(
        cached: ParagraphLineMetrics,
        paragraphNum: Int,
        reviewCount: Int,
    ): ParagraphLineMetrics {
        val lines = cached.lines
        val lastLine = lines.lastOrNull() ?: return cached
        val numChanged = cached.paragraphNum != paragraphNum
        val reviewChanged = lastLine.reviewCount != reviewCount
        if (!numChanged && !reviewChanged) return cached
        val patchedLines = if (numChanged) {
            lines.mapIndexed { index, line ->
                if (index == lines.lastIndex) {
                    line.copy(
                        paragraphNum = paragraphNum,
                        hasReview = line.reviewChar.isNotEmpty() && reviewCount > 0,
                        reviewCount = reviewCount,
                    )
                } else {
                    line.copy(paragraphNum = paragraphNum)
                }
            }
        } else {
            ArrayList(lines).apply {
                this[lastIndex] = lastLine.copy(
                    hasReview = lastLine.reviewChar.isNotEmpty() && reviewCount > 0,
                    reviewCount = reviewCount,
                )
            }
        }
        return cached.copy(paragraphNum = paragraphNum, lines = patchedLines)
    }

    /**
     * 断行收口：一律走 [LineBreaker]（平台 ICU 断点 ∩ 中文禁则），产出逐行的簇区间。
     *
     * 传入的 [widths] 必须是已定稿的最终宽度（含阶段 0 挤压与中西间距）：本函数不再改宽度。
     *
     * @param isFirstLine 段落首行（true 时首行回退不越过缩进，避免切出「只有缩进」的空行）
     * @param indentLength 缩进字符数（[isFirstLine] 时作为首行回退下限）
     * @param cnCharWidth 汉字基准宽（px），>0 时启用行末标点裁半回馈（clreq 6.1.1 先挤进）
     */
    fun breakLines(
        text: String,
        words: List<String>,
        widths: List<Float>,
        measurer: TextMeasurer,
        isFirstLine: Boolean,
        indentLength: Int,
        visibleWidth: Int,
        cnCharWidth: Float = 0f,
    ): List<IntRange> {
        val breaker = LineBreaker(
            words = words,
            widths = widths,
            opportunities = measurer.lineBreakOpportunities(text),
            indentSize = if (isFirstLine) indentLength else 0,
            width = visibleWidth,
            letterSpacingPx = measurer.letterSpacingPx,
            cnCharWidth = cnCharWidth,
        )
        val result = ArrayList<IntRange>(breaker.lineCount)
        for (i in 0 until breaker.lineCount) {
            val start = breaker.lineStartCluster[i]
            val end = breaker.lineStartCluster[i + 1] - 1
            if (end >= start) result.add(start..end)
        }
        return result
    }

    /**
     * 行末那一份字距不占版面（[LineBreaker] 装行时同样把它算作余量），
     * 故期望宽度要扣回来：不扣的话两端对齐会把列盒尾对到可视右边线，
     * 而盒尾含这份多余字距 → 墨迹尾端短 1×letterSpacing，行尾对不齐（clreq 6.2.2.1）。
     *
     * 末簇宽度为 0（零宽字符）时说明它不带字距补偿，不扣。
     */
    private fun trailingSpacingOf(measurer: TextMeasurer, widths: List<Float>): Float {
        val spacing = measurer.letterSpacingPx
        if (spacing <= 0f) return 0f
        val last = widths.lastOrNull() ?: return 0f
        return if (last > 0f) spacing else 0f
    }

    /**
     * 汉字基准宽（`measureWidth("我")`）按度量器缓存：安卓上每次都是一次 JNI Paint 测量，
     * 而每段都要取一次（[PunctuationTrimmer] 两阶段的全角判据都用它）。度量器实例 + 字号 + 字距
     * 三者一致才复用，快照整体替换避免读到错配的宽度。
     */
    private class CnCharWidth(
        val measurer: TextMeasurer,
        val textSizePx: Float,
        val letterSpacingPx: Float,
        val width: Float,
    )

    @Volatile
    private var cnCharWidthCache: CnCharWidth? = null

    internal fun cnCharWidth(measurer: TextMeasurer): Float {
        val textSizePx = measurer.textSizePx
        val letterSpacingPx = measurer.letterSpacingPx
        val cached = cnCharWidthCache
        if (cached != null && cached.measurer === measurer &&
            cached.textSizePx == textSizePx && cached.letterSpacingPx == letterSpacingPx
        ) {
            return cached.width
        }
        val width = measurer.measureWidth("我")
        cnCharWidthCache = CnCharWidth(measurer, textSizePx, letterSpacingPx, width)
        return width
    }

    private data class IndentAdjustment(
        val words: List<String>,
        val widths: List<Float>,
        val drawOffsets: List<Float>?,
        val indentWidth: Float,
        val indentLength: Int,
    )
}
