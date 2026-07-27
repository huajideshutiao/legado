package io.legado.app.ui.book.manga.entities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import coil3.size.Size
import coil3.transform.Transformation

class GrayscaleTransformation : Transformation() {
    override val cacheKey: String = "io.legado.app.model.GrayscaleTransformation"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val resultBitmap = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint()
        val matrix = ColorMatrix(
            floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return resultBitmap
    }
}
