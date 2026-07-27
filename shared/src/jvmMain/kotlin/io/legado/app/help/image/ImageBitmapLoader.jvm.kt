package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.CbzFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * [ImageBitmapLoader] 的 JVM (desktop) 实现。
 *
 * 合并自 desktop AudioPlayScreen.loadAudioCover + MangaReaderScreen.loadMangaImage,
 * 消除两端 OkHttp + ImageIO + toComposeImageBitmap 重复样板。
 *
 * - 本地路径 (`file://` / `/...`): [ImageIO.read] 读文件
 * - 网络路径 (`http(s)://`):
 *   - 本地书 / 无书源: [OkHttpClientProviders] 直接 GET (无书源 header 配置)
 *   - 网络书: [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS
 *     (对照 app 端 `AnalyzeUrl(imageUrl, source=bookSource).getByteArrayAwait()`)
 * - `cbz://`: [CbzFile.getImage] 读取 zip 内嵌条目图片流
 * - GIF: [ImageIO] 不支持动图, [loadBitmap] 仅取静态首帧; 需要动图的消费点改用 [loadBytes]
 *   取裸字节走 [rememberAnimatedImageBitmap] (skiko Codec 逐帧解码)
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val image = when {
                url.startsWith("cbz://") && book != null -> {
                    // 本地 cbz/zip 漫画: 从 zip 内嵌条目读取图片流
                    val entryName = url.removePrefix("cbz://")
                    CbzFile.getImage(book, entryName)?.use { input -> ImageIO.read(input) }
                }
                url.startsWith("file://") -> ImageIO.read(File(url.removePrefix("file://")))
                url.startsWith("/") -> ImageIO.read(File(url))
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val bytes = if (bookSource == null || book?.isLocal == true) {
                        // 本地书 / 无书源: 直接 OkHttp GET
                        downloadBytesSimple(url)
                    } else {
                        // 网络书: AnalyzeUrlCore 带书源 header/cookie/charset/JS
                        downloadBytesWithSource(url, bookSource)
                    }
                    bytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                }
                // Windows 盘符 (C:\...) / 相对路径: 对照原 DesktopPhotoDialog else 分支 File(src) 直读
                else -> File(url).takeIf { it.isFile }?.let { ImageIO.read(it) }
            }
            image?.toComposeImageBitmap()
        }

    actual suspend fun loadBytes(url: String, book: Book?, bookSource: BookSource?): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("cbz://") && book != null ->
                        CbzFile.getImage(book, url.removePrefix("cbz://"))?.use { it.readBytes() }
                    url.startsWith("file://") -> File(url.removePrefix("file://")).readBytes()
                    url.startsWith("/") -> File(url).readBytes()
                    url.startsWith("http://") || url.startsWith("https://") ->
                        if (bookSource == null || book?.isLocal == true) {
                            downloadBytesSimple(url)
                        } else {
                            downloadBytesWithSource(url, bookSource)
                        }
                    // Windows 盘符 / 相对路径: 与 loadBitmap else 分支同规则
                    else -> File(url).takeIf { it.isFile }?.readBytes()
                }
            }.getOrNull()
        }

    /** 简单 OkHttp GET 取字节流 (本地书 / 无书源用)。 */    private fun downloadBytesSimple(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        }.getOrNull()
    }

    /** 用 [AnalyzeUrlCore] 发请求带书源 header/cookie/charset/JS (网络书用)。 */
    private suspend fun downloadBytesWithSource(url: String, bookSource: BookSource?): ByteArray? {
        if (bookSource == null) return downloadBytesSimple(url)
        return runCatching {
            val analyzeUrl = AnalyzeUrlCore(
                rawUrl = url,
                source = bookSource,
                coroutineContext = currentCoroutineContext(),
            )
            analyzeUrl.getByteArrayAwait()
        }.getOrNull()
    }
}
