package io.legado.app.ui.book.read.page.provider

import kotlin.math.abs

/**
 * 一次挤压的产出：改写后的逐簇字宽 + 逐簇绘制偏移。
 *
 * [drawOffsets] 为 null 表示本段（本行）没有任何字形需要平移，调用方不必携带这份数组，
 * 省下一整段正文的 float 内存；非 null 时长度与 [widths] 相同。
 *
 * @param widths 逐簇字宽（px），未挤压的簇就是原宽
 * @param drawOffsets 逐簇绘制 X 偏移（px）：只有裁左半的簇为负，其余为 0
 */
class TrimResult(
    val widths: List<Float>,
    val drawOffsets: List<Float>?,
)

/**
 * 中文标点挤压（W3C clreq 6.3.2 + CSS Text 4 §8.5.1 Fullwidth Punctuation Collapsing
 * + GB/T 15834—2011 §5.1.10）。纯算术，只读字宽与字符类别，不触发任何平台度量。
 *
 * # 两个阶段
 *
 * - [trimAdjacent]（阶段 0，**必须先于断行**）：句中相邻全角标点互相靠拢。
 *   clreq 6.1.1 注：「在处理禁则之前，应优先按照排版风格处理标点符号的宽度调整，
 *   因为标点挤压处理会影响换行位置」——所以它排在 [LineBreaker] 之前，宽度在此定稿。
 * - [trimLineEdges]（阶段 2，切行之后逐行）：行尾结束标点裁右半（GB/T 15834 用「应」，
 *   无条件执行）、行首开始夹注裁左半（clreq 用「可以」，随阶段开关）。
 *
 * 两阶段合起来就是 clreq 6.1.1 的「先挤进」：把标点占的空白先收掉，装不下的字才轮到
 * [LineBreaker] 回退下移（「后推出」）与两端对齐拉伸。负字距与 `exceed()` 平移因此退回真正的兜底。
 *
 * # 判定表（CSS Text 4 §8.5.1，逐条对应）
 *
 * - opening 裁左半 ⟸ 前一字符 ∈ {fullwidth opening, fullwidth middle dot, U+3000, fullwidth closing}
 *   （规范另有「larger font-size 的 closing」一档，本项目正文单一字号，不实现）
 * - closing 裁右半 ⟸ 后一字符 ∈ {fullwidth closing, fullwidth middle dot, U+3000}
 *   （规范另有「larger font-size 的 opening」一档，同上）
 *
 * `：“` 走这张表：`：`(closing) 后一字是 opening，而规范要求那个 opening 字号更大才触发
 * → 不裁；`“`(opening) 前一字是 closing → 裁左半。合计 1.5 字宽，
 * 正落在 clreq 6.3.2.2「任意两个相邻标点应当缩减成 1.5 个字宽」。
 *
 * # 不可裁的判据
 *
 * CSS §8.5.1：字体若为全角标点用了比例字形，「the UA must not add or remove space to these glyphs」。
 * 本项目按 `advance == 汉字基准宽` 判定（汉字基准宽取 `measureWidth("我")`，规范建议的 `水 U+6C34`
 * 同口径）。实测微软雅黑下 `…`(22.8) `·`(6.7) `—`(30.2) 都不等于汉字宽，被这条判据自动排除，
 * 正好覆盖 clreq 6.3.2.1 点名的「半字连接号 / 间隔号 / 分隔号 / 破折号 / 省略号不可调整」。
 *
 * 已裁过一半的簇宽度不再等于汉字宽，因此同一簇不会被两阶段重复挤压。
 *
 * # 与 [LineBreaker] 禁则表的关系
 *
 * 下面的类别表与禁则表（`POST_PANC_CHARS` / `PRE_PANC_CHARS`）**刻意不等值**：禁则表还含半角标点
 * （`!,.:;?)]}>` 等）与 `…—～·`，它们不参与挤压；挤压表只收 CSS 认定的 fullwidth 标点。
 */
object PunctuationTrimmer {

