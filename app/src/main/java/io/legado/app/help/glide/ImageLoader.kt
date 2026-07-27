package io.legado.app.help.glide

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.ImageProvider
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isFilePath
import io.legado.app.utils.isUri
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * 漫画页字节加载(走 BookHelp 缓存 + AnalyzeUrl 下载), 供 Coil3 [MangaModelFetcher] 委托。
 * 原 Glide 相关入口(with/load/loadBitmap/clearMemory)已随 Coil3 迁移移除。
 */
object ImageLoader {

    suspend fun loadManga(
        imageUrl: String,
        book: Book,
        bookSource: BookSource?,
        coroutineContext: CoroutineContext
    ): ByteArray? {
        if (BookHelp.isImageExist(book, imageUrl)) {
            return BookHelp.getImage(book, imageUrl).readBytes()
        }
        if (book.isLocal) {
            if (book.bookUrl.isUri() || book.bookUrl.isFilePath()) {
                return FileBook.getImage(book, imageUrl)?.use { it.readBytes() }
            }
            val file = ImageProvider.cacheImage(book, imageUrl, bookSource)
            return if (file.exists()) file.readBytes() else null
        }
        val analyzeUrl = AnalyzeUrl(
            imageUrl, source = bookSource, coroutineContext = coroutineContext
        )
        val bytes = analyzeUrl.getByteArrayAwait()
        val decodedBytes = runScriptWithContext {
            ImageUtils.decode(
                imageUrl, bytes, isCover = false, bookSource, book
            )
        } ?: return null
        // 先写磁盘 (同步), 再返回。避免协程持有 decodedBytes 导致 OOM
        BookHelp.writeImage(book, imageUrl, decodedBytes)
        return decodedBytes
    }
}
