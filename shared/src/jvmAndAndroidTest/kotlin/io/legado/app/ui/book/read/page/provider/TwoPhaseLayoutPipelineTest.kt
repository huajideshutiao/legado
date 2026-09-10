package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定字宽度量器（纯 JVM fake）：ASCII 半宽、其余全宽，字高固定。
 * 排版金样全靠它把「测量」这一变量钉死，断言才能写成手推出来的整数。
 */
internal class FixedWidthMeasurer(
    override val textSizePx: Float = 10f,
    override val letterSpacingPx: Float = 0f,
    override val descent: Float = 2f,
    override val ascent: Float = -8f,
    override val leading: Float = 0f,
) : TextMeasurer {

    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        val last = minOf(text.length, widths.size) - 1
        for (i in 0..last) widths[i] = widthOf(text[i])
    }

    override fun measureWidth(text: String): Float {
        var sum = 0f
        for (c in text) sum += widthOf(c)
        return sum
    }

    private fun widthOf(c: Char): Float = if (c.code < 128) textSizePx / 2f else textSizePx
}

/**
 * 两阶段排版管线金样：Phase 1（[ParagraphLayoutEngine.layoutParagraph]）与
 * Phase 2（[PaginationEngine.paginate]）的等价性与分页边界。
 *
 * 固定字宽 10px / 可视宽 100px ⇒ 一行恰好 10 个全角字；字高 10px。
 */
class TwoPhaseLayoutPipelineTest {

    private val measurer = FixedWidthMeasurer()

    /** 10 个互不相同的全角字，拼接后每 10 字一行，便于手推断行位置。 */
    private val group = "甲乙丙丁戊己庚辛壬癸"

    private fun bodyParagraph(
        text: String,
        cache: ParagraphLayoutCache? = null,
        paragraphNum: Int = 1,
        indent: String = "",
        indentCharWidth: Float = 0f,
        visibleWidth: Int = 100,
    ) = ParagraphLayoutEngine.layoutParagraph(
        text = text,
        measurer = measurer,
        visibleWidth = visibleWidth,
        paragraphIndent = indent,
        indentCharWidth = indentCharWidth,
        isTitle = false,
        isFirstLine = true,
        paragraphNum = paragraphNum,
        textHeight = 10f,
        descent = 2f,
        cache = cache,
        fontKey = "body",
    )

    private fun titleParagraph(
        centerTitle: Boolean,
        cache: ParagraphLayoutCache?,
        text: String = "第一章标题",
    ) = ParagraphLayoutEngine.layoutParagraph(
        text = text,
        measurer = measurer,
        visibleWidth = 100,
        paragraphIndent = "",
        isTitle = true,
        isFirstLine = true,
        paragraphNum = 0,
        centerTitle = centerTitle,
        textHeight = 10f,
        descent = 2f,
        cache = cache,
        fontKey = "title",
    )

    private fun config(
        visibleHeight: Int,
        lineSpacingExtra: Float = 1f,
        paragraphSpacing: Int = 0,
        endPadding: Int = 0,
        doublePage: Boolean = false,
        paddingLeft: Int = 0,
        viewWidth: Int = 100,
    ) = PaginationConfig(
        visibleWidth = 100,
        visibleHeight = visibleHeight,
        paddingLeft = paddingLeft,
        paddingTop = 0,
        viewWidth = viewWidth,
        lineSpacingExtra = lineSpacingExtra,
        paragraphSpacing = paragraphSpacing,
        textHeight = 10f,
        endPadding = endPadding,
        doublePage = doublePage,
        textFullJustify = false,
    )

    /**
     * 走缓存与不走缓存必须产出逐字段相等的 [ParagraphLineMetrics]（含几何缩进首行），
     * 且第二次同参调用只命中缓存不重算。
     */
    @Test
    fun `有无缓存两条路径的段落度量逐字段相等`() {
        val text = "　　" + group.repeat(2)
        val noCache = bodyParagraph(text, cache = null, indent = "　　", indentCharWidth = 10f)

        val cache = ParagraphLayoutCache()
        val first = bodyParagraph(text, cache = cache, indent = "　　", indentCharWidth = 10f)
        val second = bodyParagraph(text, cache = cache, indent = "　　", indentCharWidth = 10f)

        assertEquals(noCache, first)
        assertEquals(noCache, second)
        assertEquals(1L, cache.missCount)
        assertEquals(1L, cache.hitCount)
        // 首行扣掉两个缩进簇：words 只剩 8 个，indentWidth 记 20px
        assertEquals(3, noCache.lineCount)
        assertEquals(8, noCache.lines[0].words.size)
        assertEquals(20f, noCache.lines[0].indentWidth, 0f)
        assertEquals(2, noCache.lines[0].indentLength)
        assertEquals(80f, noCache.lines[0].desiredWidth, 0f)
    }

