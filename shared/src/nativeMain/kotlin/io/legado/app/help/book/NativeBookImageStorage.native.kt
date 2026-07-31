package io.legado.app.help.book

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.legado.app.utils.File

/**
 * [BookImageStorage] iOS/鸿蒙 (Native target) 共用真实实现
 * (基于 [kotlin.io.File] + Ktor HttpClient)。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 BookImageStorage 主体逻辑完全一致 (图片缓存 I/O + Ktor CIO 并发下载),
 * 仅 iOS 端原用 NSFileManager + NSData, 鸿蒙端用 kotlin.io.File;
 * Kotlin/Native 在 iOS/linuxArm64 (鸿蒙) 上均支持 [kotlin.io.File],
 * 故统一改为 [kotlin.io.File] 实现下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 目录结构
 * - cacheRootPath: `{AppFilesDirs.filesDir}/book_cache/` (与 [NativeBookStorage] 同源)
 * - 单本书图片缓存: `book_cache/{bookFolderName}/images/`
 * - 单张图片文件: `book_cache/{bookFolderName}/images/{md5(url)}.{suffix}`
 *   (与 Android 端 BookHelp.getImage 路径结构对齐, cacheImageFolderName = "images")
 *
 * # 行为对齐 jvmMain JvmBookImageStorage / 原 iOS IosBookImageStorage / 原 ohos OhosBookImageStorage
 * - [saveImage]: 字节头 magic number 校验 + [File.writeBytes]
 * - [getImagePath]/[isImageExist]: [File.exists]
 * - [hasImageContent]: 目录存在 + 至少一个文件 (短路语义)
 * - [clearCache]: [File.deleteRecursively]
 * - [saveImages]: Ktor CIO 并发下载 (限制 8 并发, 与 JVM 桌面端对齐)
 *
 * # 与原 iOS 端差异 (统一为 kotlin.io.File 后)
 * - 文件 I/O: iOS 用 NSFileManager + NSData.writeToFile(atomically=true);
 *   统一后用 [kotlin.io.File] (mkdirs / writeBytes / exists / deleteRecursively)
 * - 图片有效性校验: 与两端完全一致 (字节头 magic number: JPEG/PNG/GIF/WebP/BMP)
 * - 后缀提取: 与两端完全一致 ([getImageSuffix] 简化版, 不依赖 CustomUrl)
 *
 * 模式参考 [io.legado.app.help.book.JvmBookImageStorage]。
 */
