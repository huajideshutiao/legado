package io.legado.app.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import androidx.annotation.ColorInt
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.radius

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
object DrawableUtils {

    fun createTransitionDrawable(
        @ColorInt startColor: Int,
        @ColorInt endColor: Int
    ): TransitionDrawable {
        return createTransitionDrawable(startColor.toDrawable(), endColor.toDrawable())
    }

    fun createTransitionDrawable(start: Drawable, end: Drawable): TransitionDrawable {
        val drawables = arrayOfNulls<Drawable>(2)

        drawables[0] = start
        drawables[1] = end

        return TransitionDrawable(drawables)
    }

    /**
     * 创建动态主题着色的卡片背景
     * 使用当前主题的 bottomBackground 颜色，支持 8dp 圆角
     */
    fun createCardBackground(context: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(context.bottomBackground)
            cornerRadius = context.radius.defaultF
        }
    }

}

fun Drawable.setTintListMutate(
    tint: ColorStateList,
    tintMode: PorterDuff.Mode = PorterDuff.Mode.SRC_ATOP
) {
    val wrappedDrawable = DrawableCompat.wrap(this)
    wrappedDrawable.mutate()
    DrawableCompat.setTintMode(wrappedDrawable, tintMode)
    DrawableCompat.setTintList(wrappedDrawable, tint)
}

fun Drawable.setTintMutate(
    @ColorInt tint: Int,
    tintMode: PorterDuff.Mode = PorterDuff.Mode.SRC_ATOP
) {
    val wrappedDrawable = DrawableCompat.wrap(this)
    wrappedDrawable.mutate()
    DrawableCompat.setTintMode(wrappedDrawable, tintMode)
    DrawableCompat.setTint(wrappedDrawable, tint)
}