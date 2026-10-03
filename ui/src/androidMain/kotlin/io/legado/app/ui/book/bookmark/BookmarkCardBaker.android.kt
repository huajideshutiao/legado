package io.legado.app.ui.book.bookmark

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Android 烘焙: compose 位图 → Bitmap.compress PNG (无损)。
 * compress 返回 false 即编码失败 (位图已回收/内存不足), 抛错交调用方记日志。
 */
internal actual fun ImageBitmap.encodePngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    if (!asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) {
        error("PNG 编码失败")
    }
    return out.toByteArray()
}
