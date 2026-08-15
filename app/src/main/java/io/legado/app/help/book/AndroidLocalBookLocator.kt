package io.legado.app.help.book

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.isContentScheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [LocalBookLocator] 安卓端 actual 实现。
 *
 * 本地书路径解析规则:
 * - bookUrl 形如 `file:///storage/.../book.txt` → 取 Uri.path 转 File 绝对路径
 * - bookUrl 形如 `content://...` (SAF) → 返回 null (非文件系统路径, 走 DocumentFile 通道)
 * - bookUrl 形如 `/path/book.txt` 或 `C:\path\book.txt` → 直接当本地路径
 * - 网络书源 (`http(s)://...`) → 返回 null
 *
 * 压缩包子书路径: 从 book.origin 提取 (形如 `local_book::archive.rar`),
 * 由 [Book.archiveName] 扩展提供 (BookExtensionsShared.kt, commonMain 已下沉)。
 *
 * 缓存: 用 [ConcurrentHashMap] 缓存 bookUrl → 本地路径, 避免重复 URI 解析。
 *
 * 注册: app 端 [io.legado.app.App.onCreate] 早期通过 [LocalBookLocators.register] 注入。
 * 桌面 JVM 端对应 [io.legado.app.help.book.JvmLocalBookLocator] (java.nio.file.Path 实现)。
 *
 * 模式参考 [BookStorageProviders] / [BookHelpProviders]。
 */
class AndroidLocalBookLocator : LocalBookLocator {

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

    /**
     * 取本地书文件最后修改时间 (epoch millis); 文件不存在返回 0。
     *
     * - content scheme (SAF): 走 [DocumentFile.fromSingleUri].lastModified
     * - file scheme / 普通路径: 走 [File.lastModified]
     *
     * 与 app 端 [io.legado.app.model.fileBook.FileBookAccessorImpl.getLastModified] 行为一致。
     */
    override fun getLastModified(book: Book): Long {
        return try {
            val uri = book.bookUrl.toUri()
            when {
                uri.isContentScheme() -> {
                    DocumentFile.fromSingleUri(App.instance, uri)?.lastModified() ?: 0L
                }
                else -> {
                    val path = getLocalPath(book) ?: uri.path ?: return 0L
                    val file = File(path)
                    if (file.exists()) file.lastModified() else 0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 删除本地书文件 (本地书场景); 非本地书或删除失败返回 false。
     *
     * 委托 [FileBook.deleteBook]`(book, deleteOriginal = true)`:
     * 内部清缓存 + 删封面 + 删原文件 (content scheme 走 FileDoc, 普通路径走 FileUtils)。
     */
    override fun deleteBook(book: Book): Boolean {
        if (!book.isLocal) return false
        return try {
            FileBook.deleteBook(book, deleteOriginal = true)
            removeLocalPathCache(book)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析 bookUrl 为本地文件路径。
     *
     * - `file:///storage/.../book.txt` → Uri.path 转 File 绝对路径
     * - `content://...` → null (SAF, 非文件系统路径)
     * - `http(s)://...` → null (网络书源, 非本地)
     * - 其他 (`/path` 或 `C:\path`) → 直接当本地路径
     */
    private fun parseLocalPath(bookUrl: String): String? {
        return try {
            when {
                bookUrl.startsWith("file:") -> {
                    val uri: Uri = bookUrl.toUri()
                    val path = uri.path ?: return null
                    File(path).absolutePath
                }
                bookUrl.startsWith("content:") -> {
                    // content scheme (SAF) 不是文件系统路径, 返回 null
                    null
                }
                bookUrl.startsWith("http:") || bookUrl.startsWith("https:") -> {
                    null
                }
                else -> {
                    File(bookUrl).absolutePath
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
