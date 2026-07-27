package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZhLineBreaker 断行金样：mock 固定字宽表 → 断行结果快照断言。
 *
 * 纯 JVM 可跑（ZhLineBreaker 无 android 依赖），作为排版结果回归红线的地基，
 * 也是将来跨端（common ZhLineBreaker）对拍的基准。手工推演每一步的 lineStart/
 * lineStartCluster/lineWidth，任何断行逻辑漂移都会被捕获。
 */
class ZhLineBreakerGoldenTest {

    private val cnCharWidth = 10f

    private fun break0(
        words: List<String>,
        widths: List<Float>,
        width: Int,
        indentSize: Int = 0,
    ) = ZhLineBreaker(words, widths, indentSize, width, cnCharWidth, 0f)

    /** 场景 A：无标点，纯正常断行。三行 [我是][一二][三]。 */
    @Test
    fun normalBreak() {
        val words = listOf("我", "是", "一", "二", "三")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(20f, 20f, 10f), b.lineWidth.copyOf(b.lineCount), 0f)
    }

    /**
     * 场景 B：禁行首标点（后置标点“，”不可落行首）触发 BREAK_ONE_CHAR，
     * 把“是”连同“，”一起下移。结果 [我][是，][三]，验证避头尾生效。
     */
    @Test
    fun postPancPullDown() {
        val words = listOf("我", "是", "，", "三")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 4), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 1, 3, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(10f, 20f, 10f), b.lineWidth.copyOf(b.lineCount), 0f)
    }

    /** 场景 C：整串未超宽 → 单行，行宽=各字宽之和。 */
    @Test
    fun singleLineNoBreak() {
        val words = listOf("你", "好")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 100)

        assertEquals(1, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(20f), b.lineWidth.copyOf(b.lineCount), 0f)
    }
}
