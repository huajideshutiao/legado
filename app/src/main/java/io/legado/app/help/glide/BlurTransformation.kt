package io.legado.app.help.glide

import android.graphics.Bitmap
import android.view.View
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.utils.stackBlur
import java.security.MessageDigest

class BlurTransformation(val radius: Int = 20, val view: View? = null) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int
    ): Bitmap = toTransform.stackBlur(radius, view = view)

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("io.legado.app.help.glide.BlurTransformation(radius=$radius, hasView=${view != null})".toByteArray())
    }

    override fun equals(other: Any?): Boolean {
        return other is BlurTransformation && other.radius == radius && other.view == view
    }

    override fun hashCode(): Int {
        return radius * 31 + (view?.hashCode() ?: 0)
    }
}
