package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.lib.epublib.epub.EpubReader
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * [LocalEpubResource] 的 JVM (desktop) actual 实现。
 *
 * # 设计
 * desktop 端 bookUrl 走 [io.legado.app.help.book.LocalBookLocators] 解析,
 * 格式为 `file:///...` 或绝对路径 `C:\...` / `/home/...` (见 `LocalBookLocator.jvm.kt`)。
 * 直接用 [java.util.zip.ZipFile] 打开, 无需 content scheme 处理。
 *
 * # 与 android actual 的差异
 * - 无 content scheme (desktop 无 ContentResolver), 仅 file scheme / 绝对路径
 * - 无需 Context 注入 (无 [epubApplicationContext] 等价物)
 * - 无临时文件复制 (直接打开本地文件)
 *
 * 模式参考 `DesktopBookCover.kt` 的 `ImageIO.read(File)` 用法。
 */
actual class LocalEpubResource actual constructor(book: Book) {

    /** 已解析的 EpubBook (失败返回 null, 由 EpubFile 记录错误日志)。返回 Any? 对齐 commonMain expect。 */
    actual val epubBook: Any?

    /** 底层 ZipFile, close 时释放。 */
    private var zipFile: ZipFile? = null

    init {
        epubBook = runCatching {
            // desktop 端 bookUrl 为 file:// 或绝对路径, 解析为 File
            val file = resolveLocalFile(book)
            val zf = ZipFile(file, Charsets.ISO_8859_1)
            zipFile = zf
            // 与 android actual 一致, 走 readEpubLazy(ZipFile, encoding) 重载
            EpubReader().readEpubLazy(zf, "utf-8")
        }.getOrElse {
            close()
            null
        }
    }

    /**
     * 解析 [book.bookUrl] 为可被 [ZipFile] 打开的 [File]。
     *
     * desktop 端 bookUrl 格式 (见 `LocalBookLocator.jvm.kt`):
     * - `file:///C:/path/book.epub` → [File](uri.path)
     * - `C:\path\book.epub` (绝对路径) → [File](url)
     * - `/home/user/book.epub` (绝对路径) → [File](url)
     */
    private fun resolveLocalFile(book: Book): File {
        val url = book.bookUrl
        return when {
            url.startsWith("file:") -> {
                // file:// URI, 取 path 部分
                val path = java.net.URI(url).path ?: url.removePrefix("file:")
                File(path)
            }
            else -> {
                // 绝对路径直接用
                File(url)
            }
        }
    }

    /** 释放底层 ZipFile, 幂等。 */
    actual fun close() {
        zipFile?.let { runCatching { it.close() } }
        zipFile = null
    }
}

/**
 * [decodeBitmap] 的 JVM (desktop) actual 实现。
 *
 * 用 [ImageIO.read] 解码字节数组, 返回 [BufferedImage]。
 * 替代原 app 端 `BitmapFactory.decodeStream(input)`。
 *
 * @return [BufferedImage], 失败返回 null
 */
actual fun decodeBitmap(bytes: ByteArray): Any? {
    return runCatching {
        ImageIO.read(ByteArrayInputStream(bytes))
    }.getOrNull()
}

/**
 * [compressBitmap] 的 JVM (desktop) actual 实现。
 *
 * 用 ImageIO ImageWriter + ImageWriteParam 写文件, 真正落实 quality
 * (JPEG compressionQuality = quality/100f): 不能像原来那样用 [ImageIO.write]
 * 默认质量 —— 默认 JPEG≈0.75, 与 Android 端 quality=90 的产物不一致。
 *
 * @param bitmap [decodeBitmap] 返回的 [BufferedImage]
 * @param format "JPEG" / "PNG" / "WEBP"
 * @param quality 0..100 (JPEG 压缩质量)
 * @param destPath 目标文件绝对路径 (内部自动创建父目录)
 * @return 成功 true
 */
actual fun compressBitmap(bitmap: Any?, format: String, quality: Int, destPath: String): Boolean {
    if (bitmap !is BufferedImage) return false
    // JDK ImageIO 无内置 WEBP writer, 映射到 PNG (无损), 不静默落 jpg
    val formatName = when (format.uppercase()) {
        "JPEG", "JPG" -> "jpg"
        "PNG", "WEBP" -> "png"
        else -> "jpg"
    }
    return runCatching {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val writer = ImageIO.getImageWritersByFormatName(formatName).next()
        try {
            val ios = ImageIO.createImageOutputStream(dest)
            try {
                writer.setOutput(ios)
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = (quality / 100f).coerceIn(0f, 1f)
                }
                writer.write(null, IIOImage(bitmap, null, null), param)
            } finally {
                ios.close()
            }
        } finally {
            writer.dispose()
        }
        true
    }.getOrElse { false }
}
