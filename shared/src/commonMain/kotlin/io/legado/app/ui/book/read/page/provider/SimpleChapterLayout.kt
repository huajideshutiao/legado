package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

/**
 * 解析后的段落（文本 + 内嵌图片），供 [SimpleChapterLayout.layout] 图片排版路径使用。
 *
 * 对应 app 端 `ChapterContentParser.ParsedLine`，下沉 commonMain 让跨端排版器直接消费。
 * 调用方负责把原始正文（含 `<img>` 标签）解析为 [ParsedParagraph] 列表：
 * - 文本中的 `<img>` 标签替换为图片占位符（[SimpleChapterLayout.srcReplaceChar]），
 *   并按顺序把 src/style/onclick 收集到 [images]
 * - `<br>` / `\n` 保留在 [text] 中，排版器按 `\n` 切行
 *
 * 不直接依赖 app 端 `ChapterContentParser`（其依赖 `EscapeUtils` / `BookContent`），
 * 由调用方按需构造，保持 commonMain 纯净。
 */
data class ParsedParagraph(
    val text: String,
    val images: List<ImgData> = emptyList(),
)

/**
 * 章节排版器（基于两阶段 Two-Phase Pipeline：ParagraphLayoutEngine + PaginationEngine）。
 *
 * # 架构设计
 *
 * - **Phase 1（断行与度量）**：[ParagraphLayoutEngine.layoutParagraph]，纯函数计算 X 轴字素簇切片、
 *   字符宽度、字高与基线，输出 [ParagraphLineMetrics]，与 Y 轴行距/段距/视口高度完全解耦。
 * - **段落折行缓存**：[ParagraphLayoutCache]（基于 LRU），调节行间距、段间距、上下边距或
 *   视口高度时实现 100% 缓存命中，秒级重排。
 * - **Phase 2（增量分页切片）**：[PaginationEngine.paginate]，纯算术累加 Y 轴坐标（`durY`），
 *   支持双页分栏（[doublePage]）与底部对齐（[textBottomJustify]）作为切页算子，产出只读 [TextPage] 列表。
 *
 * # 功能特性
 *
 * - **图片排版**：支持 SINGLE / FULL / TEXT / DEFAULT 四种图片风格。
 * - **段评**：识别 [reviewChar] 占位符构造 [ReviewColumn]，段评计数从 `reviewCountMap` 注入。
 * - **双页**：[doublePage]=true 时左右分栏，标记 [io.legado.app.ui.book.read.page.entities.TextLine.isLeftLine]。
 * - **底部对齐**：[textBottomJustify]=true 时由 [TextPage.upLinesPosition] 调整 surplus 均摊。
 * - **段落缩进**：支持字符拼接与等宽几何缩进（[indentCharWidth]）。
 * - **断行**：复用 [LineBreaker]（平台 ICU 断点 ∩ 中文禁则），前置 [PunctuationTrimmer] 标点挤压。
 * - **朗读高亮**：写入真实连续章节字符偏移 [io.legado.app.ui.book.read.page.entities.TextLine.chapterPosition]。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class SimpleChapterLayout(
    val measurer: TextMeasurer,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val paddingLeft: Int,
    val paddingTop: Int,
    val textHeight: Float,
    val descent: Float,
    val lineSpacingExtra: Float,
    val paragraphSpacing: Int,
    val titleTopSpacing: Int,
    val titleBottomSpacing: Int,
    val endPadding: Int = 0,
    val paragraphIndent: String,
    val textFullJustify: Boolean,
    val viewWidth: Int = visibleWidth + paddingLeft * 2,
    val doublePage: Boolean = false,
    val textBottomJustify: Boolean = false,
    val indentCharWidth: Float = 0f,
    val indentChar: String = "　",
    val titleMode: Int = 0,
    val titleMeasurer: TextMeasurer = measurer,
    val titleTextHeight: Float = textHeight,
    val titleDescent: Float = descent,
    val reviewChar: String = "",
    val srcReplaceChar: String = ChapterContentParserShared.srcReplaceChar,
    val layoutCache: ParagraphLayoutCache = ParagraphLayoutCache(),
    val contentWeight: Int = 400,
    val titleWeight: Int = 700,
) {

    /**
     * 段评计数 map（layout 开始时由调用方注入，key=逻辑段号，value=段评数）。
     */
    @Volatile
    private var reviewCountMap: Map<Int, Int>? = null

    private val titleFontKey: String =
        "title_${titleMeasurer.textSizePx}_${titleMeasurer.letterSpacingPx}_$titleWeight"

    private val bodyFontKey: String =
        "body_${measurer.textSizePx}_${measurer.letterSpacingPx}_$contentWeight"

    /**
     * 排版章节正文，产出 [TextPage] 列表。
     *
     * 编排流程：
     * 1. 触发 [prefetchCallback] 异步预取上一章 / 下一章；
     * 2. Phase 1：将标题与段落通过 [ParagraphLayoutEngine] 断行度量为 [ParagraphLineMetrics] 列表（带 LRU 缓存）；
     * 3. Phase 2：将 [ParagraphLineMetrics] 列表交由 [PaginationEngine] 进行纯算术切片与分页，
     *    执行双页分栏切片与底部对齐均摊。
     *
     * @param displayTitle 章节标题（可含 `\n` 多行）
     * @param contents 段落列表（每项为一段正文，已去除 `\n`）；[parsedParagraphs] 为空时使用
     * @param chapterIndex 章节序号（写入 [TextPage.chapterIndex]）
     * @param chapterSize 章节总数（写入 [TextPage.chapterSize]）
     * @param reviewCountMap 段评计数 map（key=逻辑段号 1..N，value=段评数）；null=不挂段评气泡
     * @param parsedParagraphs 解析后的段落（含图片占位符 [srcReplaceChar] + 图片列表）；非空时替代 [contents]
     * @param imageResolver 图片尺寸解析器；null 时跳过图片排版（退化为纯文本）
     * @param imageStyle 图片风格（`SINGLE` / `FULL` / `TEXT` / `DEFAULT`）
     * @param contentProcessor 替换规则处理函数（对每段正文应用，返回处理后文本）；null=不处理
     * @param prefetchCallback 预取回调（`direction=-1` 预取上一章，`+1` 预取下一章）
     * @return 排版后的 [TextPage] 列表
     */
    suspend fun layout(
        displayTitle: String,
        contents: List<String>,
        chapterIndex: Int,
        chapterSize: Int,
        reviewCountMap: Map<Int, Int>? = null,
        parsedParagraphs: List<ParsedParagraph>? = null,
        imageResolver: ImageResolver? = null,
        imageStyle: String? = null,
        contentProcessor: ((String) -> String)? = null,
        prefetchCallback: ((Int) -> Unit)? = null,
    ): ArrayList<TextPage> {
        this.reviewCountMap = reviewCountMap

        // 1. 异步预取上下章
        prefetchCallback?.let { cb ->
            cb(-1)
            cb(1)
        }

        val paragraphMetricsList = mutableListOf<ParagraphLineMetrics>()
        val emptyContent = if (parsedParagraphs != null) parsedParagraphs.isEmpty() else contents.isEmpty()
        val centerTitle = titleMode == 1 || emptyContent ||
            imageStyle?.uppercase() == Book.imgStyleSingle

        // 2. Phase 1：排版标题段落
        if (displayTitle.isNotEmpty() && (titleMode != 2 || emptyContent)) {
            val titleLines = displayTitle.split("\n").filter { it.isNotBlank() }
            titleLines.forEachIndexed { idx, titleLine ->
                coroutineContext.ensureActive()
                val isLastTitleLine = idx == titleLines.lastIndex
                val reviewCountForTitle = if (isLastTitleLine) (reviewCountMap?.get(0) ?: 0) else 0
                val titleText = if (reviewChar.isNotEmpty() && reviewCountForTitle > 0) {
                    titleLine + reviewChar
                } else titleLine

                val metrics = ParagraphLayoutEngine.layoutParagraph(
                    text = titleText,
                    measurer = titleMeasurer,
                    visibleWidth = visibleWidth,
                    paragraphIndent = "",
                    indentCharWidth = 0f,
                    isTitle = true,
                    isFirstLine = true,
                    paragraphNum = 0,
                    centerTitle = centerTitle,
                    textHeight = titleTextHeight,
                    descent = titleDescent,
                    reviewChar = reviewChar,
                    reviewCount = reviewCountForTitle,
                    cache = layoutCache,
                    fontKey = titleFontKey,
                )
                paragraphMetricsList.add(metrics)
            }
        }

        // 3. Phase 1：排版正文段落
        if (parsedParagraphs != null) {
            val parsedMetrics = buildParsedParagraphMetrics(
                parsedParagraphs = parsedParagraphs,
                imageResolver = imageResolver,
                imageStyle = imageStyle,
            )
            paragraphMetricsList.addAll(parsedMetrics)
        } else {
            var paragraphSeq = 0
            for (paragraph in contents) {
                coroutineContext.ensureActive()
                if (paragraph.isBlank()) continue
                paragraphSeq++
                val processed = contentProcessor?.invoke(paragraph.trim()) ?: paragraph.trim()
                val line = if (paragraphIndent.isNotEmpty()) paragraphIndent + processed else processed
                val reviewCountForLine = reviewCountMap?.get(paragraphSeq) ?: 0
                val lineWithReview = if (reviewChar.isNotEmpty() && line.isNotEmpty() && reviewCountForLine > 0) {
                    line + reviewChar
                } else line

                val metrics = ParagraphLayoutEngine.layoutParagraph(
                    text = lineWithReview,
                    measurer = measurer,
                    visibleWidth = visibleWidth,
                    paragraphIndent = paragraphIndent,
                    indentCharWidth = indentCharWidth,
                    isTitle = false,
                    isFirstLine = true,
                    paragraphNum = paragraphSeq,
                    textHeight = textHeight,
                    descent = descent,
                    reviewChar = reviewChar,
                    reviewCount = reviewCountForLine,
                    cache = layoutCache,
                    fontKey = bodyFontKey,
                )
                paragraphMetricsList.add(metrics)
            }
        }

        // 4. Phase 2：分页切片引擎
        val paginationConfig = PaginationConfig(
            visibleWidth = visibleWidth,
            visibleHeight = visibleHeight,
            viewWidth = viewWidth,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            lineSpacingExtra = lineSpacingExtra,
            paragraphSpacing = paragraphSpacing,
            textHeight = textHeight,
            titleTopSpacing = titleTopSpacing,
            titleBottomSpacing = titleBottomSpacing,
            endPadding = endPadding,
            doublePage = doublePage,
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            titleMode = titleMode,
            displayTitle = displayTitle,
            chapterIndex = chapterIndex,
            chapterSize = chapterSize,
            imageStyle = imageStyle,
            emptyContent = emptyContent,
            indentChar = indentChar,
            // 两端对齐各档余量上限的基准（clreq 6.2.2.4），与 Phase 1 挤压取同一个汉字宽
            cnCharWidth = ParagraphLayoutEngine.cnCharWidth(measurer),
            columnFactory = SimpleColumnFactory(),
        )

        return PaginationEngine.paginate(
            paragraphs = paragraphMetricsList,
            config = paginationConfig,
        )
    }

    /**
     * 图片段落解析与 Phase 1 度量构建。
     */
    private suspend fun buildParsedParagraphMetrics(
        parsedParagraphs: List<ParsedParagraph>,
        imageResolver: ImageResolver?,
        imageStyle: String?,
    ): List<ParagraphLineMetrics> {
        val result = mutableListOf<ParagraphLineMetrics>()
        var paragraphSeq = 0
        val styleUpper = imageStyle?.uppercase()
        val isTextImageStyle = styleUpper == Book.imgStyleText

        for (parsedLine in parsedParagraphs) {
            coroutineContext.ensureActive()
            val contentText = parsedLine.text
            val imgList = ArrayDeque<ImgData>(parsedLine.images.size)
            parsedLine.images.forEach { imgList.add(it) }

            var lineStartIndex = 0
            val contentLength = contentText.length
            while (lineStartIndex < contentLength) {
                coroutineContext.ensureActive()
                var lineEndIndex = contentText.indexOf('\n', lineStartIndex)
                if (lineEndIndex == -1) lineEndIndex = contentLength

                val rawLine = contentText.substring(lineStartIndex, lineEndIndex)
                val startsWithImage = srcReplaceChar.isNotEmpty() &&
                    rawLine.trimStart(' ', '　').startsWith(srcReplaceChar)

                val line = when {
                    startsWithImage && !isTextImageStyle -> rawLine.trimStart(' ', '　')
                    rawLine.startsWith("　　") -> paragraphIndent + rawLine.substring(2)
                    paragraphIndent.isNotEmpty() && rawLine.isNotEmpty() ->
                        paragraphIndent + rawLine.trimStart(' ', '　')
                    else -> rawLine
                }

                paragraphSeq++
                val reviewCountForLine = reviewCountMap?.get(paragraphSeq) ?: 0
                val lineWithReview =
                    if (reviewChar.isNotEmpty() && line.isNotEmpty() && reviewCountForLine > 0) {
                        line + reviewChar
                    } else line

                if (isTextImageStyle || imgList.isEmpty()) {
                    val countInLine = if (srcReplaceChar.isNotEmpty()) {
                        lineWithReview.count { it == srcReplaceChar[0] }
                    } else 0
                    val lineImgs = if (countInLine > 0 && imgList.isNotEmpty()) {
                        val list = ArrayList<ImgData>(countInLine)
                        repeat(countInLine) {
                            imgList.removeFirstOrNull()?.let { list.add(it) }
                        }
                        list
                    } else emptyList()

                    val metrics = ParagraphLayoutEngine.layoutParagraph(
                        text = lineWithReview,
                        measurer = measurer,
                        visibleWidth = visibleWidth,
                        paragraphIndent = paragraphIndent,
                        indentCharWidth = indentCharWidth,
                            isTitle = false,
                        isFirstLine = true,
                        paragraphNum = paragraphSeq,
                        textHeight = textHeight,
                        descent = descent,
                        reviewChar = reviewChar,
                        reviewCount = reviewCountForLine,
                        images = lineImgs,
                        srcReplaceChar = srcReplaceChar,
                        cache = if (lineImgs.isEmpty()) layoutCache else null,
                        fontKey = bodyFontKey,
                    )
                    result.add(metrics)
                } else {
                    val embeddedImages = ArrayDeque<ImgData>()
                    val hasNonEmbeddedImage = srcReplaceChar.isNotEmpty() && line.contains(srcReplaceChar)
                    var isFirstSegment = true
                    val tmp = StringBuilder()

                    lineWithReview.forEach { char ->
                        if (srcReplaceChar.isNotEmpty() && char == srcReplaceChar[0]) {
                            val img = imgList.removeFirstOrNull() ?: return@forEach
                            if (img.style.equals("TEXT", true)) {
                                embeddedImages.add(img)
                                tmp.append(char)
                            } else {
                                if (tmp.isNotEmpty()) {
                                    val metrics = ParagraphLayoutEngine.layoutParagraph(
                                        text = tmp.toString(),
                                        measurer = measurer,
                                        visibleWidth = visibleWidth,
                                        paragraphIndent = paragraphIndent,
                                        indentCharWidth = indentCharWidth,
                                                            isTitle = false,
                                        isFirstLine = isFirstSegment,
                                        paragraphNum = paragraphSeq,
                                        textHeight = textHeight,
                                        descent = descent,
                                        images = embeddedImages.toList(),
                                        srcReplaceChar = srcReplaceChar,
                                        cache = if (embeddedImages.isEmpty()) layoutCache else null,
                                        fontKey = bodyFontKey,
                                    )
                                    result.add(metrics)
                                    tmp.clear()
                                    embeddedImages.clear()
                                    isFirstSegment = false
                                }
                                val rawSize = imageResolver?.getImageSize(img.src)
                                val effectiveStyle = img.style.takeIf { it.isNotBlank() } ?: imageStyle
                                val effectiveUpper = effectiveStyle?.uppercase()
                                val (fitW, fitH) = if (rawSize != null && rawSize.width > 0 && rawSize.height > 0) {
                                    if (effectiveUpper == Book.imgStyleFull) {
                                        val fullW = visibleWidth.toFloat()
                                        val fullH = rawSize.height.toFloat() * visibleWidth / rawSize.width
                                        getFitSize(fullW, fullH, visibleWidth.toFloat(), visibleHeight.toFloat())
                                    } else {
                                        getFitSize(
                                            rawSize.width.toFloat(),
                                            rawSize.height.toFloat(),
                                            visibleWidth.toFloat(),
                                            visibleHeight.toFloat(),
                                        )
                                    }
                                } else {
                                    val defaultW = visibleWidth.toFloat()
                                    val defaultH = (visibleWidth * 0.6f).coerceAtMost(visibleHeight * 0.5f).coerceAtLeast(100f)
                                    defaultW to defaultH
                                }
                                val imgMetrics = ParagraphLineMetrics.createImage(
                                    img = img,
                                    width = fitW,
                                    height = fitH,
                                    paragraphNum = paragraphSeq,
                                )
                                result.add(imgMetrics)
                            }
                        } else {
                            tmp.append(char)
                        }
                    }
                    if (tmp.isNotEmpty()) {
                        val metrics = ParagraphLayoutEngine.layoutParagraph(
                            text = tmp.toString(),
                            measurer = measurer,
                            visibleWidth = visibleWidth,
                            paragraphIndent = paragraphIndent,
                            indentCharWidth = indentCharWidth,
                                    isTitle = false,
                            isFirstLine = !hasNonEmbeddedImage && isFirstSegment,
                            paragraphNum = paragraphSeq,
                            textHeight = textHeight,
                            descent = descent,
                            reviewChar = reviewChar,
                            reviewCount = reviewCountForLine,
                            images = embeddedImages.toList(),
                            srcReplaceChar = srcReplaceChar,
                            cache = if (embeddedImages.isEmpty()) layoutCache else null,
                            fontKey = bodyFontKey,
                        )
                        result.add(metrics)
                    }
                }
                lineStartIndex = lineEndIndex + 1
            }
        }
        return result
    }

    /**
     * 列工厂：按 [reviewChar] / [srcReplaceChar] / 普通字符分别构造 [ReviewColumn] /
     * [ImageColumn] / [TextColumn]。段号只认 [PaginationEngine] 传入的行内真实段号。
     */
    private inner class SimpleColumnFactory : ColumnFactory {
        override fun createColumn(
            absStartX: Int,
            char: String,
            xStart: Float,
            xEnd: Float,
            imgList: MutableList<ImgData>?,
            paragraphIndex: Int,
            drawOffsetX: Float,
        ): BaseColumn = when {
            reviewChar.isNotEmpty() && char == reviewChar -> {
                val cnt = reviewCountMap?.get(paragraphIndex) ?: 0
                ReviewColumn(absStartX + xStart, absStartX + xEnd, paragraphIndex, cnt)
            }
            isImagePlaceholder(char, srcReplaceChar) -> {
                val img = imgList?.removeFirstOrNull()
                // 占位符没有配对图片时退回文本列（对齐 app 端 createColumn 的 else 分支），
                // 不发空 src 图片列
                if (img != null) {
                    ImageColumn(absStartX + xStart, absStartX + xEnd, img.src, img.onclick)
                } else {
                    TextColumn(absStartX + xStart, absStartX + xEnd, char, drawOffsetX)
                }
            }
            else -> TextColumn(absStartX + xStart, absStartX + xEnd, char, drawOffsetX)
        }
    }
}
