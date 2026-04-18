package io.legado.app.model.fileBook

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.model.fileBook.FileBook.downloadRemoteBook
import io.legado.app.model.fileBook.FileBook.importFromArchive
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 *companion object interface
 *see EpubFile.kt
 */
interface BaseFileBook {

    fun upBookInfo(book: Book)

    fun getChapterList(book: Book): ArrayList<BookChapter>

    fun getContent(book: Book, chapter: BookChapter): String?

    fun getImage(book: Book, href: String): InputStream?

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val uri = book.getLocalUri()
        val inputStream = uri.inputStream(appCtx).getOrNull() ?: let {
            book.removeLocalUriCache()
            val localArchiveUri = book.getArchiveUri()
            val webDavUrl = book.getRemoteUrl()
            if (localArchiveUri != null) {
                // 重新导入对应的压缩包
                importFromArchive(localArchiveUri, book.originName) {
                    it.contains(book.originName)
                }.firstOrNull()?.let {
                    getBookInputStream(it)
                }
            } else if (webDavUrl != null && downloadRemoteBook(book)) {
                // 下载远程链接
                getBookInputStream(book)
            } else {
                null
            }
        }
        if (inputStream != null) return inputStream
        book.removeLocalUriCache()
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = book.bookUrl.toUri()
            if (uri.isContentScheme()) {
                return@runCatching DocumentFile.fromSingleUri(appCtx, uri)!!.lastModified()
            }
            val file = File(uri.path!!)
            if (file.exists()) {
                return@runCatching file.lastModified()
            }
            throw FileNotFoundException("${uri.path} 文件不存在")
        }
    }
}