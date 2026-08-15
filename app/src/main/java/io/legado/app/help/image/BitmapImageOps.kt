package io.legado.app.help.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.util.Base64
import androidx.core.graphics.createBitmap
import com.script.jsdispatch.JsApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * [ImageOps] 的 Android 实现，[ImageRef] 内持 [Bitmap]。
 * 单例，由 JsBindings 统一注入为 `image` 变量（App.onCreate 注册进 JsBindingInjector）。
 */
@JsApi
object BitmapImageOps : ImageOps {

    private class BitmapRef(val bitmap: Bitmap) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("image.decode: 无法解码图片字节(${bytes.size} bytes)")
        return BitmapRef(bitmap)
    }

    override fun decode(base64: String): ImageRef {
        val payload = base64.substringAfter("base64,", base64)
        return decode(Base64.decode(payload, Base64.DEFAULT))
    }

    /** 解码输入流（封面解密路径下 `result` 为流）。JVM 专属重载，不在 common [ImageOps] 签名面。 */
    fun decode(input: InputStream): ImageRef {
        return decode(input.readBytes())
    }

    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray {
        val compressFormat = when (format.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            else -> throw IllegalArgumentException("image.encode: 不支持的格式 $format，仅 png/jpg/webp")
        }
        val out = ByteArrayOutputStream()
        bitmapOf(img).compress(compressFormat, quality.coerceIn(0, 100), out)
        return out.toByteArray()
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        val bitmap = bitmapOf(img)
        require(rows > 0 && cols > 0) { "image.split: rows/cols 必须为正数($rows,$cols)" }
        require(cols <= bitmap.width && rows <= bitmap.height) {
            "image.split: 切块数超过像素尺寸(${bitmap.width}x${bitmap.height} / ${rows}行${cols}列)"
        }
        val cellW = bitmap.width / cols
        val cellH = bitmap.height / rows
        val out = ArrayList<ImageRef>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val w = if (c == cols - 1) bitmap.width - cellW * c else cellW
                val h = if (r == rows - 1) bitmap.height - cellH * r else cellH
                out.add(BitmapRef(Bitmap.createBitmap(bitmap, cellW * c, cellH * r, w, h)))
            }
        }
        return out
    }

    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef {
        val bitmaps = (imgs as List<*>).map { bitmapOf(it) }
        require(bitmaps.isNotEmpty()) { "image.stitch: imgs 为空" }
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException("image.stitch: direction 仅支持 h/v，收到 $direction")
        }
        val width = if (horizontal) bitmaps.sumOf { it.width } else bitmaps.maxOf { it.width }
        val height = if (horizontal) bitmaps.maxOf { it.height } else bitmaps.sumOf { it.height }
        val result = createBitmap(width, height)
        val canvas = Canvas(result)
        var offset = 0f
        for (b in bitmaps) {
            if (horizontal) {
                canvas.drawBitmap(b, offset, 0f, null)
                offset += b.width
            } else {
                canvas.drawBitmap(b, 0f, offset, null)
                offset += b.height
            }
        }
        return BitmapRef(result)
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        return BitmapRef(Bitmap.createBitmap(bitmapOf(img), x, y, w, h))
    }

    override fun rotate(img: ImageRef, deg: Int): ImageRef {
        val bitmap = bitmapOf(img)
        val angle = deg % 360
        if (angle == 0) return BitmapRef(bitmap)
        // 旋转后外接矩形尺寸 (任意角度; 90/180/270 时精确)
        val rad = angle * PI / 180.0
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))
        val newW = ceil(bitmap.width * cos + bitmap.height * sin).toInt()
        val newH = ceil(bitmap.width * sin + bitmap.height * cos).toInt()
        val matrix = Matrix()
        // 绕中心旋转 + 平移进外接矩形; Matrix 正值顺时针 (与 desktop AWT / ios UIKit 视觉一致)
        matrix.postRotate(angle.toFloat(), bitmap.width / 2f, bitmap.height / 2f)
        matrix.postTranslate((newW - bitmap.width) / 2f, (newH - bitmap.height) / 2f)
        return BitmapRef(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
    }

    override fun flip(img: ImageRef, direction: String): ImageRef {
        val bitmap = bitmapOf(img)
        val matrix = Matrix()
        when (direction.lowercase()) {
            "h" -> {
                matrix.postScale(-1f, 1f)
                matrix.postTranslate(bitmap.width.toFloat(), 0f)
            }
            "v" -> {
                matrix.postScale(1f, -1f)
                matrix.postTranslate(0f, bitmap.height.toFloat())
            }
            else -> throw IllegalArgumentException("image.flip: direction 仅支持 h/v，收到 $direction")
        }
        return BitmapRef(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val bitmap = bitmapOf(img)
        return mapOf("w" to bitmap.width, "h" to bitmap.height)
    }

    /**
     * 解出句柄内的 Bitmap。
     * 注: rhino 已弃用并删除, 不再处理 NativeJavaObject 包装;
     * quickjs 下 JS 数组元素直接是 Java 对象, 无需 unwrap。
     */
    private fun bitmapOf(ref: Any?): Bitmap {
        return (ref as? BitmapRef)?.bitmap
            ?: throw IllegalArgumentException(
                "image: 参数不是 image.decode/split/stitch/crop 返回的 ImageRef(${ref?.javaClass?.name})"
            )
    }
}
