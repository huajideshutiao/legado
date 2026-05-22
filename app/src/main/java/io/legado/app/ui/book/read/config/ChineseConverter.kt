package io.legado.app.ui.book.read.config

import android.content.Context
import android.util.AttributeSet
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils

class ChineseConverter(context: Context, attrs: AttributeSet?) :
    SegmentSelectTextView(context, attrs) {

    override val segments = "简/繁"
    override val segmentPositions = mapOf(
        1 to (0 to 1),
        2 to (2 to 3)
    )

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

}
