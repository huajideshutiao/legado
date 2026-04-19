package io.legado.app.ui.book.read.config

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.text.StrokeTextView
import io.legado.app.utils.ChineseUtils

class ChineseConverter(context: Context, attrs: AttributeSet?) : StrokeTextView(context, attrs) {

    private val spannableString = SpannableString("简/繁")
    private var enabledSpan: ForegroundColorSpan = ForegroundColorSpan(context.accentColor)
    var onChanged: (() -> Unit)? = null

    init {
        text = spannableString
        if (!isInEditMode) {
            upUi(AppConfig.chineseConverterType)
        }
        setOnClickListener {
            ChineseUtils.showConverterSelector(context) {
                upUi(it)
                onChanged?.invoke()
            }
        }
    }

    private fun upUi(type: Int) {
        spannableString.removeSpan(enabledSpan)
        when (type) {
            1 -> spannableString.setSpan(enabledSpan, 0, 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
            2 -> spannableString.setSpan(enabledSpan, 2, 3, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        text = spannableString
    }

}