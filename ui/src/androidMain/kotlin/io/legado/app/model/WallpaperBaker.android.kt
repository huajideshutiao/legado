package io.legado.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import io.legado.app.help.FileUtilsCommon
import io.legado.app.model.AndroidRealScreen.register
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Android StackBlur 是纯 CPU 两趟遍历, 工作图压到短边 [BAKE_MAX_SHORT_SIDE] 才不卡。 */
internal actual val blurWorkMaxShortSide: Int = BAKE_MAX_SHORT_SIDE

/**
 * Android ScreenInfoProvider 实现: 真实屏幕全屏物理像素 (含状态栏/cutout),
 * 与 iOS nativeBounds / 鸿蒙显示物理像素 / 桌面 Toolkit.screenSize 对齐
 * (壁纸/启动图烘焙与各端对话框尺寸统一锚定全屏)。
 * [AndroidRealScreen] 未注册 context 时退系统级 displayMetrics (同尺寸语义, 仅丢状态栏精度)。
 */
object AndroidRealScreenInfoProvider : io.legado.app.utils.ScreenInfoProvider {
    override val screenWidthPx: Int get() = AndroidRealScreen.size()?.first ?: 0
    override val screenHeightPx: Int get() = AndroidRealScreen.size()?.second ?: 0
}

internal actual fun probeDecodeImage(bytes: ByteArray): Boolean {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return opts.outWidth > 0 && opts.outHeight > 0
}

/** bounds 读原图尺寸并算居中裁剪区; 尺寸非法返回 null。 */
private fun readAndCrop(srcPath: String, aspect: Float): IntRect? {
    val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(srcPath, op)
    if (op.outWidth <= 0 || op.outHeight <= 0) return null
    return centerCropRect(op.outWidth, op.outHeight, aspect)
}

/**
 * 区域解码 + 精确缩放核心 (启动图烘焙与壁纸模糊烘焙共用): 只加载 [crop] 区
 * (inSampleSize 2^n 保证解码结果不小于 [targetW]×[targetH]), 再精确缩到位
 * (2^n 与目标有半档误差)。调用方保证 crop 与 target 等比。
 */
private fun decodeRegionTo(srcPath: String, crop: IntRect, targetW: Int, targetH: Int): Bitmap? {
    val cropW = crop.width
    val cropH = crop.height
    var inSampleSize = 1
    while (cropW / (inSampleSize * 2) >= targetW && cropH / (inSampleSize * 2) >= targetH) {
        inSampleSize *= 2
    }
    val decoder = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(srcPath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(srcPath, false)
        }
    }.getOrNull() ?: return null
    val cropped = try {
        decoder.decodeRegion(
            Rect(crop.left, crop.top, crop.right, crop.bottom),
            BitmapFactory.Options().apply { this.inSampleSize = inSampleSize },
        )
    } finally {
        decoder.recycle()
    } ?: return null
    return if (cropped.width != targetW || cropped.height != targetH) {
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (scaled !== cropped) cropped.recycle()
        scaled
    } else {
        cropped
    }
}

/**
 * 按 [aspect] (w/h) 从 [srcPath] 居中裁剪后等比缩放到**不超出** [maxW]×[maxH]
 * (min(1,·): [aspect] 与目标框比例一致时, 裁剪区小于目标框就保持原尺寸不放大,
 * 放大交给显示端 Crop)。
 */
private fun decodeCenterCropTo(srcPath: String, aspect: Float, maxW: Int, maxH: Int): Bitmap? {
    val crop = readAndCrop(srcPath, aspect) ?: return null
    val scale = minOf(
        1f,
        maxW.coerceAtLeast(1).toFloat() / crop.width,
        maxH.coerceAtLeast(1).toFloat() / crop.height,
    )
    val targetW = (crop.width * scale).roundToInt().coerceAtLeast(1)
    val targetH = (crop.height * scale).roundToInt().coerceAtLeast(1)
    return decodeRegionTo(srcPath, crop, targetW, targetH)
}