    /**
     * 「行距边距微秒级响应」的前提：行距 / 段距 / 视口高度都不进缓存键，
     * 换 Y 轴参数时 Phase 1 全部命中缓存、断行结果一字不变，只有分页切点变。
     */
    @Test
    fun `只改 Y 轴参数不重算 Phase 1 且断行结果不变`() = runBlocking {
        val text = group.repeat(6)
        val cache = ParagraphLayoutCache()
        val configs = listOf(
            config(visibleHeight = 25),
            config(visibleHeight = 45, paragraphSpacing = 6),
            config(visibleHeight = 100, lineSpacingExtra = 1.8f, paragraphSpacing = 12),
        )

        val lineTexts = configs.map { cfg ->
            val metrics = bodyParagraph(text, cache = cache)
            assertEquals(6, metrics.lineCount)
            PaginationEngine.paginate(listOf(metrics), cfg)
                .flatMap { page -> page.lines.map { it.text } }
        }

        assertEquals(1L, cache.missCount)
        assertEquals(2L, cache.hitCount)
        assertEquals(lineTexts[0], lineTexts[1])
        assertEquals(lineTexts[0], lineTexts[2])

        // Y 轴参数确实改变了切页结果（否则上面的相等断言毫无意义）
        val pagesTight = PaginationEngine.paginate(listOf(bodyParagraph(text, cache)), configs[0])
        val pagesLoose = PaginationEngine.paginate(listOf(bodyParagraph(text, cache)), configs[2])
        assertTrue(pagesTight.size > pagesLoose.size)
    }

    /**
     * titleMode 切换（居中↔居左）走的是同一段标题文本，centerTitle 必须进缓存键，
     * 否则第二次会复用到错误的居中标记。两个方向各自 miss 一次，同参再来才命中。
     */
    @Test
    fun `切换标题居中不复用错误的 centerTitle`() {
        val cache = ParagraphLayoutCache()

        val centered = titleParagraph(centerTitle = true, cache = cache)
        val left = titleParagraph(centerTitle = false, cache = cache)
        val centeredAgain = titleParagraph(centerTitle = true, cache = cache)

        assertTrue(centered.lines.isNotEmpty())
        assertTrue(centered.lines.all { it.centerTitle })
        assertTrue(left.lines.none { it.centerTitle })
        assertTrue(centeredAgain.lines.all { it.centerTitle })
        assertEquals(2L, cache.missCount)
        assertEquals(1L, cache.hitCount)
    }

    /**
     * 阶段 0 在断行之前就定稿宽度：句中 `：“` 的 `“` 裁左半（5px）并记下 -5px 绘制偏移。
     * 这正是 clreq 6.1.1「挤压先于禁则」的可观测结果。
     */
    @Test
    fun `句中冒号引号在断行前挤压宽度`() {
        val line = bodyParagraph("甲乙：“丙丁").lines.single()

        assertEquals(listOf(10f, 10f, 10f, 5f, 10f, 10f), line.widths)
        assertEquals(listOf(0f, 0f, 0f, -5f, 0f, 0f), line.drawOffsets)
        assertEquals(55f, line.desiredWidth, 0f)
    }

    /**
     * 阶段 2 行首挤压：`“` 顶到行首 → 裁左半 + 偏移，行宽从 100 收到 95（两端对齐据此拉伸）；
     * 次行不带偏移数组。
     *
     * 正文取 12 字而非 10 字：末行若只剩一个字会触发 [LineBreaker] 的孤字修正（clreq 7.1.2）
     * 从首行取字下移，那是另一条金样的事，这里留足字数让首行切片保持满行。
     */
    @Test
    fun `行首开始夹注在度量里留下裁半宽与绘制偏移`() {
        val metrics = bodyParagraph("“甲乙丙丁戊己庚辛壬癸子丑")

        assertEquals(2, metrics.lineCount)
        val first = metrics.lines[0]
        assertEquals(10, first.words.size)
        assertEquals(5f, first.widths[0], 0f)
        assertEquals(List(10) { idx -> if (idx == 0) -5f else 0f }, first.drawOffsets)
        assertEquals(95f, first.desiredWidth, 0f)
        assertNull(metrics.lines[1].drawOffsets)
    }

