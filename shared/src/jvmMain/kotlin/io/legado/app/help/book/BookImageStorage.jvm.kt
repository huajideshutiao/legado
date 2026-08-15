package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgRasterizer
import io.legado.app.utils.UrlUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * [BookImageStorage] 桌面 JVM 实现。
 *
 * 图片缓存目录结构: `~/.legado/book_cache/{bookFolderName}/images/{md5(url)}.{suffix}`
 * - 与 Android 端 [io.legado.app.help.book.BookHelp.getImage] 路径结构对齐
 *   (cacheImageFolderName = "images")
 * - 文件名 = `MD5(url, 16位) + url 后缀` (默认 jpg), 与 Android 端 [BookHelp.getImageSuffix] 对齐
 *
 * 图片有效性校验: 用 [ImageIO.read] 判断字节流是否为有效图 (返回 null 视为损坏),
 * 不保留 BufferedImage (与 Android 端 BitmapFactory.Options.inJustDecodeBounds 行为对齐,
 * 仅做有效性判断后立即丢弃解码结果)。
 *
 * SVG: 对照 Android 端 SvgUtils 兜底, ImageIO 失败时改由 [SvgRasterizer] 解析。
 * 与 Android 端存原始 SVG 字节不同, 桌面读取侧 (ImageIO / Coil3) 无 SVG 解码器,
 * 故落盘的是栅格化后的 PNG 字节 (文件名仍按 url 后缀派生, 消费方按魔数识别)。
 *
 * 下载: 通过 [OkHttpClientProviders] 取 OkHttpClient 同步 GET, 在 [Dispatchers.IO] 并发下载。
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
        Files.createDirectories(file.parent)
        Files.write(file, data)
        return file.toString()
    }

    override fun getImagePath(book: Book, chapter: BookChapter, url: String): String? {
        val file = resolveImageFile(book, url)
        return if (Files.isRegularFile(file)) file.toString() else null
    }

    override fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean {
        return Files.isRegularFile(resolveImageFile(book, url))
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

    override suspend fun saveImages(book: Book, chapter: BookChapter, urls: List<String>): Boolean = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope true
        // 并发下载, 限制 8 并发 (与 Android BookHelp.saveImages concurrency=AppConfig.threadCount 默认对齐)
        // 注意: 桌面端 AppConfigProviders.threadCount 可能未注入, 这里固定 8 避免依赖
        val client = OkHttpClientProviders.get().okHttpClient
        val results = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    // 已存在跳过 (与 Android BookHelp.saveImage 内 isImageExist 短路对齐)
                    if (isImageExist(book, chapter, url)) return@async true
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val bytes = response.body.bytes()
                        saveImage(book, chapter, url, bytes) != null
                    }
                } catch (e: Exception) {
                    // 网络 / IO 异常视为失败, 不中断其他下载 (与 Android BookHelp.saveImage
                    // 的 try-catch + ensureActive 行为对齐)
                    false
                }
            }
        }.awaitAll()
        results.all { it }
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
     * 校验字节流是否为有效栅格图。
     *
     * 用 [ImageIO.read] 尝试解码, 返回 null 视为损坏 (与 Android 端
     * BitmapFactory.decodeByteArray + `outWidth < 1 && outHeight < 1` 判断对齐)。
     * 不保留 BufferedImage, 仅做有效性判断后立即丢弃。
     *
     * SVG 不被 ImageIO 识别, 由 [saveImage] 回落到 [SvgRasterizer] (对照 Android 端
     * BitmapFactory 失败后试 SvgUtils.getSize 的兜底顺序)。
     */
    private fun isValidImage(bytes: ByteArray): Boolean {
        return try {
            ImageIO.read(ByteArrayInputStream(bytes)) != null
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** 图片缓存子目录名 (与 Android 端 BookHelp.cacheImageFolderName = "images" 对齐)。 */
        private const val IMAGE_FOLDER_NAME = "images"
    }
}
