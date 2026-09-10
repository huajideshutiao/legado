package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.LineBreaker.Companion.MIN_SUBSTANTIVE
import io.legado.app.ui.book.read.page.provider.LineBreaker.Companion.ORPHAN_BUDGET_CHARS
import io.legado.app.ui.book.read.page.provider.LineBreaker.Companion.POST_PANC_CHARS
import io.legado.app.ui.book.read.page.provider.LineBreaker.Companion.PRE_PANC_CHARS


/**
 * 行断行器（纯 Kotlin，无平台依赖）：排版链路唯一的断行实现。
 *
 * # 断点来源
 *
 * 平台 ICU（UAX#14）断点表与中文禁则表求交集：
 *
 * - **ICU 表**（[TextMeasurer.lineBreakOpportunities]）：全 Unicode 行分隔规则 —— 禁行首
 *   （`，。、」』％‰℃々〞` 等）、禁行尾（`“（【` 等）、英文按词断、数字千分位不拆、行尾空白不占宽。
 *   对应原版 `useZhLayout=false` 走的 `StaticLayout`：AOSP legacy 构造默认
 *   `BREAK_STRATEGY_SIMPLE`（贪心）/ `HYPHENATION_FREQUENCY_NONE` / `LineBreakConfig.NONE`
 *   → 不追加 `-u-lb-*` 关键字 → 落到 ICU 默认行分隔表。
 * - **中文禁则表** [POST_PANC_CHARS] / [PRE_PANC_CHARS]：补 ICU 按通用规则允许、中文版面却不接受的
 *   位置（实测 ICU 允许 `—` `～` `·` `>` 落行首、`<` 落行尾），并补 clreq 6.1.1 basic/GB 法
 *   点名的**分隔号**（`/` `／` 禁行首、禁行尾）。
 *
 * 交集口径下两张表的禁则同时生效，且保留 ICU 的词边界与超出自研表的字符覆盖。平台拿不到 ICU 断点时
 * （[TextMeasurer.lineBreakOpportunities] 返回 null，如单测用的 [SimpleTextMeasurer]）退化为只用
 * 中文禁则表，等价于迁移前的自研断行。
 *
 * # 与标点挤压的关系
 *
 * 本类只负责「在哪些位置可以断」与「装得下就塞」，**不做任何标点宽度调整**：
 * clreq 6.1.1 规定挤压必须先于禁则（「标点挤压处理会影响换行位置」），所以
 * [PunctuationTrimmer.trimAdjacent] 在 [widths] 进本类之前就已把宽度定稿，本类看到的就是最终宽度。
 * 行首 / 行尾挤压（阶段 2）在切行之后由 [PunctuationTrimmer.trimLineEdges] 逐行补做。
 *
 * 唯一的例外是 [cnCharWidth] > 0 时的**行末裁半回馈**（clreq 6.1.1「先挤进，后推出」+
 * 6.2.2.3 第 1 档）：某簇装不下、但它按行末标点裁成半字后装得下，就先按半字收下，
 * 省掉一次「退字 → 行尾留白 → 拉伸」。此处不改宽度表 —— 该簇已是本行末簇，
 * [PunctuationTrimmer.trimLineEdges] 随后会把它写成同一个半宽，两处口径天然一致。
 *
 * # 孤字不成行
 *
 * [fixOrphanLastLine] 落实 clreq 7.1.2：段落末行只剩一个汉字（或一字加标点）时，
 * 比照避头点由前一行取簇下移，前一行交给两端对齐均排。下移量有预算：clreq 6.3.1.2 只把
 * 「一到两字空白」当均排常态，超出就按 6.1.1 注的救济措施局部不处理，免得把前一行拉得过松。
 *
 * # 输出
 *
 * [lineStartCluster] 为各行起始簇下标，长度 [lineCount] + 1（末位 = 总簇数）；
 * 第 `i` 行覆盖簇区间 `[lineStartCluster[i], lineStartCluster[i + 1] - 1]`。
 */
