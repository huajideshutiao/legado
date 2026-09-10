package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/**
 * 分页切片配置数据类。
 *
 * 封装 Phase 2（增量分页切片）所需的所有几何尺寸与排版开关。
 */
data class PaginationConfig(
    val visibleWidth: Int,
    val visibleHeight: Int,
    val paddingLeft: Int = 0,
    val paddingTop: Int = 0,
    val viewWidth: Int = visibleWidth + paddingLeft * 2,
    val lineSpacingExtra: Float = 1.0f,
    val paragraphSpacing: Int = 0,
    /**
     * 正文行盒高（px，= `descent - ascent`）：底部对齐折算与图片段落的段间距都按它算，
     * 图片高不参与。标题段落的行高另存在各自的 [ParagraphLineMetrics.textHeight] 里。
     */
    val textHeight: Float = 0f,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    val endPadding: Int = 0,
    val doublePage: Boolean = false,
    val textFullJustify: Boolean = true,
    val textBottomJustify: Boolean = false,
    val titleMode: Int = 0,
    val displayTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterSize: Int = 1,
    val imageStyle: String? = null,
    val emptyContent: Boolean = false,
    val indentChar: String = "　",
    /**
     * 汉字基准宽（px，= `measureWidth("我")`）：两端对齐各档余量上限的基准
     * （clreq 6.2.2.4 的 1/2 / 1/4 汉字宽）。<=0 时不拉伸，余量留在行尾。
     */
    val cnCharWidth: Float = 0f,
    val columnFactory: ColumnFactory? = null,
)

/**
 * 增量分页切片引擎（Phase 2：纯算术切片与分页）。
 *
 * 核心功能：
 * 1. 接收 Phase 1 计算出的 [ParagraphLineMetrics] 段落度量列表。
 * 2. 纯算术累加 Y 轴坐标（[durY]），根据 [PaginationConfig.visibleHeight] 动态切页。
 * 3. 支持双页分栏（[PaginationConfig.doublePage]）：左栏排满切右栏，右栏排满翻页。
 * 4. 支持底部对齐算子（[PaginationConfig.textBottomJustify]）：页面排满后按 surplus 均摊行间距。
 * 5. 产出只读 [TextPage] 列表，包含各行准确的几何位置、字符偏移与朗读索引。
 */
object PaginationEngine {

    /**
     * 执行 Phase 2 分页切片。
     *
     * @param paragraphs Phase 1 产出的段落度量列表
     * @param config 分页参数配置
     * @param callback 协程取消与页面完成回调（可选）
     * @return 分页排版后的 [TextPage] 列表
     */
    suspend fun paginate(
        paragraphs: List<ParagraphLineMetrics>,
        config: PaginationConfig,
        callback: TextLayoutCallback? = null,
    ): ArrayList<TextPage> {
        val pages = arrayListOf<TextPage>()
        var durY = 0f
        var absStartX = config.paddingLeft
        var pendingTextPage = TextPage()
        val stringBuilder = StringBuilder()

        val columnFactory = config.columnFactory ?: DefaultColumnFactory
        // 正文行盒高（全章常量）：底部对齐折算与图片段落的段间距都用它
        val bodyTextHeight = config.textHeight

        fun onPageCompleted() {
            val page = pendingTextPage
            val pIdx = pages.size
            page.index = pIdx
            page.chapterIndex = config.chapterIndex
            page.chapterSize = config.chapterSize
            page.title = config.displayTitle
            page.doublePage = config.doublePage
            page.paddingTop = config.paddingTop
            page.textBottomJustify = config.textBottomJustify
            page.visibleHeight = config.visibleHeight
            page.visibleBottom = config.paddingTop + config.visibleHeight
            page.contentPaintTextHeight = bodyTextHeight
            page.lineSpacingExtra = config.lineSpacingExtra
            if (page.leftLineSize == 0) page.leftLineSize = page.lineSize
            page.upLinesPosition()
            if (page.lineSize > 0) {
                page.upRenderHeight()
            }
            pages.add(page)
            callback?.onPageCompleted()
        }

        suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
            if (requestHeight > config.visibleHeight || requestHeight == -1f) {
                if (pendingTextPage.height < durY) pendingTextPage.height = durY
                if (config.doublePage && absStartX < config.viewWidth / 2) {
                    pendingTextPage.leftLineSize = pendingTextPage.lineSize
                    absStartX = config.viewWidth / 2 + config.paddingLeft
                } else {
                    if (pendingTextPage.leftLineSize == 0) {
                        pendingTextPage.leftLineSize = pendingTextPage.lineSize
                    }
                    pendingTextPage.text = stringBuilder.toString()
                    currentCoroutineContext().ensureActive()
                    callback?.ensureActive()
                    onPageCompleted()
                    pendingTextPage = TextPage()
                    stringBuilder.clear()
                    absStartX = config.paddingLeft
                }
                durY = 0f
            }
        }

