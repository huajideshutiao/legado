package io.legado.app.ui.book.info

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 详情页背景专用转换：顶部微遮 + 底部透明渐变 + 暗化蒙层
 * 优化版：通过 ComposeShader 合并绘制步骤，减少像素遍历次数和对象分配
 * 渐变分区：顶部 30% 清晰 → 30%-65% 柔和过渡 → 65%-100% 加速淡出
 */
class BookInfoBgTransformation : BitmapTransformation() {

    companion object {
        private const val ID = "io.legado.app.ui.book.info.BookInfoBgTransformation"
        private val idBytes = ID.toByteArray()

        private val GRADIENT_COLORS = intArrayOf(
            Color.BLACK,                     // 0f 封面主体完全清晰
            Color.BLACK,                     // 0.30f 封面主体完全清晰
            Color.argb(210, 0, 0, 0),       // 0.48f 开始柔和过渡
            Color.argb(165, 0, 0, 0),       // 0.63f 继续过渡
            Color.argb(110, 0, 0, 0),       // 0.77f 明显淡化
            Color.argb(50, 0, 0, 0),        // 0.90f 接近透明
            Color.TRANSPARENT,              // 1f    完全透明
        )
        private val GRADIENT_STOPS = floatArrayOf(0f, 0.30f, 0.48f, 0.63f, 0.77f, 0.90f, 1f)

        // 暗化滤镜：Color.argb(50, 0, 0, 0) + SRC_ATOP
        private val DARK_COLOR_FILTER = PorterDuffColorFilter(
            Color.argb(50, 0, 0, 0),
            PorterDuff.Mode.SRC_ATOP,
        )

        private val SRC_XFERMODE = PorterDuffXfermode(PorterDuff.Mode.SRC)

        private val threadGradient = ThreadLocal.withInitial {
            LinearGradient(
                0f, 0f, 0f, 1f,
                GRADIENT_COLORS,
                GRADIENT_STOPS,
                Shader.TileMode.CLAMP
            )
        }

        private val threadPaint = ThreadLocal.withInitial {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        }

        private val threadMatrix = ThreadLocal.withInitial { Matrix() }
    }

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height

        // 使用 getDirty 配合 SRC 模式，比 get 更高效且能完全覆盖旧数据，避免杂色
        val result = pool.getDirty(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(result)
        val paint = threadPaint.get()!!
        val matrix = threadMatrix.get()!!
        val gradient = threadGradient.get()!!

        // 1. 将原图设为 Shader
        val bitmapShader = BitmapShader(toTransform, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        // 2. 使用模板渐变并通过 Matrix 缩放，避免每次 new LinearGradient
        matrix.setScale(1f, height.toFloat())
        gradient.setLocalMatrix(matrix)

        // 3. 组合 Shader：使用 DST_IN 模式，使 gradient 的 alpha 通道应用到 bitmapShader 上
        // DST_IN 效果为：结果颜色 = Destination 颜色 * Source Alpha
        paint.shader = ComposeShader(bitmapShader, gradient, PorterDuff.Mode.DST_IN)

        // 4. 应用暗化滤镜：在 Shader 输出后进行像素着色处理
        paint.colorFilter = DARK_COLOR_FILTER

        // 5. 使用 SRC 模式确保直接覆盖 result 内存，不与旧数据混合
        paint.xfermode = SRC_XFERMODE

        // 单次绘制完成：原图采样 + 底部透明化 + 暗化
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 清理状态，避免影响下次使用或产生泄漏
        paint.shader = null
        paint.colorFilter = null
        paint.xfermode = null
        matrix.reset()
        gradient.setLocalMatrix(null)

        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(idBytes)
    }

    override fun equals(other: Any?): Boolean = other is BookInfoBgTransformation

    override fun hashCode(): Int = ID.hashCode()
}
