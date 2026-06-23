package io.legado.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BitmapUtils {

    /**
     * 从path中获取图片信息,在通过BitmapFactory.decodeFile(String path)方法将突破转成Bitmap时，
     * 遇到大一些的图片，我们经常会遇到OOM(Out Of Memory)的问题。所以用到了我们上面提到的BitmapFactory.Options这个类。
     *
     * @param path   文件路径
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String, width: Int, height: Int? = null): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
            op.inSampleSize = calculateInSampleSize(op, width, height)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
        }
    }

    /**
     *计算 InSampleSize。缺省返回1
     * @param options BitmapFactory.Options,
     * @param reqWidth  想要显示的图片的宽度
     * @param reqHeight 想要显示的图片的高度
     * @return
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int? = null,
        reqHeight: Int? = null,
    ): Int {
        val h = options.outHeight
        val w = options.outWidth
        var inSampleSize = 1
        if (reqHeight != null && reqWidth != null) {
            if (h > reqHeight || w > reqWidth) {
                val halfHeight = h / 2
                val halfWidth = w / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        } else if (reqWidth != null) {
            if (w > reqWidth) {
                val halfWidth = w / 2
                while (halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        } else if (reqHeight != null && h > reqHeight) {
            val halfHeight = h / 2
            while (halfHeight / inSampleSize >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** 从path中获取Bitmap图片
     * @param path 图片路径
     * @return
     */
    @Throws(IOException::class)
    fun decodeBitmap(path: String): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true

            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
            opts.inSampleSize = computeSampleSize(opts, -1, 128 * 128)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
        }
    }

    /**
     * 以最省内存的方式读取本地资源的图片
     * @param context 设备上下文
     * @param resId 资源ID
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int): Bitmap? {
        val opt = BitmapFactory.Options()
        opt.inPreferredConfig = Config.RGB_565
        return BitmapFactory.decodeResource(context.resources, resId, opt)
    }

    /**
     * @param context 设备上下文
     * @param resId 资源ID
     * @param width
     * @param height
     * @return
     */
    fun decodeBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap? {
        val op = BitmapFactory.Options()
        // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
        op.inJustDecodeBounds = true
        BitmapFactory.decodeResource(context.resources, resId, op) //获取尺寸信息
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(context.resources, resId, op)
    }

    /**
     * @param context 设备上下文
     * @param fileNameInAssets Assets里面文件的名称
     * @param width 图片的宽度
     * @param height 图片的高度
     * @return Bitmap
     * @throws IOException
     */
    @Throws(IOException::class)
    fun decodeAssetsBitmap(
        context: Context,
        fileNameInAssets: String,
        width: Int,
        height: Int
    ): Bitmap? {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(fileNameInAssets).use {
            BitmapFactory.decodeStream(it, null, op)
        }
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return context.assets.open(fileNameInAssets).use {
            BitmapFactory.decodeStream(it, null, op)
        }
    }

    fun decodeBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
    }

    /**
     * @param options
     * @param minSideLength
     * @param maxNumOfPixels
     * @return
     * 设置恰当的inSampleSize是解决该问题的关键之一。BitmapFactory.Options提供了另一个成员inJustDecodeBounds。
     * 设置inJustDecodeBounds为true后，decodeFile并不分配空间，但可计算出原始图片的长度和宽度，即opts.width和opts.height。
     * 有了这两个参数，再通过一定的算法，即可得到一个恰当的inSampleSize。
     * 查看Android源码，Android提供了下面这种动态计算的方法。
     */
    fun computeSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {
        val initialSize = computeInitialSampleSize(options, minSideLength, maxNumOfPixels)
        var roundedSize: Int
        if (initialSize <= 8) {
            roundedSize = 1
            while (roundedSize < initialSize) {
                roundedSize = roundedSize shl 1
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8
        }
        return roundedSize
    }


    private fun computeInitialSampleSize(
        options: BitmapFactory.Options,
        minSideLength: Int,
        maxNumOfPixels: Int
    ): Int {

        val w = options.outWidth.toDouble()
        val h = options.outHeight.toDouble()

        val lowerBound = when (maxNumOfPixels) {
            -1 -> 1
            else -> ceil(sqrt(w * h / maxNumOfPixels)).toInt()
        }

        val upperBound = when (minSideLength) {
            -1 -> 128
            else -> min(
                floor(w / minSideLength),
                floor(h / minSideLength)
            ).toInt()
        }

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound
        }

        return when {
            maxNumOfPixels == -1 && minSideLength == -1 -> {
                1
            }

            minSideLength == -1 -> {
                lowerBound
            }

            else -> {
                upperBound
            }
        }
    }

    /**
     * 将Bitmap转换成InputStream
     *
     * @param bitmap
     * @return
     */
    fun toInputStream(bitmap: Bitmap): InputStream {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90 /*ignored for PNG*/, bos)
        return ByteArrayInputStream(bos.toByteArray()).also { bos.close() }
    }

}

