package io.legado.app.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.legado.app.R
import io.legado.app.lib.theme.ThemeStore
import splitties.init.appCtx

class BottomBackgroundDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radius = appCtx.resources.getDimension(R.dimen.arco_radius_default)
    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        paint.color = ThemeStore.bottomBackground
        rectF.set(bounds)
        canvas.drawRoundRect(rectF, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
