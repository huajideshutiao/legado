package io.legado.app.help.image

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookImageDownloadShared
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isFilePath
import io.legado.app.utils.readAllAndClose
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * 漫画页字节加载 (app 端 `help.glide.ImageLoader.loadManga` 下沉)。
 *
 * 图片缓存读写走 [BookImageStorageProviders] (Android 委托 BookHelp, 桌面走 JvmBookImageStorage,
 * iOS/鸿蒙走 NativeBookImageStorage), 下载 + 解密 + 落盘 + 同图去重走章节图片同一条编排
 * [BookImageDownloadShared.loadImageBytes] (四端共用: Coil 端经 MangaModelFetcher 调用,
 * 鸿蒙自绘端直接调用); 本对象的差别只在失败语义: 取不到图抛 [ImageLoadException] 让页面显示
 * 占位/错误 (共享编排自身失败只记日志返回 null)。
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
        // 下载 (含插件源分流) + 解密 + 落盘 + 同图去重走章节图片同一条编排 [BookImageDownloadShared];
        // 漫画页的失败语义: 取不到图抛异常, 由页面显示占位/错误 (共享编排自身失败只记日志返回 null)。
        // 在传入的上下文中执行: 共享编排内部按 currentCoroutineContext 建 AnalyzeUrl (书源 header/代理/限流)
        return withContext(coroutineContext) {
            BookImageDownloadShared.loadImageBytes(bookSource, book, imageUrl, chapter)
        } ?: throw ImageLoadException("图片下载失败: $imageUrl")
    }

    class ImageLoadException(message: String) : IllegalStateException(message)
}
