package io.legado.app.ui.book.read.page.provider

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
 * @param widths 该行各字素簇宽度（px）
 * @param rawWords 原始完整字素簇列表（含缩进字符）
 * @param rawWidths 原始完整字素簇宽度列表
 * @param text 该行完整文本
 * @param textHeight 字体高度（px，descent - ascent）
 * @param descent 文字下行度量（px）
 * @param desiredWidth 期望宽度（px，adjustedWidths 之和）
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
 */
data class LineMetrics(
    val lineIndex: Int,
    val words: List<String>,
    val widths: List<Float>,
    val rawWords: List<String> = words,
    val rawWidths: List<Float> = widths,
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
 * @param textHeight 段落字体高度（px）
 * @param descent 段落下行度量（px）
 */
data class ParagraphLineMetrics(
    val lines: List<LineMetrics>,
    val isTitle: Boolean = false,
    val isImage: Boolean = false,
    val text: String = "",
    val paragraphNum: Int = 0,
    val textHeight: Float = lines.firstOrNull()?.textHeight ?: 0f,
    val descent: Float = lines.firstOrNull()?.descent ?: 0f,
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
                rawWords = listOf(" "),
                rawWidths = listOf(width),
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
 * 2. **中文避头尾断行**：内置 [ZhLineBreaker] 避头尾规则与按宽度累加的退化断行。
 * 3. **首行缩进几何度量**：支持字符拼接与等宽几何缩进（[indentCharWidth]）双模式。
 * 4. **段落折行缓存对接**：支持传入 [ParagraphLayoutCache] 实现秒级重排与 100% 缓存命中。
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
     * @param useZhLayout 是否启用 [ZhLineBreaker] 中文避头尾断行
     * @param isTitle 是否为章节标题
     * @param isFirstLine 是否为段落首行（影响缩进判定）
     * @param paragraphNum 逻辑段号（0 为标题，>=1 为正文段号）
     * @param centerTitle 标题是否居中
     * @param textHeight 字体高度（px，<=0 时自动取 measurer.descent - measurer.ascent）
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
        useZhLayout: Boolean = true,
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

        val th = if (textHeight > 0f) textHeight else (measurer.descent - measurer.ascent)
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
        val widths = split.widths
        if (words.isEmpty()) {
            return ParagraphLineMetrics.EMPTY
        }

        val lineRanges: List<IntRange> = if (useZhLayout) {
            breakByZh(
                words = words,
                widths = widths,
                measurer = measurer,
                isFirstLine = isFirstLine && !isTitle,
                indentLength = paragraphIndent.length,
                visibleWidth = visibleWidth,
            )
        } else {
            breakByAccumulateWidth(
                words = words,
                widths = widths,
                visibleWidth = visibleWidth,
            )
        }

        val useGeomIndent = indentCharWidth > 0f
        val lines = ArrayList<LineMetrics>(lineRanges.size)
        var imageOffset = 0

        for ((lineIdx, range) in lineRanges.withIndex()) {
            val lineWords = words.subList(range.first, range.last + 1)
            val lineWidths = widths.subList(range.first, range.last + 1)
            if (lineWords.isEmpty()) continue

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
            val (adjustedWords, adjustedWidths, indentWidth, indentLength) = if (needsIndent) {
                val indentLen = paragraphIndent.length.coerceAtMost(lineWords.size)
                val indentW = indentLen * indentCharWidth
                IndentAdjustment(
                    words = lineWords.subList(indentLen, lineWords.size),
                    widths = lineWidths.subList(indentLen, lineWidths.size),
                    indentWidth = indentW,
                    indentLength = indentLen,
                )
            } else {
                IndentAdjustment(
                    words = lineWords,
                    widths = lineWidths,
                    indentWidth = 0f,
                    indentLength = 0,
                )
            }

            val desiredWidth = adjustedWidths.fastSum()
            val isLastLine = lineIdx == lineRanges.lastIndex
            val lineText = lineWords.joinToString("")

            lines.add(
                LineMetrics(
                    lineIndex = lineIdx,
                    words = adjustedWords,
                    widths = adjustedWidths,
                    rawWords = lineWords,
                    rawWidths = lineWidths,
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
     * 用 [ZhLineBreaker] 避头尾断行。
     */
    fun breakByZh(
        words: List<String>,
        widths: List<Float>,
        measurer: TextMeasurer,
        isFirstLine: Boolean,
        indentLength: Int,
        visibleWidth: Int,
    ): List<IntRange> {
        val breaker = ZhLineBreaker(
            words = words,
            widths = widths,
            indentSize = if (isFirstLine) indentLength else 0,
            width = visibleWidth,
            cnCharWidth = cnCharWidth(measurer),
            letterSpacingPx = measurer.letterSpacingPx,
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
     * 退化断行：按 [visibleWidth] 累积字宽。
     */
    fun breakByAccumulateWidth(
        words: List<String>,
        widths: List<Float>,
        visibleWidth: Int,
    ): List<IntRange> {
        val result = ArrayList<IntRange>()
        var lineStart = 0
        var lineWidth = 0f
        for (i in words.indices) {
            val cw = widths[i]
            if (lineWidth + cw > visibleWidth && i > lineStart) {
                result.add(lineStart..(i - 1))
                lineStart = i
                lineWidth = 0f
            }
            lineWidth += cw
        }
        if (lineStart < words.size) {
            result.add(lineStart..(words.size - 1))
        }
        return result
    }

    /**
     * 汉字基准宽（`measureWidth("我")`）按度量器缓存：安卓上每次都是一次 JNI Paint 测量，
     * 而每段都要取一次。度量器实例 + 字号 + 字距三者一致才复用，快照整体替换避免读到错配的宽度。
     */
    private class CnCharWidth(
        val measurer: TextMeasurer,
        val textSizePx: Float,
        val letterSpacingPx: Float,
        val width: Float,
    )

    @Volatile
    private var cnCharWidthCache: CnCharWidth? = null

    private fun cnCharWidth(measurer: TextMeasurer): Float {
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
        val indentWidth: Float,
        val indentLength: Int,
    )
}
