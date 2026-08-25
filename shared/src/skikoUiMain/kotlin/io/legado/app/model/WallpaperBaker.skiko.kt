package io.legado.app.model

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntRect
import io.legado.app.help.FileUtilsCommon
import io.legado.app.utils.ScreenInfoProviders
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.use
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Skiko 壁纸解码 (jvm / iOS / 鸿蒙 三端共用一份): 文件解码 → 按 Crop 覆盖尺寸重采样 →
 * (radius>0 时) 离屏 Surface 套 `ImageFilter.makeBlur` 高斯模糊 → 新位图。
 * Surface 离屏渲染同 ImageBitmapLoader 的 SVG 兜底路径 (纯 skiko common API)。
 *
 * 模糊路径 (兜底重烘焙) 另受 [blurWorkMaxShortSide] 约束: 桌面按覆盖尺寸,
 * iOS/鸿蒙压到短边 800 免卡。
 */
internal actual fun decodeWallpaper(
    path: String,
    widthPx: Int,
    heightPx: Int,
    radiusPx: Int,
): ImageBitmap? = runCatching {
    val bytes = FileUtilsCommon.readBytes(path) ?: return@runCatching null
    val src = Image.makeFromEncoded(bytes)
    // Crop 的缩放比是 max(W/sw, H/sh); 按它解码出的图恰好铺满窗口, 绘制端 1:1 不再重采样。
    // 只缩不放 (原图比窗口小就保持原尺寸, 放大交给绘制端, 省一份大位图)。
    val cover = min(
        1f,
        maxOf(
            widthPx.coerceAtLeast(1).toFloat() / src.width,
            heightPx.coerceAtLeast(1).toFloat() / src.height,
        ),
    )
    val scale = if (radiusPx > 0) min(cover, src.blurWorkScale()) else cover
    src.drawScaled(
        (src.width * scale).roundToInt(),
        (src.height * scale).roundToInt(),
        radiusPx,
    ).toComposeImageBitmap()
}.getOrNull()

internal actual fun bakeBlurredImageFile(
    srcPath: String,
    destPath: String,
    radiusPx: Int,
): Boolean = runCatching {
    val bytes = FileUtilsCommon.readBytes(srcPath) ?: return@runCatching false
    val src = Image.makeFromEncoded(bytes)
    val cap = blurWorkMaxShortSide
    val encoded = if (cap > 0) {
        // 移动端: 先按屏幕宽高比居中裁剪 (显示端 Crop 的构图以屏幕比例为基准), 裁剪区
        // 短边压到 cap 后模糊。屏幕尺寸经 ScreenInfoProviders (iOS nativeBounds / 鸿蒙注入
        // 显示物理像素, 均为全屏)
        val si = ScreenInfoProviders.get()
        val aspect = si.screenWidthPx.coerceAtLeast(1).toFloat() /
            si.screenHeightPx.coerceAtLeast(1)
        val crop = centerCropRect(src.width, src.height, aspect)
        val workScale = min(1f, cap.toFloat() / min(crop.width, crop.height).coerceAtLeast(1))
        src.drawScaled(
            (crop.width * workScale).roundToInt().coerceAtLeast(1),
            (crop.height * workScale).roundToInt().coerceAtLeast(1),
            radiusPx,
            crop,
        )
    } else {
        // 桌面: 原图尺寸模糊 (窗口比例随时可变, 不预裁剪)
        val scale = src.blurWorkScale()
        src.drawScaled(
            (src.width * scale).roundToInt(),
            (src.height * scale).roundToInt(),
            radiusPx,
        )
    }.encodeToData(EncodedImageFormat.WEBP, 80)?.bytes
        ?: return@runCatching false
    FileUtilsCommon.writeBytes(destPath, encoded)
}.getOrNull() ?: false

/**
 * 清晰烘焙: 按 [maxW]:[maxH] 比例居中裁剪 + 等比缩放到不超出目标框
 * (min(1,·) 不放大, 放大交给显示端 Crop), WEBP q80 写盘。选图导入时一次性产出,
 * 启动图与主题背景图共用。
 */
actual fun bakeCoverImageFile(
    srcPath: String,
    destPath: String,
    maxW: Int,
    maxH: Int,
): Boolean = runCatching {
    val bytes = FileUtilsCommon.readBytes(srcPath) ?: return@runCatching false
    val src = Image.makeFromEncoded(bytes)
    val crop = centerCropRect(
        src.width, src.height,
        maxW.coerceAtLeast(1).toFloat() / maxH.coerceAtLeast(1),
    )
    val scale = minOf(
        1f,
        maxW.coerceAtLeast(1).toFloat() / crop.width,
        maxH.coerceAtLeast(1).toFloat() / crop.height,
    )
    val out = src.drawScaled(
        (crop.width * scale).roundToInt().coerceAtLeast(1),
        (crop.height * scale).roundToInt().coerceAtLeast(1),
        0,
        crop,
    )
    val encoded = out.encodeToData(EncodedImageFormat.WEBP, 80)?.bytes
        ?: return@runCatching false
    FileUtilsCommon.writeBytes(destPath, encoded)
}.getOrNull() ?: false

internal actual fun probeDecodeImage(bytes: ByteArray): Boolean =
    // makeFromEncoded 解不开时抛 IllegalArgumentException (require ptr != NullPointer)
    runCatching { Image.makeFromEncoded(bytes).use { it.width > 0 } }.getOrDefault(false)

/** 模糊工作图的缩放上限, 1f = 不降采样 (见 [blurWorkMaxShortSide])。 */
private fun Image.blurWorkScale(): Float {
    val cap = blurWorkMaxShortSide
    if (cap <= 0) return 1f
    return min(1f, cap.toFloat() / min(width, height).coerceAtLeast(1))
}

/**
 * 离屏 Surface 把 [crop] 区 (缺省整张图) 画到 [w]×[h], [radiusPx] > 0 时叠高斯模糊。
 *
 * 缩放显式给 [SamplingMode.MITCHELL]: 省略 samplingMode 的 `drawImageRect` 重载用的是
 * `SamplingMode.DEFAULT` = FilterMipmap(NEAREST, NONE), 即最近邻抽点, 缩小必起锯齿。
 */
private fun Image.drawScaled(w: Int, h: Int, radiusPx: Int, crop: IntRect? = null): Image {
    val dw = w.coerceAtLeast(1)
    val dh = h.coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(dw, dh)
    // 源矩形必须是实际采样区: drawImage/drawImageRect 的源矩形给成目标尺寸只会取左上角一块
    val srcRect = if (crop == null) {
        Rect.makeWH(width.toFloat(), height.toFloat())
    } else {
        Rect.makeXYWH(
            crop.left.toFloat(), crop.top.toFloat(),
            crop.width.toFloat(), crop.height.toFloat(),
        )
    }
    surface.canvas.drawImageRect(
        this,
        srcRect,
        Rect.makeWH(dw.toFloat(), dh.toFloat()),
        SamplingMode.MITCHELL,
        if (radiusPx > 0) blurPaint(radiusPx) else null,
        true,
    )
    return surface.makeImageSnapshot()
}

/** sigma 取半径一半 (标准高斯-盒式近似换算); radiusPx 是像素半径, 不随图尺寸归一化。 */
private fun blurPaint(radiusPx: Int): Paint = Paint().apply {
    val sigma = (radiusPx / 2f).coerceAtLeast(0.5f)
    imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
}
