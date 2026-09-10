package io.legado.app.ui.book.read.page.provider

import android.os.Build
import android.text.TextPaint
import io.legado.app.utils.getTextWidthsCompat

/**
 * [TextMeasurer] 安卓实现：包一层 TextPaint，热路径零新增分配。
 */
class AndroidTextMeasurer(private val paint: TextPaint) : TextMeasurer {

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

    override val letterSpacingPx: Float
        get() = paint.letterSpacing * paint.textSize

    override val textSizePx: Float
        get() = paint.textSize

    override val descent: Float
        get() = paint.fontMetrics.descent

    override val ascent: Float
        get() = paint.fontMetrics.ascent
}
