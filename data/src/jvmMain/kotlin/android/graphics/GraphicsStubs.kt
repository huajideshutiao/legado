// android.graphics JVM 图片处理桩 (功能级): 漫画插件源的图片加工 (禁漫/18comic 类图片
// 分割重排、多页拼接) 依赖宿主提供 Bitmap/BitmapFactory/Canvas/Rect/Paint/Matrix 面 ——
// 仅类级空壳满足链接但拿不到像素, 加工型扩展会静默产出坏图。
//
// 像素以 java.awt BufferedImage 承载: 解码 ImageIO 优先 (jpeg/png/gif/bmp), webp/avif 等
// 经 Skia (skiko, 与 ui ImageBitmapLoader.jvm 同源) 兜底; 绘制/裁剪/缩放走 Java2D。
// 覆盖 Tachiyomi 扩展图片加工的常用调用口径: 字段型类型 (Rect/RectF/Options) 用 @JvmField
// 对齐 Android 公共字段面, 方法型类型 (Bitmap/Paint) 对齐 bean 方法签名; 未声明成员在
// 调用时抛 NoSuchMethodError, 由扩展装载器的单扩展失败隔离兜底。
package android.graphics

import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.ImageInfo as SkiaImageInfo
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 位图 (ARGB_8888 语义)。实例只能经 [BitmapFactory] / companion 工厂创建;
 * Java 扩展经 getWidth()/getHeight() 等 bean 方法访问 (Kotlin val 生成同名签名)。
 */
class Bitmap internal constructor(internal val image: BufferedImage) {

    val width: Int get() = image.width

    val height: Int get() = image.height

    fun getConfig(): Config = Config.ARGB_8888

    fun hasAlpha(): Boolean = image.colorModel.hasAlpha()

    fun isRecycled(): Boolean = false

    fun recycle() {}

    fun getRowBytes(): Int = width * 4

    fun getByteCount(): Int = width * height * 4

    fun eraseColor(color: Int) {
        val g = image.graphics
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, width, height)
        g.dispose()
    }

    /** 像素值 (0xAARRGGBB)。 */
    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)

    fun setPixel(x: Int, y: Int, color: Int) {
        image.setRGB(x, y, color)
    }

    fun getPixels(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        image.getRGB(x, y, width, height, pixels, offset, stride)
    }

    fun setPixels(
        pixels: IntArray,
        offset: Int,
        stride: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        image.setRGB(x, y, width, height, pixels, offset, stride)
    }

    /**
     * 编码: JPEG/PNG 走 ImageIO; WEBP 无 AWT 编码器, 兜底输出 PNG (加工型扩展实际
     * 压缩目标几乎都是 PNG/JPEG)。quality 为 Android 0..100 语义位, 桩按编码器默认值写出。
     */
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val imageFormat = when (format) {
            CompressFormat.JPEG -> "jpeg"
            CompressFormat.PNG,
            CompressFormat.WEBP,
            CompressFormat.WEBP_LOSSY,
            CompressFormat.WEBP_LOSSLESS,
            -> "png"
        }
        // JPEG 不支持 alpha: 必须先把 ARGB 位图降为 TYPE_INT_RGB (Android 为静默丢 alpha,
        // 直接 ImageIO.write 会抛 "Bogus input colorspace")
        val target = if (format == CompressFormat.JPEG) opaqueCopy() else image
        return ImageIO.write(target, imageFormat, stream)
    }

    /** TYPE_INT_RGB 副本 (JPEG 编码用): 逐像素拷 RGB, alpha 直接丢弃。 */
    private fun opaqueCopy(): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, width, height, image.getRGB(0, 0, width, height, null, 0, width), 0, width)
        return out
    }

    enum class Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, HARDWARE,
    }

    enum class CompressFormat {
        JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS,
    }

    companion object {

        @JvmStatic
        fun createBitmap(width: Int, height: Int): Bitmap = createBitmap(width, height, Config.ARGB_8888)

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

        @JvmStatic
        fun createBitmap(src: Bitmap): Bitmap {
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            val g = out.graphics
            g.drawImage(src.image, 0, 0, null)
            g.dispose()
            return Bitmap(out)
        }

        /** 裁剪 (Android 语义: 深拷贝, 与源位图不共享像素)。 */
        @JvmStatic
        fun createBitmap(src: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            if (x < 0 || y < 0 || x + width > src.width || y + height > src.height) {
                throw IllegalArgumentException("裁剪区域越界: x=$x y=$y w=$width h=$height (${src.width}x${src.height})")
            }
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = out.graphics
            g.drawImage(src.image, 0, 0, width, height, x, y, x + width, y + height, null)
            g.dispose()
            return Bitmap(out)
        }

        @JvmStatic
        @JvmOverloads
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean = true): Bitmap {
            val out = BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB)
            val g = out.graphics as java.awt.Graphics2D
            if (filter) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            }
            g.drawImage(src.image, 0, 0, dstWidth, dstHeight, null)
            g.dispose()
            return Bitmap(out)
        }
    }
}

