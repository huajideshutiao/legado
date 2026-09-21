package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LineBreaker] 断行金样：mock 固定字宽 + mock ICU 断点表 → 断行结果快照断言。
 *
 * 纯 JVM 可跑（[LineBreaker] 无平台依赖），作为排版结果回归红线的地基。断点表分两路覆盖：
 * - `opportunities = null`：退化为只用中文禁则表（对应平台无 ICU 的场景，如 [SimpleTextMeasurer]）
 * - 显式传入断点数组：模拟平台 ICU（UAX#14），验证「ICU ∩ 中文禁则」交集口径与词边界
 *
 * 本类只测「在哪些位置断」；标点挤压（改写宽度）是 [PunctuationTrimmer] 的职责，金样在 PunctuationTrimmerTest。
 */
class LineBreakerGoldenTest {

    private fun break0(
        words: List<String>,
        widths: List<Float>,
        width: Int,
        indentSize: Int = 0,
        opportunities: IntArray? = null,
        cnCharWidth: Float = 0f,
    ) = LineBreaker(
        words = words,
        widths = widths,
        opportunities = opportunities,
        indentSize = indentSize,
        width = width,
        letterSpacingPx = 0f,
        cnCharWidth = cnCharWidth,
    )

    /** 逐簇都可断的 ICU 断点表，用于「ICU 全放行、只剩中文禁则起作用」的对照。 */
    private fun allBoundaries(words: List<String>): IntArray {
        val out = ArrayList<Int>(words.size + 1)
        var offset = 0
        for (w in words) {
            out.add(offset)
            offset += w.length
        }
        out.add(offset)
        return out.toIntArray()
    }

    /** 末位必须等于总簇数：切片越界 / 丢字都由此拦住。 */
    private fun assertFullCoverage(words: List<String>, b: LineBreaker) {
        assertEquals(
            "lineStartCluster[lineCount] 必须等于段落总簇数",
            words.size,
            b.lineStartCluster[b.lineCount],
        )
    }

    /** 无标点纯正常断行：[我是][一二][三]。 */
    @Test
    fun `无标点按宽度贪心断行`() {
        val words = listOf("我", "是", "一", "二", "三")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** 禁行首标点「，」不可落行首 → 回退一簇，把「是」连同「，」一起下移：[我][是，][三]。 */
    @Test
    fun `禁行首标点触发回退下移`() {
        val words = listOf("我", "是", "，", "三")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 禁行尾标点「（」不可落行尾：宽 35 本应在簇 3 处断，但簇 2 是「（」
     * → 簇 3 之前禁断，回退到簇 2 把「（」一起下移。结果 [我是][（好]。
     */
    @Test
    fun `禁行尾标点触发回退下移`() {
        val words = listOf("我", "是", "（", "好")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 35)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** 整串未超宽 → 单行。 */
    @Test
    fun `未超宽不断行`() {
        val words = listOf("你", "好")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 100)

        assertEquals(1, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2), b.lineStartCluster.copyOf(b.lineCount + 1))
    }

