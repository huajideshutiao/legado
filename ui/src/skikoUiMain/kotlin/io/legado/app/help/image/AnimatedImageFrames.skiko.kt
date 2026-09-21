package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.use

private const val DEFAULT_FRAME_DURATION_MS = 100

/**
 * 统一图像解码器 (单通道无二次解析, 由 Skia Codec 自身 frameCount API 权威判定动静图)。
 *
 * - 动图 (frameCount > 1 且未超像素预算): 逐帧解码输出 [DecodedImageResult.Animated]
 * - 静态图 / 超预算动图: 复用当前 Codec 直接读第 0 帧输出 [DecodedImageResult.Static]
 * - 非栅格/SVG 兜底: 尝试 [decodeSvgFallback] 输出 [DecodedImageResult.Static]
 *
 * [Codec] / [Bitmap] / [Image] 都是原生对象, 一个 use 作用域内从头用到尾:
 * 已 close 的 Codec 再取 imageInfo/readPixels 会拿 nullptr 进原生代码, 直接 JVM 级崩溃。
 *
 * @param animatedOnly 只要动图: 静态图立即返回 null, 既不解像素也不走 SVG 兜底
 *   (供 [decodeAnimatedFrames], 避免静态图白解一遍全图)
 */
private fun decodeSkiaImage(
    bytes: ByteArray,
    animatedOnly: Boolean,
): DecodedImageResult? {
    if (bytes.isEmpty()) return null
    val result = runCatching {
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                val info = codec.imageInfo
                if (info.width <= 0 || info.height <= 0) return@runCatching null
                val frameCount = codec.frameCount
                val decodeInfo = ImageInfo.makeN32(info.width, info.height, ColorAlphaType.PREMUL)
                val animated = frameCount > 1 &&
                    info.width.toLong() * info.height * frameCount <= MAX_ANIMATED_PIXELS
                when {
                    animated -> {
                        val frames = ArrayList<ImageBitmap>(frameCount)
                        val durations = IntArray(frameCount)
                        // 帧间混合要求目标位图已含上一帧, 故逐帧解到同一张暂存位图再取快照
                        Bitmap().use { scratch ->
                            if (!scratch.allocPixels(decodeInfo)) return@runCatching null
                            for (i in 0 until frameCount) {
                                codec.readPixels(scratch, i, i - 1)
                                Image.makeFromBitmap(scratch).use { frame ->
                                    frames += frame.toComposeImageBitmap()
                                }
                                durations[i] = codec.getFrameInfo(i).duration
                                    .takeIf { it > 0 }
                                    ?: DEFAULT_FRAME_DURATION_MS
                            }
                        }
                        DecodedImageResult.Animated(
                            AnimatedFrames(frames, durations, codec.repetitionCount)
                        )
                    }

                    animatedOnly -> null

                    else -> {
                        // 静态图: 直接解进位图, 转不可变后把所有权交给 ImageBitmap —
                        // 零中间拷贝, 且不可变位图每次绘制能复用同一张纹理 (可变位图会全图重拷)
                        val bitmap = Bitmap()
                        if (!bitmap.allocPixels(decodeInfo)) return@runCatching null
                        codec.readPixels(bitmap, 0, -1)
                        bitmap.setImmutable()
                        DecodedImageResult.Static(bitmap.asComposeImageBitmap())
                    }
                }
            }
        }
    }.getOrNull()

    if (result != null || animatedOnly) return result
    // 栅格 Codec 无法解析时尝试 SVG 兜底 (例如矢量图)
    return decodeSvgFallback(bytes, maxDim = 0)?.let { DecodedImageResult.Static(it) }
}

/**
 * Skiko 三端的 Skia Codec 动图实现 (desktop/iOS/鸿蒙)。
 *
 * 复用 [decodeImageAuto] 统一解码结果, 仅提取动图帧表; 静态图或非动图返回 null。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? =
    (decodeSkiaImage(bytes, animatedOnly = true) as? DecodedImageResult.Animated)?.frames

/** 动静图合流解码 (原生尺寸); 见 sharedUiMain 的 expect 注释。 */
internal actual fun decodeImageAuto(bytes: ByteArray): DecodedImageResult? =
    decodeSkiaImage(bytes, animatedOnly = false)
