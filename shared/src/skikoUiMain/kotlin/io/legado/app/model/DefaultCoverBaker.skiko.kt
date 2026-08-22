package io.legado.app.model

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * 默认封面烘焙的 Skiko 实现 (jvm / iOS / 鸿蒙 三端共用一份)。
 *
 * Skia 解码 → Surface 裁剪缩放绘制 → WEBP q85 (skiko 自带 webp 编解码器;
 * JVM 的 ImageIO 生态无 webp writer, Android 无 skiko 故另有 Bitmap actual)。
 * Surface 离屏渲染同 ImageBitmapLoader 的 SVG 兜底路径。
 */
internal actual fun bakeDefaultCoverBytes(
    sourceBytes: ByteArray,
    ratio: BookCoverShared.CoverRatio,
): ByteArray? = runCatching {
    val src = Image.makeFromEncoded(sourceBytes)
    val tw = ratio.bakeW
    val th = ratio.bakeH
    val alignY = if (ratio == BookCoverShared.CoverRatio.VIDEO) 0f else 0.5f
    // max 比例缩放铺满目标, 反推源图裁剪区 (x 居中, y 按 alignY 对齐)
    val scale = maxOf(tw.toFloat() / src.width, th.toFloat() / src.height)
    val cropW = tw / scale
    val cropH = th / scale
    val sx = (src.width - cropW) / 2f
    val sy = (src.height - cropH) * alignY
    val surface = Surface.makeRasterN32Premul(tw, th)
    surface.canvas.drawImageRect(
        src,
        Rect(sx, sy, sx + cropW, sy + cropH),
        Rect(0f, 0f, tw.toFloat(), th.toFloat()),
    )
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 85)?.bytes
}.getOrNull()
