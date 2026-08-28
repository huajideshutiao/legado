package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.use

/**
 * Skiko 三端的 Skia Codec 动图实现 (desktop/iOS/鸿蒙)。
 *
 * 单个复用 Bitmap 逐帧解码，传入上一帧索引让 Skia 处理 GIF/WebP 的增量帧合成；每帧
 * 立刻转成独立 Image 快照，避免复用 Bitmap 后污染已产出的帧。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = runCatching {
    if (bytes.isEmpty()) return null
    val data = Data.makeFromBytes(bytes)
    val codec = try {
        Codec.makeFromData(data)
    } finally {
        data.close()
    }
    codec.use { c ->
        val frameCount = c.frameCount
        if (frameCount <= 1) return null
        val info = c.imageInfo
        if (info.width <= 0 || info.height <= 0) return null
        if (info.width.toLong() * info.height * frameCount > MAX_ANIMATED_PIXELS) return null

        // 强制 N32 PREMUL，保证透明增量帧在 GIF/WebP 合成时与前一帧使用同一像素格式。
        val decodeInfo = ImageInfo.makeN32(info.width, info.height, ColorAlphaType.PREMUL)
        val frames = ArrayList<ImageBitmap>(frameCount)
        val durations = IntArray(frameCount)
        val bitmap = Bitmap()
        try {
            if (!bitmap.allocPixels(decodeInfo)) return null
            for (i in 0 until frameCount) {
                c.readPixels(bitmap, i, i - 1)
                Image.makeFromBitmap(bitmap).use { image ->
                    frames += image.toComposeImageBitmap()
                }
                durations[i] = c.getFrameInfo(i).duration
                    .takeIf { it > 0 }
                    ?: DEFAULT_FRAME_DURATION_MS
            }
        } finally {
            bitmap.close()
        }
        AnimatedFrames(frames, durations, c.repetitionCount)
    }
}.getOrNull()

private const val DEFAULT_FRAME_DURATION_MS = 100
