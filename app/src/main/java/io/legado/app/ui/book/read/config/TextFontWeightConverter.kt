package io.legado.app.ui.book.read.config

import android.content.Context
import android.util.AttributeSet
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert


class TextFontWeightConverter(context: Context, attrs: AttributeSet?) :
    SegmentSelectTextView(context, attrs) {

    override val segments = context.getString(R.string.font_weight_text)
    override val segmentPositions = mapOf(
        0 to (0 to 1),
        1 to (2 to 3),
        2 to (4 to 5)
    )

    init {
        text = spannableString
        if (!isInEditMode) {
            upUi(ReadBookConfig.textBold)
        }
        setOnClickListener {
            selectType()
        }
    }

    override fun upUi(type: Int) {
        super.upUi(type)
    }

    private fun selectType() {
        context.alert(titleResource = R.string.text_font_weight_converter) {
            items(context.resources.getStringArray(R.array.text_font_weight).toList()) { _, i ->
                ReadBookConfig.textBold = i
                upUi(i)
                onChanged?.invoke()
            }
        }
    }

}