    private fun imageParagraph(style: String, width: Float, height: Float, num: Int = 2) =
        ParagraphLineMetrics.createImage(
            img = ImgData(src = "img-$num.jpg", style = style, onclick = ""),
            width = width,
            height = height,
            paragraphNum = num,
        )

    /** 行盒底边恰好等于 visibleHeight 不换页（判据是严格 `>`），少 1px 就必须换页。 */
    @Test
    fun `一行恰好填满可视高不换页`() = runBlocking {
        val metrics = bodyParagraph(group.repeat(4))
        assertEquals(4, metrics.lineCount)

        val exact = PaginationEngine.paginate(listOf(metrics), config(visibleHeight = 30))
        assertEquals(2, exact.size)
        assertEquals(3, exact[0].lineSize)
        assertEquals(listOf(0f, 10f, 20f), exact[0].lines.map { it.lineTop })
        assertEquals(1, exact[1].lineSize)

        val short = PaginationEngine.paginate(listOf(metrics), config(visibleHeight = 29))
        assertEquals(2, short.size)
        assertEquals(2, short[0].lineSize)
        assertEquals(2, short[1].lineSize)
    }

    /**
     * FULL 图片：装得下就贴着当前 durY 排在同页，装不下才整页下移到新页页首。
     * 图片列按可视区居中，X 由 (visibleWidth - imageWidth) / 2 决定。
     */
    @Test
    fun `FULL 图片装不下才翻页`() = runBlocking {
        val paragraphs = listOf(
            bodyParagraph(group),
            imageParagraph(Book.imgStyleFull, width = 60f, height = 50f, num = 2),
            imageParagraph(Book.imgStyleFull, width = 60f, height = 50f, num = 3),
        )

        val pages = PaginationEngine.paginate(paragraphs, config(visibleHeight = 100))

        assertEquals(2, pages.size)
        // 第一张紧跟正文行（durY=10），50 <= 100-10 故不翻页
        assertEquals(2, pages[0].lineSize)
        assertTrue(pages[0].lines[1].isImage)
        assertEquals(10f, pages[0].lines[1].lineTop, 0f)
        assertEquals(60f, pages[0].lines[1].lineBottom, 0f)
        assertEquals(60f, pages[0].height, 0f)
        val column = pages[0].lines[1].columns.single() as ImageColumn
        assertEquals(20f, column.start, 0f)
        assertEquals(80f, column.end, 0f)
        assertEquals("img-2.jpg", column.src)
        // 第二张剩余空间只有 40，必须落到新页页首
        assertEquals(1, pages[1].lineSize)
        assertEquals(0f, pages[1].lines[0].lineTop, 0f)
        assertEquals(50f, pages[1].lines[0].lineBottom, 0f)
        assertEquals(50f, pages[1].height, 0f)
    }

    /** SINGLE 图片独占整页并垂直居中，其后正文必须再翻一页（durY 被顶到 visibleHeight）。 */
    @Test
    fun `SINGLE 图片独占整页且垂直居中`() = runBlocking {
        val paragraphs = listOf(
            bodyParagraph(group, paragraphNum = 1),
            imageParagraph(Book.imgStyleSingle, width = 60f, height = 40f, num = 2),
            bodyParagraph(group, paragraphNum = 3),
        )

        val pages = PaginationEngine.paginate(paragraphs, config(visibleHeight = 100))

        assertEquals(3, pages.size)
        assertEquals(1, pages[1].lineSize)
        val imageLine = pages[1].lines.single()
        assertTrue(imageLine.isImage)
        assertEquals(30f, imageLine.lineTop, 0f)
        assertEquals(70f, imageLine.lineBottom, 0f)
        assertEquals(100f, pages[1].height, 0f)
        assertEquals(1, pages[2].lineSize)
    }

