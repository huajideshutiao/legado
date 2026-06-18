package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import androidx.appcompat.content.res.AppCompatResources
import io.legado.app.R
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class IntroButtonSpan(context: Context, private val label: String) : ReplacementSpan() {

    private val background = AppCompatResources.getDrawable(
        context, R.drawable.selector_fillet_btn_bg
    )
    private val textColor = context.getCompatColor(R.color.secondaryText)
    private val paddingHorizontal = 14.dpToPx().toFloat()
    private val paddingVertical = 10.dpToPx().toFloat()

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            val pad = paddingVertical.toInt()
            fm.ascent = pfm.ascent - pad
            fm.top = pfm.top - pad
            fm.descent = pfm.descent + pad
            fm.bottom = pfm.bottom + pad
        }
        return (paint.measureText(label) + paddingHorizontal * 2).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(label) + paddingHorizontal * 2
        val fm = paint.fontMetrics
        val rectTop = (y + fm.ascent - paddingVertical).toInt()
        val rectBottom = (y + fm.descent + paddingVertical).toInt()
        background?.run {
            setBounds(x.toInt(), rectTop, (x + width).toInt(), rectBottom)
            draw(canvas)
        }
        val savedColor = paint.color
        paint.color = textColor
        canvas.drawText(label, x + paddingHorizontal, y.toFloat(), paint)
        paint.color = savedColor
    }
}
