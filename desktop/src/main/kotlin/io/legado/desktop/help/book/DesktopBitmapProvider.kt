package io.legado.desktop.help.book

import io.legado.app.model.fileBook.BitmapProvider
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import java.io.File
import java.io.InputStream

/**
 * [BitmapProvider] 桌面 JVM 实现 (基于 Skia 原生高性能编解码)。
 *
 * 用 Skia [Image.makeFromEncoded] 解码图片并使用 [Image.encodeToData] 写入 JPEG,
 * 替代陈旧的 `ImageIO`。全格式原生支持 (WebP/GIF/PNG/JPG/BMP), 零 SPI 查找开销。
 *
 * 注册: desktop `Main.kt` 中 `BitmapProviders.register(DesktopBitmapProvider)`。
 */
object DesktopBitmapProvider : BitmapProvider {

    override fun decodeStreamAndCompressToJpeg(
        input: InputStream,
        outFile: File,
        quality: Int
    ): Boolean {
        return runCatching {
            val bytes = input.use { it.readBytes() }
            if (bytes.isEmpty()) return false
            val image = Image.makeFromEncoded(bytes)
            image.use { img ->
                val jpegData = img.encodeToData(
                    EncodedImageFormat.JPEG,
                    quality.coerceIn(0, 100)
                ) ?: return false
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(jpegData.bytes)
                true
            }
        }.getOrDefault(false)
    }
}