/**
 * 获取指定宽高的图片
 */
fun Bitmap.resizeAndRecycle(newWidth: Int, newHeight: Int): Bitmap {
    val bitmap = this.scale(newWidth, newHeight)
    if (bitmap != this) recycle()
    return bitmap
}

/**
 * 根据目标宽高裁剪图片 (Center Crop)
 */
fun Bitmap.centerCrop(width: Int, height: Int): Bitmap {
    return cropTo(width, height, alignY = 0.5f)
}

/**
 * 顶部对齐裁剪 (Top Crop) -- 视频封面用,保留顶部信息,底部信息被裁掉。
 */
fun Bitmap.topCrop(width: Int, height: Int): Bitmap {
    return cropTo(width, height, alignY = 0f)
}

/**
 * @param alignY 0f=顶部对齐,0.5f=居中,1f=底部对齐
 */
private fun Bitmap.cropTo(width: Int, height: Int, alignY: Float): Bitmap {
    if (this.width == width && this.height == height) return this
    val result = createBitmap(width, height, config ?: Config.ARGB_8888)
    val canvas = Canvas(result)
    val scale = max(width.toFloat() / this.width, height.toFloat() / this.height)
    val dx = (width - this.width * scale) / 2f
    val dy = (height - this.height * scale) * alignY
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate(dx, dy)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    canvas.drawBitmap(this, matrix, paint)
    return result
}

/**
 * 获取图片代表性颜色 (支持区域裁剪 + 降采样 + 批量采样)
 */
fun Bitmap.getRepresentativeColor(
    left: Int = 0,
    top: Int = 0,
    regionWidth: Int = width,
    regionHeight: Int = height
): Int {
    val targetSize = 64
    val smallBitmap = if (regionWidth > targetSize || regionHeight > targetSize) {
        val scale = targetSize.toFloat() / max(regionWidth, regionHeight)
        val matrix = Matrix().apply { setScale(scale, scale) }
        // native 级别同时完成裁剪和缩放，效率最高
        Bitmap.createBitmap(this, left, top, regionWidth, regionHeight, matrix, true)
    } else if (left != 0 || top != 0 || regionWidth != width || regionHeight != height) {
        Bitmap.createBitmap(this, left, top, regionWidth, regionHeight)
    } else {
        this
    }

    val sw = smallBitmap.width
    val sh = smallBitmap.height
    val pixels = IntArray(sw * sh)
    smallBitmap.getPixels(pixels, 0, sw, 0, 0, sw, sh)

    if (smallBitmap !== this) smallBitmap.recycle()

    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    val hsl = FloatArray(3)

    for (pixel in pixels) {
        val a = (pixel shr 24) and 0xFF
        if (a < 128) continue // 过滤透明部分

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        ColorUtils.RGBToHSL(r, g, b, hsl)
        // 过滤：饱和度太低 (灰) 或 亮度太高/太低 (黑白) 的像素不计入代表色
        if (hsl[1] < 0.1f || hsl[2] < 0.1f || hsl[2] > 0.9f) continue

        rSum += r
        gSum += g
        bSum += b
        count++
    }

    // 如果没有采到有效颜色（比如全是黑白），则重新以低要求采样或取中心点
    if (count == 0) {
        return if (pixels.isNotEmpty()) pixels[pixels.size / 2] else Color.TRANSPARENT
    }

    return Color.rgb(
        (rSum / count).toInt(),
        (gSum / count).toInt(),
        (bSum / count).toInt()
    )
}

/**
 * StackBlur 算法优化
 */
