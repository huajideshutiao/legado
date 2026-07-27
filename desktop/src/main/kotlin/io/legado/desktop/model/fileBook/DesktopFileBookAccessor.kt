package io.legado.desktop.model.fileBook

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isEpub
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.model.fileBook.FileBookAccessor
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.fileBook.TextFile
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import java.io.File
import java.nio.file.Paths

/**
 * 桌面端 [FileBookAccessor] 最小化实现。
 *
 * # 背景
 * [EpubFile] 构造时调用 `upBookCover` → `FileBook.getCoverPath` → [FileBookProviders.get],
 * 若 [FileBookProviders] 未注册会抛 IllegalStateException (被 EpubFile.upBookCover 的
 * try-catch 捕获, 封面加载失败但不崩溃)。为了让 desktop 端 EpubFile 能正常加载封面,
 * 需注册本最小化实现。
 *
 * # 已实现方法
 * - [getCoverPath]: `{desktopAppRootDir}/covers/{md516(bookUrl)}.jpg` (对齐 app 端路径结构)
 * - [getHandler]: 依据 [Book.isEpub] 分派到 [EpubFile] / [TextFile]
 * - [getBookInputStream]: [java.io.FileInputStream] 打开本地文件
 * - [getLastModified]: [File.lastModified]
 * - [getUrlSuffix]: 取文件名后缀
 * - [analyzeNameAuthor]: 简单解析文件名 (与 app 端行为一致)
 *
 * # 未实现方法 (抛 UnsupportedOperationException)
 * - [importFromArchive] / [importLocalFile] / [deleteBook] / [saveBookFile] /
 *   [downloadRemoteBook] / [mergeBook] / [importRemoteBook]: 依赖 Android 专属组件
 *   (ArchiveUtils / DocumentFile / RemoteBook 等), 待后续子代理补全
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor]。
 */
class DesktopFileBookAccessor : FileBookAccessor {

    /** 封面缓存目录: `{desktopAppRootDir}/covers` (对齐 app 端 `externalFiles/covers`)。 */
    private val coversDir: String = Paths.get(desktopAppRootDir(), "covers").toString()

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return Paths.get(coversDir, "${MD5Utils.md5Encode16(bookUrl)}.jpg").toString()
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 依据 originName 后缀分派 (与 app 端 BookExtensions.getHandler 一致)
        return when {
            book.isEpub -> EpubFile
            else -> TextFile
        }
    }

    override fun getBookInputStream(book: Book): InputStream {
        // desktop 端 bookUrl 为 file:// 或绝对路径, 解析为 File 后打开 FileInputStream
        // InputStream 是 java.io.InputStream 的 typealias (jvmAndAndroidMain), 无需包装
        val file = resolveLocalFile(book.bookUrl)
        return file.inputStream()
    }

    override fun getLastModified(book: Book): Result<Long> {
        return runCatching {
            resolveLocalFile(book.bookUrl).lastModified()
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        throw UnsupportedOperationException("DesktopFileBookAccessor.importFromArchive not implemented")
    }

    override fun importLocalFile(uriStr: String): Book {
        throw UnsupportedOperationException("DesktopFileBookAccessor.importLocalFile not implemented")
    }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        throw UnsupportedOperationException("DesktopFileBookAccessor.deleteBook not implemented")
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        throw UnsupportedOperationException("DesktopFileBookAccessor.saveBookFile(str) not implemented")
    }

    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        throw UnsupportedOperationException("DesktopFileBookAccessor.saveBookFile(inputStream) not implemented")
    }

    override fun downloadRemoteBook(book: Book): Boolean {
        throw UnsupportedOperationException("DesktopFileBookAccessor.downloadRemoteBook not implemented")
    }

    override fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        // 与 app 端一致: onLineBook 为 null 时返回 localBook
        onLineBook ?: return localBook
        throw UnsupportedOperationException("DesktopFileBookAccessor.mergeBook not implemented")
    }

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 简单解析: 去后缀, 按 " - " 或 " - " 分割书名与作者
        val name = fileName.substringBeforeLast(".")
        val parts = name.split(" - ", " - ", "——", "_")
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            name to ""
        }
    }

    override suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean
    ): Book {
        throw UnsupportedOperationException("DesktopFileBookAccessor.importRemoteBook not implemented")
    }

    override fun getUrlSuffix(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(idx) else ""
    }

    /** 解析 bookUrl 为 [File] (与 LocalEpubResource.jvm.kt 的 resolveLocalFile 逻辑一致)。 */
    private fun resolveLocalFile(bookUrl: String): File {
        return when {
            bookUrl.startsWith("file:") -> {
                val path = java.net.URI(bookUrl).path ?: bookUrl.removePrefix("file:")
                File(path)
            }
            else -> File(bookUrl)
        }
    }
}

/**
 * 桌面端启动早期注册 [FileBookProviders]。
 *
 * 调用时机: desktop `main()` 中, 在任何 EpubFile / FileBook 调用之前。
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor] 的注册方式。
 */
fun registerDesktopFileBookAccessor() {
    FileBookProviders.register(DesktopFileBookAccessor())
}
