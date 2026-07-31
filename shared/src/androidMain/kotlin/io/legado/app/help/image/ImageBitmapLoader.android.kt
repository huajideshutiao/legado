package io.legado.app.help.image

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import java.io.File
import kotlin.math.max

/**
 * [ImageBitmapLoader] 的 Android 实现。
 *
 * 逻辑镜像 [JvmImageBitmapLoader]: Android 无 `javax.imageio.ImageIO` (JDK 专属),
 * 解码改用 `android.graphics.BitmapFactory` + `asImageBitmap()`, 其余分支一致:
 * - 本地路径 (`file://` / `/...` / 盘符): 直接读文件字节
 * - 网络路径 (`http(s)://`): 本地书/无书源 → OkHttp 直 GET; 网络书 → [AnalyzeUrlCore]
 *   带书源 header / cookie / charset / JS
 * - `cbz://`: [CbzFile.getImage] 读取 zip 内嵌条目图片流
 *
 * 消费点: 图片预览 (PhotoDialogContent) / 验证码 (iOS/ohos) / ReaderImageResolver
 * 磁盘缓存回退——stub 返回 null 会白屏或退化为重新下载, 故本实现补齐。
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val bytes = loadBytes(url, book, bookSource) ?: return@withContext null
            runCatching { decodeBytes(bytes) }.getOrNull()
        }

    /** 同 [loadBitmap], 返回原始字节 (动图/需要原始数据的消费点用)。 */
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
                    // Windows 盘符 (C:\...) / 相对路径: 与 loadBitmap else 分支同规则
                    else -> File(url).takeIf { it.isFile }?.readBytes()
                }
            }.getOrNull()
        }

    /** BitmapFactory 解码, 先读边界再按长边 ≤2048 降采样, 防大图解码 OOM。 */
    private fun decodeBytes(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        var inSampleSize = 1
        while (maxDim / (inSampleSize * 2) >= 2048) inSampleSize *= 2
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    /** 简单 OkHttp GET 取字节流 (本地书 / 无书源用)。 */
    private fun downloadBytesSimple(url: String): ByteArray? {
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
            AnalyzeUrlCore(
                rawUrl = url,
                source = bookSource,
                coroutineContext = currentCoroutineContext(),
            ).getByteArrayAwait()
        }.getOrNull()
    }
}
