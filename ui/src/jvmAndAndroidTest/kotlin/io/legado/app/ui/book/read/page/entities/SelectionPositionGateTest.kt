package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.PageSelPos
import io.legado.app.ui.book.read.page.PageSelectionState
import io.legado.app.ui.book.read.page.SelectionPageSource
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 选区定位门控金样：位置里的行列号只对"算出它的那个 TextPage 实例"有效。
 *
 * 静默重排（段评迟到 / 字号视口变化）整批换实例、相邻章装载只换 next 流，拿旧行号去索引
 * 新页会算出错的手柄几何。判据只有一条：定位不成立就一律给不出几何，不许回落"取末行/末列"。
 * 几何全部手写（列宽 10px、行高 10px），不依赖真实字宽。
 */
class SelectionPositionGateTest {

    private fun page(lineCount: Int = 1): TextPage =
        TextPage(text = "甲乙丙".repeat(lineCount)).apply {
            for (i in 0 until lineCount) {
                val line = TextLine(
                    text = "甲乙丙",
                    lineTop = i * 10f,
                    lineBase = i * 10f + 8f,
                    lineBottom = (i + 1) * 10f,
                    isParagraphEnd = true,
                )
                line.addColumn(TextColumn(start = 0f, end = 10f, charData = "甲"))
                line.addColumn(TextColumn(start = 10f, end = 20f, charData = "乙"))
                line.addColumn(TextColumn(start = 20f, end = 30f, charData = "丙"))
                addLine(line)
            }
            height = lineCount * 10f
        }

    /** 三页访问器：页实例可替换，模拟 syncPageFlows / relayoutFromCache 换流 */
    private class Source(
        var cur: TextPage?,
        var next: TextPage? = null,
        var nextPlus: TextPage? = null,
        override val isScroll: Boolean = false,
    ) : SelectionPageSource {

        override val visibleHeight: Float get() = 1000f

        override fun pageAt(pagePos: Int): TextPage? = when (pagePos) {
            0 -> cur
            1 -> next
            else -> nextPlus
        }

        override fun relativeOffset(pagePos: Int): Float = when (pagePos) {
            0 -> 0f
            1 -> cur?.height ?: 0f
            else -> (cur?.height ?: 0f) + (next?.height ?: 0f)
        }
    }

    private fun selectionOn(source: Source) =
        PageSelectionState().apply { pageSource = source }

    @Test
    fun `当前页实例被换掉后手柄与菜单锚点都定位不到`() {
        val source = Source(cur = page(lineCount = 2))
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 5f))
        assertNotNull(selection.startHandleOffset())
        assertNotNull(selection.endHandleOffset())
        assertNotNull(selection.selectionAnchor())

        // 静默重排：内容相同的新实例整批换上（结构相等但不是同一个对象）
        source.cur = page(lineCount = 2)
        assertNull(selection.startHandleOffset())
        assertNull(selection.endHandleOffset())
        assertNull(selection.selectionAnchor())
    }

    @Test
    fun `只换 next 流时跨页选区的终点定位失效而起点仍在`() {
        val source = Source(cur = page(), next = page(), isScroll = true)
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 5f))
        // 终点拖到下一页第 3 列（下一页相对偏移 = 当前页页高 10）
        selection.extendTo(x = 25f, y = 15f, viewWidth = 100f)
        assertEquals(1, selection.end.pagePos)
        assertEquals(30f, selection.endHandleOffset()!!.x, 0f)
        assertEquals(20f, selection.endHandleOffset()!!.y, 0f)

        // 相邻章装载只换 next/nextPlus：当前页实例不变，旧守卫压根不触发
        source.next = page()
        assertNotNull(selection.startHandleOffset())
        assertNull(selection.endHandleOffset())
    }

    @Test
    fun `起点在行尾之后取末列右缘，终点在行首之前不成选区`() {
        val source = Source(cur = page(lineCount = 2))
        val selection = selectionOn(source)
        // 长按第 2 行首列，再反向拖到第 1 行行尾之外
        assertTrue(selection.longPressStart(x = 5f, y = 15f))
        assertEquals(PageSelPos(0, 1, 0), selection.start)
        selection.extendTo(x = 40f, y = 5f, viewWidth = 100f)
        assertEquals(3, selection.start.columnIndex)
        assertEquals(-1, selection.end.columnIndex)
        assertEquals(30f, selection.startHandleOffset()!!.x, 0f)
        assertEquals(10f, selection.startHandleOffset()!!.y, 0f)
        assertNull(selection.endHandleOffset())
    }

    @Test
    fun `程序化选区在传入页实例上定位成立`() {
        val page0 = page(lineCount = 2)
        val selection = selectionOn(Source(cur = page0))
        selection.selectRange(page0, PageSelPos(0, 0, 0), PageSelPos(0, 0, 2))
        assertNotNull(selection.startHandleOffset())
        assertNotNull(selection.endHandleOffset())
        assertEquals("甲乙丙", selection.selectedText())
    }

    @Test
    fun `行与列的下标越界直接抛出而不是回落末位`() {
        val page0 = page()
        assertFailsWith<IndexOutOfBoundsException> { page0.getLine(1) }
        val line = page0.lines[0]
        assertFailsWith<IndexOutOfBoundsException> { line.getColumn(line.getColumnsCount()) }
    }

    @Test
    fun `零列行的行首尾归零`() {
        val line = TextLine(text = "")
        assertEquals(0, line.getColumnsCount())
        assertEquals(0f, line.lineStart, 0f)
        assertEquals(0f, line.lineEnd, 0f)
    }
}
