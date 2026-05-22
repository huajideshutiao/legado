package io.legado.app.ui.book.read.config

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.text.StrokeTextView

abstract class SegmentSelectTextView(context: Context, attrs: AttributeSet?) :
    StrokeTextView(context, attrs) {

    protected abstract val segments: String
    protected abstract val segmentPositions: Map<Int, Pair<Int, Int>>

    protected val spannableString by lazy { SpannableString(segments) }
    protected val enabledSpan: ForegroundColorSpan = ForegroundColorSpan(context.accentColor)
    var onChanged: (() -> Unit)? = null

    fun onChanged(unit: () -> Unit) {
        onChanged = unit
    }

    internal open fun upUi(type: Int) {
        spannableString.removeSpan(enabledSpan)
        segmentPositions[type]?.let { (start, end) ->
            spannableString.setSpan(enabledSpan, start, end, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        text = spannableString
    }
}
