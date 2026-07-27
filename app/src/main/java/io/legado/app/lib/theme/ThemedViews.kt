package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.setEdgeEffectColor

/**
 * 残留 View 岛的显式主题着色（accent 取自 ThemeStore）。
 *
 * Factory2 注入链已删除：inflate 不再被拦截，需要动态色的 View 在构造点显式调用
 * [View.applyTheme]（单个）或 [View.applyThemeTree]（整树，如 alert customView 的
 * ViewBinding 根、RecyclerView item）。着色幂等，重复调用无副作用。
 */

fun View.applyTheme() {
    val accentColor = context.accentColor
    val isDark = AppConfig.isNightTheme
    when (this) {
        is EditText -> {
            // ViewCompat: AppCompatEditText 走 supportBackgroundTintList，普通 EditText 走框架 tint
            ViewCompat.setBackgroundTintList(this, editTextTintList(accentColor, isDark))
            setCursorTint(this, accentColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                isLocalePreferredLineHeightForMinimumUsed = false
            }
            setLinkTextColor(accentColor)
            highlightColor = ColorUtils.adjustAlpha(accentColor, 0.4f)
        }

        is CheckBox -> buttonTintList = checkableTintList(accentColor, isDark)

        is RadioButton -> buttonTintList = checkableTintList(accentColor, isDark)

        is SwitchCompat -> {
            // 直用 trackTintList/thumbTintList 由视图层着色，避免 DrawableCompat.wrap 在新 Android 上失效回落静态色
            trackTintList = switchTintList(accentColor, isDark, thumb = false)
            thumbTintList = switchTintList(accentColor, isDark, thumb = true)
        }

        is SeekBar -> {
            val sl = ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_enabled)
                ), intArrayOf(
                    ContextCompat.getColor(
                        context,
                        if (isDark) R.color.ate_control_disabled_dark else R.color.ate_control_disabled_light
                    ),
                    accentColor
                )
            )
            thumbTintList = sl
            progressTintList = sl
        }

        is BaseProgressIndicator<*> -> setIndicatorColor(accentColor)

        is ProgressBar -> {
            val sl = ColorStateList.valueOf(accentColor)
            progressTintList = sl
            secondaryProgressTintList = sl
            indeterminateTintList = sl
        }

        is RecyclerView -> setEdgeEffectColor(accentColor)

        is TextView -> {
            setLinkTextColor(accentColor)
            highlightColor = ColorUtils.adjustAlpha(accentColor, 0.4f)
        }

        // Material 1.14.0: TextInputLayout 无 editTextBackgroundTintList public setter,
        // 必须使用 setBoxStrokeColorStateList(ColorStateList) 与 setHintTextColor(ColorStateList)
        // 否则聚焦态的底线/hint 颜色会回落到主题 colorAccent(静态 #165DFF),无法跟随 ThemeStore.accentColor。
        is TextInputLayout -> {
            val csl = editTextTintList(accentColor, isDark)
            setBoxStrokeColorStateList(csl)
            setHintTextColor(csl)
            // BOX_BACKGROUND_NONE 模式下 boxBackground 为 null, setBoxStrokeColorStateList 无视觉效果;
            // 底部线由 EditText 自身背景 abc_edit_text_material 绘制, focused 状态默认回退到
            // ?attr/colorControlActivated = colorAccent = #165DFF(静态蓝)。
            // 必须显式给 editText 设置 backgroundTint, 才能让聚焦底线跟随 ThemeStore.accentColor。
            // 与下方 is EditText 分支幂等叠加, 无副作用。
            editText?.let { ViewCompat.setBackgroundTintList(it, csl) }
        }
    }
}

/** 对 View 树整树着色（迭代而非递归，防深层级 StackOverflow）。 */
fun View.applyThemeTree() {
    applyTheme()
    if (this is ViewGroup) {
        val stack = ArrayDeque<ViewGroup>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val group = stack.removeLast()
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                child.applyTheme()
                if (child is ViewGroup) {
                    stack.addLast(child)
                }
            }
        }
    }
}