/** 位图解码: ImageIO (jpeg/png/gif/bmp) 优先, webp/avif 经 Skia 兜底转 ARGB。 */
object BitmapFactory {

    /** 对齐 Android 公共字段面 (@JvmField): Java 扩展按 GETFIELD/PUTFIELD 直读直写。 */
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
        @JvmField var inMutable: Boolean = false
        @JvmField var inScaled: Boolean = true
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
        @JvmField var outMimeType: String? = null
    }

    @JvmStatic
    fun decodeStream(input: InputStream?): Bitmap? = decodeStream(input, null, null)

    @JvmStatic
    fun decodeStream(input: InputStream?, outPadding: Rect?, opts: Options?): Bitmap? {
        if (input == null) return null
        val bytes = runCatching { input.readBytes() }.getOrNull() ?: return null
        return decodeByteArray(bytes, 0, bytes.size, opts)
    }

    @JvmStatic
    @JvmOverloads
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? {
        // 边界语义对齐 Android BitmapFactory.decodeByteArray: 越界抛 ArrayIndexOutOfBoundsException
        if ((offset or length) < 0 || data.size < offset + length) {
            throw ArrayIndexOutOfBoundsException()
        }
        if (length == 0) return null
        val bytes = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        if (opts?.inJustDecodeBounds == true) {
            val size = decodeSize(bytes)
            opts.outWidth = size?.first ?: 0
            opts.outHeight = size?.second ?: 0
            opts.outMimeType = null
            return null
        }
        val image = decodeToImage(bytes) ?: return null
        return Bitmap(applyInSampleSize(image, opts?.inSampleSize ?: 1))
    }

    @JvmStatic
    @JvmOverloads
    fun decodeFile(path: String, opts: Options? = null): Bitmap? =
        runCatching { java.io.File(path).readBytes() }.getOrNull()
            ?.let { decodeByteArray(it, 0, it.size, opts) }

    private fun decodeSize(bytes: ByteArray): Pair<Int, Int>? =
        runCatching {
            SkiaData.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec -> codec.width to codec.height }
            }
        }.getOrElse {
            runCatching {
                ImageIO.read(ByteArrayInputStream(bytes))?.let { it.width to it.height }
            }.getOrNull()
        }

    private fun decodeToImage(bytes: ByteArray): BufferedImage? {
        ImageIO.read(ByteArrayInputStream(bytes))?.let { return it }
        // Skia 兜底 (webp/avif/heic): N32 UNPREMUL 字节按小端读回整型即 0xAARRGGBB
        return runCatching {
            SkiaData.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    val width = codec.width
                    val height = codec.height
                    val skBitmap = SkiaBitmap()
                    if (!skBitmap.allocPixels(SkiaImageInfo.makeN32(width, height, ColorAlphaType.UNPREMUL))) {
                        return@use null
                    }
                    codec.readPixels(skBitmap, 0, -1)
                    val raw = skBitmap.readPixels(
                        SkiaImageInfo.makeN32(width, height, ColorAlphaType.UNPREMUL),
                        width * 4, 0, 0,
                    ) ?: return@use null
                    val argb = IntArray(width * height)
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
                    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                        setRGB(0, 0, width, height, argb, 0, width)
                    }
                }
            }
        }.getOrNull()
    }

    private fun applyInSampleSize(image: BufferedImage, inSampleSize: Int): BufferedImage {
        if (inSampleSize <= 1) return image
        val width = (image.width / inSampleSize).coerceAtLeast(1)
        val height = (image.height / inSampleSize).coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.graphics as java.awt.Graphics2D
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return out
    }
}

/** 画布: 绘制直接落后备位图像素 (Android Canvas(bitmap) 语义, 不复制)。 */
class Canvas(bitmap: Bitmap) {

    private var target: BufferedImage = bitmap.image
    private var g: java.awt.Graphics2D = target.graphics as java.awt.Graphics2D

    /** 无后备画布: 内容无处落, 仅满足类面 (Android 同样在 setBitmap 前不可用)。 */
    constructor() : this(Bitmap.createBitmap(1, 1))

