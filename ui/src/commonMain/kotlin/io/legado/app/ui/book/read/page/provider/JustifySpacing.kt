package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.JustifySpacing.GAP_FIXED
import io.legado.app.ui.book.read.page.provider.JustifySpacing.classifyGaps
import io.legado.app.ui.book.read.page.provider.JustifySpacing.distribute
import io.legado.app.ui.book.read.page.provider.JustifySpacing.insertHanWestSpacing


/**
 * 行内空白的插入与分配（W3C clreq 6.2.2.4 拉伸优先顺序 + 6.2.3 混排均排口径 + 6.3.3 中西间距）。
 * 纯算术，只读簇字符串与字宽，不触发任何平台度量。
 *
 * # 两件事
 *
 * - [insertHanWestSpacing]（Phase 1，**必须先于断行**）：在汉字与西文字母 / 阿拉伯数字的边界注入
 *   1/4 汉字宽的基础间距（clreq 6.3.3「原则上，汉字与西文字母、数字间使用不多于四分之一个汉字宽的
 *   字距或空白」）。与标点挤压同理，它改变行宽就必须在 [LineBreaker] 之前定稿。
 * - [classifyGaps] + [distribute]（Phase 2，逐行）：把两端对齐的余量按 clreq 6.2.2.4 的优先顺序
 *   分配到各间隙，并对每一档设上限。
 *
 * # 有意偏离原版
 *
 * 原版 `TextChapterLayout.justifyByLetterSpacing` 把余量无上限均摊到**每一个**簇间隙，
 * 于是 `Windows` 的字母之间、`2026/09/04` 的数字之间、`——` `……` 内部都会被拉开。
 * clreq 6.2.2.4 明确「禁止对符号分离禁则规定的字间距进行拉伸」「避免对连接号、分隔号与其前后的
 * 字符进行拉伸」，6.2.3 又规定「各西文词组间、阿拉伯数字间的空格，以及西文字母与阿拉伯数字之间
 * 不使用均排，仅调整汉字、汉字与西文间的字距或空白」。本文件按规范实现，不复刻原版这处缺陷。
 *
 * # 间隙编号
 *
 * 第 `i` 个间隙 = 第 `i` 簇与第 `i + 1` 簇之间，共 `words.size - 1` 个。
 */
object JustifySpacing {

    /** 间隙类别：不可拉伸。 */
    const val GAP_FIXED: Byte = 0

    /** 间隙类别：西文词距（半角空格），拉伸第一优先。 */
    const val GAP_SPACE: Byte = 1

    /** 间隙类别：中西边界（已含 1/4 汉字宽基础间距），拉伸第二优先。 */
    const val GAP_HAN_WEST: Byte = 2

    /** 间隙类别：汉字之间，最后兜底均摊。 */
    const val GAP_GENERAL: Byte = 3

    /** 连接号与分隔号：其**前后**间隙一律不拉伸（clreq 6.2.2.4 末条）。 */
    private const val CONNECTOR_CHARS = "—～－‐‑–—〜~/／"

    /** 与前置阿拉伯数字不可分的后缀单位（clreq 6.1.2.2）。 */
    private const val NUMERIC_SUFFIX = "%％‰℃℉°"

    /** 与后置阿拉伯数字不可分的前缀符号（正负号 + 常见货币符号，clreq 6.1.2.2）。 */
    private const val NUMERIC_PREFIX = "+-±＋－￥¥$＄€£₫"

    /** 占两个汉字宽、应视为一体的标点（clreq 6.1.2.1）：连写时内部不得拉伸。 */
    private const val UNBREAKABLE_PAIR = "…—"

    /** 中文点号与夹注号：其与西文之间不加中西间距（clreq 6.3.3 第二段）。 */
    private const val NO_HAN_WEST_SPACE = "！，。、；：？”’）］｝》〉〕】〗」』﹂﹄“‘（［｛《〈〔【〖『「﹁﹃"

    private val connectorBits = bitsOf(CONNECTOR_CHARS)
    private val numericSuffixBits = bitsOf(NUMERIC_SUFFIX)
    private val numericPrefixBits = bitsOf(NUMERIC_PREFIX)
    private val unbreakablePairBits = bitsOf(UNBREAKABLE_PAIR)
    private val noHanWestBits = bitsOf(NO_HAN_WEST_SPACE)

    private fun bitsOf(chars: String): LongArray = LongArray(1024).apply {
        for (i in chars.indices) {
            val c = chars[i].code
            this[c ushr 6] = this[c ushr 6] or (1L shl c)
        }
    }

    private fun LongArray.test(code: Int): Boolean = (this[code ushr 6] and (1L shl code)) != 0L

