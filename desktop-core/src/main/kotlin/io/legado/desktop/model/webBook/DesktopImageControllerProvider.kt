package io.legado.desktop.model.webBook

import io.legado.app.api.controller.ImageControllerProvider
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.file.desktopResolveStoredRef
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.utils.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * desktop 端 [ImageControllerProvider] 真实实现 (封面/插图字节流)。
 *
 * 漫画插图链路直接复用四端统一的 [MangaImageBytesLoader] (带书源 header/防盗链下载,
 * 并跑 ImageUtils.decode 执行反爬切片重排与落盘缓存, 与 Android 原版 BookHelp 对齐)。
 */
class DesktopImageControllerProvider : ImageControllerProvider {

    // 按 bookUrl 缓存 book 与 bookSource 快照, 避免每张切片重复查库, 并发安全
    @Volatile
    private var cache: BookSourceCache? = null

    override fun getCover(coverPath: String?): ByteArray? = runCatching {
        if (coverPath.isNullOrBlank()) return@runCatching null
        if (coverPath.startsWith("http://", true) || coverPath.startsWith("https://", true)) {
            return@runCatching runBlocking { downloadBytes(coverPath) }
        }
        val file = desktopResolveStoredRef(coverPath)
        if (file.exists() && file.isFile) file.readBytes() else null
    }.getOrNull()

    override fun getImg(bookUrl: String, src: String, width: Int): ByteArray? = runCatching {
        val cached = resolveBookAndSource(bookUrl) ?: return@runCatching null
        runBlocking {
            withContext(Dispatchers.IO) {
                MangaImageBytesLoader.load(src, cached.book, cached.source, coroutineContext)
            }
        }
    }.getOrNull()

    private fun resolveBookAndSource(bookUrl: String): BookSourceCache? {
        val current = cache
        if (current != null && current.bookUrl == bookUrl) {
            return current
        }
        val book = runBlocking { AppDbProviders.get().bookDao.getBook(bookUrl) } ?: return null
        val source = if (book.isLocal) null else {
            runBlocking { AppDbProviders.get().bookSourceDao.getBookSource(book.origin) }
        }
        val newCache = BookSourceCache(bookUrl, book, source)
        cache = newCache
        return newCache
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient
        return try {
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class BookSourceCache(
        val bookUrl: String,
        val book: Book,
        val source: BookSource?,
    )
}

/**
 * 图片缓存占位章节: 路径仅由 book+url 派生, chapter 只参与签名, 占位即可。
 * webBook 图片缓存与 PDF 页渲染缓存 (model/fileBook/DesktopPdfFile) 共用。
 */
fun placeholderImageChapter(url: String, bookUrl: String): BookChapter =
    BookChapter(url = url, bookUrl = bookUrl)

