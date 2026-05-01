@file:Suppress("unused")

package io.legado.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
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


@Suppress("WeakerAccess", "MemberVisibilityCanBePrivate")
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
     * @param width  想要显示的图片的宽度
     * @param height 想要显示的图片的高度
     * @return
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        width: Int? = null,
        height: Int? = null
    ): Int {
        //获取比例大小
        val wRatio = width?.let { options.outWidth / it } ?: -1
        val hRatio = height?.let { options.outHeight / it } ?: -1
        //如果超出指定大小，则缩小相应的比例
        return when {
            wRatio > 1 && hRatio > 1 -> max(wRatio, hRatio)
            wRatio > 1 -> wRatio
            hRatio > 1 -> hRatio
            else -> 1
        }
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
        var inputStream = context.assets.open(fileNameInAssets)
        return inputStream.use {
            val op = BitmapFactory.Options()
            // inJustDecodeBounds如果设置为true,仅仅返回图片实际的宽和高,宽和高是赋值给opts.outWidth,opts.outHeight;
            op.inJustDecodeBounds = true
            BitmapFactory.decodeStream(inputStream, null, op) //获取尺寸信息
            op.inSampleSize = calculateInSampleSize(op, width, height)
            inputStream = context.assets.open(fileNameInAssets)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeStream(inputStream, null, op)
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
    recycle()
    return bitmap
}

/**
 * 获取图片代表性颜色 (近邻采样 + 饱和度提升)
 */
fun Bitmap.getRepresentativeColor(): Int {
    val w = width
    val h = height

    val samplingCount = 25
    val stepX = (w / samplingCount).coerceAtLeast(1)
    val stepY = (h / samplingCount).coerceAtLeast(1)

    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0

    val hsl = FloatArray(3)

    // 步进采样，避免创建临时 Bitmap
    for (y in 0 until h step stepY) {
        for (x in 0 until w step stepX) {
            val pixel = this[x, y]
            val a = (pixel shr 24) and 0xFF
            if (a < 128) continue // 过滤透明部分

            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // 简单的效果优化：计算该像素的鲜艳度
            // 只有当像素不是纯黑、纯白或纯灰色时，才赋予更高的权重，或者直接过滤
            ColorUtils.RGBToHSL(r, g, b, hsl)

            // 过滤：饱和度太低 (灰) 或 亮度太高/太低 (黑白) 的像素不计入代表色
            if (hsl[1] < 0.1f || hsl[2] < 0.1f || hsl[2] > 0.9f) continue

            rSum += r
            gSum += g
            bSum += b
            count++
        }
    }

    // 如果没有采到有效颜色（比如全是黑白），则重新以低要求采样或取中心点
    if (count == 0) {
        val centerPixel = this[w / 2, h / 2]
        return centerPixel
    }

    return Color.rgb(
        (rSum / count).toInt(),
        (gSum / count).toInt(),
        (bSum / count).toInt()
    )
}

fun Bitmap.stackBlur(radius: Int = 20, maxShortSide: Int = 400, view: View? = null): Bitmap {
    if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.post {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(
                    radius.toFloat(),
                    radius.toFloat(),
                    Shader.TileMode.CLAMP
                )
            )
        }
        return this
    }
    val r = radius.coerceIn(1, 100)
    val originalWidth = width
    val originalHeight = height

    // 1. 降采样：缩小图片以提升性能和模糊感
    val shortSide = min(originalWidth, originalHeight)
    val workingBitmap = if (shortSide > maxShortSide) {
        val scale = maxShortSide.toFloat() / shortSide
        this.scale((originalWidth * scale).toInt(), (originalHeight * scale).toInt(), false)
    } else {
        this.copy(config ?: Config.ARGB_8888, true)
    }

    val w = workingBitmap.width
    val h = workingBitmap.height
    val pix = IntArray(w * h)
    workingBitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = r + r + 1

    val rSumArr = IntArray(wh)
    val gSumArr = IntArray(wh)
    val bSumArr = IntArray(wh)
    val vmin = IntArray(max(w, h))

    val divSum = (div + 1 shr 1) * (div + 1 shr 1)
    val dv = IntArray(256 * divSum)
    for (i in 0 until 256 * divSum) dv[i] = i / divSum

    var yw = 0
    var yi = 0
    val stack = Array(div) { IntArray(3) }
    var stackPointer: Int
    var stackStart: Int
    var sir: IntArray
    var rbs: Int
    val r1 = r + 1
    var rOutSum: Int
    var gOutSum: Int
    var bOutSum: Int
    var rInSum: Int
    var gInSum: Int
    var bInSum: Int
    var rSum: Int
    var gSum: Int
    var bSum: Int

    for (y in 0 until h) {
        rInSum = 0; gInSum = 0; bInSum = 0
        rOutSum = 0; gOutSum = 0; bOutSum = 0
        rSum = 0; gSum = 0; bSum = 0
        for (i in -r..r) {
            val p = pix[yi + min(wm, max(i, 0))]
            sir = stack[i + r]
            sir[0] = p shr 16 and 0xff
            sir[1] = p shr 8 and 0xff
            sir[2] = p and 0xff
            rbs = r1 - abs(i)
            rSum += sir[0] * rbs
            gSum += sir[1] * rbs
            bSum += sir[2] * rbs
            if (i > 0) {
                rInSum += sir[0]
                gInSum += sir[1]
                bInSum += sir[2]
            } else {
                rOutSum += sir[0]
                gOutSum += sir[1]
                bOutSum += sir[2]
            }
        }
        stackPointer = r

        for (x in 0 until w) {
            rSumArr[yi] = dv[rSum]
            gSumArr[yi] = dv[gSum]
            bSumArr[yi] = dv[bSum]

            rSum -= rOutSum
            gSum -= gOutSum
            bSum -= bOutSum

            stackStart = stackPointer - r + div
            sir = stack[stackStart % div]

            rOutSum -= sir[0]
            gOutSum -= sir[1]
            bOutSum -= sir[2]

            if (y == 0) vmin[x] = min(x + r + 1, wm)
            val p = pix[yw + vmin[x]]

            sir[0] = p shr 16 and 0xff
            sir[1] = p shr 8 and 0xff
            sir[2] = p and 0xff

            rInSum += sir[0]
            gInSum += sir[1]
            bInSum += sir[2]

            rSum += rInSum
            gSum += gInSum
            bSum += bInSum

            stackPointer = (stackPointer + 1) % div
            sir = stack[stackPointer % div]

            rOutSum += sir[0]
            gOutSum += sir[1]
            bOutSum += sir[2]

            rInSum -= sir[0]
            gInSum -= sir[1]
            bInSum -= sir[2]

            yi++
        }
        yw += w
    }

    for (x in 0 until w) {
        rInSum = 0; gInSum = 0; bInSum = 0
        rOutSum = 0; gOutSum = 0; bOutSum = 0
        rSum = 0; gSum = 0; bSum = 0
        var yp = -r * w
        for (i in -r..r) {
            yi = max(0, yp) + x
            sir = stack[i + r]
            sir[0] = rSumArr[yi]
            sir[1] = gSumArr[yi]
            sir[2] = bSumArr[yi]
            rbs = r1 - abs(i)
            rSum += rSumArr[yi] * rbs
            gSum += gSumArr[yi] * rbs
            bSum += bSumArr[yi] * rbs
            if (i > 0) {
                rInSum += sir[0]
                gInSum += sir[1]
                bInSum += sir[2]
            } else {
                rOutSum += sir[0]
                gOutSum += sir[1]
                bOutSum += sir[2]
            }
            if (i < hm) yp += w
        }
        yi = x
        stackPointer = r
        for (y in 0 until h) {
            pix[yi] = -0x1000000 and pix[yi] or (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

            rSum -= rOutSum
            gSum -= gOutSum
            bSum -= bOutSum

            stackStart = stackPointer - r + div
            sir = stack[stackStart % div]

            rOutSum -= sir[0]
            gOutSum -= sir[1]
            bOutSum -= sir[2]

            if (x == 0) vmin[y] = min(y + r1, hm) * w
            val p = x + vmin[y]

            sir[0] = rSumArr[p]
            sir[1] = gSumArr[p]
            sir[2] = bSumArr[p]

            rInSum += sir[0]
            gInSum += sir[1]
            bInSum += sir[2]

            rSum += rInSum
            gSum += gInSum
            bSum += bInSum

            stackPointer = (stackPointer + 1) % div
            sir = stack[stackPointer % div]

            rOutSum += sir[0]
            gOutSum += sir[1]
            bOutSum += sir[2]

            rInSum -= sir[0]
            gInSum -= sir[1]
            bInSum -= sir[2]

            yi += w
        }
    }

    workingBitmap.setPixels(pix, 0, w, 0, 0, w, h)

    // 2. 还原：拉伸回原图大小，由于是线性拉伸，模糊感会更自然
    return if (originalWidth != w || originalHeight != h) {
        val result = workingBitmap.scale(originalWidth, originalHeight)
        workingBitmap.recycle()
        result
    } else {
        workingBitmap
    }
}