fun Bitmap.stackBlur(radius: Int = 20, maxShortSide: Int = 400): Bitmap {
    val r = radius.coerceIn(1, 100)
    val originalWidth = width
    val originalHeight = height

    val shortSide = min(originalWidth, originalHeight)
    val workingBitmap = if (shortSide > maxShortSide) {
        val scale = maxShortSide.toFloat() / shortSide
        // 模糊降采样不需要开启 filter (最近邻)
        this.scale(
            (originalWidth * scale).toInt(),
            (originalHeight * scale).toInt(),
            filter = false
        )
    } else {
        this.copy(config ?: Config.ARGB_8888, true)
    }

    val w = workingBitmap.width
    val h = workingBitmap.height
    val wh = w * h
    val pix = IntArray(wh)
    workingBitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val div = r + r + 1
    val r1 = r + 1
    val divSum = r1 * r1

    val tempPix = IntArray(wh) // 内存优化：打包存储 RGB
    val vmin = IntArray(max(w, h))
    val dv = IntArray(256 * divSum) { it / divSum }
    val stack = IntArray(div * 3) // 扁平化数组，减少小对象创建

    var yi = 0
    for (y in 0 until h) {
        var rInSum = 0
        var gInSum = 0
        var bInSum = 0
        var rOutSum = 0
        var gOutSum = 0
        var bOutSum = 0
        var rSum = 0
        var gSum = 0
        var bSum = 0
        for (i in -r..r) {
            val p = pix[yi + min(wm, max(i, 0))]
            val sIdx = (i + r) * 3
            stack[sIdx] = p shr 16 and 0xff
            stack[sIdx + 1] = p shr 8 and 0xff
            stack[sIdx + 2] = p and 0xff
            val rbs = r1 - abs(i)
            rSum += stack[sIdx] * rbs
            gSum += stack[sIdx + 1] * rbs
            bSum += stack[sIdx + 2] * rbs
            if (i > 0) {
                rInSum += stack[sIdx]; gInSum += stack[sIdx + 1]; bInSum += stack[sIdx + 2]
            } else {
                rOutSum += stack[sIdx]; gOutSum += stack[sIdx + 1]; bOutSum += stack[sIdx + 2]
            }
        }
        var stackPointer = r
        for (x in 0 until w) {
            tempPix[yi] = (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
            rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum
            val sirIdx = ((stackPointer - r + div) % div) * 3
            rOutSum -= stack[sirIdx]; gOutSum -= stack[sirIdx + 1]; bOutSum -= stack[sirIdx + 2]
            if (y == 0) vmin[x] = min(x + r1, wm)
            val p = pix[y * w + vmin[x]]
            stack[sirIdx] = p shr 16 and 0xff
            stack[sirIdx + 1] = p shr 8 and 0xff
            stack[sirIdx + 2] = p and 0xff
            rInSum += stack[sirIdx]; gInSum += stack[sirIdx + 1]; bInSum += stack[sirIdx + 2]
            rSum += rInSum; gSum += gInSum; bSum += bInSum
            stackPointer = (stackPointer + 1) % div
            val nextSirIdx = stackPointer * 3
            rOutSum += stack[nextSirIdx]; gOutSum += stack[nextSirIdx + 1]; bOutSum += stack[nextSirIdx + 2]
            rInSum -= stack[nextSirIdx]; gInSum -= stack[nextSirIdx + 1]; bInSum -= stack[nextSirIdx + 2]
            yi++
        }
    }

    for (x in 0 until w) {
        var rInSum = 0
        var gInSum = 0
        var bInSum = 0
        var rOutSum = 0
        var gOutSum = 0
        var bOutSum = 0
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var yp = -r * w
        for (i in -r..r) {
            yi = max(0, yp) + x
            val sIdx = (i + r) * 3
            val pVal = tempPix[yi]
            stack[sIdx] = pVal shr 16 and 0xff
            stack[sIdx + 1] = pVal shr 8 and 0xff
            stack[sIdx + 2] = pVal and 0xff
            val rbs = r1 - abs(i)
            rSum += stack[sIdx] * rbs
            gSum += stack[sIdx + 1] * rbs
            bSum += stack[sIdx + 2] * rbs
            if (i > 0) {
                rInSum += stack[sIdx]; gInSum += stack[sIdx + 1]; bInSum += stack[sIdx + 2]
            } else {
                rOutSum += stack[sIdx]; gOutSum += stack[sIdx + 1]; bOutSum += stack[sIdx + 2]
            }
            if (i < hm) yp += w
        }
        yi = x
        var stackPointer = r
        for (y in 0 until h) {
            pix[yi] = (pix[yi] and -0x1000000) or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]
            rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum
            val sirIdx = ((stackPointer - r + div) % div) * 3
            rOutSum -= stack[sirIdx]; gOutSum -= stack[sirIdx + 1]; bOutSum -= stack[sirIdx + 2]
            if (x == 0) vmin[y] = min(y + r1, hm) * w
            val pVal = tempPix[x + vmin[y]]
            stack[sirIdx] = pVal shr 16 and 0xff
            stack[sirIdx + 1] = pVal shr 8 and 0xff
            stack[sirIdx + 2] = pVal and 0xff
            rInSum += stack[sirIdx]; gInSum += stack[sirIdx + 1]; bInSum += stack[sirIdx + 2]
            rSum += rInSum; gSum += gInSum; bSum += bInSum
            stackPointer = (stackPointer + 1) % div
            val nextSirIdx = stackPointer * 3
            rOutSum += stack[nextSirIdx]; gOutSum += stack[nextSirIdx + 1]; bOutSum += stack[nextSirIdx + 2]
            rInSum -= stack[nextSirIdx]; gInSum -= stack[nextSirIdx + 1]; bInSum -= stack[nextSirIdx + 2]
            yi += w
        }
    }
    workingBitmap.setPixels(pix, 0, w, 0, 0, w, h)

    return if (originalWidth != w || originalHeight != h) {
        workingBitmap.resizeAndRecycle(originalWidth, originalHeight)
    } else {
        workingBitmap
    }
}

