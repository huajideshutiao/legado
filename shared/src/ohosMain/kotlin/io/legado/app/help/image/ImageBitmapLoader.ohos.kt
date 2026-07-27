package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import kotlin.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * [ImageBitmapLoader] 的鸿蒙实现。
 *
 * 解码用 Skia [SkiaImage.makeFromEncoded] + [toComposeImageBitmap]
 * (Compose Multiplatform 鸿蒙端用 Skia 渲染, 与 iOS 端 [io.legado.app.ui.bookshelf.IosBookCover] 解码路径一致)。
 *
 * - 本地路径 (`file://` / `/...`): [File.readBytes] 读文件后 Skia 解码
 *   (鸿蒙端 [kotlin.io.File] 基于 POSIX fs, 行为与 JVM java.io.File 等价)
 * - 网络路径 (`http(s)://`):
 *   - 本地书 / 无书源: [OkHttpClientProviders] 取 [io.legado.app.help.http.KmpHttpClient] 直接 GET
 *     (鸿蒙端 KmpHttpClient 经 napi 桥接 @ohos.net.http, API 与 OkHttp 一致;
 *     okhttp3.Request 在 ohosMain 不可用, 改用 [KmpRequestBuilder])
 *   - 网络书: [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS
 * - `cbz://`: 返回 null (CbzFile 未下沉到 ohosMain, 后续补)
 * - GIF: Skia 解码仅取静态首帧 (与 JVM 端 ImageIO 行为一致)
 */
actual class ImageBitmapLoader {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val bytes = when {
                url.startsWith("cbz://") -> return@withContext null
                url.startsWith("file://") -> runCatching {
                    File(url.removePrefix("file://")).readBytes()
                }.getOrNull()
                url.startsWith("/") -> runCatching {
                    File(url).readBytes()
                }.getOrNull()
                url.startsWith("http://") || url.startsWith("https://") -> {
                    if (bookSource == null || book?.isLocal == true) {
                        // 本地书 / 无书源: 直接 KmpHttpClient GET
                        downloadBytesSimple(url)
                    } else {
                        // 网络书: AnalyzeUrlCore 带书源 header/cookie/charset/JS
                        downloadBytesWithSource(url, bookSource)
                    }
                }
                else -> null
            }
            bytes?.let { decodeBytes(it) }
        }

    /** 简单 GET 取字节流 (本地书 / 无书源用, 参照 iOS IosBookCover.downloadAndDecode)。 */
    private fun downloadBytesSimple(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = KmpRequestBuilder().url(url).get().build()
        return runCatching {
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) null else response.body.bytes()
            } finally {
                response.close()
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

    /** Skia 解码字节数组为 [ImageBitmap] (与 iOS 端 IosBookCover uiImageToBitmap 解码路径一致)。 */
    private fun decodeBytes(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
}