/** WEBP q80 编码写盘; 该函数回收传入位图, 调用方不得再用。 */
@Suppress("DEPRECATION") // Bitmap.CompressFormat.WEBP: 无损/有损分档要 API 30, 这里保持全版本可用
private fun writeWebp(bitmap: android.graphics.Bitmap, destPath: String): Boolean {
    val encoded: ByteArray? = try {
        java.io.ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)) out.toByteArray() else null
        }
    } finally {
        bitmap.recycle()
    }
    if (encoded == null) return false
    // 经 FileUtilsCommon 落盘: 自动建父目录 (产物在缓存根子目录) 且不漏关流
    return FileUtilsCommon.writeBytes(destPath, encoded)
}

/**
 * Android 壁纸解码: BitmapFactory 按目标尺寸采样解码, [radiusPx] > 0 时叠 StackBlur
 * (算法原自 app 端 BitmapUtils.stackBlur, 该函数已删, 本文件为全项目唯一副本, 工作图短边压到 [BAKE_MAX_SHORT_SIDE])。
 */
internal actual fun decodeWallpaper(
    path: String,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
): ImageBitmap? = runCatching {
    val src = if (radiusPx > 0) {
        // 模糊路径解到工作图尺寸就够 (StackBlur 随后压到短边 800, 解更大只是白耗解码峰值)
        decodeSampled(path, BAKE_MAX_SHORT_SIDE, BAKE_MAX_SHORT_SIDE)
    } else {
        // 清晰路径按 Crop 覆盖窗口采样
        decodeSampled(path, widthPx, heightPx)
    }
    if (src == null) return@runCatching null
    if (radiusPx <= 0) return@runCatching src.asImageBitmap()
    val blurred = src.stackBlurBaked(radiusPx)
    if (blurred !== src) src.recycle()
    blurred.asImageBitmap()
}.getOrNull()

@Suppress("DEPRECATION") // Bitmap.CompressFormat.WEBP: 无损/有损分档要 API 30, 这里保持全版本可用
internal actual fun bakeBlurredImageFile(
    srcPath: String,
    destPath: String,
    radiusPx: Int,
): Boolean = runCatching {
    val working = decodeCroppedToWorkSize(srcPath)
        ?: return@runCatching false
    val blurred = working.stackBlurBaked(radiusPx)
    val ok = writeWebp(blurred, destPath)
    if (blurred !== working) working.recycle()
    ok
}.getOrNull() ?: false

/**
 * 清晰烘焙: 按目标框比例居中裁剪 + 不放大缩放到 [maxW]×[maxH] (选图导入时一次性产出,
 * 启动图与主题背景图共用), WEBP q80 写盘。
 */
actual fun bakeCoverImageFile(
    srcPath: String,
    destPath: String,
    maxW: Int,
    maxH: Int,
): Boolean = runCatching {
    val decoded = decodeCenterCropTo(srcPath, maxW.toFloat() / maxH, maxW, maxH)
        ?: return@runCatching false
    writeWebp(decoded, destPath)
}.getOrNull() ?: false

/**
 * 解码模糊工作图: 先按真实屏幕宽高比从原图居中裁剪 (显示端 Crop 的构图以屏幕比例为基准,
 * 不预裁剪时产物比例跟原图走, 横图配竖屏 Crop 只能取中间一条且放大倍数失控), 再把裁剪区
 * 短边压到 [BAKE_MAX_SHORT_SIDE] (区域解码 inSampleSize + 精确缩放到目标尺寸)。
 * 屏幕尺寸取不到时退回整图采样解码 (旧路径)。
 */
private fun decodeCroppedToWorkSize(srcPath: String): Bitmap? {
    val screen = AndroidRealScreen.size()
        ?: return decodeSampled(srcPath, BAKE_MAX_SHORT_SIDE, BAKE_MAX_SHORT_SIDE)
    val (screenW, screenH) = screen
    // 按屏幕宽高比居中裁剪, 裁剪区短边压到工作图上限 (不放大)
    val crop = readAndCrop(srcPath, screenW.toFloat() / screenH) ?: return null
    val scale = minOf(1f, BAKE_MAX_SHORT_SIDE.toFloat() / min(crop.width, crop.height))
    val targetW = (crop.width * scale).roundToInt().coerceAtLeast(1)
    val targetH = (crop.height * scale).roundToInt().coerceAtLeast(1)
    return decodeRegionTo(srcPath, crop, targetW, targetH)
}

