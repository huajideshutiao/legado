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

    /** 末行终点必须覆盖整段：字符维度=总字符数，簇维度=总簇数。切片越界/丢字都由此拦住。 */
    private fun assertFullCoverage(words: List<String>, b: ZhLineBreaker) {
        assertEquals(
            "lineStart[lineCount] 必须等于段落总字符数",
            words.sumOf { it.length },
            b.lineStart[b.lineCount],
        )
        assertEquals(
            "lineStartCluster[lineCount] 必须等于段落总簇数",
            words.size,
            b.lineStartCluster[b.lineCount],
        )
    }

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

    /**
     * 禁首标点正好落在“行首”（首行的行首 = indentSize）时只能 NORMAL 断行：
     * 缩进字属于上一行，绝不许把它拉下来当陪衬。结果 [　　][，甲][乙]。
     */
    @Test
    fun `禁首标点落在首行缩进边界时按 NORMAL 断行`() {
        val words = listOf("　", "　", "，", "甲", "乙")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, indentSize = 2)

        assertEquals(3, b.lineCount)
        // 行 1 从簇 2（即“，”）起，簇 1 的缩进字留在行 0；旧式 index>=1 会得到 [0,1,...]
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(20f, 20f, 10f), b.lineWidth.copyOf(b.lineCount), 0f)
        assertFullCoverage(words, b)
    }

    /**
     * 同一串在 indentSize=0 时前两个全角空格是正文，此时“，”前面确实有可下移的字，
     * 才允许 BREAK_ONE_CHAR。结果 [　][　，][甲乙]，与上一条对照证明 indentSize 参与行首判定。
     */
    @Test
    fun `无缩进时禁首标点才允许把上一字下移`() {
        val words = listOf("　", "　", "，", "甲", "乙")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, indentSize = 0)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 5), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 1, 3, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(10f, 20f, 20f), b.lineWidth.copyOf(b.lineCount), 0f)
        assertFullCoverage(words, b)
    }

    /**
     * 段末写满断行（NORMAL，breakCharCnt=1）且末簇是代理对 emoji（length=2）：
     * 末行字符终点必须是 4（段落总字符数），旧式 lineStart[line]+breakCharCnt 只会算出 3 而截断低位代理。
     */
    @Test
    fun `段末代理对簇的字符终点等于段落总字符数`() {
        val words = listOf("我", "是", "😀")
        val widths = listOf(10f, 10f, 20f)
        val b = break0(words, widths, width = 25)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 2, 3), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(20f, 20f), b.lineWidth.copyOf(b.lineCount), 0f)
        assertFullCoverage(words, b)
    }

    /**
     * 同上但走 BREAK_ONE_CHAR（末簇前是禁行尾标点“（”，需连它一起下移，breakCharCnt=2）：
     * 末行字符终点同样必须覆盖代理对的 2 个字符，旧式会算成 lineStart[1]+2 = 3。
     */
    @Test
    fun `段末禁行尾标点下移时代理对簇不被截断`() {
        val words = listOf("我", "（", "😀")
        val widths = listOf(10f, 10f, 20f)
        val b = break0(words, widths, width = 25)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 4), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 1, 3), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertArrayEquals(floatArrayOf(10f, 30f), b.lineWidth.copyOf(b.lineCount), 0f)
        assertFullCoverage(words, b)
    }

    /** 未断行的段末分支同样按簇长度累加：ZWJ 复合表情（8 个 char 聚成 1 簇）+ 1 汉字 = 9 字符。 */
    @Test
    fun `未断行段末按簇长度累加字符偏移`() {
        // 家庭 emoji U+1F468 ZWJ U+1F469 ZWJ U+1F467；length 断言兼作 ZWJ 未被编辑器吞掉的守卫
        val family = "👨‍👩‍👧"
        val words = listOf(family, "我")
        val widths = listOf(20f, 10f)
        val b = break0(words, widths, width = 100)

        assertEquals(8, family.length)
        assertEquals(1, b.lineCount)
        assertArrayEquals(intArrayOf(0, 9), b.lineStart.copyOf(b.lineCount + 1))
        assertArrayEquals(intArrayOf(0, 2), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }
}