    fun setBitmap(bitmap: Bitmap) {
        g.dispose()
        target = bitmap.image
        g = target.graphics as java.awt.Graphics2D
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        g.drawImage(bitmap.image, left.roundToInt(), top.roundToInt(), null)
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        drawScaled(
            bitmap,
            src?.left?.toFloat(), src?.top?.toFloat(), src?.right?.toFloat(), src?.bottom?.toFloat(),
            dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(),
        )
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        drawScaled(
            bitmap,
            src?.left?.toFloat(), src?.top?.toFloat(), src?.right?.toFloat(), src?.bottom?.toFloat(),
            dst.left, dst.top, dst.right, dst.bottom,
        )
    }

    fun drawBitmap(bitmap: Bitmap, src: RectF, dst: RectF, paint: Paint?) {
        drawScaled(bitmap, src.left, src.top, src.right, src.bottom, dst.left, dst.top, dst.right, dst.bottom)
    }

    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        // AffineTransform(m00, m10, m01, m11, m02, m12); Android 平面下标 0..5 即
        // m00,m01,m02,m10,m11,m12 (平移在 [2]/[5])
        g.drawImage(
            bitmap.image,
            AffineTransform(matrix[0], matrix[3], matrix[1], matrix[4], matrix[2], matrix[5]),
            null,
        )
    }

    fun drawColor(color: Int) {
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, target.width, target.height)
    }

    fun drawPaint(paint: Paint) = drawColor(paint.color)

    fun drawRect(left: Int, top: Int, right: Int, bottom: Int, paint: Paint) {
        g.color = java.awt.Color(paint.color, true)
        if (paint.style == Paint.Style.STROKE) {
            g.stroke = java.awt.BasicStroke(paint.strokeWidth)
            g.drawRect(left, top, right - left, bottom - top)
        } else {
            g.fillRect(left, top, right - left, bottom - top)
        }
    }

    fun drawRect(rect: Rect, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        g.color = java.awt.Color(paint.color, true)
        val size = (radius * 2).roundToInt()
        if (paint.style == Paint.Style.STROKE) {
            g.stroke = java.awt.BasicStroke(paint.strokeWidth)
            g.drawOval((cx - radius).roundToInt(), (cy - radius).roundToInt(), size, size)
        } else {
            g.fillOval((cx - radius).roundToInt(), (cy - radius).roundToInt(), size, size)
        }
    }

    private fun drawScaled(
        bitmap: Bitmap,
        sx0: Float?, sy0: Float?, sx1: Float?, sy1: Float?,
        dx0: Float, dy0: Float, dx1: Float, dy1: Float,
    ) {
        val src = bitmap.image
        g.drawImage(
            src,
            dx0.roundToInt(), dy0.roundToInt(), dx1.roundToInt(), dy1.roundToInt(),
            sx0?.roundToInt() ?: 0, sy0?.roundToInt() ?: 0,
            sx1?.roundToInt() ?: src.width, sy1?.roundToInt() ?: src.height,
            null,
        )
    }
}

/**
 * 仿射矩阵 (Android 平面布局: 下标 0..5 = MSCALE_X/MSKEW_X/MTRANS_X/MSKEW_Y/MSCALE_Y/MTRANS_Y)。
 * 映射公式 x' = v0·x + v1·y + v2, y' = v3·x + v4·y + v5; 透视位恒 0/0/1。
 */
class Matrix {

