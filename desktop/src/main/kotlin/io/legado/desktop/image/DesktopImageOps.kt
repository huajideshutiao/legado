package io.legado.desktop.image

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.image.DesktopImageOps.crop
import io.legado.desktop.image.DesktopImageOps.encode
import io.legado.desktop.image.DesktopImageOps.split
import io.legado.desktop.image.DesktopImageOps.toRef
import io.legado.desktop.image.DesktopImageOps.withScope
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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
 *
 * 两条与其他端不同的处理:
 * - 所有出口位图都转不可变 ([toRef])，[split] / [crop] 因此能用 [Bitmap.extractSubset]
 *   共享父图像素做零拷贝视图 (等价原 `BufferedImage.getSubimage`)，[encode] 里的
 *   `Image.makeFromBitmap` 也不再逐次全图重拷。
 * - 覆写 [withScope] 做作用域回收: skiko 位图像素是原生 malloc，不进 JVM 的 GC 记账，
 *   靠 GC 掉包装对象才由 Cleaner 释放；故脚本作用域一结束就直接关掉，不等 GC。
 */
object DesktopImageOps : ImageOps {

    private class SkiaBitmapRef(val bitmap: Bitmap) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        val bitmap = runCatching {
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    val info = codec.imageInfo
                    if (info.width <= 0 || info.height <= 0) return@runCatching null
                    val bitmap = Bitmap()
                    val decodeInfo =
                        ImageInfo.makeN32(info.width, info.height, ColorAlphaType.PREMUL)
                    if (!bitmap.allocPixels(decodeInfo)) return@runCatching null
                    // Codec 直接解进目标位图, 不经 Image.makeFromEncoded 中转
                    codec.readPixels(bitmap, 0, -1)
                    bitmap
                }
            }
        }.getOrNull()
            ?: throw IllegalArgumentException(jvmGetString("image_decode_failed_bytes", bytes.size))
        return bitmap.toRef()
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
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val w = if (c == cols - 1) bitmap.width - cellW * c else cellW
                val h = if (r == rows - 1) bitmap.height - cellH * r else cellH
                out.add(bitmap.subsetRef(cellW * c, cellH * r, w, h))
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
        return result.toRef()
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        val bitmap = skiaBitmapOf(img)
        require(
            x >= 0 && y >= 0 && w > 0 && h > 0 &&
                x + w <= bitmap.width && y + h <= bitmap.height
        ) {
            "image.crop: 区域 ($x,$y,$w,$h) 超出图片 ${bitmap.width}x${bitmap.height}"
        }
        return bitmap.subsetRef(x, y, w, h)
    }

    override fun rotate(img: ImageRef, deg: Int): ImageRef {
        val bitmap = skiaBitmapOf(img)
        val angle = deg % 360
        // 不旋转直接还回原句柄: 不另包一层, 免得同一张原生位图挂在两个句柄上被重复登记/释放
        if (angle == 0) return img
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
        return result.toRef()
    }

    override fun flip(img: ImageRef, direction: String): ImageRef {
        val bitmap = skiaBitmapOf(img)
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException("image.flip: direction 仅支持 h/v，收到 $direction")
        }
        val result = Bitmap()
        result.allocPixels(ImageInfo.makeN32(bitmap.width, bitmap.height, ColorAlphaType.PREMUL))
        val canvas = Canvas(result)
        canvas.use { canvas ->
            canvas.translate(bitmap.width / 2f, bitmap.height / 2f)
            if (horizontal) canvas.scale(-1f, 1f) else canvas.scale(1f, -1f)
            Image.makeFromBitmap(bitmap).use { imgRef ->
                canvas.drawImage(imgRef, -bitmap.width / 2f, -bitmap.height / 2f)
            }
        }
        return result.toRef()
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val bitmap = skiaBitmapOf(img)
        return mapOf("w" to bitmap.width, "h" to bitmap.height)
    }

    /**
     * 结果位图出口: 登记进当前脚本作用域, 转不可变后包成句柄。
     *
     * 不可变是后续所有操作能零拷贝的前提 —— `Image.makeFromBitmap` 对可变位图会全图重拷,
     * [Bitmap.extractSubset] 出来的子图也只有共享不可变 pixelRef 才安全。
     */
    private fun Bitmap.toRef(): ImageRef {
        setImmutable()
        register(this)
        return SkiaBitmapRef(this)
    }

    /** 子图句柄: [Bitmap.extractSubset] 共享父图 pixelRef, 零拷贝 (等价原 `getSubimage` 视图语义)。 */
    private fun Bitmap.subsetRef(x: Int, y: Int, w: Int, h: Int): ImageRef {
        val sub = Bitmap()
        check(extractSubset(sub, IRect.makeXYWH(x, y, w, h))) {
            "image: 取子图失败 ($x,$y,$w,$h) of ${width}x$height"
        }
        register(sub)
        return SkiaBitmapRef(sub)
    }

    private fun skiaBitmapOf(ref: Any?): Bitmap {
        val bitmap = (ref as? SkiaBitmapRef)?.bitmap
            ?: throw IllegalArgumentException(
                jvmGetString("image_ref_type_invalid", ref?.javaClass?.name)
            )
        // 已随作用域释放: 明确报错交给脚本, 不让它带着空指针进原生代码把进程打死
        check(!bitmap.isClosed) { "image: 句柄已随本次脚本作用域释放, 不能跨次复用" }
        return bitmap
    }

    // ---- 脚本作用域回收 ----
    // skiko 的 Bitmap 像素是原生 malloc, JVM 只在 GC 掉包装对象后才由 Cleaner 线程释放,
    // 而 GC 对这块内存的大小一无所知 (无 NativeAllocationRegistry 之类的压力上报)。
    // 一页漫画重排 (decode + split + stitch) 就是几十 MB, 攒几十页可以在堆水位几乎不动的
    // 情况下把原生内存顶到几百 MB。故在脚本作用域结束时直接关掉, 不等 GC。
    //
    // 线程局部: JS eval 是同步执行的, 一次作用域内不换线程; 多页并发各用自己的栈。
    // 栈式: 嵌套作用域各只关自己登记的那一层。

    private val scopes = ThreadLocal<ArrayDeque<MutableList<Bitmap>>?>()

    override fun <T> withScope(block: () -> T): T {
        val stack = scopes.get() ?: ArrayDeque<MutableList<Bitmap>>().also { scopes.set(it) }
        val frame = ArrayList<Bitmap>()
        stack.addLast(frame)
        try {
            return block()
        } finally {
            stack.removeLast()
            // 子图与父图共享 pixelRef (SkPixelRef 引用计数), 关闭顺序无关
            frame.forEach { if (!it.isClosed) it.close() }
            if (stack.isEmpty()) scopes.remove()
        }
    }

    /** 作用域外创建的位图不登记, 回落原 GC 语义。 */
    private fun register(bitmap: Bitmap) {
        scopes.get()?.lastOrNull()?.add(bitmap)
    }
}