class LineBreaker(
    private val words: List<String>,
    private val widths: List<Float>,
    opportunities: IntArray?,
    indentSize: Int,
    width: Int,
    letterSpacingPx: Float,
    cnCharWidth: Float = 0f,
) {
    private companion object {
        /** 禁行首标点（后置标点）；`/` `／` 为 clreq 6.1.1 basic 档的分隔号。 */
        const val POST_PANC_CHARS = "！，。、；：？”’）］｝》〉〕】〗」』﹂﹄…—～·/／!,.:;?)]}>"

        /** 禁行尾标点（前置标点）；`/` `／` 禁行尾出自 clreq 6.1.1 GB 法。 */
        const val PRE_PANC_CHARS = "“‘（［｛《〈〔【〖『「﹁﹃([{<／/"

        /** clreq 7.1.2：段落末行的「实质字符」下限（孤字不成行）。 */
        const val MIN_SUBSTANTIVE = 2

        /** clreq 6.3.1.2：均排常态是「一到两字空白」，孤字修正下移量以此为预算上限（汉字宽的倍数）。 */
        const val ORPHAN_BUDGET_CHARS = 2

        val postPancBits = LongArray(1024).apply {
            for (i in POST_PANC_CHARS.indices) {
                val c = POST_PANC_CHARS[i].code
                this[c ushr 6] = this[c ushr 6] or (1L shl c)
            }
        }

        val prePancBits = LongArray(1024).apply {
            for (i in PRE_PANC_CHARS.indices) {
                val c = PRE_PANC_CHARS[i].code
                this[c ushr 6] = this[c ushr 6] or (1L shl c)
            }
        }

        fun isPostPanc(cluster: String): Boolean {
            if (cluster.length != 1) return false
            val code = cluster[0].code
            return (postPancBits[code ushr 6] and (1L shl code)) != 0L
        }

        fun isPrePanc(cluster: String): Boolean {
            if (cluster.length != 1) return false
            val code = cluster[0].code
            return (prePancBits[code ushr 6] and (1L shl code)) != 0L
        }

        /** 西文字母 / 阿拉伯数字：连续这些簇算「一个词」，不逐字母计字。 */
        fun isWordChar(cluster: String): Boolean {
            if (cluster.length != 1) return false
            val c = cluster[0]
            return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        }

        const val DEFAULT_CAPACITY = 10
    }

    var lineStartCluster = IntArray(DEFAULT_CAPACITY)
        private set

    var lineCount = 0
        private set

    init {
        val size = words.size
        if (size > 0) {
            // 字距余量算进可用宽：逐字宽度已含 letterSpacing，行末那一份不占版面
            val widthLimit = width + letterSpacingPx
            val breakBefore = buildBreakFlags(opportunities)
            val indent = indentSize.coerceIn(0, size)
            val tailHalf = if (cnCharWidth > 0f) cnCharWidth / 2f else 0f

            var line = 0
            var lineStart = 0
            var lineW = 0f
            // ICU 把断点给在空白之后：行尾空白留在上一行且不占可用宽（与 StaticLayout 一致），
            // 故空白宽度先挂在 trailing 上，等下一个非空白簇到来才计入 lineW。
            var trailing = 0f
            var i = 0
            while (i < size) {
                val cw = widths[i]
                if (isTrailingSpace(words[i])) {
                    trailing += cw
                    i++
                    continue
                }
                if (lineW + trailing + cw > widthLimit && i > lineStart) {
                    // 先挤进（clreq 6.1.1 / 6.2.2.3 第 1 档）：本簇是行末全角结束标点时按半字宽
                    // 试装，装得下就收下，避免「为塞一个标点整行退字」留下大片行尾空白。
                    if (tailHalf > 0f &&
                        PunctuationTrimmer.isLineEndTrimmable(words[i], cw, cnCharWidth) &&
                        lineW + trailing + tailHalf <= widthLimit
                    ) {
                        lineW += trailing + tailHalf
                        trailing = 0f
                        i++
                        continue
                    }
                    // 后推出：回退到最近的允许断点 —— 等价于原版 ZhLayout 的 BREAK_ONE_CHAR（退一个字）
                    // 与 BREAK_MORE_CHAR（退多个字），两种模式合成同一次扫描。
                    // 首行回退下限压在缩进之后：缩进内部虽可断，切出来却是一条只有缩进的空行
                    val floor = if (line == 0 && indent > lineStart) indent else lineStart
                    var brk = i
                    var k = i
                    while (k > floor) {
                        if (breakBefore[k]) {
                            brk = k
                            break
                        }
                        k--
                    }
                    // 整行无允许断点（超长英文单词 / 全程禁断）时 brk 停在 i 就地硬断，
                    // 与 minikin 防裁切兜底同口径：宁可切开也不让文字溢出可视区。
                    ensureCapacity(line + 1)
                    lineStartCluster[line + 1] = brk
                    line++
                    lineStart = brk
                    i = brk
                    lineW = 0f
                    trailing = 0f
                    continue
                }
                lineW += trailing + cw
                trailing = 0f
                i++
            }
            ensureCapacity(line + 1)
            lineStartCluster[line + 1] = size
            lineCount = line + 1
            fixOrphanLastLine(indent, widthLimit, cnCharWidth, breakBefore)
        }
    }

    /**
     * 逐簇的「可在此簇之前断行」标记：ICU 断点表与中文禁则表求交集。
     * 两个序列都按 UTF-16 下标升序，单指针走一遍；落在簇内部（如 emoji 代理对中间）的 ICU 断点丢弃。
     *
     * `flags[0]` 恒 false（在首簇之前断行无意义），`flags[size]` 不参与回退扫描。
     */
    private fun buildBreakFlags(opportunities: IntArray?): BooleanArray {
        val size = words.size
        val flags = BooleanArray(size + 1)
        var op = 0
        var offset = words[0].length
        for (cluster in 1 until size) {
            val zhAllows = !isPostPanc(words[cluster]) && !isPrePanc(words[cluster - 1])
            flags[cluster] = if (opportunities == null) {
                zhAllows
            } else {
                while (op < opportunities.size && opportunities[op] < offset) op++
                zhAllows && op < opportunities.size && opportunities[op] == offset
            }
            offset += words[cluster].length
        }
        return flags
    }

    /**
     * clreq 7.1.2「孤字不成行」：段落末行只剩一个实质字符（汉字，或一串字母数字算一个词）
     * 或「一字 + 标点」时，从前一行末尾逐簇下移到最近的**合法断点**（[breakBefore] 已是
     * ICU ∩ 中文禁则的交集，故下移后不会把 `Windows` 切开、也不会让 `。` 落到行首）。
     *
     * 前一行让出的空白由两端对齐均排吃掉，与 clreq 给的「比照避头点处理，前一行采用均排」一致。
     * 三种情况放弃修正、保持原样（宁可留一个孤字，也不制造更坏的观感）：
     * - 下移量超出 [ORPHAN_BUDGET_CHARS] 字宽预算 → 前一行会被拉得过松（clreq 6.1.1 注的救济措施）
     * - 下移后末行超宽 → 等于制造一条溢出行
     * - 前一行被掏到不足 [MIN_SUBSTANTIVE] → 把孤字问题搬到了前一行
     */
    private fun fixOrphanLastLine(
        indent: Int,
        widthLimit: Float,
        cnCharWidth: Float,
        breakBefore: BooleanArray,
    ) {
        if (lineCount < 2) return
        val boundary = lineCount - 1
        val total = words.size
        val oldStart = lineStartCluster[boundary]
        val prevStart = lineStartCluster[boundary - 1]
        if (substantiveCount(oldStart, total) >= MIN_SUBSTANTIVE) return
        // 首行的下限压在缩进之后，避免把首行掏成一条只有缩进的空行
        val floor = if (boundary == 1) maxOf(prevStart, indent) else prevStart
        // 宽度不可判时（单测直接构造）按簇数兜底，口径同为「至多两字」
        val widthBudget = if (cnCharWidth > 0f) ORPHAN_BUDGET_CHARS * cnCharWidth else Float.MAX_VALUE
        val countBudget = if (cnCharWidth > 0f) Int.MAX_VALUE else ORPHAN_BUDGET_CHARS
        var moved = 0f
        var start = oldStart
        while (start > floor) {
            start--
            moved += widths[start]
            if (moved > widthBudget || oldStart - start > countBudget) return
            if (!breakBefore[start]) continue
            if (lineWidthFrom(start, total, cnCharWidth) > widthLimit) return
            if (substantiveCount(start, total) >= MIN_SUBSTANTIVE &&
                substantiveCount(prevStart, start) >= MIN_SUBSTANTIVE
            ) {
                lineStartCluster[boundary] = start
                return
            }
        }
    }

    /** `[from, until)` 的装行宽度：与装行口径一致，末尾的半角空白不占可用宽。若行尾标点符合 isLineEndTrimmable 则扣减裁半宽度。 */
    private fun lineWidthFrom(from: Int, until: Int, cnCharWidth: Float): Float {
        var end = until
        while (end > from && isTrailingSpace(words[end - 1])) end--
        var sum = 0f
        for (i in from until end) sum += widths[i]
        if (end > from && cnCharWidth > 0f) {
            val lastIdx = end - 1
            if (PunctuationTrimmer.isLineEndTrimmable(
                    words[lastIdx],
                    widths[lastIdx],
                    cnCharWidth
                )
            ) {
                sum -= cnCharWidth / 2f
            }
        }
        return sum
    }

    /**
     * `[from, until)` 内的实质字符数：禁则标点与空白不计，连续的西文字母 / 数字算一个词
     * （否则末行只剩 `Windows` 的尾字母也算不上孤字）。
     */
    private fun substantiveCount(from: Int, until: Int): Int {
        var count = 0
        var inWord = false
        for (i in from until until) {
            val w = words[i]
            if (isWordChar(w)) {
                if (!inWord) count++
                inWord = true
                continue
            }
            inWord = false
            if (!isPostPanc(w) && !isPrePanc(w) && !isTrailingSpace(w) && w != IDEOGRAPHIC_SPACE) {
                count++
            }
        }
        return count
    }

    /** 行尾可忽略的空白：仅半角空格与制表符（全角空格 `　` 是缩进实体，必须占宽）。 */
    private fun isTrailingSpace(cluster: String): Boolean =
        cluster.length == 1 && (cluster[0] == ' ' || cluster[0] == '\t')

    /** 保证 [lineStartCluster] 能写下标 [index]。 */
    private fun ensureCapacity(index: Int) {
        if (lineStartCluster.size <= index) {
            lineStartCluster = lineStartCluster.copyOf(index + DEFAULT_CAPACITY)
        }
    }
}

/** 全角空格：段首缩进实体，不计入实质字符。 */
private const val IDEOGRAPHIC_SPACE = "　"
