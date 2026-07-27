package io.legado.app.ui.book.read.page.provider

import androidx.compose.ui.text.TextMeasurer as ComposeTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
// 注：TextUnit.toPx() 是 Density 的成员扩展，with(density){} 内可直接调用，无需单独 import

/**
 * [TextMeasurer] 桌面 JVM 实现：用 Compose Multiplatform 的 [ComposeTextMeasurer] /
 * [androidx.compose.ui.text.TextLayoutResult] 替代 app 端 `TextPaint` 测量原语。
 *
 * 与 app 端 [AndroidTextMeasurer] 一一对应：
 * - [measureGlyphWidths] 对应 `TextPaint.getTextWidthsCompat(text, widths)`，
 *   通过 `textMeasurer.measure(text, style).getBoundingBox(i).width` 取得每个 UTF-16 code unit
 *   的宽度并写入复用数组（热路径零新增分配，与接口契约一致）。
 * - [measureWidth] 对应 `TextPaint.measureText(text) + letterSpacing*textSize`，
 *   Compose 版 `TextLayoutResult.size.width` 已包含 letterSpacing，无需再加。
 * - [letterSpacingPx] / [textSizePx] / [descent] 直接从 [textStyle] + [density] 推算，
 *   消除对 `android.text.TextPaint` / `android.graphics.Paint.FontMetrics` 的依赖。
 *
 * 构造参数与 [AndroidTextMeasurer] 的 `paint` 同生命周期：随样式变化重建
 * [io.legado.app.ui.book.read.page.provider.TextChapterLayout]，可长期复用。
 *
 * 注：[ComposeTextMeasurer] 用 import alias 与本包同名接口 [TextMeasurer] 区分，
 * 避免编译期歧义。
 */
class DesktopTextMeasurer(
    private val textMeasurer: ComposeTextMeasurer,
    private val textStyle: TextStyle,
    private val density: Density,
) : TextMeasurer {

    /**
     * textSize（px）：消除 `android.text.TextPaint.textSize` 直接访问，
     * 统一从测量面取值。fontSize 以 sp 给出，用 [density] 转 px。
     */
    override val textSizePx: Float
        get() = with(density) { textStyle.fontSize.toPx() }

    /**
     * letterSpacing*textSize（px），断行宽度余量用。
     * Compose 中 `TextStyle.letterSpacing` 以 sp 为单位，用 [density] 转 px 后即等价于
     * `letterSpacing.value * textSize`（app 端 [AndroidTextMeasurer.letterSpacingPx] 口径）。
     */
    override val letterSpacingPx: Float
        get() = with(density) { textStyle.letterSpacing.toPx() }

    /**
     * descent（px）：消除 `android.graphics.Paint.FontMetrics` 类型依赖，仅取下行度量 1 字段。
     * 用一次 measure 拿 `TextLayoutResult`，取首行 baseline → bottom 的差作为 descent。
     */
    override val descent: Float
        get() {
            val layout = textMeasurer.measure("水", textStyle)
            return layout.getLineBottom(0) - layout.getLineBaseline(0)
        }

    /**
     * 逐字素簇宽度写入 [widths]（口径 = TextPaint.getTextWidthsCompat，
     * 含 API35 letterSpacing 首末补偿）。
     * 热路径：[widths] 由调用方复用，实现不得新分配。
     *
     * 实现走 `textMeasurer.measure(text, style).getBoundingBox(i).width`，
     * i 为 char index（UTF-16 code unit 偏移，与 `getTextWidthsCompat` 一致）。
     */
    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        val layout = textMeasurer.measure(text, textStyle)
        val last = text.length - 1
        for (i in 0..last) {
            // getBoundingBox 接收 code unit 偏移；emoji/代理对情形下后续 code unit 返回同宽。
            // 与 app 端 TextPaint.getTextWidthsCompat 的 code unit 口径对齐。
            widths[i] = layout.getBoundingBox(i).width
        }
    }

    /**
     * 整串期望宽度（口径 = measureText + API35 letterSpacing*textSize 补偿）。
     * Compose 版 `TextLayoutResult.size.width` 已含 letterSpacing，无需额外补偿。
     */
    override fun measureWidth(text: String): Float {
        val layout = textMeasurer.measure(text, textStyle)
        // TextLayoutResult.size 是 IntSize，width 为 Int，需转 Float 与接口契约对齐
        return layout.size.width.toFloat()
    }
}
