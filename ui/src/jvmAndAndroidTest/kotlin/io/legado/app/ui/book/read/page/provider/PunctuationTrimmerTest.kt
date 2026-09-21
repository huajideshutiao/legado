package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [PunctuationTrimmer] 标点挤压金样：字宽数组 → 挤压结果快照断言。
 *
 * 汉字基准宽取 10px（裁半 = 5px），与 [LineBreakerGoldenTest] 同一套 mock 口径，
 * 不依赖任何平台度量。判定表逐条对照 CSS Text 4 §8.5.1 / clreq 6.3.2.2 / GB/T 15834 §5.1.10。
 */
class PunctuationTrimmerTest {

    private val cn = 10f
    private val half = 5f

    private fun trim(text: String, vararg widths: Float): TrimResult {
        val words = text.map { it.toString() }
        val ws = if (widths.isEmpty()) List(words.size) { cn } else widths.toList()
        require(words.size == ws.size) { "字宽个数要与簇数一致" }
        return PunctuationTrimmer.trimAdjacent(words, ws, cn)
    }

    private fun assertWidths(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("第 $i 簇", expected[i], actual[i], 0f)
        }
    }

    private fun assertOffsets(expected: List<Float>, actual: List<Float>?) {
        assertNotNull("应存在绘制偏移", actual)
        assertEquals(expected.size, actual!!.size)
        for (i in expected.indices) {
            assertEquals("第 $i 簇偏移", expected[i], actual[i], 0f)
        }
    }

    // ---- 阶段 0：相邻标点挤压 ----

    /**
     * `：“`（结束 + 开始，中文小说每段对话开头）：`：` 后一字是同号 opening，规范要求 larger 字号才触发
     * → 不裁；`“` 前一字是 closing → 裁左半。合计 1.5 字宽，正落在 clreq 6.3.2.2「应当缩减成 1.5 个字宽」。
     */
    @Test
    fun `冒号后接开始引号只裁引号左半`() {
        val r = trim("：“")
        assertWidths(listOf(10f, 5f), r.widths)
        assertOffsets(listOf(0f, -5f), r.drawOffsets)
    }

    /** `”。`（结束 + 结束）：前一个 closing 后接 closing → 裁右半，字形不动、无偏移。 */
    @Test
    fun `双引号后接句号裁前一个右半`() {
        val r = trim("”。")
        assertWidths(listOf(5f, 10f), r.widths)
        assertNull("裁右半不需要平移字形", r.drawOffsets)
    }

    /** CSS §8.5.1 成对示例 `）（`→`）(`：后一个 opening 前接 closing → 裁左半。 */
    @Test
    fun `右括号后接左括号裁左括号`() {
        val r = trim("）（")
        assertWidths(listOf(10f, 5f), r.widths)
        assertOffsets(listOf(0f, -5f), r.drawOffsets)
    }

    /** CSS 成对示例 `〔（`→`〔(`：连续两个 opening，只有后一个裁左半。 */
    @Test
    fun `连续开始夹注只裁后一个`() {
        val r = trim("“‘")
        assertWidths(listOf(10f, 5f), r.widths)
        assertOffsets(listOf(0f, -5f), r.drawOffsets)
    }

    /** CSS 成对示例 `　（`→`　(`：全角空格（段首缩进实体）是触发位，自身不裁。 */
    @Test
    fun `全角空格后接左括号裁左括号`() {
        val r = trim("　（")
        assertWidths(listOf(10f, 5f), r.widths)
        assertOffsets(listOf(0f, -5f), r.drawOffsets)
    }

    /** CSS 成对示例 `）・`→`)・`：间隔号是触发位，自身不裁（GB/T 固定半字宽）。 */
    @Test
    fun `间隔号触发前一个裁右但自身不裁`() {
        val r = trim("”·", 10f, 6.7f)
        assertWidths(listOf(5f, 6.7f), r.widths)
        assertNull(r.drawOffsets)
    }

    /** 三个连续 closing：前两簇各裁右半，末簇后面没有触发位 → 保持全宽。 */
    @Test
    fun `三连结束标点逐对收缩`() {
        val r = trim("甲。””")
        assertWidths(listOf(10f, 5f, 5f, 10f), r.widths)
    }

    /**
     * 比例字形不可裁（CSS §8.5.1「the UA must not add or remove space to these glyphs」）：
     * 类别命中但 advance 不等于汉字基准宽 → 整簇跳过。
     */
    @Test
    fun `窄于汉字宽的开始标点不裁`() {
        val r = trim("。“", 10f, 8f)
        assertWidths(listOf(10f, 8f), r.widths)
        assertNull(r.drawOffsets)
    }

    /** 半角标点不在 fullwidth 类别表里 → 永不参与挤压。 */
    @Test
    fun `半角标点不参与挤压`() {
        val r = trim("a,b", 5f, 5f, 5f)
        assertWidths(listOf(5f, 5f, 5f), r.widths)
        assertNull(r.drawOffsets)
    }

    /** 汉字隔开两个标点 → 触发位不成立，两侧都不裁。 */
    @Test
    fun `被汉字隔开的标点不裁`() {
        val r = trim("。甲“")
        assertWidths(listOf(10f, 10f, 10f), r.widths)
        assertNull(r.drawOffsets)
    }

    /** 代理对簇（length=2）不分类：既不当触发位，也不被裁。 */
    @Test
    fun `代理对簇不参与判定`() {
        val words = listOf("。", "😀", "“")
        val r = PunctuationTrimmer.trimAdjacent(words, List(3) { cn }, cn)
        assertWidths(listOf(10f, 10f, 10f), r.widths)
        assertNull(r.drawOffsets)
    }

    /** 一处都挤不动时原样返回入参列表（热路径不新分配宽度表）。 */
    @Test
    fun `无可挤压时不新分配`() {
        val words = listOf("甲", "乙", "丙")
        val widths = List(3) { cn }
        val r = PunctuationTrimmer.trimAdjacent(words, widths, cn)
        assertSame(widths, r.widths)
        assertNull(r.drawOffsets)
    }

    /** 基准宽不可判（<=0）时整段不挤。 */
    @Test
    fun `基准宽不可判时不挤`() {
        val r = PunctuationTrimmer.trimAdjacent(listOf("：", "“"), listOf(cn, cn), 0f)
        assertWidths(listOf(10f, 10f), r.widths)
        assertNull(r.drawOffsets)
    }

    // ---- 阶段 2：行首 / 行尾挤压 ----

    /** 行尾结束标点裁右半（GB/T 15834 用「应」，无条件）：字形不动，只收掉末侧空白。 */
    @Test
    fun `行尾结束标点裁右半`() {
        val r = PunctuationTrimmer.trimLineEdges(
            words = listOf("甲", "乙", "丙", "。"),
            widths = listOf(cn, cn, cn, cn),
            drawOffsets = null,
            cnCharWidth = cn,
        )
        assertNotNull(r)
        assertWidths(listOf(10f, 10f, 10f, 5f), r!!.widths)
        assertNull("只裁右半时不需要平移", r.drawOffsets)
    }

    /** 行首开始夹注裁左半（clreq 用「可以」，随开关）：字形左移半字，正文起点前移。 */
    @Test
    fun `行首开始夹注裁左半`() {
        val r = PunctuationTrimmer.trimLineEdges(
            words = listOf("“", "甲", "乙"),
            widths = listOf(cn, cn, cn),
            drawOffsets = null,
            cnCharWidth = cn,
        )
        assertNotNull(r)
        assertWidths(listOf(5f, 10f, 10f), r!!.widths)
        assertOffsets(listOf(-5f, 0f, 0f), r.drawOffsets)
    }

    /** 行首 + 行尾同时命中：两处一起改，互不干扰。 */
    @Test
    fun `行首行尾同时挤压`() {
        val r = PunctuationTrimmer.trimLineEdges(
            words = listOf("“", "甲", "”"),
            widths = listOf(cn, cn, cn),
            drawOffsets = null,
            cnCharWidth = cn,
        )
        assertNotNull(r)
        assertWidths(listOf(5f, 10f, 5f), r!!.widths)
        assertOffsets(listOf(-5f, 0f, 0f), r.drawOffsets)
    }

    /** 阶段 0 已裁过的簇宽度不再等于基准宽 → 阶段 2 不重复裁（同一簇最多裁一半）。 */
    @Test
    fun `阶段零已裁的簇不被阶段二重复裁`() {
        val r = PunctuationTrimmer.trimLineEdges(
            words = listOf("“", "甲", "”"),
            widths = listOf(half, cn, cn),
            drawOffsets = listOf(-half, 0f, 0f),
            cnCharWidth = cn,
        )
        assertNotNull("行尾仍可裁", r)
        assertWidths(listOf(5f, 10f, 5f), r!!.widths)
        assertOffsets(listOf(-5f, 0f, 0f), r.drawOffsets)
    }

    /** 行尾裁半时必须原样保留阶段 0 算出的偏移，否则句中平移会丢。 */
    @Test
    fun `行尾裁半保留阶段零偏移`() {
        val r = PunctuationTrimmer.trimLineEdges(
            words = listOf("甲", "“", "乙", "”"),
            widths = listOf(cn, half, cn, cn),
            drawOffsets = listOf(0f, -half, 0f, 0f),
            cnCharWidth = cn,
        )
        assertNotNull(r)
        assertWidths(listOf(10f, 5f, 10f, 5f), r!!.widths)
        assertOffsets(listOf(0f, -5f, 0f, 0f), r.drawOffsets)
    }

    /** 整行只有一簇：裁行尾既不省空间也不改观感，直接放弃。 */
    @Test
    fun `单簇行不做行尾挤压`() {
        assertNull(
            PunctuationTrimmer.trimLineEdges(
                words = listOf("。"),
                widths = listOf(cn),
                drawOffsets = null,
                cnCharWidth = cn,
            ),
        )
    }

    /** 行首是汉字、行尾是图片占位符 → 两处都不命中，返回 null 让调用方沿用原宽。 */
    @Test
    fun `无可挤压的行返回null`() {
        assertNull(
            PunctuationTrimmer.trimLineEdges(
                words = listOf("甲", "乙", "▩"),
                widths = listOf(cn, cn, cn),
                drawOffsets = null,
                cnCharWidth = cn,
            ),
        )
    }
}
