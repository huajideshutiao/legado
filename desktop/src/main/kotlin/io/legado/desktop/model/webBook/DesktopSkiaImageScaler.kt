package io.legado.desktop.model.webBook

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use

/**
 * Skia 超宽等比缩图策略 (注入 [DesktopImageControllerProvider])。
 *
 * width 参数: 仅当图片实际宽度超过 width 时等比缩到 width (对照 app 端
 * ImageProvider.getImage 的缩图语义), 输出 PNG (与 app 端 getImg 的 PNG 编码一致);
 * 不超宽/不可解码时原样返回缓存字节。
 *
 * skia 依赖只在本文件 —— 抽出它是为了让 DesktopImageControllerProvider 可下沉
 * desktop-core (headless 无 skia, 注入恒等策略)。
 */
object DesktopSkiaImageScaler : (ByteArray, Int) -> ByteArray {

    override fun invoke(bytes: ByteArray, width: Int): ByteArray {
        if (width <= 0) return bytes
        return runCatching {
            val image = Image.makeFromEncoded(bytes)
            if (image.width <= width) {
                image.close()
                return bytes
            }
            val height = (image.height.toLong() * width / image.width).toInt().coerceAtLeast(1)
            val result = Bitmap()
            result.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
            result.use { dst ->
                Canvas(dst).use { canvas ->
                    image.use { img ->
                        // 缩图必须给采样模式: 默认是最近邻, 缩下来全是锯齿
                        canvas.drawImageRect(
                            img,
                            Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                            Rect.makeWH(width.toFloat(), height.toFloat()),
                            SamplingMode.MITCHELL,
                            null,
                            true,
                        )
                    }
                }
                Image.makeFromBitmap(dst).use { scaled ->
                    scaled.encodeToData(EncodedImageFormat.PNG, 100)?.bytes ?: bytes
                }
            }
        }.getOrDefault(bytes)
    }
}
