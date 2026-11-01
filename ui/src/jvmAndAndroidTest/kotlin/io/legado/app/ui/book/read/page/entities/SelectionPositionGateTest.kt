package io.legado.app.ui.book.read.page.entities

import androidx.compose.ui.geometry.Rect
import io.legado.app.ui.book.read.page.PageSelPos
import io.legado.app.ui.book.read.page.PageSelectionState
import io.legado.app.ui.book.read.page.SelectionPageSource
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.overlay.HighlightOverlay
import io.legado.app.ui.book.read.page.overlay.PageOverlayProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 选区定位门控金样：位置里的行列号只对"算出它的那个 TextPage 实例"有效。
 *
 * 静默重排（段评迟到 / 字号视口变化）整批换实例、相邻章装载只换 next 流，拿旧行号去索引
 * 新页会算出错的手柄几何。判据只有一条：定位不成立就一律给不出几何，不许回落"取末行/末列"
 * 或任何回退矩形。几何全部手写（列宽 10px、行高 10px），不依赖真实字宽；
 * 菜单几何快照走生产同一投影（geometrySnapshot → PageOverlayProjector.projectSelection），
 * 覆盖跨页相对偏移、双栏不假设 y 单调、行尾之后手柄锚。
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

    /** 双栏页：左栏 5 行 (top 0..50，列 x 0..30)，右栏 3 行 (top 0..30，列 x 100..130) */
    private fun doublePage(): TextPage = TextPage(text = "甲乙丙").apply {
        fun row(top: Float, left: Boolean): TextLine = TextLine(
            text = "甲乙丙",
            lineTop = top,
            lineBase = top + 8f,
            lineBottom = top + 10f,
            isParagraphEnd = true,
        ).apply {
            isLeftLine = left
            var x = if (left) 0f else 100f
            repeat(3) {
                addColumn(TextColumn(start = x, end = x + 10f, charData = "甲"))
                x += 10f
            }
        }
        repeat(5) { addLine(row(it * 10f, left = true)) }
        repeat(3) { addLine(row(it * 10f, left = false)) }
        doublePage = true
        leftLineSize = 5
        height = 50f
    }

    /** 三页访问器：页实例/滚动偏移可替换，模拟 syncPageFlows / relayoutFromCache 换流与滚动 */
    /** 三页访问器：页实例/滚动偏移可替换，模拟 syncPageFlows / relayoutFromCache 换流与滚动；
     *  记录活页流读取次数用于验证快照只读取一次 */
    private class Source(
        var cur: TextPage?,
        var next: TextPage? = null,
        var nextPlus: TextPage? = null,
        override val isScroll: Boolean = false,
        var scrollOffset: Float = 0f,
    ) : SelectionPageSource {

        val pageAtCalls = mutableListOf<Int>()
        val relativeOffsetCalls = mutableListOf<Int>()

        fun clearCalls() {
            pageAtCalls.clear()
            relativeOffsetCalls.clear()
        }

        override val visibleHeight: Float get() = 1000f

        override fun pageAt(pagePos: Int): TextPage? {
            pageAtCalls += pagePos
            return when (pagePos) {
                0 -> cur
                1 -> next
                else -> nextPlus
            }
        }

        override fun relativeOffset(pagePos: Int): Float {
            relativeOffsetCalls += pagePos
            return when (pagePos) {
                0 -> scrollOffset
                1 -> scrollOffset + (cur?.height ?: 0f)
                else -> scrollOffset + (cur?.height ?: 0f) + (next?.height ?: 0f)
            }
        }
    }

    private fun selectionOn(source: Source) =
        PageSelectionState().apply { pageSource = source }

    @Test
    fun `当前页实例被换掉后手柄与选区几何快照都定位不到`() {
        val source = Source(cur = page(lineCount = 2))
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 5f))
        assertNotNull(selection.startHandleOffset())
        assertNotNull(selection.endHandleOffset())
        assertNotNull(selection.geometrySnapshot(handleSizePx = 24f))

        // 静默重排：内容相同的新实例整批换上（结构相等但不是同一个对象）
        source.cur = page(lineCount = 2)
        assertNull(selection.startHandleOffset())
        assertNull(selection.endHandleOffset())
        assertNull(selection.geometrySnapshot(handleSizePx = 24f))
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
        // 终点未落成选区：几何快照整体不给（不产生任何部分矩形）
        assertNull(selection.geometrySnapshot(handleSizePx = 24f))
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
    fun `起点在行尾之后时该行无选中列，手柄仍贴末列 end 且前行末字不入选区`() {
        val page0 = page(lineCount = 2)
        val selection = selectionOn(Source(cur = page0))
        // 起点 = 第 1 行行尾之后（columnIndex == size），终点在第 2 行第 2 列
        selection.selectRange(page0, PageSelPos(0, 0, 3), PageSelPos(0, 1, 1))
        val geometry = selection.geometrySnapshot(handleSizePx = 24f)!!
        // 起点行无选中列（半开起点），文字区域只含真正选中的第 2 行前两列
        assertEquals(10f, geometry.textArea.top, 0f)
        assertEquals(20f, geometry.textArea.right, 0f)
        assertEquals(20f, geometry.textArea.bottom, 0f)
        // 起点手柄仍贴末列右缘（x=30），矩形在锚点左侧
        assertEquals(30f, geometry.startHandle.right, 0f)
        assertEquals(10f, geometry.startHandle.top, 0f)
        assertEquals(20f, geometry.endHandle.left, 0f)
        assertEquals(44f, geometry.endHandle.right, 0f)

        // 反向参数（start 在后且为行尾之后）：归正后终点为行尾之后 → 该行含到末列右缘，
        // 与正向 (0,0,2)→(0,1,3) 投影一致，同 selectedText/存档账本
        selection.selectRange(page0, PageSelPos(0, 1, 3), PageSelPos(0, 0, 2))
        val reversed = selection.geometrySnapshot(handleSizePx = 24f)!!
        assertEquals(0f, reversed.textArea.top, 0f)
        assertEquals(30f, reversed.textArea.right, 0f)
        assertEquals(20f, reversed.textArea.bottom, 0f)
    }

    @Test
    fun `跨页快照文字区域与两手柄各按所在页偏移叠加，负偏移与不同页高同基准`() {
        val source = Source(cur = page(), next = page(), isScroll = true)
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 5f))
        // 终点拖到下一页第 3 列（下一页相对偏移 = 当前页页高 10）
        selection.extendTo(x = 25f, y = 15f, viewWidth = 100f)
        val geometry = selection.geometrySnapshot(handleSizePx = 24f)!!
        // 文字区域 = 两页逐行投影各按自身偏移平移后的并集：第 0 页 (0,0,30,10)，第 1 页整体 +10
        assertEquals(0f, geometry.textArea.top, 0f)
        assertEquals(20f, geometry.textArea.bottom, 0f)
        assertEquals(0f, geometry.textArea.left, 0f)
        assertEquals(30f, geometry.textArea.right, 0f)
        // 两个手柄各自贴所在页行底：起点在第 0 页 (y=10)，终点在第 1 页 (y=20)
        assertEquals(-24f, geometry.startHandle.left, 0f)
        assertEquals(10f, geometry.startHandle.top, 0f)
        assertEquals(30f, geometry.endHandle.left, 0f)
        assertEquals(20f, geometry.endHandle.top, 0f)
        // 菜单锚含两真实手柄：下沿落在更低手柄底
        val anchor = geometry.menuAnchor()
        assertEquals(44f, anchor.bottom, 0f)
        assertEquals(-24f, anchor.left, 0f)
        assertEquals(54f, anchor.right, 0f)

        // 负滚动偏移 + 两页不同高（当前页 2 行 20、下一页 1 行 10）：文字与手柄同一连排基准
        val scrolled = Source(cur = page(lineCount = 2), next = page(), isScroll = true, scrollOffset = -5f)
        val selection2 = selectionOn(scrolled)
        assertTrue(selection2.longPressStart(x = 5f, y = 0f))
        selection2.extendTo(x = 25f, y = 20f, viewWidth = 100f)
        // y20 落在下一页第 0 行（相对偏移 -5+20=15，行覆盖 15..25），第 3 列
        assertEquals(PageSelPos(1, 0, 2), selection2.end)
        val geometry2 = selection2.geometrySnapshot(handleSizePx = 24f)!!
        // 第 0 页偏移 -5（行 0..10 → -5..5），第 1 页偏移 15（行 15..25）
        assertEquals(-5f, geometry2.textArea.top, 0f)
        assertEquals(25f, geometry2.textArea.bottom, 0f)
        assertEquals(30f, geometry2.textArea.right, 0f)
        assertEquals(5f, geometry2.startHandle.top, 0f)
        assertEquals(25f, geometry2.endHandle.top, 0f)
    }

    @Test
    fun `选区从后页开始时前页实例被换导致偏移基准失效，快照整体不给`() {
        // 前页 cur 高 10、后页 next 高 10；选区完全在 next（长按落点 y=15 → 页 1 行 0）
        val source = Source(cur = page(), next = page(), isScroll = true)
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 15f))
        assertEquals(PageSelPos(1, 0, 0), selection.start)
        assertNotNull(selection.geometrySnapshot(handleSizePx = 24f))
        // 前页被换（前缀页高参与后页落点）：即使选区自身的锚页未变，快照也失效
        source.cur = page(lineCount = 2)
        assertNull(selection.geometrySnapshot(handleSizePx = 24f))
    }

    @Test
    fun `同一快照期间各页引用与滚动基准只读取一次`() {
        val source = Source(cur = page(lineCount = 2), next = page(), isScroll = true)
        val selection = selectionOn(source)
        assertTrue(selection.longPressStart(x = 5f, y = 5f))
        selection.extendTo(x = 25f, y = 25f, viewWidth = 100f)
        assertEquals(1, selection.end.pagePos)
        source.clearCalls()
        assertNotNull(selection.geometrySnapshot(handleSizePx = 24f))
        // 0..终点页各一次；滚动基准仅 relativeOffset(0) 一次，后页偏移由已捕获前缀页高推出，
        // 手柄也只用已捕获页 —— 文字与两手柄同批
        assertEquals(listOf(0, 1), source.pageAtCalls)
        assertEquals(listOf(0), source.relativeOffsetCalls)
    }

    @Test
    fun `双栏跨栏快照不假设起止行 y 单调，文字区域为正高并覆盖两栏`() {
        val source = Source(cur = doublePage())
        val selection = selectionOn(source)
        // 长按左栏末行首列（行 top 40..50），终点拖到右栏首行（top 0..10，列 x 从 100 起）
        assertTrue(selection.longPressStart(x = 5f, y = 45f))
        selection.extendTo(x = 105f, y = 5f, viewWidth = 100f)
        assertEquals(PageSelPos(0, 4, 0), selection.start)
        assertEquals(PageSelPos(0, 5, 0), selection.end)
        val geometry = selection.geometrySnapshot(handleSizePx = 24f)!!
        // 旧单矩形口径（起点行顶 40 到终点行底 10）是负高；逐行投影并集恒正
        assertEquals(0f, geometry.textArea.top, 0f)
        assertEquals(50f, geometry.textArea.bottom, 0f)
        assertEquals(0f, geometry.textArea.left, 0f)
        assertEquals(110f, geometry.textArea.right, 0f)
        assertTrue(geometry.textArea.top <= geometry.textArea.bottom)
        // 菜单锚含两个真实手柄（起点手柄在左栏末行底 50，终点手柄在右栏首行底 10）
        val anchor = geometry.menuAnchor()
        assertEquals(50f, geometry.startHandle.top, 0f)
        assertEquals(10f, geometry.endHandle.top, 0f)
        assertEquals(74f, anchor.bottom, 0f)
    }

    @Test
    fun `选区菜单锚由文字投影并集与两手柄合成，跨行时并集覆盖中间行`() {
        val page0 = page(lineCount = 2)
        val selection = selectionOn(Source(cur = page0))
        // 跨行：第 1 行首列 → 第 2 行末列（列宽 10px、行高 10px）
        selection.selectRange(page0, PageSelPos(0, 0, 0), PageSelPos(0, 1, 2))
        val geometry = selection.geometrySnapshot(handleSizePx = 24f)!!
        // 文字投影并集：上沿 = 起点行顶，下沿 = 终点行底，左右覆盖两行全部选中列
        assertEquals(0f, geometry.textArea.top, 0f)
        assertEquals(20f, geometry.textArea.bottom, 0f)
        assertEquals(0f, geometry.textArea.left, 0f)
        assertEquals(30f, geometry.textArea.right, 0f)
        val anchor = geometry.menuAnchor()
        // 菜单锚 = 文字并集 ∪ 两手柄：下沿 = 终点手柄底（行底 20 + 手柄 24）
        assertEquals(44f, anchor.bottom, 0f)
        assertEquals(0f, anchor.top, 0f)
        // 反向跨行：起点第 1 行末列、终点第 2 行首列，中间行整行覆盖（完整真实范围）
        selection.selectRange(page0, PageSelPos(0, 0, 2), PageSelPos(0, 1, 0))
        val geometry2 = selection.geometrySnapshot(handleSizePx = 24f)!!
        assertEquals(0f, geometry2.textArea.left, 0f)
        assertEquals(30f, geometry2.textArea.right, 0f)
        assertEquals(20f, geometry2.textArea.bottom, 0f)
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

    /** 独立宽图片行夹在两行窄文字之间（行章内账本与列长度一致，模拟真实排版产物） */
    private fun pageWithImageLine(): TextPage = TextPage(
        text = "甲乙丙a甲乙丙",
        chapterIndex = 3,
    ).apply {
        addLine(
            TextLine(
                text = "甲乙丙",
                lineTop = 0f,
                lineBase = 8f,
                lineBottom = 10f,
                chapterPosition = 0,
                pagePosition = 0,
                isParagraphEnd = false,
            ).apply {
                addColumn(TextColumn(start = 0f, end = 10f, charData = "甲"))
                addColumn(TextColumn(start = 10f, end = 20f, charData = "乙"))
                addColumn(TextColumn(start = 20f, end = 30f, charData = "丙"))
            },
        )
        addLine(
            TextLine(
                text = "a",
                lineTop = 10f,
                lineBase = 18f,
                lineBottom = 20f,
                chapterPosition = 3,
                pagePosition = 3,
                isParagraphEnd = false,
                isImage = true,
            ).apply {
                addColumn(ImageColumn(start = 0f, end = 100f, src = "img"))
            },
        )
        addLine(
            TextLine(
                text = "甲乙丙",
                lineTop = 20f,
                lineBase = 28f,
                lineBottom = 30f,
                chapterPosition = 4,
                pagePosition = 4,
                isParagraphEnd = true,
            ).apply {
                addColumn(TextColumn(start = 0f, end = 10f, charData = "甲"))
                addColumn(TextColumn(start = 10f, end = 20f, charData = "乙"))
                addColumn(TextColumn(start = 20f, end = 30f, charData = "丙"))
            },
        )
        height = 30f
    }

    /** 同一行内文字/图片/文字（图片段 10..50） */
    private fun pageWithInlineImage(): TextPage = TextPage(text = "甲乙丙").apply {
        addLine(
            TextLine(
                text = "甲乙丙",
                lineTop = 0f,
                lineBase = 8f,
                lineBottom = 10f,
                chapterPosition = 0,
                pagePosition = 0,
                isParagraphEnd = true,
            ).apply {
                addColumn(TextColumn(start = 0f, end = 10f, charData = "甲"))
                addColumn(ImageColumn(start = 10f, end = 50f, src = "img"))
                addColumn(TextColumn(start = 50f, end = 60f, charData = "乙"))
                addColumn(TextColumn(start = 60f, end = 70f, charData = "丙"))
            },
        )
        height = 10f
    }

    /** 只有空 TextColumn 与图片列 */
    private fun pageWithOnlyNonTextColumns(): TextPage = TextPage(text = "a").apply {
        addLine(
            TextLine(
                text = "a",
                lineTop = 0f,
                lineBase = 8f,
                lineBottom = 10f,
                chapterPosition = 0,
                pagePosition = 0,
                isParagraphEnd = true,
            ).apply {
                addColumn(TextColumn(start = 0f, end = 0f, charData = ""))
                addColumn(ImageColumn(start = 0f, end = 40f, src = "img"))
            },
        )
        height = 10f
    }

    @Test
    fun `跨独立宽图片行选两侧文字时文字区域不含图片宽且与批注投影一致`() {
        val page0 = pageWithImageLine()
        val selection = selectionOn(Source(cur = page0))
        // 行0 首列 → 行2 末列（跨过独立宽图片行）
        selection.selectRange(page0, PageSelPos(0, 0, 0), PageSelPos(0, 2, 2))
        val geometry = selection.geometrySnapshot(handleSizePx = 24f)!!
        // 文字区域只由两侧文字列并集：x 0..30，不含图片行的 0..100 宽
        assertEquals(0f, geometry.textArea.left, 0f)
        assertEquals(30f, geometry.textArea.right, 0f)
        assertEquals(0f, geometry.textArea.top, 0f)
        assertEquals(30f, geometry.textArea.bottom, 0f)
        // 与批注回显锚定范围一致（同一文字列过滤 + 同一章内账本）
        val range = selection.selectedChapterRange()!!
        val highlight = HighlightOverlay(
            chapterIndex = page0.chapterIndex,
            start = range.start,
            endExclusive = range.endExclusive,
        )
        val highlighted = PageOverlayProjector.projectHighlightAnchorRect(page0, highlight)!!
        assertEquals(geometry.textArea.left, highlighted.left, 0f)
        assertEquals(geometry.textArea.top, highlighted.top, 0f)
        assertEquals(geometry.textArea.right, highlighted.right, 0f)
        assertEquals(geometry.textArea.bottom, highlighted.bottom, 0f)
    }

    @Test
    fun `同一行文字图片文字只投影两段文字列，图片段不着选区`() {
        val page0 = pageWithInlineImage()
        val rects = mutableListOf<Rect>()
        PageOverlayProjector.projectSelection(page0, 0, 0, 0, 3) { l, t, r, b, _ ->
            rects += Rect(l, t, r, b)
        }
        assertEquals(2, rects.size)
        assertEquals(0f, rects[0].left, 0f)
        assertEquals(10f, rects[0].right, 0f)
        assertEquals(50f, rects[1].left, 0f)
        assertEquals(70f, rects[1].right, 0f)
        // 图片区间 10..50 不在任何投影段内
        assertFalse(rects.any { it.left < 50f && it.right > 10f })
    }

    @Test
    fun `非文字列与空 TextColumn 全段不投影，正反向同结果`() {
        val page0 = pageWithOnlyNonTextColumns()
        val forward = mutableListOf<Rect>()
        PageOverlayProjector.projectSelection(page0, 0, 0, 0, 1) { l, t, r, b, _ ->
            forward += Rect(l, t, r, b)
        }
        assertTrue(forward.isEmpty())
        val reversed = mutableListOf<Rect>()
        PageOverlayProjector.projectSelection(page0, 0, 1, 0, 0) { l, t, r, b, _ ->
            reversed += Rect(l, t, r, b)
        }
        assertTrue(reversed.isEmpty())
    }
}
