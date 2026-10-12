package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.file.desktopResolveStoredRef
import io.legado.app.help.writeBytesAtomically
import io.legado.app.lib.epublib.epub.EpubReader
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.zip.ZipFile

/**
 * [LocalEpubResource] 的 JVM (desktop) actual 实现。
 *
 * # 设计
 * desktop 端 bookUrl 走 [io.legado.app.help.book.LocalBookLocators] 解析,
 * 格式为 `books/...` 数据根相对引用、`file:///...` 或绝对路径 (旧数据)
 * (见 `LocalBookLocator.jvm.kt`)。
 * 直接用 [java.util.zip.ZipFile] 打开, 无需 content scheme 处理。
 *
 * # 与 android actual 的差异
 * - 无 content scheme (desktop 无 ContentResolver), 仅存储引用 / file scheme / 绝对路径
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
            // bookUrl 存储引用统一解析 (相对引用对准数据根, 旧数据绝对路径原样)
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
     * 先经 [LocalBookLocators] 取当前可用的文件引用: bookUrl 打不开时按文件名在"书籍保存位置"
     * 与导入目录找回同名文件, 不回写 bookUrl、不落库。
     * 定位不到时按原存储引用解析 (打开失败由 [EpubFile.readEpub] 记日志)。
     *
     * desktop 端 bookUrl 格式 (见 `LocalBookLocator.jvm.kt`):
     * - `books/x.epub` (数据根相对引用, saveBookFile 新格式) → `{数据根}/books/x.epub`
     * - `file:///C:/path/book.epub` → [File](uri.path) (旧数据)
     * - `C:\path\book.epub`、`/home/user/book.epub` (绝对路径) → [File](url) (旧数据)
     */
    private fun resolveLocalFile(book: Book): File {
        // 存储引用统一解析: 相对引用对准数据根, file: URI/绝对路径 (旧数据) 原样
        val path = LocalBookLocators.get().getReadableFileRef(book)
        return if (path != null) File(path) else desktopResolveStoredRef(book.bookUrl)
    }

    /** 释放底层 ZipFile, 幂等。 */
    actual fun close() {
        zipFile?.let { runCatching { it.close() } }
        zipFile = null
    }
}

/**
 * [decodeBitmap] 的 JVM (desktop) actual 实现 (基于 Skia 原生解码)。
 *
 * 用 Skia [Image.makeFromEncoded] 解码字节数组, 返回 [Image]。
 *
 * @return [Image], 失败返回 null
 */
actual fun decodeBitmap(bytes: ByteArray): Any? {
    if (bytes.isEmpty()) return null
    return runCatching {
        Image.makeFromEncoded(bytes)
    }.getOrNull()
}

/**
 * [compressBitmap] 的 JVM (desktop) actual 实现 (基于 Skia 原生编码)。
 *
 * @param bitmap [decodeBitmap] 返回的 [Image]
 * @param format "JPEG" / "PNG" / "WEBP"
 * @param quality 0..100 (JPEG / WEBP 压缩质量)
 * @param destPath 目标文件绝对路径 (内部自动创建父目录, 整份落盘)
 * @return 成功 true
 */
actual fun resolveStoredLocalPath(path: String): String {
    // 桌面端落库引用: 数据根下相对引用 (books/, oldCovers/) 对准数据根, 其余 (file: URI/绝对路径) 原样
    return desktopResolveStoredRef(path).absolutePath
}

actual fun compressBitmap(bitmap: Any?, format: String, quality: Int, destPath: String): Boolean {
    if (bitmap !is Image) return false
    val skFormat = when (format.uppercase()) {
        "JPEG", "JPG" -> EncodedImageFormat.JPEG
        "PNG" -> EncodedImageFormat.PNG
        "WEBP" -> EncodedImageFormat.WEBP
        else -> EncodedImageFormat.JPEG
    }
    return runCatching {
        // 封面按"文件已存在即有效"被复用, 往目标上盖写留下的半截 JPEG 会被永久当成有效封面
        val data = bitmap.encodeToData(skFormat, quality.coerceIn(0, 100)) ?: return false
        writeBytesAtomically(destPath, data.bytes)
    }.getOrDefault(false)
}