/** CheckBox/RadioButton：disabled / 未选中(control normal) / 选中(accent) */
private fun View.checkableTintList(color: Int, isDark: Boolean): ColorStateList {
    return ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
        ), intArrayOf(
            ContextCompat.getColor(
                context,
                if (isDark) R.color.ate_control_disabled_dark else R.color.ate_control_disabled_light
            ),
            ContextCompat.getColor(
                context,
                if (isDark) R.color.ate_control_normal_dark else R.color.ate_control_normal_light
            ),
            color
        )
    )
}

/** EditText 底线：disabled / 未聚焦(control normal) / 聚焦(accent) */
private fun View.editTextTintList(color: Int, isDark: Boolean): ColorStateList {
    return ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(
                android.R.attr.state_enabled,
                -android.R.attr.state_pressed,
                -android.R.attr.state_focused
            ),
            intArrayOf()
        ),
        intArrayOf(
            ContextCompat.getColor(
                context,
                if (isDark) R.color.ate_text_disabled_dark else R.color.ate_text_disabled_light
            ),
            ContextCompat.getColor(
                context,
                if (isDark) R.color.ate_control_normal_dark else R.color.ate_control_normal_light
            ),
            color
        )
    )
}

/** SwitchCompat thumb/track tint：勾选=accent(track 半透明)，未勾选=ate 灰阶 */
private fun View.switchTintList(
    color: Int,
    isDark: Boolean,
    thumb: Boolean
): ColorStateList {
    var tint = if (isDark) ColorUtils.shiftColor(color, 1.1f) else color
    tint = ColorUtils.adjustAlpha(tint, if (thumb) 1.0f else 0.5f)
    val disabled: Int
    val normal: Int
    if (thumb) {
        disabled = ContextCompat.getColor(
            context,
            if (isDark) R.color.ate_switch_thumb_disabled_dark else R.color.ate_switch_thumb_disabled_light
        )
        normal = ContextCompat.getColor(
            context,
            if (isDark) R.color.ate_switch_thumb_normal_dark else R.color.ate_switch_thumb_normal_light
        )
    } else {
        disabled = ContextCompat.getColor(
            context,
            if (isDark) R.color.ate_switch_track_disabled_dark else R.color.ate_switch_track_disabled_light
        )
        normal = ContextCompat.getColor(
            context,
            if (isDark) R.color.ate_switch_track_normal_dark else R.color.ate_switch_track_normal_light
        )
    }
    return ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(
                android.R.attr.state_enabled,
                -android.R.attr.state_activated,
                -android.R.attr.state_checked
            ),
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_activated),
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
        ),
        intArrayOf(disabled, normal, tint, tint)
    )
}

@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
private fun setCursorTint(editText: EditText, color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+：取系统 drawable mutate+tint，保持系统光标/手柄原始尺寸形状
        editText.textCursorDrawable?.mutate()?.let {
            it.setTint(color)
            editText.setTextCursorDrawable(it)
        }
        editText.textSelectHandleLeft?.mutate()?.let {
            it.setTint(color)
            editText.setTextSelectHandleLeft(it)
        }
        editText.textSelectHandleRight?.mutate()?.let {
            it.setTint(color)
            editText.setTextSelectHandleRight(it)
        }
        editText.textSelectHandle?.mutate()?.let {
            it.setTint(color)
            editText.setTextSelectHandle(it)
        }
        return
    }
    // API 26~28：反射（失败静默，仅光标颜色回落默认）
    try {
        val fCursorDrawableRes = TextView::class.java.getDeclaredField("mCursorDrawableRes")
        fCursorDrawableRes.isAccessible = true
        val mCursorDrawableRes = fCursorDrawableRes.getInt(editText)
        val fEditor = TextView::class.java.getDeclaredField("mEditor")
        fEditor.isAccessible = true
        val editor = fEditor.get(editText)
        val fCursorDrawable = editor.javaClass.getDeclaredField("mCursorDrawable")
        fCursorDrawable.isAccessible = true
        val drawables = arrayOfNulls<Drawable>(2)
        for (i in drawables.indices) {
            drawables[i] = ContextCompat.getDrawable(editText.context, mCursorDrawableRes)?.let {
                DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, color) }
            }
        }
        fCursorDrawable.set(editor, drawables)
    } catch (_: Exception) {
    }
}
