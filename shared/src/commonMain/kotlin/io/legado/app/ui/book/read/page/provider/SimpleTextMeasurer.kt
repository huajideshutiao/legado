package io.legado.app.ui.book.read.page.provider

/**
 * 简化版 [TextMeasurer] 实现（commonMain 纯 Kotlin，无 android 依赖）。
 *
 * # 设计目的
 *
 * 桌面 / iOS / 鸿蒙端 [io.legado.app.ui.book.read.ReadBookViewModelShared] 在调用
 * [SimpleChapterLayout] 排版时需要 [TextMeasurer] 提供逐字宽度。app 端用
 * `AndroidTextMeasurer`（基于 `TextPaint.getTextWidthsCompat`）实现，
 * 桌面 JVM 端虽然有 `DesktopTextMeasurer`（基于 Compose `TextMeasurer`），
 * 但它只能在 Composable 上下文里构造（需要 `rememberTextMeasurer()` / `LocalDensity`），
 * 而 `ReadBookViewModelShared.loadChapter` 跑在协程里、脱离 Composable 重组。
 *
 * 因此本类提供 **等宽近似** 实现，仅依赖 [textSizePx] / [letterSpacingPx] 两个标量，
 * 任何平台都能 new 出来直接用，让"读章节 → 排版 → 显示"链路在无 Compose 上下文时
 * 也能跑通。代价是排版精度低于 `AndroidTextMeasurer`（西文 / 标点宽度估算偏差），
 * 后续若需精确可由 actual 平台注入更精确的 [TextMeasurer]。
 *
 * # 等宽口径
 *
 * - 中日韩文字 / 全角符号：宽度 = [textSizePx]（一字宽）
 * - 半角 ASCII / 半角标点 / 半角空格：宽度 = [textSizePx] * [halfWidthRatio]
 * - 控制字符（ZWSP/ZWNJ/ZWJ/WJ）：宽度 = 0
 * - 其他（含代理对 / 组合字符）：按簇首字宽近似
 *
 * [halfWidthRatio] 默认 0.5（西文窄于中文一半的常见近似）。
 *
 * 与 app 端 `TextPaint.getTextWidthsCompat` 的差异：app 端按真实字形宽度度量，
 * 本类按等宽近似——断行位置可能略有偏移，但 `ZhLineBreaker` 避头尾逻辑不受影响。
 */
class SimpleTextMeasurer(
    override val textSizePx: Float,
    override val letterSpacingPx: Float = 0f,
    override val descent: Float = textSizePx * 0.2f,
    private val halfWidthRatio: Float = 0.5f,
) : TextMeasurer {

    /**
     * 逐字素簇宽度写入 [widths]（口径 = `TextPaint.getTextWidthsCompat`，热路径零新增分配）。
     *
     * 实现按 [Char.isCJK] / 零宽控制字符判断给每个 UTF-16 code unit 写入等宽近似值。
     * 代理对 / 组合字符按 code unit 逐个写入，与 `getTextWidthsCompat` 行为对齐
     * （后续 [measureTextSplit] 会按零宽聚合到字素簇）。
     */
    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        val cnW = textSizePx
        val halfW = textSizePx * halfWidthRatio
        val last = minOf(text.length, widths.size) - 1
        for (i in 0..last) {
            val c = text[i]
            widths[i] = when {
                isZeroWidthControl(c) -> 0f
                c.isCJK() -> cnW
                else -> halfW
            }
        }
    }

    /**
     * 整串期望宽度：逐字累加，与 [measureGlyphWidths] 口径一致。
     * 注意 `StaticLayout`/`TextLayoutResult.size.width` 已含 letterSpacing，
     * 这里加 [letterSpacingPx] * charCount 补偿，保持与 app 端
     * `AndroidTextMeasurer.measureWidth` 口径一致。
     */
    override fun measureWidth(text: String): Float {
        val cnW = textSizePx
        val halfW = textSizePx * halfWidthRatio
        var sum = 0f
        for (c in text) {
            sum += when {
                isZeroWidthControl(c) -> 0f
                c.isCJK() -> cnW
                else -> halfW
            }
        }
        // letterSpacing 按 UTF-16 code unit 数累计（与 app 端 API35 补偿口径一致）
        sum += letterSpacingPx * text.length
        return sum
    }

    private fun isZeroWidthControl(c: Char): Boolean {
        // ZWSP(8203) / ZWNJ(8204) / ZWJ(8205) / WJ(8288)
        val code = c.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

    /**
     * 判断字符是否属于中日韩文字 / 全角符号范围（用于等宽近似）。
     *
     * 覆盖范围：
     * - 0x3000..0x303F CJK 符号和标点（含全角空格 `　` U+3000）
     * - 0x3400..0x4DBF CJK 扩展 A
     * - 0x4E00..0x9FFF CJK 统一表意文字（基本汉字）
     * - 0xF900..0xFAFF CJK 兼容表意文字
     * - 0xFF00..0xFFEF 全角形式（含全角字母 / 数字 / 标点）
     * - 0x3040..0x30FF 平假名 / 片假名
     * - 0xAC00..0xD7AF 韩文音节
     */
    private fun Char.isCJK(): Boolean {
        val code = this.code
        return code in 0x3000..0x303F ||
                code in 0x3400..0x4DBF ||
                code in 0x4E00..0x9FFF ||
                code in 0xF900..0xFAFF ||
                code in 0xFF00..0xFFEF ||
                code in 0x3040..0x30FF ||
                code in 0xAC00..0xD7AF
    }
}
