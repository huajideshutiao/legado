package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.provider.FixedWidthMeasurer
import io.legado.app.ui.book.read.page.provider.ParagraphLayoutCache
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段评就地补丁（[tryPatchReviewCounts]）账本自洽性金样。
 *
 * 判据只有一条：补丁后的字符账本必须与「排版期就带占位符」逐字段相等 ——
 * 朗读定位 / containPos / 搜索选区 / 阅读进度全按这套账本算，差一个字符就全线错位。
 *
 * 固定字宽 10px、可视宽 100px、可视高 30px ⇒ 每行 10 字、每页 3 行，
 * 标题 1 行 + 8 个正文段各 1 行 = 9 行 = 3 页，跨页顺移才会被真正执行到。
 */
class ReviewCountPatchLedgerTest {

    private val measurer = FixedWidthMeasurer()
    private val reviewChar = "▨"
    private val title = "第一章"
    private val contents = listOf("甲一", "乙二", "丙三", "丁四", "戊五", "己六", "庚七", "辛八")

    private fun layout(reviewCounts: Map<Int, Int>?): ArrayList<TextPage> = runBlocking {
        SimpleChapterLayout(
            measurer = measurer,
            visibleWidth = 100,
            visibleHeight = 30,
            paddingLeft = 0,
            paddingTop = 0,
            textHeight = 10f,
            descent = 2f,
            lineSpacingExtra = 1f,
            paragraphSpacing = 0,
            titleTopSpacing = 0,
            titleBottomSpacing = 0,
            paragraphIndent = "",
            textFullJustify = false,
            reviewChar = reviewChar,
            layoutCache = ParagraphLayoutCache(),
        ).layout(
            displayTitle = title,
            contents = contents,
            chapterIndex = 0,
            chapterSize = 1,
            reviewCountMap = reviewCounts,
        )
    }

    private fun TextChapterShared.patch(
        counts: Map<Int, Int>,
        visibleWidth: Int = 100,
    ) = tryPatchReviewCounts(
        reviewCountMap = counts,
        reviewChar = reviewChar,
        measurer = measurer,
        titleMeasurer = measurer,
        visibleWidth = visibleWidth,
    )

    private fun chapter(reviewCounts: Map<Int, Int>?) = TextChapterShared(0, layout(reviewCounts))

    /**
     * 核心红线：段 1（第 0 页）与段 5（第 1 页）各挂一个气泡，
     * 补丁结果的 page.text / charSize / 每行 text / pagePosition / chapterPosition /
     * chapterIndices / getReadLength 必须与排版期就带 ▨ 的结果逐字段相等。
     */
    @Test
    fun `就地补丁的字符账本与排版期带占位符逐字段相等`() {
        val counts = mapOf(1 to 3, 5 to 7)
        val patched = chapter(null)
        assertTrue("空间足够时必须走就地补丁", patched.patch(counts))
        val fresh = chapter(counts)

        assertEquals(3, fresh.pageSize)
        assertEquals(fresh.pageSize, patched.pageSize)
        assertEquals(fresh.lastReadLength, patched.lastReadLength)

        for (p in 0 until fresh.pageSize) {
            val expected = fresh.pages[p]
            val actual = patched.pages[p]
            assertEquals("page$p.text", expected.text, actual.text)
            assertEquals("page$p.charSize", expected.charSize, actual.charSize)
            assertEquals("page$p.lineSize", expected.lineSize, actual.lineSize)
            assertEquals("readLength$p", fresh.getReadLength(p), patched.getReadLength(p))
            for (l in 0 until expected.lineSize) {
                val e = expected.lines[l]
                val a = actual.lines[l]
                assertEquals("page$p.line$l.text", e.text, a.text)
                assertEquals("page$p.line$l.pagePosition", e.pagePosition, a.pagePosition)
                assertEquals("page$p.line$l.chapterPosition", e.chapterPosition, a.chapterPosition)
                assertEquals("page$p.line$l.chapterIndices", e.chapterIndices, a.chapterIndices)
            }
        }

        // 气泡本身的几何与计数也要与排版期一致（占位符宽度就是一个字宽）
        for ((paragraphNum, page, lineIndex) in listOf(Triple(1, 0, 1), Triple(5, 1, 2))) {
            val e = fresh.pages[page].lines[lineIndex].columns.last() as ReviewColumn
            val a = patched.pages[page].lines[lineIndex].columns.last() as ReviewColumn
            assertEquals("p$paragraphNum.start", e.start, a.start, 0f)
            assertEquals("p$paragraphNum.end", e.end, a.end, 0f)
            assertEquals("p$paragraphNum.count", e.count, a.count)
            assertEquals("p$paragraphNum.paragraphIndex", e.paragraphIndex, a.paragraphIndex)
        }
    }

    /** 拿不到可视区宽度就没有可靠的行宽上界，必须放弃就地补丁且不动账本。 */
    @Test
    fun `没有可视区宽度直接放弃补丁`() {
        val patched = chapter(null)
        val before = patched.pages.map { it.text }

        assertFalse(patched.patch(mapOf(1 to 3), visibleWidth = 0))
        assertEquals(before, patched.pages.map { it.text })
        assertTrue(patched.pages.all { page -> page.lines.none { it.columns.any { c -> c is ReviewColumn } } })
    }

    /** page.text 与行文本对不上（外部改过页文本）时放弃，且不许留下半套改动。 */
    @Test
    fun `页文本与行文本对不上时放弃补丁`() {
        val patched = chapter(null)
        val lineTexts = patched.pages.flatMap { page -> page.lines.map { it.text } }
        val positions = patched.pages.flatMap { page -> page.lines.map { it.chapterPosition } }
        patched.pages[0].text = "被外部改过"

        assertFalse(patched.patch(mapOf(1 to 3)))
        assertEquals(lineTexts, patched.pages.flatMap { page -> page.lines.map { it.text } })
        assertEquals(positions, patched.pages.flatMap { page -> page.lines.map { it.chapterPosition } })
    }

    /** 已有气泡时二次补丁只改计数：不再插占位符，字符账本必须一动不动。 */
    @Test
    fun `二次补丁只更新计数不重复插占位符`() {
        val patched = chapter(null)
        assertTrue(patched.patch(mapOf(1 to 3)))
        val texts = patched.pages.map { it.text }
        val positions = patched.pages.flatMap { page -> page.lines.map { it.chapterPosition } }

        assertTrue(patched.patch(mapOf(1 to 9)))
        assertEquals(texts, patched.pages.map { it.text })
        assertEquals(positions, patched.pages.flatMap { page -> page.lines.map { it.chapterPosition } })
        val column = patched.pages[0].lines[1].columns.last() as ReviewColumn
        assertEquals(9, column.count)
        assertEquals(1, patched.pages[0].lines[1].columns.count { it is ReviewColumn })
    }

    /** 段评数全为 0：直接算成功并标记已应用，不碰任何账本。 */
    @Test
    fun `段评数全为零时直接成功且不动账本`() {
        val patched = chapter(null)
        val before = patched.pages.map { it.text }

        assertTrue(patched.patch(mapOf(1 to 0, 5 to 0)))
        assertTrue(patched.reviewCountApplied)
        assertEquals(before, patched.pages.map { it.text })
    }

    /** 目标段落不存在时必须放弃补丁返回 false，以便调用方走完整重排。 */
    @Test
    fun `目标段落不存在时返回false`() {
        val patched = chapter(null)
        val before = patched.pages.map { it.text }

        assertFalse(patched.patch(mapOf(999 to 5)))
        assertEquals(before, patched.pages.map { it.text })
    }
}
