package io.legado.desktop.help.book

import io.legado.app.model.fileBook.BitmapProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * [BitmapProvider] 桌面 JVM 实现。
 *
 * 用 `javax.imageio.ImageIO.read` 解码图片, `ImageIO.write` 写入 JPEG,
 * 替代 app 端 `android.graphics.BitmapFactory` + `Bitmap.compress`。
 *
 * # 与 app 端差异
 * - **quality 参数**: 用 ImageWriter + JPEG ImageWriteParam 映射到压缩比 (quality/100),
 *   与 app 端 `Bitmap.compress(JPEG, quality)` 语义一致 (默认 90 时接近默认质量)。
 * - **GIF**: ImageIO 仅取静态首帧 (AWT/ImageIO 解码限制: 无 GIF 动画帧 API,
 *   与 [MangaReaderScreen] loadMangaImage 一致), 封面场景静态首帧即可接受。
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
            writeJpegWithQuality(image, quality, outFile.outputStream())
            image.flush()
            true
        }.getOrDefault(false)
    }

    /** ImageIO.write 不支持质量参数, 改用 JPEG ImageWriter + ImageWriteParam (quality/100)。 */
    private fun writeJpegWithQuality(
        image: java.awt.image.BufferedImage,
        quality: Int,
        out: OutputStream
    ) {
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        check(writers.hasNext()) { "no jpeg writer" }
        val writer = writers.next()
        try {
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality.coerceIn(0, 100) / 100f
            }
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(image, null, null), param)
        } finally {
            writer.dispose()
        }
    }
}