    /**
     * 禁行首标点正好落在首行「行首」（= indentSize）时不能回退：
     * 缩进字属于上一行，绝不许把它拉下来当陪衬。结果 [　　][，甲][乙]。
     */
    @Test
    fun `禁行首标点落在首行缩进边界时就地断行`() {
        val words = listOf("　", "　", "，", "甲", "乙")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, indentSize = 2)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 同一串 indentSize=0 时前两个全角空格是正文，此时「，」前面确实有可下移的字才允许回退。
     * 结果 [　][　，][甲乙]，与上一条对照证明 indentSize 参与首行判定。
     */
    @Test
    fun `无缩进时禁行首标点才允许回退`() {
        val words = listOf("　", "　", "，", "甲", "乙")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, indentSize = 0)

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** 段末代理对 emoji（length=2）不被截断：末位簇下标 = 总簇数 3。 */
    @Test
    fun `段末代理对簇不被截断`() {
        val words = listOf("我", "是", "😀")
        val widths = listOf(10f, 10f, 20f)
        val b = break0(words, widths, width = 25)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 3), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** ZWJ 复合表情（8 char 聚成 1 簇）按簇计数，不按 char。 */
    @Test
    fun `ZWJ复合表情按簇计数`() {
        // 家庭 emoji U+1F468 ZWJ U+1F469 ZWJ U+1F467；length 断言兼作 ZWJ 未被编辑器吞掉的守卫
        val family = "👨‍👩‍👧"
        val words = listOf(family, "我")
        val widths = listOf(20f, 10f)
        val b = break0(words, widths, width = 100)

        assertEquals(8, family.length)
        assertEquals(1, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 连续两个禁行首标点顶到行尾 → 整块下移、行尾留白（clreq 6.1.1 的「后推出」，
     * 等于原版 `StaticLayout` 口径）：宽 45 装 4 簇（40px），簇 4「。」超宽，
     * 「”」、「。」 都禁行首，回退一路退到簇 2。结果 [一二][三”。四]。
     *
     * 把「”。」 整块压进本行是标点挤压（[PunctuationTrimmer]）的职责，不在断行器里做。
     */
    @Test
    fun `连续禁行首标点整块下移行尾留白`() {
        val words = listOf("一", "二", "三", "”", "。", "四")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 45)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 6), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 阶段 0 挤压定稿的宽度直接参与装行：末簇「。」裁到 5px 后，原本超宽的 5 簇恰好压线装下，
     * 「四」才落到次行。这就是 clreq「先挤进、后推出」里「挤进」那一步的实测效果。
     */
    @Test
    fun `挤压定稿后的宽度参与贪心装行`() {
        val words = listOf("一", "二", "三", "”", "。", "四")
        val widths = listOf(10f, 10f, 10f, 10f, 5f, 10f)
        val b = break0(words, widths, width = 45)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 5, 6), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * ICU 断点表参与交集：英文单词内部无断点 → 整词下移，不从中间切断。
     * 断点只给在段首 0 / 空格前 3 / 空格后 4 / 段末 6，结果 [abc ][de]。
     */
    @Test
    fun `ICU断点表阻止英文单词被切断`() {
        val words = listOf("a", "b", "c", " ", "d", "e")
        val widths = List(words.size) { 10f }
        val b = break0(
            words, widths, width = 45,
            opportunities = intArrayOf(0, 3, 4, 6),
        )

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 4, 6), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 整行无允许断点（超长不可断串）→ 在溢出处就地硬断，宁可切开也不让文字溢出可视区。
     * ICU 只给段首段末断点，宽 25 装 2 簇 → [ab][cd]。
     */
    @Test
    fun `无断点时在溢出处硬断`() {
        val words = listOf("a", "b", "c", "d")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, opportunities = intArrayOf(0, 4))

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** 行尾半角空格不占可用宽（对齐 `StaticLayout`）：[我是 ][好]，空格留在上一行。 */
    @Test
    fun `行尾半角空格不占可用宽`() {
        val words = listOf("我", "是", " ", "好")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, opportunities = intArrayOf(0, 3, 4))

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 3, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 交集口径：ICU 全放行时中文禁则表仍然生效。
     * 实测 ICU 允许 `—` `～` `·` 落行首，中文版面不接受 → 「·」被禁则表拦住，回退一簇。
     */
    @Test
    fun `ICU放行的间隔号仍被中文禁则表拦住`() {
        val words = listOf("我", "是", "·", "好")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, opportunities = allBoundaries(words))

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 4), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /** 空段落：lineCount = 0，不产出任何行。 */
    @Test
    fun `空段落产出零行`() {
        val b = break0(emptyList(), emptyList(), width = 25)
        assertEquals(0, b.lineCount)
    }

    /**
     * clreq 6.1.1「先挤进」+ 6.2.2.3 第 1 档：末簇是行尾全角结束标点时按半字宽试装，
     * 装得下就不退字。宽 45 已装 4 簇（40px），「。」整字 10px 超宽、裁半后 5px 刚好装下 ⇒ 单行。
     */
    @Test
    fun `行尾标点裁半回馈免去退字`() {
        val words = listOf("一", "二", "三", "四", "。")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 45, cnCharWidth = 10f)

