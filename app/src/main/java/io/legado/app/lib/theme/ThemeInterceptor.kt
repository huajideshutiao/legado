package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setEdgeEffectColor

/**
 * 自动主题拦截器：在 View 填充时自动应用 ThemeStore 中的颜色。
 * 解决“苦哈哈给所有控件上色”的问题。
 */
object ThemeInterceptor {

    fun apply(view: View, attrs: AttributeSet) {
        runCatching {
            val context = view.context
            val accentColor = context.accentColor
            val isDark = AppConfig.isNightTheme
            when (view) {
                is TextInputLayout -> {
                    setTint(view, accentColor, isDark)
                }

                is EditText -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        view.isLocalePreferredLineHeightForMinimumUsed = false
                    }
                    view.setLinkTextColor(accentColor)
                    view.setHighlightColor(ColorUtils.adjustAlpha(accentColor, 0.4f))
                }

                is CheckBox, is RadioButton, is Switch, is SwitchCompat -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                }

                is BaseProgressIndicator<*> -> {
                    view.setIndicatorColor(accentColor)
                }

                is ProgressBar -> {
                    TintHelper.setTintAuto(view, accentColor, false, isDark)
                }

                is SwipeRefreshLayout -> {
                    view.setColorSchemeColors(accentColor)
                }

                is RecyclerView -> {
                    view.setEdgeEffectColor(accentColor)
                }

                is TextView -> {
                    view.setLinkTextColor(accentColor)
                    view.setHighlightColor(ColorUtils.adjustAlpha(accentColor, 0.4f))
                }
            }
        }
    }

    /**
     * 为 TextInputLayout 动态应用主题色。
     *
     * 调研依据（Material TextInputLayout 源码行为）：
     * 1. boxStrokeColor/hintTextColor/defaultHintTextColor 需在 inflate 后设置，
     *    构造函数阶段 boxBackground 可能尚未初始化，applyBoxAttributes 难以立即生效。
     * 2. addView 会触发 onEditTextChanged → updateInputTextColors →
     *    setEditTextBackgroundTintList，用默认 editTextBackgroundTintList
     *    （基于 colorControlActivated 静态值）覆盖 EditText 的 supportBackgroundTintList，
     *    导致底线颜色不跟随动态主题色。
     *
     * 解决方案：通过 addOnEditTextAttachedListener 在 EditText 添加后重新应用 tint。
     * 若 EditText 已添加，监听器会立即触发（Material 官方文档保证）。
     */
    private fun setTint(layout: TextInputLayout, color: Int, isDark: Boolean) {
        layout.boxStrokeColor = color
        val csl = ColorStateList.valueOf(color)
        layout.defaultHintTextColor = csl
        layout.hintTextColor = csl
        // 在 EditText 添加后重新应用 backgroundTintList 和光标颜色
        layout.addOnEditTextAttachedListener { textInputLayout ->
            textInputLayout.editText?.let { editText ->
                if (editText is AppCompatEditText) {
                    TintHelper.setTint(editText, color, isDark)
                }
            }
        }
    }
}
