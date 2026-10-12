package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.writeBytesAtomically
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgRasterizer
import io.legado.app.utils.UrlUtil
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.impl.use
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [BookImageStorage] 桌面 JVM 实现。
 *
 * 图片缓存目录结构: `~/.legado/book_cache/{bookFolderName}/images/{md5(url)}.{suffix}`
 * - 与 Android 端 [io.legado.app.help.book.BookHelp.getImage] 路径结构对齐
 *   (cacheImageFolderName = "images")
 * - 文件名 = `MD5(url, 16位) + url 后缀` (默认 jpg), 与 Android 端 [BookHelp.getImageSuffix] 对齐
 *
 * 图片有效性校验: 用 Skia [Codec.makeFromData] 判断字节流是否为有效图 (返回 null 视为损坏),
 * 仅做有效性判断后立即丢弃解码结果 (微秒级, 零 SPI 反射查找, 完美支持 WebP/GIF/PNG/JPG/BMP)。
 *
 * 落盘走 [writeBytesAtomically]: 缓存文件被读的并发度高于写入, 直接覆盖写会让
 * 同时读该文件的链路拿到半截字节。
 *
 * SVG: 对照 Android 端 SvgUtils 兜底, 栅格校验失败时改由 [SvgRasterizer] 解析为 PNG 字节。
 *
 * 注册: desktop 模块启动时通过 [BookImageStorageProviders.register] 注入。
 *
 * @param cacheRootPath 图片缓存根路径, 默认与 [JvmBookStorage.defaultRootPath] 同源
 *        (`~/.legado/book_cache`), 让章节缓存与图片缓存共用同一 book_cache 父目录,
 *        与 Android 端 BookHelp.downloadDir 一致
 */
class JvmBookImageStorage(
    private val cacheRootPath: String = JvmBookStorage.defaultRootPath()
) : BookImageStorage {

    /** 图片缓存根目录 Path (构造时一次性解析)。 */
    private val cacheRoot: Path = Paths.get(cacheRootPath)

    override fun saveImage(book: Book, chapter: BookChapter, url: String, bytes: ByteArray): String? {
        // 与 Android BookHelp.checkImage 行为对齐: 先按栅格图校验, 失败再试 SVG, 都不是则返回 null
        val data = if (isValidImage(bytes)) bytes else SvgRasterizer.toPng(bytes) ?: return null
        val file = resolveImageFile(book, url)
        if (!writeBytesAtomically(file.toString(), data)) return null
        return file.toString()
    }

    override fun getImagePath(book: Book, chapter: BookChapter, url: String): String? {
        val file = resolveImageFile(book, url)
        return if (Files.isRegularFile(file)) file.toString() else null
    }

    override fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean {
        return isCachedImageUsable(resolveImageFile(book, url).toString())
    }

    override fun hasImageContent(book: Book): Boolean {
        val imageDir = cacheRoot.resolve(book.getFolderName()).resolve(IMAGE_FOLDER_NAME)
        if (!Files.isDirectory(imageDir)) return false
        // findAny 短路: 只要存在任一文件即返回 true (与 Android BookHelp.hasImageContent 的
        // "目录存在 + 至少一张图片" 语义对齐, 不做图片有效性校验)
        return Files.list(imageDir).use { it.findAny().isPresent }
    }

    override fun clearCache(book: Book) {
        val imageDir = cacheRoot.resolve(book.getFolderName()).resolve(IMAGE_FOLDER_NAME)
        if (Files.isDirectory(imageDir)) {
            imageDir.toFile().deleteRecursively()
        }
    }

    /**
     * 校验字节流是否为有效图片: 先按栅格图校验 (Skia), 失败再试 SVG 栅格化兜底
     * (与 Android BookHelp.checkImage 对齐), 都不是则 false。
     */
    override fun checkImageBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return isValidImage(bytes) || SvgRasterizer.toPng(bytes) != null
    }

    /** 原始字节落盘 (不校验, 校验失败也照写, 与 Android BookHelp.writeImage 行为对齐)。 */
    override fun writeImageBytes(book: Book, chapter: BookChapter, url: String, bytes: ByteArray) {
        writeBytesAtomically(resolveImageFile(book, url).toString(), bytes)
    }

    /**
     * 解析图片缓存文件路径: `{cacheRoot}/{bookFolderName}/images/{md5(url)}.{suffix}`。
     *
     * suffix 用 [UrlUtil.getSuffix] 提取, 默认 "jpg" (与 Android BookHelp.getImageSuffix 对齐)。
     */
    private fun resolveImageFile(book: Book, url: String): Path {
        val suffix = UrlUtil.getSuffix(url, "jpg")
        val fileName = "${MD5Utils.md5Encode16(url)}.$suffix"
        return cacheRoot
            .resolve(book.getFolderName())
            .resolve(IMAGE_FOLDER_NAME)
            .resolve(fileName)
    }

    /**
     * 校验字节流是否为有效栅格图 (Skia 原生校验)。
     *
     * 避免 Java ImageIO 的 SPI 反射查找卡顿与 WebP 无法识别缺陷。
     * 仅做有效性判断后立即释放资源。
     */
    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            val data = Data.makeFromBytes(bytes)
            try {
                val codec = Codec.makeFromData(data)
                codec.use { c ->
                    c.imageInfo.width > 0 && c.imageInfo.height > 0
                }
            } finally {
                data.close()
            }
        }.getOrDefault(false)
    }

    companion object {
        /** 图片缓存子目录名 (与 Android 端 BookHelp.cacheImageFolderName = "images" 对齐)。 */
        private const val IMAGE_FOLDER_NAME = "images"
    }
}
