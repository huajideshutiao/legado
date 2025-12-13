package io.legado.app.help.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.utils.stackBlur
import java.security.MessageDigest
import androidx.core.graphics.scale

class FastBlurTransformation(
    private val maxShortSide: Int = 400
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        var targetWidth = toTransform.width
        var targetHeight = toTransform.height
        if (targetWidth > targetHeight) {
            val ratio = targetWidth.toFloat() / targetHeight
            targetWidth = (maxShortSide * ratio).toInt()
            targetHeight = maxShortSide
        } else {
            val ratio = targetHeight.toFloat() / targetWidth
            targetHeight = (maxShortSide * ratio).toInt()
            targetWidth = maxShortSide
        }
        return toTransform.scale(targetWidth, targetHeight).stackBlur(3)
    }


    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("fast blur transformation".toByteArray())
    }
}