    private const val CAT_NONE = 0
    private const val CAT_OPENING = 1
    private const val CAT_CLOSING = 2

    /**
     * fullwidth 开始夹注标点（CSS §8.5.2：Unicode `Ps` ∩ CJK Symbols/Fullwidth，外加 U+2018 `‘`
     * 与 U+201C `“`）。裁左半，字形需跟着左移。
     */
    private const val FW_OPENING = "“‘（［｛《〈〔【〖『「﹁﹃"

    /**
     * fullwidth 结束夹注标点（Unicode `Pe` 同上，外加 U+2019 `’` / U+201D `”`）。
     * 简体中文横排约定把 colon 类（`：；`）与 dot 类（`。，、`）也归到 closing（裁右半）；
     * `？！` 同理——clreq 6.3.2.1 只把**港台式**问号感叹号列为不可调整。
     */
    private const val FW_CLOSING = "”’）］｝》〉〕】〗」』﹂﹄，。、；：？！"

    /**
     * 只当触发位、自身永不裁的字符：fullwidth middle dot（U+00B7 `·` / U+2027 `‧` / U+30FB `・`）
     * 与 ideographic space（U+3000 `　`）。前者按 GB/T 15834 固定半字宽，后者是段首缩进实体。
     */
    private const val FW_DOT_OR_SPACE = "·‧・　"

    private val openingBits = bitsOf(FW_OPENING)
    private val closingBits = bitsOf(FW_CLOSING)
    private val dotOrSpaceBits = bitsOf(FW_DOT_OR_SPACE)

    /** 码位 → 位表（手法同 [LineBreaker]：`LongArray(1024)` 覆盖 BMP 全码位，无装箱）。 */
    private fun bitsOf(chars: String): LongArray = LongArray(1024).apply {
        for (i in chars.indices) {
            val c = chars[i].code
            this[c ushr 6] = this[c ushr 6] or (1L shl c)
        }
    }

    private fun LongArray.test(code: Int): Boolean = (this[code ushr 6] and (1L shl code)) != 0L

    /** 代理对 / 组合记号聚成的多字簇一律不分类（表内字符全是 BMP 单字）。 */
    private fun category(cluster: String): Int {
        if (cluster.length != 1) return CAT_NONE
        val code = cluster[0].code
        return when {
            openingBits.test(code) -> CAT_OPENING
            closingBits.test(code) -> CAT_CLOSING
            else -> CAT_NONE
        }
    }

    /** 触发 opening 裁左的邻居类别：开始夹注 / 结束夹注 / 间隔号 / 全角空格。 */
    private fun triggersOpening(cluster: String): Boolean {
        if (cluster.length != 1) return false
        val code = cluster[0].code
        return openingBits.test(code) || closingBits.test(code) || dotOrSpaceBits.test(code)
    }

    /** 触发 closing 裁右的邻居类别：结束夹注 / 间隔号 / 全角空格（不含开始夹注）。 */
    private fun triggersClosing(cluster: String): Boolean {
        if (cluster.length != 1) return false
        val code = cluster[0].code
        return closingBits.test(code) || dotOrSpaceBits.test(code)
    }

    /** 比例字形判据：advance 等于汉字基准宽（±0.5px 容差吸收字距补偿的零头）。 */
    private fun isFullWidth(width: Float, cnCharWidth: Float): Boolean =
        abs(width - cnCharWidth) <= 0.5f

    /**
     * 「本簇作为行尾时可否裁右半」—— [trimLineEdges] 的行尾判据同一口径，
     * 供 [LineBreaker] 在装行时回馈使用（clreq 6.1.1「先挤进」：先把行末标点压成半字，
     * 再检讨那个标点是否还有机会挤进上一行）。
     */
    fun isLineEndTrimmable(cluster: String, width: Float, cnCharWidth: Float): Boolean =
        cnCharWidth > 0f && category(cluster) == CAT_CLOSING && isFullWidth(width, cnCharWidth)

