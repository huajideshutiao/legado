package io.legado.app.ui.book.read.config

import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.utils.ColorUtils

data class ReadMenuTheme(
    val bgColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val isLight: Boolean
)

fun createReadMenuTheme(context: android.content.Context): ReadMenuTheme {
    val bgColor = context.bottomBackground
    val isLight = ColorUtils.isColorLight(bgColor)
    val textColor = context.getPrimaryTextColor(isLight)
    val secondaryTextColor = context.getSecondaryTextColor(isLight)
    return ReadMenuTheme(bgColor, textColor, secondaryTextColor, isLight)
}

fun View.applyMenuTheme(theme: ReadMenuTheme) {
    setBackgroundColor(theme.bgColor)
}

fun ImageView.applyMenuThemeColorFilter(theme: ReadMenuTheme) {
    setColorFilter(theme.textColor)
}

fun ImageView.applyMenuThemeColorFilter(theme: ReadMenuTheme, mode: PorterDuff.Mode) {
    setColorFilter(theme.textColor, mode)
}

fun ImageView.applyMenuThemeSecondaryColorFilter(theme: ReadMenuTheme, mode: PorterDuff.Mode) {
    setColorFilter(theme.secondaryTextColor, mode)
}

fun TextView.applyMenuThemeTextColor(theme: ReadMenuTheme) {
    setTextColor(theme.textColor)
}

fun TextView.applyMenuThemeSecondaryTextColor(theme: ReadMenuTheme) {
    setTextColor(theme.secondaryTextColor)
}
