package io.legado.app.ui.book.bookmark

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Skiko 烘焙 (jvm/iOS/鸿蒙 三端共用一份): compose 位图 → skia Bitmap → PNG 编码,
 * 编码路径同 NativeBookArchiveExport 的 EPUB 封面 PNG (skiko 自带 png 编解码器)。
 */
internal actual fun ImageBitmap.encodePngBytes(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("PNG 编码失败")