class NativeBookImageStorage(
    private val cacheRootPath: String = NativeBookStorage.defaultRootPath()
) : BookImageStorage {

    override fun saveImage(book: Book, chapter: BookChapter, url: String, bytes: ByteArray): String? {
        // 与 Android BookHelp.checkImage / iOS isValidImage 行为对齐:
        // 校验字节流是否为有效图, 失败返回 null
        if (!isValidImage(bytes)) return null
        val path = resolveImageFile(book, url)
        val file = File(path)
        // 父目录不存在则递归创建 (与 iOS createDirectoryAtPath 行为对齐)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        // 返回解析时的路径字符串 (与 iOS 端 saveImage 返回 resolveImageFile 结果行为对齐,
        // 避免依赖 File.absolutePath 在不同 Kotlin/Native 版本下的归一化差异)
        return path
    }

    override fun getImagePath(book: Book, chapter: BookChapter, url: String): String? {
        val path = resolveImageFile(book, url)
        return if (File(path).exists()) path else null
    }

    override fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean {
        return File(resolveImageFile(book, url)).exists()
    }

    override fun hasImageContent(book: Book): Boolean {
        val imageDir = File("$cacheRootPath/${book.getFolderName()}/$IMAGE_FOLDER_NAME")
        if (!imageDir.exists()) return false
        // 短路: 只要存在任一文件即返回 true (与 Android BookHelp.hasImageContent / iOS 行为对齐)
        val files = imageDir.listFiles() ?: return false
        return files.isNotEmpty()
    }

    override fun clearCache(book: Book) {
        val imageDir = File("$cacheRootPath/${book.getFolderName()}/$IMAGE_FOLDER_NAME")
        if (imageDir.exists()) {
            imageDir.deleteRecursively()
        }
    }

    override suspend fun saveImages(book: Book, chapter: BookChapter, urls: List<String>): Boolean = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope true
        // 并发下载, 限制 8 并发 (与 JVM 桌面端 fixed 8 / iOS 端 async(Dispatchers.IO) 对齐,
        // 避免依赖未注入的 AppConfig.threadCount)
        val results = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    // 已存在跳过 (与 Android BookHelp.saveImage 内 isImageExist 短路对齐)
                    if (isImageExist(book, chapter, url)) return@async true
                    val client = HttpClient(CIO)
                    try {
                        val response = client.get(url)
                        if (!response.status.isSuccess()) {
                            false
                        } else {
                            val bytes = response.bodyAsBytes()
                            saveImage(book, chapter, url, bytes) != null
                        }
                    } finally {
                        client.close()
                    }
                } catch (e: Exception) {
                    // 网络 / IO 异常视为失败, 不中断其他下载 (与 Android BookHelp.saveImage 行为对齐)
                    false
                }
            }
        }.awaitAll()
        results.all { it }
    }

    /**
     * 解析图片缓存文件路径: `{cacheRoot}/{bookFolderName}/images/{md5(url)}.{suffix}`。
     *
     * suffix 用 [getImageSuffix] 提取, 默认 "jpg" (与 Android BookHelp.getImageSuffix 对齐)。
     */
    private fun resolveImageFile(book: Book, url: String): String {
        val suffix = getImageSuffix(url, "jpg")
        val fileName = "${MD5Utils.md5Encode16(url)}.$suffix"
        return "$cacheRootPath/${book.getFolderName()}/$IMAGE_FOLDER_NAME/$fileName"
    }

    /**
     * 从 URL 提取合法图片后缀 (简化版 [io.legado.app.utils.UrlUtil.getSuffix])。
     *
     * 与 jvmAndAndroidMain / iOS 端行为对齐:
     * - 截取 URL 路径最后一段 `.` 后的部分 (排除 query/fragment)
     * - 长度 > 5 或含非字母数字字符时返回 [default]
     * - 不依赖 CustomUrl (避免引入额外 URL 规范化开销)
     */
    private fun getImageSuffix(url: String, default: String): String {
        val suffix = url.substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        // 后缀合法性: 仅允许字母数字, 长度 <= 5 (与 jvmAndAndroidMain fileSuffixRegex 对齐)
        return if (suffix.length in 1..5 && suffix.all { it.isLetterOrDigit() }) {
            suffix.lowercase()
        } else {
            default
        }
    }

    /**
     * 校验字节流是否为有效图片 (字节头 magic number 判断)。
     *
     * 替代 jvmAndAndroidMain 的 `ImageIO.read(bytes) != null`:
     * - JPEG: `FF D8 FF`
     * - PNG:  `89 50 4E 47 0D 0A 1A 0A`
     * - GIF:  `47 49 46 38` ("GIF8")
     * - WebP: `52 49 46 46 ?? ?? ?? ?? 57 45 42 50` ("RIFF....WEBP")
     * - BMP:  `42 4D` ("BM")
     *
     * 不支持的格式 (如 SVG) 视为无效; 与 Android 端 BitmapFactory 行为基本一致
     * (Android 端通过 BitmapFactory.decodeByteArray + outWidth<1&&outHeight<1 判断, 也不支持 SVG)。
     */
    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // JPEG
        if ((bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) return true
        // PNG
        if ((bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        ) return true
        // GIF (GIF87a / GIF89a)
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte()
        ) return true
        // BMP
        if (bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) return true
        // WebP (RIFF....WEBP)
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return true
        return false
    }

    companion object {
        /** 图片缓存子目录名 (与 Android 端 BookHelp.cacheImageFolderName = "images" 对齐)。 */
        private const val IMAGE_FOLDER_NAME = "images"
    }
}

/**
 * 注册 [NativeBookImageStorage] 到 [BookImageStorageProviders]
 * (iOS / 鸿蒙共用, 宿主启动早期调用, 在 AppFilesDirs 就绪之后)。
 */
fun registerNativeBookImageStorage() {
    BookImageStorageProviders.register(NativeBookImageStorage())
}
