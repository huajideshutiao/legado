package io.legado.app.help.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.utils.stackBlur
import java.security.MessageDigest

class BlurTransformation(val radio: Int = 20) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool, img: Bitmap, outWidth: Int, outHeight: Int
    ): Bitmap = img.stackBlur(radio)

    override fun updateDiskCacheKey(messageDigest: MessageDigest) =
        messageDigest.update("fast blur transformation".toByteArray())
}