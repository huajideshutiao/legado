package io.legado.app.help.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 解码后的位图按长边缩放 (双线性, Compose Canvas 绘制到新位图), iOS/鸿蒙共用。
 *
 * 两端都无解码前采样 API, 只省常驻内存与绘制带宽, 解码峰值内存不变;
 * 缩放失败 (如 CPF 鸿蒙 raster Canvas 桥未接) 退回原位图, 不影响加载。
 */
internal fun ImageBitmap.downscaled(maxDim: Int): ImageBitmap {
    val max = maxOf(width, height)
    if (max <= maxDim) return this
    val scale = maxDim.toFloat() / max
    val nw = (width * scale).toInt().coerceAtLeast(1)
    val nh = (height * scale).toInt().coerceAtLeast(1)
    return runCatching {
        val out = ImageBitmap(nw, nh)
        val canvas = Canvas(out)
        canvas.drawImageRect(
            image = this,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(width, height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(nw, nh),
            paint = Paint().apply { filterQuality = FilterQuality.Low },
        )
        out
    }.getOrDefault(this)
}
