package io.legado.app.help.glide

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import io.legado.app.utils.stackBlur

class BlurTransformation(val radio: Int = 20) : Transformation() {
    override val cacheKey: String = "legado-blur-$radio"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap = input.stackBlur(radio)
}