    private val values = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    fun reset() = setValues(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

    fun set(src: Matrix?) {
        src?.let { setValues(it.values.copyOf()) }
    }

    fun isIdentity(): Boolean = values.contentEquals(IDENTITY)

    fun setTranslate(dx: Float, dy: Float) = reset().also { postTranslate(dx, dy) }

    fun setScale(sx: Float, sy: Float) = reset().also { postScale(sx, sy, 0f, 0f) }

    fun setScale(sx: Float, sy: Float, px: Float, py: Float) = reset().also { postScale(sx, sy, px, py) }

    fun setRotate(degrees: Float) = reset().also { postRotate(degrees, 0f, 0f) }

    fun setRotate(degrees: Float, px: Float, py: Float) = reset().also { postRotate(degrees, px, py) }

    fun postTranslate(dx: Float, dy: Float) = post(translateOf(dx, dy))

    fun postScale(sx: Float, sy: Float) = postScale(sx, sy, 0f, 0f)

    /** 绕支点缩放: op = T(px,py) ∘ S ∘ T(-px,-py), post 到既有矩阵之后。 */
    fun postScale(sx: Float, sy: Float, px: Float, py: Float) {
        post(translateOf(px, py))
        post(scaleOf(sx, sy))
        post(translateOf(-px, -py))
    }

    fun postRotate(degrees: Float) = post(rotateOf(degrees, 0f, 0f))

    fun postRotate(degrees: Float, px: Float, py: Float) = post(rotateOf(degrees, px, py))

    fun postConcat(other: Matrix) = post(other.values.copyOf())

    fun preTranslate(dx: Float, dy: Float) = pre(translateOf(dx, dy))

    fun preScale(sx: Float, sy: Float) = pre(scaleOf(sx, sy))

    fun preRotate(degrees: Float) = pre(rotateOf(degrees, 0f, 0f))

    fun preConcat(other: Matrix) = pre(other.values.copyOf())

    fun getValues(values: FloatArray) {
        this.values.copyInto(values)
    }

    fun setValues(values: FloatArray) {
        values.copyInto(this.values, 0, 0, 6)
        this.values[6] = 0f
        this.values[7] = 0f
        this.values[8] = 1f
    }

    /** 原地映射点序列 [x0, y0, x1, y1, …]。 */
    fun mapPoints(points: FloatArray) = mapPoints(points, 0, points, 0, points.size / 2)

    fun mapPoints(dst: FloatArray, src: FloatArray) =
        mapPoints(dst, 0, src, 0, minOf(dst.size, src.size) / 2)

    fun mapPoints(dst: FloatArray, dstIndex: Int, src: FloatArray, srcIndex: Int, pointCount: Int) {
        repeat(pointCount) { i ->
            val x = src[srcIndex + i * 2]
            val y = src[srcIndex + i * 2 + 1]
            dst[dstIndex + i * 2] = x * values[0] + y * values[1] + values[2]
            dst[dstIndex + i * 2 + 1] = x * values[3] + y * values[4] + values[5]
        }
    }

    /** 四角映射取包围盒 (旋转/错切下两角不足以确定包围盒)。 */
    fun mapRect(rect: RectF) {
        val pts = floatArrayOf(
            rect.left, rect.top,
            rect.right, rect.top,
            rect.right, rect.bottom,
            rect.left, rect.bottom,
        )
        mapPoints(pts)
        rect.left = minOf(pts[0], pts[2], pts[4], pts[6])
        rect.top = minOf(pts[1], pts[3], pts[5], pts[7])
        rect.right = maxOf(pts[0], pts[2], pts[4], pts[6])
        rect.bottom = maxOf(pts[1], pts[3], pts[5], pts[7])
    }

    /** 矩阵组合 M' = op * M (op 在既有变换之后生效, 对齐 Android postConcat)。 */
    private fun post(op: FloatArray) {
        val m = values
        val r = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] =
                    op[row * 3] * m[col] + op[row * 3 + 1] * m[3 + col] + op[row * 3 + 2] * m[6 + col]
            }
        }
        r.copyInto(m)
    }

    /** 矩阵组合 M' = M * op (op 在既有变换之前生效, 对齐 Android preConcat)。 */
    private fun pre(op: FloatArray) {
        val m = values
        val r = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] =
                    m[row * 3] * op[col] + m[row * 3 + 1] * op[3 + col] + m[row * 3 + 2] * op[6 + col]
            }
        }
        r.copyInto(m)
    }

    private fun translateOf(dx: Float, dy: Float) =
        floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    private fun scaleOf(sx: Float, sy: Float) =
        floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f)

    private fun rotateOf(degrees: Float, px: Float, py: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val a = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        return mulRow(mulRow(translateOf(px, py), a), translateOf(-px, -py))
    }

    private fun mulRow(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] =
                    a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
            }
        }
        return r
    }

    /** 下标访问 m[i] (Canvas.drawBitmap 转 AffineTransform 用, 扩展不感知)。 */
    internal operator fun get(index: Int): Float = values[index]

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

/** 画笔 (颜色/样式/线宽; Kotlin 属性生成 Android 同名 bean 签名 getColor/setStyle 等)。 */
open class Paint {

    var color: Int = -0x1000000
    var strokeWidth: Float = 0f
    var alpha: Int = 255
    var isAntiAlias: Boolean = false
    var textSize: Float = 16f
    var style: Style = Style.FILL

    enum class Style {
        FILL, STROKE, FILL_AND_STROKE,
    }
}

/** 整数矩形 (对齐 Android 公共字段面: Java 扩展按字段直读直写)。 */
class Rect {

    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    constructor()

