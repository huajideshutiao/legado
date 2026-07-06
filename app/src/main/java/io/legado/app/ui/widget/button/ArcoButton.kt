package io.legado.app.ui.widget.button

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.StateSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * Arco 按钮：让 Primary / Outline / Secondary 三种 style 自动跟随 ThemeStore.accentColor。
 * XML 里换成本类 + 原有 style 即可，不需要业务代码逐个 tint。
 */
class ArcoButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        val styleRes = attrs?.styleAttribute ?: 0
        if (styleRes != 0) {
            val ta = context.obtainStyledAttributes(
                styleRes, intArrayOf(android.R.attr.background)
            )
            val bgResId = ta.getResourceId(0, 0)
            ta.recycle()
            when (bgResId) {
                R.drawable.bg_arco_btn_primary -> tintPrimary()
                R.drawable.bg_arco_btn_outline -> tintOutline()
                R.drawable.bg_arco_btn_secondary -> tintSecondary()
                R.drawable.bg_arco_btn_danger -> tintDanger()
            }
        }
    }

    private val arcoRadius get() = context.resources.getDimension(R.dimen.arco_radius_default)
    private val arcoStrokeWidth get() = 1.dpToPx()

    private fun tintPrimary() = tintSolid(context.accentColor)

    private fun tintDanger() = tintSolid(ContextCompat.getColor(context, R.color.arco_danger))

    /**
     * 实心按钮通用 tint: 主色背景 + 白字, 支持 pressed(加深) / disabled(半透明) 状态。
     * Primary 和 Danger 共用此逻辑, 仅基础色不同。
     */
    private fun tintSolid(baseColor: Int) {
        val pressed = ColorUtils.darkenColor(baseColor)
        val disabled = ColorUtils.withAlpha(baseColor, 0.4f)
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), arcoSolid(disabled))
            addState(intArrayOf(android.R.attr.state_pressed), arcoSolid(pressed))
            addState(StateSet.WILD_CARD, arcoSolid(baseColor))
        }
        setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    private fun tintOutline() {
        val accent = context.accentColor
        val pressed = ColorUtils.darkenColor(accent)
        val disabledStroke = ContextCompat.getColor(context, R.color.arco_fill_3)
        val disabledText = ContextCompat.getColor(context, R.color.arco_text_4)
        val pressedFill = ContextCompat.getColor(context, R.color.arco_fill_2)
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), arcoStroke(0, disabledStroke))
            addState(intArrayOf(android.R.attr.state_pressed), arcoStroke(pressedFill, pressed))
            addState(StateSet.WILD_CARD, arcoStroke(0, accent))
        }
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_pressed),
                    StateSet.WILD_CARD,
                ), intArrayOf(disabledText, pressed, accent)
            )
        )
    }

    private fun tintSecondary() {
        val fill2 = ContextCompat.getColor(context, R.color.arco_fill_2)
        val fill3 = ContextCompat.getColor(context, R.color.arco_fill_3)
        val fill1 = ContextCompat.getColor(context, R.color.arco_fill_1)
        val text1 = ContextCompat.getColor(context, R.color.arco_text_1)
        val text4 = ContextCompat.getColor(context, R.color.arco_text_4)
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), arcoSolid(fill1))
            addState(intArrayOf(android.R.attr.state_pressed), arcoSolid(fill3))
            addState(StateSet.WILD_CARD, arcoSolid(fill2))
        }
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    StateSet.WILD_CARD,
                ), intArrayOf(text4, text1)
            )
        )
    }

    private fun arcoSolid(color: Int) = GradientDrawable().apply {
        cornerRadius = arcoRadius
        setColor(color)
    }

    private fun arcoStroke(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = arcoRadius
        setColor(fill)
        setStroke(arcoStrokeWidth, stroke)
    }
}
