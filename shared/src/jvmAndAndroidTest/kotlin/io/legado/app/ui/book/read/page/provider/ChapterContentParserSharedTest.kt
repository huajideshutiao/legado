package io.legado.app.ui.book.read.page.provider

import io.legado.app.help.book.BookContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentParserSharedTest {

    @Test
    fun `保留 BookContent textList 项边界与空项`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(
                sameTitleRemoved = true,
                textList = listOf("  第一段  ", "", "第二段\n\n第三段"),
                effectiveReplaceRules = null,
            )
        )

        assertEquals(3, parsed.size)
        assertEquals("  第一段  ", parsed[0].text)
        assertEquals("", parsed[1].text)
        assertEquals("第二段\n\n第三段", parsed[2].text)
    }

    @Test
    fun `图片 br 与 HTML entity 按原版顺序解析`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(
                sameTitleRemoved = false,
                textList = listOf("甲&amp;乙<br><img src='a.jpg' style=FULL onclick=preview()>尾"),
                effectiveReplaceRules = null,
            )
        ).single()

        assertEquals("甲&乙\n${ChapterContentParserShared.srcReplaceChar}尾", parsed.text)
        assertEquals(1, parsed.images.size)
        assertEquals("a.jpg", parsed.images.single().src)
        assertEquals("FULL", parsed.images.single().style)
        assertEquals("preview()", parsed.images.single().onclick)
    }

    @Test
    fun `残缺标签作为普通文本保留`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(false, listOf("正文<img src='broken'"), null)
        ).single()

        assertEquals("正文<img src='broken'", parsed.text)
        assertTrue(parsed.images.isEmpty())
    }
}