        fun calcChapterPosition(sbLength: Int): Int {
            val lastPageLastLine = pages.lastOrNull()?.lines?.lastOrNull()
            val base = lastPageLastLine?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0
            return base + sbLength
        }

        fun addIndentChars(textLine: TextLine, indentLength: Int, indentWidth: Float): Float {
            if (indentLength <= 0) return 0f
            val step = indentWidth / indentLength
            var x = 0f
            repeat(indentLength) {
                val x1 = x + step
                textLine.addColumn(TextColumn(absStartX + x, absStartX + x1, config.indentChar))
                x = x1
                textLine.indentWidth = x
            }
            textLine.indentSize = indentLength
            return x
        }

        fun exceed(textLine: TextLine, words: List<String>) {
            var size = words.size
            if (size < 2) return
            val visibleEnd = absStartX + config.visibleWidth
            val columns = textLine.columns
            var offset = 0
            val endColumn = if (words.last() == " ") {
                size--
                offset++
                columns[columns.lastIndex - 1]
            } else columns.last()
            val endX = endColumn.end.roundToInt()
            if (endX > visibleEnd) {
                textLine.exceed = true
                val cc = (endX - visibleEnd) / size
                for (i in 0 until size) {
                    textLine.getColumnReverseAt(i, offset).let {
                        val py = cc * (size - i)
                        it.start -= py
                        it.end -= py
                    }
                }
            }
        }

        fun addCharsToLine(
            textLine: TextLine,
            char: String,
            xStart: Float,
            xEnd: Float,
            imgList: MutableList<ImgData>? = null,
            drawOffsetX: Float = 0f,
        ) {
            val column = columnFactory.createColumn(
                absStartX, char, xStart, xEnd, imgList, textLine.paragraphNum, drawOffsetX,
            )
            textLine.addColumn(column)
        }

        /** 取第 [index] 簇的绘制偏移（无挤压表时为 0）。 */
        fun offsetAt(drawOffsets: List<Float>?, index: Int): Float =
            drawOffsets?.get(index) ?: 0f

