package io.legado.app.ui.book.read.page.provider

import android.os.Build
import android.text.TextPaint
import io.legado.app.utils.getTextWidthsCompat
import java.text.BreakIterator
import java.util.Locale

/**
 * [TextMeasurer] 安卓实现：包一层 TextPaint，热路径零新增分配。
 */
class AndroidTextMeasurer(private val paint: TextPaint) : TextMeasurer {

    /**
     * `java.text.BreakIterator.getLineInstance` 在 Android 上是 `android.icu.text.BreakIterator`
     * 的包装（libcore `ojluni/.../BreakIterator.java:483`），与 `StaticLayout` 走的 minikin
     * `ubrk_open(UBRK_LINE)` 同一份 ICU 行分隔表。直取断点而不新建 `StaticLayout`：
     * 后者会再跑一遗 shaping 与字形度量，而字宽已由本类的字宽表给出。
     * 度量器一 worker 一实例（`TextMeasurerProviders.createOrNull`），故迭代器可直接复用。
     */
    private val lineBreaker: BreakIterator by lazy(LazyThreadSafetyMode.NONE) {
        BreakIterator.getLineInstance(Locale.getDefault())
    }

    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        paint.getTextWidthsCompat(text, widths)
    }

    override fun measureWidth(text: String): Float {
        var width = paint.measureText(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            width += paint.letterSpacing * paint.textSize
        }
        return width
    }

    override fun lineBreakOpportunities(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val bi = lineBreaker
        bi.setText(text)
        // 上限 = 逐字都可断（text.length + 1 个位置），一次分配到位
        val out = IntArray(text.length + 1)
        var n = 0
        var p = bi.first()
        while (p != BreakIterator.DONE) {
            out[n++] = p
            p = bi.next()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    override val letterSpacingPx: Float
        get() = paint.letterSpacing * paint.textSize

    override val textSizePx: Float
        get() = paint.textSize

    override val descent: Float
        get() = paint.fontMetrics.descent

    override val ascent: Float
        get() = paint.fontMetrics.ascent

    override val leading: Float
        get() = paint.fontMetrics.leading
}
