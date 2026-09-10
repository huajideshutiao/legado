package io.legado.desktop.model.webBook

import io.legado.app.api.controller.ImageControllerProvider
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookImageStorage
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.utils.File
import kotlinx.coroutines.runBlocking

/**
 * desktop 端 [ImageControllerProvider] 最小真实实现 (封面/插图字节流; 超宽缩图策略经构造注入,
 * :desktop 注入 Skia 实现 [DesktopSkiaImageScaler], headless 用默认恒等 —— 不背 skia 依赖)。
 *
 * 直接返回本地缓存原始字节, 失败返回 null → 调用方回 "getCover/getImg error", 不抛异常。
 */
class DesktopImageControllerProvider(
    /** 超宽等比缩图策略 (bytes, width) -> bytes; 默认恒等 (headless 无 skia, 原样返回缓存字节) */
    private val scaleToWidth: (bytes: ByteArray, width: Int) -> ByteArray = { bytes, _ -> bytes },
) : ImageControllerProvider {

    // 按 bookUrl 缓存 book, 避免重复查库
    private var book: Book? = null
    private var bookUrl: String = ""

    override fun getCover(coverPath: String?): ByteArray? = runCatching {
        if (coverPath.isNullOrBlank()) return@runCatching null
        if (coverPath.startsWith("http://", true) || coverPath.startsWith("https://", true)) {
            return@runCatching runBlocking { downloadBytes(coverPath) }
        }
        val file = File(coverPath.removePrefix("file://"))
        if (file.exists()) file.readBytes() else null
    }.getOrNull()

    // width 参数: 仅当图片实际宽度超过 width 时等比缩到 width (对照 app 端
    // ImageProvider.getImage 的缩图语义), 输出 PNG (与 app 端 getImg 的 PNG 编码一致);
    // 不超宽/不可解码时原样返回缓存字节
    override fun getImg(bookUrl: String, src: String, width: Int): ByteArray? = runCatching {
        val cached = if (this.bookUrl == bookUrl) this.book else null
        val book = cached
            ?: runBlocking { AppDbProviders.get().bookDao.getBook(bookUrl) }?.also {
                this.book = it; this.bookUrl = bookUrl
            }
            ?: return@runCatching null
        val path = runBlocking { BookImageStorageProviders.get().imagePathOrSave(book, src) }
        val bytes = path?.let { File(it).takeIf(File::exists)?.readBytes() }
            ?: return@runCatching null
        scaleToWidth(bytes, width)
    }.getOrNull()

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
}

/**
 * 图片缓存占位章节: 路径仅由 book+url 派生, chapter 只参与签名, 占位即可。
 * webBook 图片缓存 (上方 getImg) 与 PDF 页渲染缓存 (model/fileBook/DesktopPdfFile) 共用。
 */
fun placeholderImageChapter(url: String, bookUrl: String): BookChapter =
    BookChapter(url = url, bookUrl = bookUrl)

/**
 * 查图片缓存路径, 未命中先落盘再查一次 (webBook getImg 模式):
 * [BookImageStorage.saveImages] 按源 url 拉取并写入缓存, 二次查询命中即得路径。
 */
private suspend fun BookImageStorage.imagePathOrSave(book: Book, url: String): String? {
    val chapter = placeholderImageChapter(url, book.bookUrl)
    var path = getImagePath(book, chapter, url)
    if (path == null) {
        saveImages(book, chapter, listOf(url))
        path = getImagePath(book, chapter, url)
    }
    return path
}