    /** 双页：左栏排满切右栏（同页 Y 归零、isLeftLine 转 false），右栏排满才真正翻页。 */
    @Test
    fun `双页左右栏切换后才翻页`() = runBlocking {
        val metrics = bodyParagraph(group.repeat(8))
        assertEquals(8, metrics.lineCount)

        val pages = PaginationEngine.paginate(
            listOf(metrics),
            config(visibleHeight = 30, doublePage = true, paddingLeft = 10, viewWidth = 240),
        )

        assertEquals(2, pages.size)
        assertEquals(6, pages[0].lineSize)
        assertEquals(3, pages[0].leftLineSize)
        assertTrue(pages[0].lines.take(3).all { it.isLeftLine })
        assertTrue(pages[0].lines.drop(3).none { it.isLeftLine })
        // 右栏第一行回到页顶，起始 X 挪到中缝右侧
        assertEquals(0f, pages[0].lines[3].lineTop, 0f)
        assertEquals(130f, pages[0].lines[3].startX, 0f)
        assertEquals(2, pages[1].lineSize)
        assertTrue(pages[1].lines.all { it.isLeftLine })
    }

    /** endPadding 只加在末页页高上（末页 height 不足 durY+endPadding 时抬到该值）。 */
    @Test
    fun `endPadding 只抬高末页页高`() = runBlocking {
        val metrics = bodyParagraph(group)

        val noPadding = PaginationEngine.paginate(listOf(metrics), config(visibleHeight = 100))
        assertEquals(10f, noPadding.single().height, 0f)

        val padded = PaginationEngine.paginate(
            listOf(metrics),
            config(visibleHeight = 100, endPadding = 13),
        )
        assertEquals(23f, padded.single().height, 0f)
    }

    private fun inlineImageParagraph(images: List<ImgData>) =
        ParagraphLayoutEngine.layoutParagraph(
            text = "甲" + ChapterContentParserShared.srcReplaceChar + "乙",
            measurer = measurer,
            visibleWidth = 100,
            paragraphIndent = "",
            isTitle = false,
            paragraphNum = 1,
            textHeight = 10f,
            descent = 2f,
            images = images,
        )

    /** 行内图片：只有占位符消费图片队列，普通字绝不许把队首图片吞掉。 */
    @Test
    fun `只有图片占位符消费图片队列`() = runBlocking {
        val img = ImgData(src = "inline.jpg", style = "", onclick = "zoom()")
        val pages = PaginationEngine.paginate(
            listOf(inlineImageParagraph(listOf(img))),
            config(visibleHeight = 100),
        )

        val columns = pages.single().lines.single().columns
        assertEquals(3, columns.size)
        assertEquals("甲", (columns[0] as TextColumn).charData)
        assertEquals("inline.jpg", (columns[1] as ImageColumn).src)
        assertEquals("乙", (columns[2] as TextColumn).charData)
    }

    /** 占位符没有配对图片时退回文本列，不发空 src 的图片列（否则渲染侧会去加载空地址）。 */
    @Test
    fun `无配图的占位符退回文本列`() = runBlocking {
        val pages = PaginationEngine.paginate(
            listOf(inlineImageParagraph(emptyList())),
            config(visibleHeight = 100),
        )

        val columns = pages.single().lines.single().columns
        assertEquals(3, columns.size)
        assertTrue(columns.none { it is ImageColumn })
        assertEquals(ChapterContentParserShared.srcReplaceChar, (columns[1] as TextColumn).charData)
    }

    /**
     * 中西间距恰好在断行处时行尾空白清理：
     * 汉字「戊」与西文「A」之间注入 1/4 汉字宽（2.5px），但在「戊」与「A」之间断行时，
     * 上一行末尾残留的 2.5px 必须被扣除，使第一行期望宽度与各字宽精确等于 50px（5 个汉字），
     * 不残留行尾空白。
     */
    @Test
    fun `中西间距恰在断行处时行尾空白清理`() {
        val paragraph = bodyParagraph("甲乙丙丁戊A", visibleWidth = 53)
        assertEquals(2, paragraph.lines.size)
        val line0 = paragraph.lines[0]
        val line1 = paragraph.lines[1]
        assertEquals("甲乙丙丁戊", line0.text)
        assertEquals("A", line1.text)
        assertEquals(50f, line0.desiredWidth, 0.001f)
        assertEquals(listOf(10f, 10f, 10f, 10f, 10f), line0.widths)
    }
}
