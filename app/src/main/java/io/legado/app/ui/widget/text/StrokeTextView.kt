package io.legado.app.ui.widget.text

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.withStyledAttributes
import io.legado.app.R
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

@Suppress("unused")
open class StrokeTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs) {

    private var radius = 1.dpToPx()
    private val isBottomBackground: Boolean

    init {
        // isBottomBackground 是 val,不能在 withStyledAttributes 的 lambda 内直接赋值,
        // 用局部变量 bottomBg 在 lambda 内接收,lambda 外再赋值给 val
        var bottomBg = false
        context.withStyledAttributes(attrs, R.styleable.StrokeTextView) {
            radius = getDimensionPixelOffset(R.styleable.StrokeTextView_radius, radius)
            bottomBg =
                getBoolean(R.styleable.StrokeTextView_isBottomBackground, false)
        }
        isBottomBackground = bottomBg
        upBackground()
    }

    fun setRadius(radius: Int) {
        this.radius = radius.dpToPx()
        upBackground()
    }

    private fun upBackground() {
        when {
            isInEditMode -> {
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(context.getCompatColor(R.color.secondaryText))
                    .setSelectedStrokeColor(context.getCompatColor(R.color.accent))
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.getCompatColor(R.color.secondaryText))
                        .setSelectedColor(context.getCompatColor(R.color.accent))
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
            isBottomBackground -> {
                val isLight = ColorUtils.isColorLight(context.bottomBackground)
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(context.getPrimaryTextColor(isLight))
                    .setSelectedStrokeColor(context.accentColor)
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.getPrimaryTextColor(isLight))
                        .setSelectedColor(context.accentColor)
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
            else -> {
                background = Selector.shapeBuild()
                    .setCornerRadius(radius)
                    .setStrokeWidth(1.dpToPx())
                    .setDisabledStrokeColor(context.getCompatColor(R.color.md_grey_500))
                    .setDefaultStrokeColor(context.secondaryTextColor)
                    .setSelectedStrokeColor(ThemeStore.accentColor)
                    .setPressedBgColor(context.getCompatColor(R.color.transparent30))
                    .create()
                setTextColor(
                    Selector.colorBuild()
                        .setDefaultColor(context.secondaryTextColor)
                        .setSelectedColor(ThemeStore.accentColor)
                        .setDisabledColor(context.getCompatColor(R.color.md_grey_500))
                        .create()
                )
            }
        }
    }
}