    private fun single(cluster: String): Int = if (cluster.length == 1) cluster[0].code else -1

    /** 西文字母。 */
    private fun isLatin(cluster: String): Boolean {
        val c = single(cluster)
        return c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
    }

    /** 阿拉伯数字。 */
    private fun isDigit(cluster: String): Boolean = single(cluster) in '0'.code..'9'.code

    /** 西文字母或阿拉伯数字（合起来构成「西文」侧）。 */
    private fun isWestern(cluster: String): Boolean = isLatin(cluster) || isDigit(cluster)

    /**
     * 汉字侧：CJK 表意文字 / 假名 / 韩文音节 / 全角形式 / CJK 符号标点。
     * 全角空格 U+3000（段首缩进实体）与代理对多字簇不算，前者是空白、后者无从判类别。
     */
    private fun isHan(cluster: String): Boolean {
        val code = single(cluster)
        if (code < 0 || code == 0x3000) return false
        return code in 0x2E80..0x303F || code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F || code in 0xFF01..0xFF60
    }

    /** 半角空格 / 制表符：西文词距的载体。 */
    private fun isWordSpace(cluster: String): Boolean {
        val code = single(cluster)
        return code == ' '.code || code == '\t'.code
    }

    /**
     * 判定两字素簇之间是否符合 clreq 6.3.3 中西间距注入条件（汉字与西文边界，且无点号/夹注号）。
     */
    fun isHanWestBoundary(a: String, b: String): Boolean {
        val boundary = (isHan(a) && isWestern(b)) || (isWestern(a) && isHan(b))
        if (!boundary) return false
        val hanSide = if (isHan(a)) a else b
        val code = single(hanSide)
        return !(code >= 0 && noHanWestBits.test(code))
    }

    /**
     * clreq 6.3.3：在汉字与西文字母 / 阿拉伯数字的边界注入 1/4 汉字宽基础间距。
     *
     * 空白加在**前一簇的字宽**上（绘制侧按列盒起点落笔，故多出来的宽度呈现为该字之后的空隙）。
     * 中文点号前后、开始夹注符号之后、结束夹注符号之前不注入（规范明列）；
     * 若断行恰发生在汉字与西文边界处，由 [ParagraphLayoutEngine] 在行尾清理时移除残留附加宽度。
     *
     * @return 改写后的字宽；没有任何边界需要注入时返回 null（不新分配）
     */
    fun insertHanWestSpacing(
        words: List<String>,
        widths: List<Float>,
        cnCharWidth: Float,
    ): List<Float>? {
        val size = words.size
        if (size < 2 || cnCharWidth <= 0f) return null
        val quarter = cnCharWidth / 4f
        var out: FloatArray? = null
        for (i in 0 until size - 1) {
            if (!isHanWestBoundary(words[i], words[i + 1])) continue
            val dst = out ?: FloatArray(size) { widths[it] }
            dst[i] += quarter
            out = dst
        }
        return out?.asList()
    }

    /**
     * 逐间隙分类，供 [distribute] 按 clreq 6.2.2.4 的优先顺序分配余量。
     *
     * 判为 [GAP_FIXED]（不可拉伸）的情形：
     * - 阿拉伯数字串内部、数字与其前后缀单位符号之间（6.1.2.2 符号分离禁则）
     * - 西文单词内部、字母与数字之间（6.2.3「不使用均排」）
     * - 连接号 / 分隔号与其前后（6.2.2.4 末条）
     * - `——` `……` 这类占两字宽的标点连写内部（6.1.2.1）
     *
     * 西文词距只计**空白簇自身之后**那一个间隙（空白之前的那个归 [GAP_FIXED]）：
     * 两侧都算会把同一个词距拉两次，破掉 6.2.2.4 的单处上限。空白落在末簇时它之后无间隙，
     * 自然不拉伸 —— 与 [LineBreaker]「行尾空白不占可用宽」同口径。
     *
     * @return 长度 `words.size - 1` 的类别数组；簇数不足 2 时为空数组
     */
    fun classifyGaps(words: List<String>): ByteArray {
        val size = words.size
        if (size < 2) return ByteArray(0)
        val kinds = ByteArray(size - 1)
        for (i in 0 until size - 1) {
            val a = words[i]
            val b = words[i + 1]
            kinds[i] = when {
                isWordSpace(a) -> GAP_SPACE
                isWordSpace(b) -> GAP_FIXED
                isFixedGap(a, b) -> GAP_FIXED
                isHan(a) && isWestern(b) || isWestern(a) && isHan(b) -> GAP_HAN_WEST
                else -> GAP_GENERAL
            }
        }
        return kinds
    }

