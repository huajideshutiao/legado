@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * 主题相关的 Context / Fragment 扩展属性。
 *
 * @author Karim Abou Zeid (kabouzeid)
 */

// ===== 文字颜色函数（按背景明暗返回对应色） =====

/**
 * 背景深色时返回浅色文字，背景浅色时返回深色文字。
 * @param dark 背景是否为深色
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

/**
 * 背景深色时返回浅色次要文字，背景浅色时返回深色次要文字。
 * @param dark 背景是否为深色
 */
@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

/**
 * 背景深色时返回浅色禁用文字，背景浅色时返回深色禁用文字。
 * @param dark 背景是否为深色
 */
@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

// ===== Context 扩展属性 =====

val Context.primaryColor: Int
    get() = ThemeStore.backgroundColor

val Context.accentColor: Int
    get() = ThemeStore.accentColor

val Context.backgroundColor: Int
    get() = ThemeStore.backgroundColor

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground

val Context.primaryTextColor: Int
    get() = getPrimaryTextColor(isDarkTheme)

val Context.secondaryTextColor: Int
    get() = getSecondaryTextColor(isDarkTheme)

val Context.isDarkTheme: Boolean
    get() = ColorUtils.isColorLight(ThemeStore.backgroundColor)

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.cornerRadius = 5f.dpToPx()
        background.setColor(bottomBackground)
        return background
    }

// ===== Fragment 扩展属性（委托给 requireContext()） =====

val Fragment.primaryColor: Int
    get() = requireContext().primaryColor

val Fragment.accentColor: Int
    get() = requireContext().accentColor

val Fragment.backgroundColor: Int
    get() = requireContext().backgroundColor

val Fragment.bottomBackground: Int
    get() = requireContext().bottomBackground

val Fragment.primaryTextColor: Int
    get() = requireContext().primaryTextColor

val Fragment.secondaryTextColor: Int
    get() = requireContext().secondaryTextColor

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme
