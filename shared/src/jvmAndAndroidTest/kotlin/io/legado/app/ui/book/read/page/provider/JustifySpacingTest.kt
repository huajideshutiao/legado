package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JustifySpacing] 金样：中西间距注入（clreq 6.3.3）与两端对齐余量分配（clreq 6.2.2.4 / 6.2.3）。
 *
 * 汉字基准宽固定 10px ⇒ 1/4 = 2.5px、1/2 = 5px，断言全是手推出来的定值。
 */
class JustifySpacingTest {

    private val cn = 10f

    private fun widths(vararg w: Float) = w.toList()

    // ---------- 中西间距（clreq 6.3.3）----------

    /** 汉字 → 西文字母边界注入 1/4 汉字宽，加在前一簇宽度上。 */
    @Test
    fun `汉字与西文字母之间注入四分之一字宽`() {
        val words = listOf("的", "a", "的")
        val out = JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f, 10f), cn)

        assertEquals(listOf(12.5f, 7.5f, 10f), out)
    }

    /** 汉字 → 阿拉伯数字同样注入；数字串内部不注入。 */
    @Test
    fun `汉字与数字之间注入且数字内部不注入`() {
        val words = listOf("共", "1", "2", "章")
        val out = JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f, 5f, 10f), cn)

        assertEquals(listOf(12.5f, 5f, 7.5f, 10f), out)
    }

    /** clreq 6.3.3 第二段：中文点号前后不加中西间距。 */
    @Test
    fun `中文点号与西文之间不注入`() {
        val words = listOf("。", "a", "，")
        val out = JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f, 10f), cn)

        assertNull(out)
    }

    /** 开始夹注符号之后、结束夹注符号之前也不加。 */
    @Test
    fun `夹注符号与西文之间不注入`() {
        val words = listOf("（", "a", "）")
        val out = JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f, 10f), cn)

        assertNull(out)
    }

    /** 纯汉字段落不动，且不新分配。 */
    @Test
    fun `纯汉字不注入`() {
        val words = listOf("一", "二", "三")
        assertNull(JustifySpacing.insertHanWestSpacing(words, widths(10f, 10f, 10f), cn))
    }

    /** 全角空格（段首缩进实体）不算汉字侧，避免缩进后多出 2.5px。 */
    @Test
    fun `全角空格与西文之间不注入`() {
        val words = listOf("　", "a")
        assertNull(JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f), cn))
    }

    /** 基准宽不可判时整段不注入。 */
    @Test
    fun `基准宽不可判时不注入`() {
        val words = listOf("的", "a")
        assertNull(JustifySpacing.insertHanWestSpacing(words, widths(10f, 5f), 0f))
    }

    // ---------- 间隙分类（clreq 6.1.2 / 6.2.2.4 / 6.2.3）----------

    @Test
    fun `西文单词内部与字母数字之间不可拉伸`() {
        val kinds = JustifySpacing.classifyGaps(listOf("a", "b", "1", "的"))

        assertArrayEquals(
            byteArrayOf(JustifySpacing.GAP_FIXED, JustifySpacing.GAP_FIXED, JustifySpacing.GAP_HAN_WEST),
            kinds,
        )
    }
    /** clreq 6.1.2.2：数字与后缀单位、前缀符号与数字都不可拆，也就不可拉伸。 */
    @Test
    fun `数字与前后缀单位符号之间不可拉伸`() {
        val kinds = JustifySpacing.classifyGaps(listOf("9", "%", "的", "+", "1"))

        assertEquals(JustifySpacing.GAP_FIXED, kinds[0])
        assertEquals(JustifySpacing.GAP_FIXED, kinds[3])
    }

    /** clreq 6.2.2.4 末条：连接号 / 分隔号与其前后一律不拉伸。 */
    @Test
    fun `连接号与分隔号前后不可拉伸`() {
        val kinds = JustifySpacing.classifyGaps(listOf("甲", "—", "乙", "/", "丙"))

        assertArrayEquals(
            byteArrayOf(
                JustifySpacing.GAP_FIXED,
                JustifySpacing.GAP_FIXED,
                JustifySpacing.GAP_FIXED,
                JustifySpacing.GAP_FIXED,
            ),
            kinds,
        )
    }

    /** clreq 6.1.2.1：`——` / `……` 占两字宽视为一体，内部不可拉伸。 */
    @Test
    fun `破折号与省略号连写内部不可拉伸`() {
        assertEquals(JustifySpacing.GAP_FIXED, JustifySpacing.classifyGaps(listOf("—", "—"))[0])
        assertEquals(JustifySpacing.GAP_FIXED, JustifySpacing.classifyGaps(listOf("…", "…"))[0])
    }

    /**
     * 西文词距只计空白簇自身之后那一个间隙（第 i 个间隙 = 簇 i 与簇 i+1 之间，
     * 空白在簇 1 ⇒ 归间隙 1）：两侧都算会把同一个词距拉两次，破掉 clreq 6.2.2.4 的单处上限。
     */
    @Test
    fun `半角空格只在其后算西文词距`() {
        val kinds = JustifySpacing.classifyGaps(listOf("a", " ", "b"))

        assertArrayEquals(
            byteArrayOf(JustifySpacing.GAP_FIXED, JustifySpacing.GAP_SPACE),
            kinds,
        )
    }

    @Test
    fun `汉字之间归为通用间隙`() {
        assertEquals(JustifySpacing.GAP_GENERAL, JustifySpacing.classifyGaps(listOf("甲", "乙"))[0])
    }

    @Test
    fun `单簇无间隙`() {
        assertEquals(0, JustifySpacing.classifyGaps(listOf("甲")).size)
    }

    // ---------- 余量分配（clreq 6.2.2.4 优先顺序与上限）----------

    /** 有西文词距时优先摊给它，汉字间隙与中西边界一分不吃。 */
    @Test
    fun `余量优先摊给西文词距`() {
        val words = listOf("甲", "a", " ", "b", "乙")
        val w = widths(10f, 5f, 3f, 5f, 10f)
        val kinds = JustifySpacing.classifyGaps(words)
        val out = JustifySpacing.distribute(2f, kinds, cn, w)!!

        // 只有间隙 2（空白之后）是 GAP_SPACE，全部余量归它
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(2f, out[2], 1e-4f)
        assertEquals(0f, out[3], 1e-4f)
    }

    /**
     * clreq 6.2.2.4：西文词距「最大可以拉伸到半个汉字字宽」—— 取的是最终宽度上限，
     * 故 3px 的空白最多再加 2px（= cn/2 - 3），吃不下的落到汉字间隙。
     */
    @Test
    fun `西文词距最终宽不超过半字宽`() {
        val words = listOf("甲", " ", "乙", "丙")
        val w = widths(10f, 3f, 10f, 10f)
        val kinds = JustifySpacing.classifyGaps(words)
        val out = JustifySpacing.distribute(30f, kinds, cn, w)!!

        assertEquals(0f, out[0], 1e-4f)
        assertEquals(2f, out[1], 1e-4f)
        // 余下 28f 全部落到唯一的汉字间隙（这一档规范未设上限）
        assertEquals(28f, out[2], 1e-4f)
    }

    /** 中西间距在 [JustifySpacing.insertHanWestSpacing] 给的 1/4 基础上最多再 +1/4。 */
    @Test
    fun `中西间距拉伸不超过再四分之一字宽`() {
        val words = listOf("甲", "a")
        val w = widths(12.5f, 5f)
        val kinds = JustifySpacing.classifyGaps(words)
        val out = JustifySpacing.distribute(100f, kinds, cn, w)!!

        assertEquals(2.5f, out[0], 1e-4f)
    }

    /**
     * 整行全是不可拉伸间隙（数字串）时余量留在行尾不拉伸 —— 拉散数字是 clreq 明令禁止的，
     * 6.1.1 注也允许个别行局部不处理。
     */
    @Test
    fun `全为不可拉伸间隙时不拉伸`() {
        val words = listOf("1", "2", "3")
        val kinds = JustifySpacing.classifyGaps(words)

        assertNull(JustifySpacing.distribute(20f, kinds, cn, widths(5f, 5f, 5f)))
    }

    @Test
    fun `无余量时不分配`() {
        val words = listOf("甲", "乙")
        val kinds = JustifySpacing.classifyGaps(words)
        val w = widths(10f, 10f)

        assertNull(JustifySpacing.distribute(0f, kinds, cn, w))
        assertNull(JustifySpacing.distribute(-5f, kinds, cn, w))
    }

    /** 汉字间隙等量拉伸，总量与余量守恒（clreq 6.3.1.2 均排）。 */
    @Test
    fun `汉字间隙等量摊完余量`() {
        val words = listOf("甲", "乙", "丙", "丁")
        val kinds = JustifySpacing.classifyGaps(words)
        val out = JustifySpacing.distribute(9f, kinds, cn, widths(10f, 10f, 10f, 10f))!!

        assertEquals(3f, out[0], 1e-4f)
        assertEquals(3f, out[1], 1e-4f)
        assertEquals(3f, out[2], 1e-4f)
        assertTrue(out.sum() - 9f < 1e-3f)
    }
}
