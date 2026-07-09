package io.legado.app.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.radius
import splitties.init.appCtx

class BottomBackgroundDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radius = appCtx.radius.defaultF
    private val rectF = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        paint.color = ThemeStore.bottomBackground
        rectF.set(bounds)
        path.reset()
        path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
