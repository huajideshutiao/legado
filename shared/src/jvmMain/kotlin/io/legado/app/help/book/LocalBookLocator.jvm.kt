package io.legado.app.help.book

import io.legado.app.data.entities.Book
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * [LocalBookLocator] 桌面 JVM 实现。
 *
 * 本地书路径解析规则:
 * - bookUrl 形如 `file:///C:/path/book.txt` → 取 URI.path 后转 Path
 * - bookUrl 形如 `/path/book.txt` 或 `C:\path\book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * 压缩包子书路径: 从 book.origin 提取 (形如 `local_book::archive.rar`),
 * 由 [Book.archiveName] 扩展提供 (BookExtensionsShared.kt, commonMain 已下沉)。
 *
 * 缓存: 用 [ConcurrentHashMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析。
 *
 * 注册: desktop 模块启动时通过 [LocalBookLocators.register] 注入。
 */
class JvmLocalBookLocator : LocalBookLocator {

    /** bookUrl → 本地路径缓存 (避免重复 URI 解析)。 */
    private val pathCache = ConcurrentHashMap<String, String>()

    override fun getLocalPath(book: Book): String? {
        if (!book.isLocal) return null
        pathCache[book.bookUrl]?.let { return it }
        val path = parseLocalPath(book.bookUrl) ?: return null
        pathCache[book.bookUrl] = path
        return path
    }

    override fun getArchivePath(book: Book): String? {
        if (!book.isArchive) return null
        // archiveName 扩展已校验 isArchive, 直接取
        // runCatching 防御: archiveName 在非 archive 场景抛 NoStackTraceException, 此处虽已校验仍兜底
        return runCatching { book.archiveName }.getOrNull()
    }

    override fun cacheLocalPath(book: Book, path: String) {
        pathCache[book.bookUrl] = path
    }

    override fun removeLocalPathCache(book: Book) {
        pathCache.remove(book.bookUrl)
    }

    override fun getLastModified(book: Book): Long {
        val path = getLocalPath(book) ?: return 0L
        return try {
            Files.getLastModifiedTime(Paths.get(path)).toMillis()
        } catch (e: Exception) {
            0L
        }
    }

    override fun deleteBook(book: Book): Boolean {
        val path = getLocalPath(book) ?: return false
        return try {
            Files.delete(Paths.get(path))
            removeLocalPathCache(book)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析 bookUrl 为本地文件路径。
     *
     * - `file:///C:/path/book.txt` → `C:\path\book.txt` (URI 解码 + 平台分隔符)
     * - `/path/book.txt` → 直接当本地路径
     * - `C:\path\book.txt` → 直接当本地路径
     * - `http(s)://...` → null (网络书源, 非本地)
     */
    private fun parseLocalPath(bookUrl: String): String? {
        return try {
            when {
                bookUrl.startsWith("file:") -> {
                    // URI 解析 file scheme, 自动处理 %20 等转义
                    Paths.get(URI(bookUrl)).toString()
                }
                bookUrl.startsWith("http:") || bookUrl.startsWith("https:") -> {
                    null
                }
                else -> {
                    // 直接当本地路径 (/path 或 C:\path)
                    Paths.get(bookUrl).toString()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