        fun addCharsToLineNatural(
            textLine: TextLine,
            words: List<String>,
            startX: Float,
            textWidths: List<Float>,
            imgList: MutableList<ImgData>? = null,
            drawOffsets: List<Float>? = null,
        ) {
            textLine.startX = absStartX + startX
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = x + cw
                addCharsToLine(textLine, char, x, x1, imgList, offsetAt(drawOffsets, index))
                x = x1
            }
            exceed(textLine, words)
        }

        /**
         * 两端对齐的余量分配（clreq 6.2.2.4 拉伸优先顺序）。
         *
         * 有意偏离原版：原版 `justifyByLetterSpacing` 把余量无上限均摊到**每一个**簇间隙，
         * 会把 `Windows` 的字母、`2026/09/04` 的数字、`——` `……` 内部都拉开；clreq 禁止对
         * 符号分离禁则与连接号 / 分隔号前后拉伸，6.2.3 又规定西文词组内不均排。
         * 分类与上限见 [JustifySpacing]。
         */
        fun justifyLine(
            textLine: TextLine,
            words: List<String>,
            startX: Float,
            textWidths: List<Float>,
            residualWidth: Float,
            imgList: MutableList<ImgData>? = null,
            drawOffsets: List<Float>? = null,
        ) {
            val extra = JustifySpacing.distribute(
                residual = residualWidth,
                kinds = JustifySpacing.classifyGaps(words),
                cnCharWidth = config.cnCharWidth,
                widths = textWidths,
            )
            var x = startX
            for (index in words.indices) {
                val x1 = x + textWidths[index] + (extra?.getOrNull(index) ?: 0f)
                addCharsToLine(textLine, words[index], x, x1, imgList, offsetAt(drawOffsets, index))
                x = x1
            }
        }

        fun addCharsToLineMiddle(
            textLine: TextLine,
            words: List<String>,
            desiredWidth: Float,
            startX: Float,
            textWidths: List<Float>,
            imgList: MutableList<ImgData>? = null,
            drawOffsets: List<Float>? = null,
        ) {
            if (!config.textFullJustify) {
                addCharsToLineNatural(textLine, words, startX, textWidths, imgList, drawOffsets)
                return
            }
            textLine.startX = absStartX + startX
            val residualWidth = config.visibleWidth - startX - desiredWidth
            justifyLine(
                textLine, words, startX, textWidths, residualWidth, imgList, drawOffsets,
            )
            exceed(textLine, words)
        }

        for ((pIdx, paragraph) in paragraphs.withIndex()) {
            // 逐段取消检查：切章/翻页后不再白排完整章（逐字循环里不放，段级足够）
            currentCoroutineContext().ensureActive()
            if (paragraph.isEmpty) continue

            if (paragraph.isTitle) {
                val isLastTitleParagraph = paragraphs.getOrNull(pIdx + 1)?.isTitle != true
                // 标题垂直居中计算
                if (config.emptyContent && pages.isEmpty()) {
                    val textPage = pendingTextPage
                    if (textPage.lineSize == 0) {
                        val ty = (config.visibleHeight - paragraph.lineCount * paragraph.textHeight) / 2
                        durY = if (ty > config.titleTopSpacing) ty else config.titleTopSpacing.toFloat()
                    } else {
                        var textLayoutHeight = paragraph.lineCount * paragraph.textHeight
                        val firstLine = textPage.getLine(0)
                        if (firstLine.lineTop < textLayoutHeight + config.titleTopSpacing) {
                            textLayoutHeight = firstLine.lineTop - config.titleTopSpacing
                        }
                        textPage.lines.forEach {
                            it.lineTop -= textLayoutHeight
                            it.lineBase -= textLayoutHeight
                            it.lineBottom -= textLayoutHeight
                        }
                        durY -= textLayoutHeight
                    }
                } else if (pages.isEmpty() && pendingTextPage.lines.isEmpty()) {
                    durY = when (config.imageStyle?.uppercase()) {
                        Book.imgStyleSingle -> {
                            val ty = (config.visibleHeight - paragraph.lineCount * paragraph.textHeight) / 2
                            if (ty > config.titleTopSpacing) ty else config.titleTopSpacing.toFloat()
                        }
                        else -> durY + config.titleTopSpacing
                    }
                }

                for (line in paragraph.lines) {
                    prepareNextPageIfNeed(durY + line.textHeight)
                    val textLine = TextLine(isTitle = true)
                    textLine.paragraphNum = line.paragraphNum
                    val shouldCenter = line.centerTitle || config.titleMode == 1 || config.emptyContent ||
                        config.imageStyle?.uppercase() == Book.imgStyleSingle
                    val startX = if (shouldCenter) (config.visibleWidth - line.desiredWidth) / 2 else 0f
                    // 标题不做两端对齐：clreq 6.2.1.3「单行对齐处理」—— 标题这类短文本取居中或
                    // 行首对齐，行内文字原则上密排，不把余量均排到字间（长标题自动折行时同理）。
                    addCharsToLineNatural(textLine, line.words, startX, line.widths, null, line.drawOffsets)
                    if (config.doublePage) textLine.isLeftLine = absStartX < config.viewWidth / 2
                    textLine.text = line.text
                    textLine.lineTop = config.paddingTop + durY
                    textLine.lineBottom = textLine.lineTop + line.textHeight
                    textLine.lineBase = textLine.lineBottom - line.descent
                    textLine.isParagraphEnd = line.isParagraphEnd
                    textLine.chapterPosition = calcChapterPosition(stringBuilder.length)
                    textLine.pagePosition = stringBuilder.length
                    stringBuilder.append(line.text)
                    pendingTextPage.addLine(textLine)
                    durY += line.textHeight * config.lineSpacingExtra
                    if (pendingTextPage.height < durY) pendingTextPage.height = durY
                }

                if (isLastTitleParagraph) {
                    durY += config.titleBottomSpacing
                    stringBuilder.append("\n")
                    if (config.imageStyle?.uppercase() == Book.imgStyleSingle &&
                        pendingTextPage.lines.isNotEmpty() && !config.emptyContent
                    ) {
                        prepareNextPageIfNeed(-1f)
                    }
                } else {
                    durY += paragraph.textHeight * config.paragraphSpacing / 10f
                    stringBuilder.append("\n")
                }
            } else if (paragraph.isImage) {
                val line = paragraph.lines.first()
                // 块状图片段落只由 ParagraphLineMetrics.createImage 产出，imageData 必然在
                val img = checkNotNull(line.imageData) { "图片段落缺 ImgData" }
                val effectiveStyle = img.style.takeIf { it.isNotBlank() } ?: config.imageStyle
                val styleUpper = effectiveStyle?.uppercase()
                val isSingle = styleUpper == Book.imgStyleSingle

                when (styleUpper) {
                    Book.imgStyleFull -> {
                        if (line.imageHeight > config.visibleHeight - durY) {
                            prepareNextPageIfNeed(durY + line.imageHeight)
                        }
                    }
                    Book.imgStyleSingle -> {
                        if (durY > 0f || pendingTextPage.lines.isNotEmpty()) {
                            prepareNextPageIfNeed(-1f)
                        }
                        durY = (config.visibleHeight - line.imageHeight) / 2f
                    }
                    else -> prepareNextPageIfNeed(durY + line.imageHeight)
                }

                val textLine = TextLine(isImage = true)
                textLine.text = " "
                textLine.lineTop = durY + config.paddingTop
                textLine.lineBottom = durY + line.imageHeight + config.paddingTop
                val startX = if (config.visibleWidth > line.imageWidth) (config.visibleWidth - line.imageWidth) / 2f else 0f
                textLine.addColumn(
                    ImageColumn(
                        absStartX + startX,
                        absStartX + startX + line.imageWidth,
                        img.src,
                        img.onclick,
                    ),
                )
                if (config.doublePage) textLine.isLeftLine = absStartX < config.viewWidth / 2
                textLine.chapterPosition = calcChapterPosition(stringBuilder.length)
                textLine.pagePosition = stringBuilder.length
                stringBuilder.append(" ")
                pendingTextPage.addLine(textLine)
                durY += line.imageHeight
                if (isSingle) {
                    durY = config.visibleHeight.toFloat()
                } else {
                    durY += bodyTextHeight * config.paragraphSpacing / 10f
                }
            } else {
                // 正文普通段落
                for ((lineIdx, line) in paragraph.lines.withIndex()) {
                    prepareNextPageIfNeed(durY + line.textHeight)
                    val textLine = TextLine(isTitle = false)
                    textLine.paragraphNum = line.paragraphNum

                    val indentX = if (line.indentLength > 0 && line.indentWidth > 0f) {
                        addIndentChars(textLine, line.indentLength, line.indentWidth)
                    } else 0f

                    val startX = indentX
                    val isLast = lineIdx == paragraph.lines.lastIndex
                    val lineImgList = if (line.images.isNotEmpty()) line.images.toMutableList() else null
                    if (isLast || !config.textFullJustify) {
                        addCharsToLineNatural(
                            textLine, line.words, startX, line.widths, lineImgList, line.drawOffsets,
                        )
                    } else {
                        addCharsToLineMiddle(
                            textLine, line.words, line.desiredWidth, startX, line.widths,
                            lineImgList, line.drawOffsets,
                        )
                    }
                    if (config.doublePage) textLine.isLeftLine = absStartX < config.viewWidth / 2
                    textLine.text = line.text
                    textLine.lineTop = config.paddingTop + durY
                    textLine.lineBottom = textLine.lineTop + line.textHeight
                    textLine.lineBase = textLine.lineBottom - line.descent
                    textLine.isParagraphEnd = line.isParagraphEnd
                    textLine.chapterPosition = calcChapterPosition(stringBuilder.length)
                    textLine.pagePosition = stringBuilder.length
                    stringBuilder.append(line.text)
                    pendingTextPage.addLine(textLine)
                    durY += line.textHeight * config.lineSpacingExtra
                    if (pendingTextPage.height < durY) pendingTextPage.height = durY
                }
                durY += paragraph.textHeight * config.paragraphSpacing / 10f
                stringBuilder.append("\n")
            }
        }

        // 收尾末页
        if (pendingTextPage.lineSize > 0) {
            if (pendingTextPage.height < durY + config.endPadding) {
                pendingTextPage.height = durY + config.endPadding
            } else {
                pendingTextPage.height += config.endPadding
            }
            pendingTextPage.text = stringBuilder.toString()
            onPageCompleted()
        }

        // textChapter 由调用方拿到 pages 后统一注入真实 TextChapterShared，这里不占位
        if (pages.isEmpty()) {
            pages.add(
                TextPage(
                    text = "",
                    title = config.displayTitle,
                    chapterIndex = config.chapterIndex,
                    chapterSize = config.chapterSize,
                ),
            )
        }

        return pages
    }
}

/**
 * 未注入 [ColumnFactory] 时的兜底列工厂：只产出 [ImageColumn] / [TextColumn]，
 * 不产出段评列故与逻辑段号无关。
 */
private object DefaultColumnFactory : ColumnFactory {
    override fun createColumn(
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: MutableList<ImgData>?,
        paragraphIndex: Int,
        drawOffsetX: Float,
    ): BaseColumn {
        // 只有图片占位符才消费图片队列，普通字符不许把队首图片吞掉
        if (isImagePlaceholder(char)) {
            val img = imgList?.removeFirstOrNull()
            if (img != null) {
                return ImageColumn(absStartX + xStart, absStartX + xEnd, img.src, img.onclick)
            }
        }
        return TextColumn(absStartX + xStart, absStartX + xEnd, char, drawOffsetX)
    }
}