        assertEquals(1, b.lineCount)
        assertArrayEquals(intArrayOf(0, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 同一串不给 cnCharWidth（拿不到汉字基准宽）时不回馈：「。」禁行首 → 回退，
     * 而末行「四。」只一字加标点又触发孤字修正 → [一二][三四。]。
     */
    @Test
    fun `无汉字基准宽时行尾标点仍退字`() {
        val words = listOf("一", "二", "三", "四", "。")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 45)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * clreq 7.1.2「孤字不成行」：宽 45 本应切成 [一二三四][五]，末行只剩一字
     * → 由前一行取「四」下移为 [一二三][四五]，前一行留出的空白交给两端对齐均排。
     */
    @Test
    fun `末行孤字由前一行取字补齐`() {
        val words = listOf("一", "二", "三", "四", "五")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 45)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 3, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 孤字修正不得把前一行掏到不达标：整段三字、宽 25 切成 [一二][三]，
     * 下移一字后前一行只剩一字 → 放弃修正保持原样。
     */
    @Test
    fun `孤字无解时保持原样`() {
        val words = listOf("一", "二", "三")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 3), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 孤字修正宁可不做，也不切开西文词：断点只给在 0 / 甲(3) / 乙(4) / 段末(5)，
     * `abc` 内部无 ICU 断点。宽 45 切成 [abc甲][乙]，末行是孤字；退到簇 3 会把前一行
     * 掏成单个西文词（仍不达 [MIN_SUBSTANTIVE]），再退就要切开 `abc` 或超出两字预算
     * ⇒ 放弃修正保持原样。
     */
    @Test
    fun `孤字修正不切开西文词`() {
        val words = listOf("a", "b", "c", "甲", "乙")
        val widths = List(words.size) { 10f }
        val b = break0(
            words, widths, width = 45, cnCharWidth = 10f,
            opportunities = intArrayOf(0, 3, 4, 5),
        )

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 4, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 分隔号 `/` 禁行首（clreq 6.1.1 basic 档，ICU 不管）：宽 25 本应切在簇 2，
     * 回退一簇为 [一][二/]，末行[三四]不是孤字。
     */
    @Test
    fun `分隔号禁行首`() {
        val words = listOf("一", "二", "/", "三", "四")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 25, opportunities = allBoundaries(words))

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 3, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 分隔号禁行尾（clreq 6.1.1 GB 法）：若允许 `/` 落行尾，宽 35 会切成 [一二/][三四五]；
     * 禁行尾使它回退到 [一][二/三][四五] —— `/` 不孤立在行尾、也不落行首。
     */
    @Test
    fun `分隔号禁行尾`() {
        val words = listOf("一", "二", "/", "三", "四", "五")
        val widths = List(words.size) { 10f }
        val b = break0(words, widths, width = 35, opportunities = allBoundaries(words))

        assertEquals(3, b.lineCount)
        assertArrayEquals(intArrayOf(0, 1, 4, 6), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }

    /**
     * 孤字修正末行宽度校验复用标点裁半口径：
     * words: ["三", "四", "五", "六", "。"]
     * widths: [8f, 8f, 8f, 8f, 10f], width = 25, cnCharWidth = 10f.
     * 原装行：前一行 [三四五] (24 <= 25)，末行 [六。] (18 <= 25，只有1个实质字符，触发孤字修正)。
     * 尝试将「五」下移：前一行剩 [三四] (16 <= 25, 实质字符 2 个达标)。
     * 末行变为 [五六。]，若直接累加宽度为 8 + 8 + 10 = 26 > 25 会被错误拒绝；
     * 但末行尾标点「。」可裁半（10 - 5 = 5），实际宽度 8 + 8 + 5 = 21 <= 25，修正成功。
     * 结果切成：[三四][五六。]。
     */
    @Test
    fun `孤字修正末行宽度校验支持标点裁半`() {
        val words = listOf("三", "四", "五", "六", "。")
        val widths = listOf(8f, 8f, 8f, 8f, 10f)
        val b = break0(words, widths, width = 25, cnCharWidth = 10f)

        assertEquals(2, b.lineCount)
        assertArrayEquals(intArrayOf(0, 2, 5), b.lineStartCluster.copyOf(b.lineCount + 1))
        assertFullCoverage(words, b)
    }
}