    private fun isFixedGap(a: String, b: String): Boolean {
        val ca = single(a)
        val cb = single(b)
        // 连接号 / 分隔号与其前后
        if (ca >= 0 && connectorBits.test(ca)) return true
        if (cb >= 0 && connectorBits.test(cb)) return true
        // 数字 / 字母内部与彼此之间
        if (isWestern(a) && isWestern(b)) return true
        // 数字与后缀单位、前缀符号与数字
        if (isDigit(a) && cb >= 0 && numericSuffixBits.test(cb)) return true
        if (ca >= 0 && numericPrefixBits.test(ca) && isDigit(b)) return true
        // 占两字宽的标点连写（`——` / `……`）内部
        if (ca >= 0 && ca == cb && unbreakablePairBits.test(ca)) return true
        return false
    }

    /**
     * 按 clreq 6.2.2.4 的优先顺序把 [residual] 分配到各间隙：
     * ① 西文词距 → ② 中西间距 → ③ 剩余全部均摊到汉字之间的间隙（这一档规范未设上限）。
     *
     * 上限取的是规范的「拉伸到……」，即**最终宽度**上界，不是增量上界：
     * - 西文词距最终不超过 1/2 汉字宽 ⇒ 增量 ≤ `cn/2 - 空白自身宽`
     * - 中西间距最终不超过 1/2 汉字宽，而 [insertHanWestSpacing] 已给了 1/4 ⇒ 增量 ≤ cn/4
     *
     * 同一档内「同时、同等量处理」：逐轮均分，碰上限的间隙先取满退出，剩下的再均分，
     * 直到余量发完或本档全满（各空白宽度一致时一轮就收）。
     *
     * 三档都吃不下（如整行是一串数字）时余量留在行尾不拉伸 —— 拉散数字与单词是规范明令禁止的，
     * clreq 6.1.1 注也允许「个别特殊状况局部不处理」。
     *
     * @param widths 逐簇字宽（与 [kinds] 同一行，长度 = `kinds.size + 1`），算西文词距上限用
     * @return 长度与 [kinds] 相同的逐间隙增量；无处可拉时返回 null
     */
    fun distribute(
        residual: Float,
        kinds: ByteArray,
        cnCharWidth: Float,
        widths: List<Float>,
    ): FloatArray? {
        if (residual <= 0f || kinds.isEmpty() || cnCharWidth <= 0f) return null
        val caps = FloatArray(kinds.size) { i ->
            when (kinds[i]) {
                // 空白自身宽度已在 widths[i]（GAP_SPACE 的间隙左侧就是那个空白簇）
                GAP_SPACE -> (cnCharWidth / 2f - widths[i]).coerceAtLeast(0f)
                GAP_HAN_WEST -> cnCharWidth / 4f
                GAP_GENERAL -> Float.MAX_VALUE
                else -> 0f
            }
        }
        val out = FloatArray(kinds.size)
        var remaining = residual
        remaining -= fill(out, kinds, GAP_SPACE, remaining, caps)
        remaining -= fill(out, kinds, GAP_HAN_WEST, remaining, caps)
        remaining -= fill(out, kinds, GAP_GENERAL, remaining, caps)
        return if (remaining < residual) out else null
    }

    /**
     * 把 [remaining] 摊给类别为 [kind] 的间隙，每处不超过 [caps]，返回实际用掉的量。
     * 逐轮均分：碰上限的先取满并退出本轮池子，剩下的继续均分。
     */
    private fun fill(
        out: FloatArray,
        kinds: ByteArray,
        kind: Byte,
        remaining: Float,
        caps: FloatArray,
    ): Float {
        if (remaining <= 0f) return 0f
        var pool = remaining
        var used = 0f
        // 每轮至少定死一个间隙（取满上限或全部均分完），故轮数不超过本档间隙数
        while (pool > 0f) {
            var count = 0
            for (i in kinds.indices) if (kinds[i] == kind && out[i] < caps[i]) count++
            if (count == 0) break
            val each = pool / count
            var minCap = Float.MAX_VALUE
            for (i in kinds.indices) {
                if (kinds[i] == kind && out[i] < caps[i]) {
                    val room = caps[i] - out[i]
                    if (room < minCap) minCap = room
                }
            }
            val step = if (minCap < each) minCap else each
            if (step <= 0f) break
            for (i in kinds.indices) {
                if (kinds[i] == kind && out[i] < caps[i]) {
                    out[i] += step
                    used += step
                    pool -= step
                }
            }
            if (step == each) break
        }
        return used
    }
}
