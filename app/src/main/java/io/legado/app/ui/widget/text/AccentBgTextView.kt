package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.withStyledAttributes
import io.legado.app.R
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.radius
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class AccentBgTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private var radius = context.radius.default

    init {
        context.withStyledAttributes(attrs, R.styleable.AccentBgTextView) {
            radius = getDimensionPixelOffset(R.styleable.AccentBgTextView_arcoRadius, radius)
        }
        upBackground()
    }

    fun setRadius(radius: Int) {
        this.radius = radius.dpToPx()
        upBackground()
    }

    private fun upBackground() {
        val accentColor = if (isInEditMode) {
            context.getCompatColor(R.color.accent)
        } else {
            ThemeStore.accentColor
        }
        // accent 底圆角，按压加深(原 Selector.shapeBuild 语义)
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = this@AccentBgTextView.radius.toFloat()
            setColor(color)
        }
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                shape(ColorUtils.darkenColor(accentColor))
            )
            addState(intArrayOf(), shape(accentColor))
        }
        setTextColor(
            if (ColorUtils.isColorLight(accentColor)) {
                Color.BLACK
            } else {
                Color.WHITE
            }
        )
    }
}
