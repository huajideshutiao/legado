package io.legado.app.help.image

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isFilePath
import io.legado.app.utils.readAllAndClose
import kotlin.coroutines.CoroutineContext

/**
 * 漫画页字节加载 (app 端 `help.glide.ImageLoader.loadManga` 下沉)。
 *
 * 图片缓存读写走 [BookImageStorageProviders] (Android 委托 BookHelp, 桌面走 JvmBookImageStorage,
 * iOS/鸿蒙走 NativeBookImageStorage), 下载走 [AnalyzeUrlFactories] (书源 header/代理/限流),
 * 解密走 [ImageUtils.decode]。四端共用同一条链路: Coil 端经 MangaModelFetcher 调用,
 * 鸿蒙自绘端直接调用。
 */
object MangaImageBytesLoader {

    suspend fun load(
        imageUrl: String,
        book: Book,
        bookSource: BookSource?,
        coroutineContext: CoroutineContext,
    ): ByteArray? {
        // BookImageStorage 的路径只由 book+url 派生, chapter 仅参与签名, 占位即可
        val chapter = BookChapter(url = imageUrl, bookUrl = book.bookUrl)
        val storage = BookImageStorageProviders.get()
        storage.getImagePath(book, chapter, imageUrl)?.let { path ->
            FileUtilsCommon.readBytes(path)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (book.isLocal) {
            val bookUrl = book.bookUrl
            val embedded = runCatching {
                FileBook.getImage(book, imageUrl)?.readAllAndClose()
            }.getOrNull()
            // 书文件在本地(uri/路径): 只可能从书里取图; 远程书(webDav epub 等)取不到则回落网络
            if (bookUrl.startsWith("file://", true) || bookUrl.isContentScheme() ||
                bookUrl.isFilePath()
            ) {
                return embedded ?: throw ImageLoadException("本地书图片不存在: $imageUrl")
            }
            if (embedded != null) {
                storage.saveImage(book, chapter, imageUrl, embedded)
                return embedded
            }
        }
        val bytes = AnalyzeUrlFactories.create(
            rawUrl = imageUrl, source = bookSource, coroutineContext = coroutineContext
        ).getByteArrayAwait()
        val decodedBytes = runScriptWithContext {
            ImageUtils.decode(imageUrl, bytes, isCover = false, bookSource, book)
        } ?: throw ImageLoadException("图片解码失败: $imageUrl")
        // 先写磁盘 (同步), 再返回。避免协程持有 decodedBytes 导致 OOM
        storage.saveImage(book, chapter, imageUrl, decodedBytes)
        return decodedBytes
    }

    class ImageLoadException(message: String) : IllegalStateException(message)
}
