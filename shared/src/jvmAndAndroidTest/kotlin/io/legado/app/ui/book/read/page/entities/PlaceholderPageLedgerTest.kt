package io.legado.app.ui.book.read.page.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 零行占位页（视口未注入时的消息页、空正文占位页）的定位口径金样。
 *
 * [TextChapterShared] 的定位算法不再逐处判 `lineSize == 0`，只依赖
 * [TextPage.chapterPosition] 对零行页返回 0 这一条领域约定，这里把它钉住。
 */
class PlaceholderPageLedgerTest {

    private val msg = "加载数据中…"

    private fun placeholderChapter() =
        TextChapterShared(0, listOf(TextPage(text = msg, title = "第一章")))

    @Test
    fun `零行占位页页首偏移为零`() {
        val page = TextPage(text = msg)
        assertEquals(0, page.lineSize)
        assertEquals(0, page.chapterPosition)
    }

    @Test
    fun `零行占位页的已读长度与页定位不越界`() {
        val chapter = placeholderChapter()
        assertEquals(0, chapter.getReadLength(0))
        assertEquals(0, chapter.lastReadLength)
        assertEquals(0, chapter.getPageIndexByCharIndex(0))
        assertEquals(-1, chapter.getNextPageLength(0))
        assertEquals(-1, chapter.getPrevPageLength(0))
    }

    @Test
    fun `零行占位页仍能取到章节标题与朗读文本`() {
        val chapter = placeholderChapter()
        assertEquals("第一章", chapter.title)
        assertTrue(chapter.pageParagraphsOf(0).isEmpty())
        assertEquals(msg, chapter.getNeedReadAloud(0, false, 0))
    }
}
