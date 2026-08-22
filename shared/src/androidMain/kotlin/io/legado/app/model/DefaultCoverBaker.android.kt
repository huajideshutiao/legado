package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Android 烘焙: BitmapFactory 解码 → 裁剪缩放 → WEBP q85
 * (裁剪算法自原 app 端 BitmapUtils.cropTo 下沉, 1:1 保留)。
 */
internal actual fun bakeDefaultCoverBytes(
    sourceBytes: ByteArray,
    ratio: BookCoverShared.CoverRatio,
): ByteArray? = runCatching {
    val src = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
        ?: return@runCatching null
    val baked = when (ratio) {
        BookCoverShared.CoverRatio.NOVEL -> src.cropTo(ratio.bakeW, ratio.bakeH, alignY = 0.5f)
        BookCoverShared.CoverRatio.VIDEO -> src.cropTo(ratio.bakeW, ratio.bakeH, alignY = 0f)
    }
    try {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        baked.compress(Bitmap.CompressFormat.WEBP, 85, out)
        out.toByteArray()
    } finally {
        if (baked !== src) baked.recycle()
        src.recycle()
    }
}.getOrNull()

/**
 * 缩放裁剪到目标宽高: max 比例缩放铺满, x 居中, y 按 [alignY] 对齐 (0=顶/0.5=中)。
 */
private fun Bitmap.cropTo(width: Int, height: Int, alignY: Float): Bitmap {
    if (this.width == width && this.height == height) return this
    val result = Bitmap.createBitmap(width, height, config ?: Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val scale = maxOf(width.toFloat() / this.width, height.toFloat() / this.height)
    val dx = (width - this.width * scale) / 2f
    val dy = (height - this.height * scale) * alignY
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate(dx, dy)
    canvas.drawBitmap(this, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
    return result
}
