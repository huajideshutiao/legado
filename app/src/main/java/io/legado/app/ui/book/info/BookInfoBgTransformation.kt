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
import android.graphics.Shader
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 详情页背景专用转换：底部透明渐变 + 暗化蒙层
 * 优化版：通过 ComposeShader 合并绘制步骤，减少像素遍历次数和对象分配
 */
class BookInfoBgTransformation : BitmapTransformation() {

    companion object {
        private const val ID = "io.legado.app.ui.book.info.BookInfoBgTransformation"
        private val idBytes = ID.toByteArray()

        // 提取常量，减少 transform 时的分配
        private val GRADIENT_COLORS = intArrayOf(
            Color.BLACK,
            Color.BLACK,
            Color.argb(160, 0, 0, 0),
            Color.argb(100, 0, 0, 0),
            Color.TRANSPARENT,
        )
        private val GRADIENT_STOPS = floatArrayOf(0f, 0.5f, 0.7f, 0.85f, 1f)

        // 暗化滤镜：Color.argb(50, 0, 0, 0) + SRC_ATOP
        private val DARK_COLOR_FILTER = PorterDuffColorFilter(
            Color.argb(50, 0, 0, 0),
            PorterDuff.Mode.SRC_ATOP,
        )

        private val threadGradient = object : ThreadLocal<LinearGradient>() {
            override fun initialValue() = LinearGradient(
                0f, 0f, 0f, 1f,
                GRADIENT_COLORS,
                GRADIENT_STOPS,
                Shader.TileMode.CLAMP
            )
        }

        private val threadPaint = object : ThreadLocal<Paint>() {
            override fun initialValue() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        }

        private val threadMatrix = object : ThreadLocal<Matrix>() {
            override fun initialValue() = Matrix()
        }
    }

    @Suppress("DEPRECATION")
    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height

        // 必须使用 pool.get 以确保获得一块全透明干净的内存。
        // getDirty 会带有之前的内存脏数据，在底部透明区域会显示出杂色。
        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(result)
        val paint = threadPaint.get()!!
        val matrix = threadMatrix.get()!!
        val gradient = threadGradient.get()!!

        // 1. 将原图设为 Shader
        val bitmapShader =
            BitmapShader(toTransform, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        // 2. 使用模板渐变并通过 Matrix 缩放，避免 new LinearGradient
        matrix.setScale(1f, height.toFloat())
        gradient.setLocalMatrix(matrix)

        // 3. 组合 Shader：使用 DST_IN 模式，使 maskShader 的 alpha 通道应用到 bitmapShader 上
        // shaderA (bitmapShader) 是 destination, shaderB (maskShader) 是 source
        // DST_IN 效果为：结果颜色 = Destination 颜色 * Source Alpha
        paint.shader = ComposeShader(bitmapShader, gradient, PorterDuff.Mode.DST_IN)

        // 4. 应用暗化滤镜：在 Shader 输出后进行像素着色处理
        paint.colorFilter = DARK_COLOR_FILTER

        // 单次绘制完成：原图采样 + 暗化 + 底部透明化
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 清理状态，避免影响下次使用或产生泄漏
        paint.shader = null
        paint.colorFilter = null
        matrix.reset()
        gradient.setLocalMatrix(null)

        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(idBytes)
    }

    override fun equals(other: Any?): Boolean {
        return other is BookInfoBgTransformation
    }

    override fun hashCode(): Int {
        return ID.hashCode()
    }
}
