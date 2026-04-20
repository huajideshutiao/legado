package io.legado.app.model.fileBook

import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.utils.isContentScheme
import java.io.File
import java.io.InputStream
import java.util.Enumeration

interface ZipFileWrapper {
    fun getEntry(name: String): ZipEntry?
    fun getInputStream(entry: ZipEntry): InputStream?
    fun entries(): Enumeration<out ZipEntry>
    fun close()

    data class CreateResult(
        val wrapper: ZipFileWrapper, val fileDescriptor: ParcelFileDescriptor? = null
    )

    companion object {
        fun create(book: Book): CreateResult? {
            return runCatching {
                if (book.bookUrl.startsWith(BookType.webDavTag) || book.bookUrl.startsWith("http")) {
                    val url = book.getRemoteUrl() ?: book.bookUrl
                    val webdav = runCatching { WebDav.fromPath(url) }.getOrElse {
                        AppWebDav.authorization?.let {
                            WebDav(url, it)
                        } ?: throw WebDavException("No Auth")
                    }
                    var eocd = 0L
                    var central = 0L
                    var size = 0L
                    book.variable?.takeIf { it.startsWith("cbz:") }?.substring(4)?.split(",")
                        ?.let { p ->
                            eocd = p.getOrNull(0)?.toLongOrNull() ?: 0L
                            central = p.getOrNull(1)?.toLongOrNull() ?: 0L
                            size = p.getOrNull(2)?.toLongOrNull() ?: 0L
                        }
                    val count = book.wordCount?.replace("页", "")?.toIntOrNull() ?: 0
                    val wrapper = if (eocd > 0 && central > 0 && count > 0) {
                        RemoteZipWrapper(webdav, book.originName, size, eocd, central, count)
                    } else {
                        RemoteZipWrapper(webdav, book.originName, size)
                    }
                    CreateResult(wrapper)
                } else if (book.bookUrl.lowercase().endsWith(".cbz") || book.bookUrl.lowercase()
                        .endsWith(".zip")
                ) {
                    val uri = book.getLocalUri()
                    if (uri.isContentScheme()) {
                        val pfd = BookHelp.getBookPFD(book)
                        CreateResult(ContentZipWrapper(pfd!!), pfd)
                    } else {
                        CreateResult(LocalZipWrapper(File(uri.path!!)))
                    }
                } else {
                    val pfd = BookHelp.getBookPFD(book)
                    CreateResult(LocalArchiveWrapper(pfd!!), pfd)
                }
            }.onFailure { AppLog.put("读取Cbz文件失败\n${it.localizedMessage}", it) }.getOrNull()
        }
    }
}