    /**
     * 阶段 0：整段扫描，相邻 fullwidth 标点互相靠拢。
     *
     * 没有任何一对满足条件时原样返回入参宽度、偏移为 null（不新分配）。
     *
     * @param words 逐字素簇字符串
     * @param widths 逐字素簇宽度（px，含字距补偿）
     * @param cnCharWidth 汉字基准宽（px），<=0 时视为不可判，整段不挤
     */
    fun trimAdjacent(
        words: List<String>,
        widths: List<Float>,
        cnCharWidth: Float,
    ): TrimResult {
        val size = words.size
        if (size < 2 || cnCharWidth <= 0f) return TrimResult(widths, null)
        val half = cnCharWidth / 2f
        val last = size - 1
        var out: FloatArray? = null
        var offsets: FloatArray? = null
        for (i in 0 until size) {
            if (!isFullWidth(widths[i], cnCharWidth)) continue
            when (category(words[i])) {
                CAT_OPENING -> if (i > 0 && triggersOpening(words[i - 1])) {
                    out = writeWidth(out, widths, i, half)
                    offsets = writeOffset(offsets, size, i, -half)
                }

                CAT_CLOSING -> if (i < last && triggersClosing(words[i + 1])) {
                    out = writeWidth(out, widths, i, half)
                }
            }
        }
        val trimmed = out ?: return TrimResult(widths, null)
        return TrimResult(trimmed.asList(), offsets?.asList())
    }

    /**
     * 阶段 2：逐行处理行尾 / 行首。
     *
     * - 行尾：末簇是 fullwidth closing 且未被阶段 0 裁过 → 裁右半（字形不动，只收掉末侧空白，
     *   腾出的宽度由两端对齐拉伸吃掉，标点墨迹贴到右边线）。
     * - 行首：首簇是 fullwidth opening 且未被裁过 → 裁左半 + 字形左移半字（标点悬出左边线，
     *   正文起点前移半字）。段首缩进后的开始夹注符号已由阶段 0 的 `　` 触发位处理。
     *
     * @param drawOffsets 阶段 0 已算出的本行逐簇偏移（可为 null），本函数不改它，只在需要时复制
     * @return null 表示本行两处都不满足，调用方沿用原宽
     */
    fun trimLineEdges(
        words: List<String>,
        widths: List<Float>,
        drawOffsets: List<Float>?,
        cnCharWidth: Float,
    ): TrimResult? {
        val size = words.size
        if (size == 0 || cnCharWidth <= 0f) return null
        val half = cnCharWidth / 2f
        val headTrim = category(words[0]) == CAT_OPENING && isFullWidth(widths[0], cnCharWidth)
        // 单簇行不裁行尾：裁了既不省空间也不改变观感，只会让 desiredWidth 少半字
        val tailTrim = size > 1 && category(words[size - 1]) == CAT_CLOSING &&
            isFullWidth(widths[size - 1], cnCharWidth)
        if (!headTrim && !tailTrim) return null

        // 上面已保证至少裁一处，直接复制一份本行宽度
        val out = FloatArray(size) { widths[it] }
        if (headTrim) out[0] = half
        if (tailTrim) out[size - 1] = half
        val offsets: List<Float>? = if (headTrim) {
            FloatArray(size) { drawOffsets?.get(it) ?: 0f }
                .apply { this[0] = -half }
                .asList()
        } else {
            drawOffsets
        }
        return TrimResult(out.asList(), offsets)
    }

    /** 首次改写时按 [src] 复制一份可写宽度表，之后原地改下标 [index]。 */
    private fun writeWidth(dst: FloatArray?, src: List<Float>, index: Int, value: Float): FloatArray {
        val out = dst ?: FloatArray(src.size) { src[it] }
        out[index] = value
        return out
    }

    /** 首次需要平移时建整行零值偏移表，之后原地写。 */
    private fun writeOffset(dst: FloatArray?, size: Int, index: Int, value: Float): FloatArray {
        val out = dst ?: FloatArray(size)
        out[index] = value
        return out
    }
}
