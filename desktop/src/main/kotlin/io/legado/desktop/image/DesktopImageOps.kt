package io.legado.desktop.image

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.ui.compose.platform.jvmGetString
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌面 JVM 端 [ImageOps] 实现 (基于 Skia 原生高性能图形引擎)。
 *
 * 全面基于 Skia [Bitmap] / [Image] / [Canvas] 实现，彻底废除 ImageIO，
 * 原生支持 WebP / GIF / PNG / JPEG / BMP，零 SPI 查找与类反射开销。
 */
object DesktopImageOps : ImageOps {

    private class SkiaBitmapRef(val bitmap: Bitmap) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
            ?: throw IllegalArgumentException(jvmGetString("image_decode_failed_bytes", bytes.size))
        val bitmap = Bitmap()
        val info = ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)
        if (!bitmap.allocPixels(info)) {
            image.close()
            throw IllegalArgumentException(jvmGetString("image_decode_failed_bytes", bytes.size))
        }
        val canvas = Canvas(bitmap)
        canvas.use { canvas ->
            image.use { img ->
                canvas.drawImage(img, 0f, 0f)
            }
        }
        return SkiaBitmapRef(bitmap)
    }

    override fun decode(base64: String): ImageRef {
        val payload = base64.substringAfter("base64,", base64)
        return decode(Base64.getDecoder().decode(payload))
    }

    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray {
        val bitmap = skiaBitmapOf(img)
        val skFormat = when (format.lowercase()) {
            "webp" -> EncodedImageFormat.WEBP
            "png" -> EncodedImageFormat.PNG
            "jpg", "jpeg" -> EncodedImageFormat.JPEG
            else -> throw IllegalArgumentException(jvmGetString("image_encode_unsupported_format", format))
        }
        return Image.makeFromBitmap(bitmap).use { image ->
            image.encodeToData(skFormat, quality.coerceIn(0, 100))?.bytes
                ?: throw IllegalStateException(jvmGetString("image_encode_write_failed", format))
        }
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        val bitmap = skiaBitmapOf(img)
        require(rows > 0 && cols > 0) { jvmGetString("image_split_rows_cols_positive", rows, cols) }
        require(cols <= bitmap.width && rows <= bitmap.height) {
            jvmGetString("image_split_exceeds_size", bitmap.width, bitmap.height, rows, cols)
        }
        val cellW = bitmap.width / cols
        val cellH = bitmap.height / rows
        val out = ArrayList<ImageRef>(rows * cols)
        Image.makeFromBitmap(bitmap).use { skImage ->
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val w = if (c == cols - 1) bitmap.width - cellW * c else cellW
                    val h = if (r == rows - 1) bitmap.height - cellH * r else cellH
                    val sub = Bitmap()
                    sub.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))
                    val canvas = Canvas(sub)
                    canvas.use { canvas ->
                        canvas.drawImageRect(
                            skImage,
                            Rect.makeXYWH(
                                (cellW * c).toFloat(),
                                (cellH * r).toFloat(),
                                w.toFloat(),
                                h.toFloat()
                            ),
                            Rect.makeWH(w.toFloat(), h.toFloat())
                        )
                    }
                    out.add(SkiaBitmapRef(sub))
                }
            }
        }
        return out
    }

    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef {
        val bitmaps = imgs.map { skiaBitmapOf(it) }
        require(bitmaps.isNotEmpty()) { jvmGetString("image_stitch_imgs_empty") }
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException(jvmGetString("image_stitch_direction_invalid", direction))
        }
        val width = if (horizontal) bitmaps.sumOf { it.width } else bitmaps.maxOf { it.width }
        val height = if (horizontal) bitmaps.maxOf { it.height } else bitmaps.sumOf { it.height }
        val result = Bitmap()
        result.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
        val canvas = Canvas(result)
        canvas.use { canvas ->
            var offset = 0
            for (b in bitmaps) {
                Image.makeFromBitmap(b).use { img ->
                    if (horizontal) {
                        canvas.drawImage(img, offset.toFloat(), 0f)
                        offset += b.width
                    } else {
                        canvas.drawImage(img, 0f, offset.toFloat())
                        offset += b.height
                    }
                }
            }
        }
        return SkiaBitmapRef(result)
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        val bitmap = skiaBitmapOf(img)
        val result = Bitmap()
        result.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))
        val canvas = Canvas(result)
        canvas.use { canvas ->
            Image.makeFromBitmap(bitmap).use { skImage ->
                canvas.drawImageRect(
                    skImage,
                    Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat())
                )
            }
        }
        return SkiaBitmapRef(result)
    }

    override fun rotate(img: ImageRef, deg: Int): ImageRef {
        val bitmap = skiaBitmapOf(img)
        val angle = deg % 360
        if (angle == 0) return SkiaBitmapRef(bitmap)
        val rad = angle * PI / 180.0
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))
        val w = ceil(bitmap.width * cos + bitmap.height * sin).toInt()
        val h = ceil(bitmap.width * sin + bitmap.height * cos).toInt()
        val result = Bitmap()
        result.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))
        val canvas = Canvas(result)
        canvas.use { canvas ->
            canvas.translate(w / 2f, h / 2f)
            canvas.rotate(deg.toFloat())
            Image.makeFromBitmap(bitmap).use { imgRef ->
                canvas.drawImage(imgRef, -bitmap.width / 2f, -bitmap.height / 2f)
            }
        }
        return SkiaBitmapRef(result)
    }

    override fun flip(img: ImageRef, direction: String): ImageRef {
        val bitmap = skiaBitmapOf(img)
        val result = Bitmap()
        result.allocPixels(ImageInfo.makeN32(bitmap.width, bitmap.height, ColorAlphaType.PREMUL))
        val canvas = Canvas(result)
        canvas.use { canvas ->
            canvas.translate(bitmap.width / 2f, bitmap.height / 2f)
            when (direction.lowercase()) {
                "h" -> canvas.scale(-1f, 1f)
                "v" -> canvas.scale(1f, -1f)
                else -> throw IllegalArgumentException("image.flip: direction 仅支持 h/v，收到 $direction")
            }
            Image.makeFromBitmap(bitmap).use { imgRef ->
                canvas.drawImage(imgRef, -bitmap.width / 2f, -bitmap.height / 2f)
            }
        }
        return SkiaBitmapRef(result)
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val bitmap = skiaBitmapOf(img)
        return mapOf("w" to bitmap.width, "h" to bitmap.height)
    }

    private fun skiaBitmapOf(ref: Any?): Bitmap {
        return (ref as? SkiaBitmapRef)?.bitmap
            ?: throw IllegalArgumentException(
                jvmGetString("image_ref_type_invalid", ref?.javaClass?.name)
            )
    }
}