    constructor(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun isEmpty(): Boolean = width() <= 0 || height() <= 0

    fun centerX(): Int = (left + right) / 2

    fun centerY(): Int = (top + bottom) / 2

    fun exactCenterX(): Float = (left + right) / 2f

    fun exactCenterY(): Float = (top + bottom) / 2f

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(src: Rect) = set(src.left, src.top, src.right, src.bottom)

    fun setEmpty() = set(0, 0, 0, 0)

    fun inset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right -= dx
        bottom -= dy
    }

    fun offset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun contains(r: Rect): Boolean =
        r.left >= left && r.top >= top && r.right <= right && r.bottom <= bottom

    override fun toString(): String = "Rect($left, $top, $right, $bottom)"
}

/** 浮点矩形 (字段直读直写, 对齐 Android)。 */
class RectF {

    @JvmField var left: Float = 0f
    @JvmField var top: Float = 0f
    @JvmField var right: Float = 0f
    @JvmField var bottom: Float = 0f

    constructor()

    constructor(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    constructor(rect: Rect) {
        left = rect.left.toFloat()
        top = rect.top.toFloat()
        right = rect.right.toFloat()
        bottom = rect.bottom.toFloat()
    }

    fun width(): Float = right - left

    fun height(): Float = bottom - top

    fun isEmpty(): Boolean = width() <= 0f || height() <= 0f

    fun centerX(): Float = (left + right) / 2f

    fun centerY(): Float = (top + bottom) / 2f

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(src: RectF) = set(src.left, src.top, src.right, src.bottom)

    fun set(src: Rect) = set(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat())

    fun setEmpty() = set(0f, 0f, 0f, 0f)

    fun inset(dx: Float, dy: Float) {
        left += dx
        top += dy
        right -= dx
        bottom -= dy
    }

    fun offset(dx: Float, dy: Float) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    fun round(dst: Rect) = dst.set(
        left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt(),
    )

    /** 对齐 Android RectF.roundOut: left/top 向下取整, right/bottom 向上取整 (向外扩)。 */
    fun roundOut(dst: Rect) = dst.set(
        floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt(),
    )

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}

/** 颜色工具 (扩展按静态方法调用)。 */
object Color {

    const val BLACK = -0x1000000
    const val DKGRAY = -0xbbbbbc
    const val GRAY = -0x777778
    const val LTGRAY = -0x333334
    const val WHITE = -0x1
    const val RED = -0x10000
    const val GREEN = -0xff0100
    const val BLUE = -0xffff01
    const val YELLOW = -0x100
    const val CYAN = -0xff0101
    const val MAGENTA = -0xff01
    const val TRANSPARENT = 0

    @JvmStatic
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @JvmStatic
    fun rgb(r: Int, g: Int, b: Int): Int = argb(0xFF, r, g, b)

    @JvmStatic
    fun alpha(color: Int): Int = color ushr 24

    @JvmStatic
    fun red(color: Int): Int = color shr 16 and 0xFF

    @JvmStatic
    fun green(color: Int): Int = color shr 8 and 0xFF

    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF

    @JvmStatic
    fun parseColor(colorString: String): Int {
        if (colorString.startsWith("#")) {
            val raw = colorString.substring(1)
            return when (raw.length) {
                3 -> {
                    val r = Integer.parseInt(raw.substring(0, 1), 16) * 17
                    val g = Integer.parseInt(raw.substring(1, 2), 16) * 17
                    val b = Integer.parseInt(raw.substring(2, 3), 16) * 17
                    rgb(r, g, b)
                }

                6 -> -0x1000000 or raw.toInt(16)
                8 -> raw.toInt(16)
                else -> throw IllegalArgumentException("Unknown color: $colorString")
            }
        }
        throw IllegalArgumentException("Unknown color: $colorString")
    }
}

/** 字体句柄占位 (文本绘制面未覆盖, 仅保留类面与常用常量满足链接)。 */
class Typeface {

    companion object {
        @JvmField val DEFAULT = Typeface()
        @JvmField val DEFAULT_BOLD = Typeface()
        @JvmField val MONOSPACE = Typeface()
        @JvmField val SANS_SERIF = Typeface()
        @JvmField val SERIF = Typeface()

        @JvmStatic
        fun create(family: Typeface?, style: Int): Typeface = Typeface()

        @JvmStatic
        fun create(familyName: String?, style: Int): Typeface = Typeface()

        @JvmStatic
        fun defaultFromStyle(style: Int): Typeface = Typeface()
    }
}