/**
 * 按 Crop 覆盖 [widthPx]×[heightPx] 采样解码 (inSampleSize 2^n, 采样后两维都不小于目标);
 * 目标给正方形时退化为"短边不小于边长"。
 */
private fun decodeSampled(path: String, widthPx: Int, heightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val tw = widthPx.coerceAtLeast(1)
    val th = heightPx.coerceAtLeast(1)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * StackBlur 统一入口: 半径钳 1..100, 短边 > [BAKE_MAX_SHORT_SIDE] 先降采样再模糊,
 * 返回工作图不放大。app 端原 BitmapUtils.stackBlur 下沉后的唯一副本 (glide 封面模糊
 * 与壁纸烘焙/兜底共用, 见 [BlurTransformation])。
 */
fun stackBlurBitmap(bitmap: android.graphics.Bitmap, radius: Int): android.graphics.Bitmap =
    bitmap.stackBlurBaked(radius)

/**
 * StackBlur 算法优化 (全项目唯一副本):
 * 短边 > [BAKE_MAX_SHORT_SIDE] 时先降采样再模糊, 直接返回工作图不放大
 * (模糊产物仅供显示端拉伸, 无需保持原尺寸白耗内存)。
 */
private fun android.graphics.Bitmap.stackBlurBaked(radius: Int): android.graphics.Bitmap {
    val r = radius.coerceIn(1, 100)
    val shortSide = minOf(width, height)
    val working = if (shortSide > BAKE_MAX_SHORT_SIDE) {
        val scale = BAKE_MAX_SHORT_SIDE.toFloat() / shortSide
        android.graphics.Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt(),
            (height * scale).toInt(),
            false,
        )
    } else {
        copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    }

    val w = working.width
    val h = working.height
    val wh = w * h
    val pix = IntArray(wh)
    working.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val div = r + r + 1
    val r1 = r + 1
    val divSum = r1 * r1

    val tempPix = IntArray(wh)
    val vmin = IntArray(maxOf(w, h))
    val dv = IntArray(256 * divSum) { it / divSum }
    val stack = IntArray(div * 3)

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
            val p = pix[yi + minOf(wm, maxOf(i, 0))]
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
            if (y == 0) vmin[x] = minOf(x + r1, wm)
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
            yi = maxOf(0, yp) + x
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
            if (x == 0) vmin[y] = minOf(y + r1, hm) * w
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
    working.setPixels(pix, 0, w, 0, 0, w, h)
    return working
}

/**
 * 安卓宿主启动早期注册 [AndroidRealScreen] 的 context (App.onCreate 调用,
 * 模式同 registerAndroidAppFilesDir)。
 */
fun registerAndroidRealScreen(context: Context) {
    AndroidRealScreen.register(context)
}

/**
 * Android 真实屏幕尺寸 (含状态栏/导航栏/cutout 的完整物理区), 供壁纸/启动图烘焙取全屏比例。
 *
 * [io.legado.app.utils.ScreenInfoProvider] 安卓实现读 `resources.displayMetrics`
 * (扣掉系统栏的应用可用区), 语义是"内容区"; 启动页与页面壁纸层都是 edge-to-edge,
 * 图要铺到状态栏后面, 原版 setCoverFromUri 用 `getRealMetrics` 正是全屏语义。
 *
 * App.onCreate 经 [register] 注入 context 后按需现查 (跟随旋转方向);
 * 未注册时退回系统级 displayMetrics (可用区, 只丢状态栏一条的精度), 保证可预期。
 */
internal object AndroidRealScreen {

    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
    }

    /** @return 屏幕宽高 (物理像素, 当前方向); 宽高非法时返回 null */
    fun size(): Pair<Int, Int>? {
        val wm = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                val w = bounds.width()
                val h = bounds.height()
                if (w > 0 && h > 0) return w to h
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                if (display != null) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        return metrics.widthPixels to metrics.heightPixels
                    }
                }
            }
        }
        val fallback = android.content.res.Resources.getSystem().displayMetrics
        if (fallback.widthPixels > 0 && fallback.heightPixels > 0) {
            return fallback.widthPixels to fallback.heightPixels
        }
        return null
    }
}
