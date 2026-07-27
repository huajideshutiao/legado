package io.legado.desktop.help.book

import io.legado.app.model.fileBook.BitmapProvider
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * [BitmapProvider] 桌面 JVM 实现。
 *
 * 用 `javax.imageio.ImageIO.read` 解码图片, `ImageIO.write` 写入 JPEG,
 * 替代 app 端 `android.graphics.BitmapFactory` + `Bitmap.compress`。
 *
 * # 与 app 端差异
 * - **quality 参数忽略**: JDK ImageIO 默认 JPEG 质量无法直接配置 (需 ImageWriter +
 *   ImageWriteParam), 桌面端封面质量不关键, 用默认质量 (与 app 端 90 略有差异,
 *   视觉上无明显区别)。
 * - **GIF**: ImageIO 仅取静态首帧 (与 [MangaReaderScreen] loadMangaImage 一致)。
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
            val image = ImageIO.read(input) ?: return false
            outFile.parentFile?.mkdirs()
            ImageIO.write(image, "jpg", outFile)
            image.flush()
            true
        }.getOrDefault(false)
    }
}
