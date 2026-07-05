package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.ImageViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.applyTint

/**
 * 自动主题拦截器：在 View 填充时自动应用 ThemeStore 中的颜色。
 * 解决“苦哈哈给所有控件上色”的问题。
 */
object ThemeInterceptor {

    private val themeAttributes = intArrayOf(
        android.R.attr.textColor,
        android.R.attr.background,
        android.R.attr.tint,
        androidx.appcompat.R.attr.backgroundTint,
        com.google.android.material.R.attr.strokeColor
    )

    fun apply(view: View, attrs: AttributeSet) {
        val context = view.context
        val typedArray = context.obtainStyledAttributes(attrs, themeAttributes)

        for (i in 0 until typedArray.indexCount) {
            val attrIndex = typedArray.getIndex(i)
            val resId = typedArray.getResourceId(attrIndex, -1)
            val attr = themeAttributes[attrIndex]

            if (resId == R.color.accent || resId == R.color.primary) {
                val targetColor = if (resId == R.color.accent) {
                    context.accentColor
                } else {
                    context.primaryColor
                }

                when (attr) {
                    android.R.attr.textColor -> if (view is TextView) {
                        view.setTextColor(targetColor)
                    }

                    android.R.attr.background -> {
                        view.setBackgroundColor(targetColor)
                    }

                    android.R.attr.tint -> {
                        if (view is ImageView) {
                            ImageViewCompat.setImageTintList(
                                view,
                                ColorStateList.valueOf(targetColor)
                            )
                        } else {
                            view.applyTint(targetColor)
                        }
                    }

                    androidx.appcompat.R.attr.backgroundTint -> {
                        view.backgroundTintList = ColorStateList.valueOf(targetColor)
                    }

                    com.google.android.material.R.attr.strokeColor -> if (view is MaterialButton) {
                        view.strokeColor = ColorStateList.valueOf(targetColor)
                    }
                }
            }
        }
        typedArray.recycle()

        // 特殊组件处理
        val isDark = AppConfig.isNightTheme
        when (view) {
            is EditText -> {
                TintHelper.setTintAuto(view, context.accentColor, false, isDark)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    view.isLocalePreferredLineHeightForMinimumUsed = false
                }
                view.setLinkTextColor(context.accentColor)
            }

            is CheckBox, is RadioButton, is Switch, is SwitchCompat -> {
                TintHelper.setTintAuto(view, context.accentColor, false, isDark)
            }

            is ProgressBar -> {
                TintHelper.setTintAuto(view, context.accentColor, false, isDark)
            }

            is SwipeRefreshLayout -> {
                view.setColorSchemeColors(context.accentColor)
            }

            is TextView -> {
                view.setLinkTextColor(context.accentColor)
            }
        }
    }
}
