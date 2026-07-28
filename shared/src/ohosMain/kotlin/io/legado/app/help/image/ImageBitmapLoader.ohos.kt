package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext


/**
 * [ImageBitmapLoader] 的鸿蒙实现。
 *
 * 解码用 Skia [org.jetbrains.skia.Image.makeFromEncoded] + [toComposeImageBitmap]
 * (Compose Multiplatform 鸿蒙端用 Skia 渲染, 与 iOS 端 [io.legado.app.ui.bookshelf.IosBookCover] 解码路径一致)。
 *
 * - 本地路径 (`file://` / `/...`): [File.readBytes] 读文件后 Skia 解码
 *   (鸿蒙端 [kotlin.io.File] 基于 POSIX fs, 行为与 JVM java.io.File 等价)
 * - 网络路径 (`http(s)://`):
 *   - 本地书 / 无书源: [OkHttpClientProviders] 取 [io.legado.app.help.http.KmpHttpClient] 直接 GET
 *     (鸿蒙端 KmpHttpClient 经 napi 桥接 @ohos.net.http, API 与 OkHttp 一致;
 *     okhttp3.Request 在 ohosMain 不可用, 改用 [KmpRequestBuilder])
 *   - 网络书: [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS
 * - `cbz://`: [loadCbzEntryBytes] 经 ArchiveProviders 抽压缩包条目字节后 Skia 解码
 *   (支持 `cbz://{entry}` + Book 与 `cbz://{path}#{entry}` 自含两种形式)
 * - GIF: [loadBitmap] 的 Skia 解码仅取静态首帧; 需要动图的消费点改用 [loadBytes] 取裸字节走
 *   [rememberAnimatedImageBitmap] (skiko Codec 逐帧解码, 与 desktop 共用 skikoUiMain 实现)
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            ohosLoadImageBytes(url, book, bookSource)?.let { ohosDecodeImageBytes(it) }
        }

    actual suspend fun loadBytes(url: String, book: Book?, bookSource: BookSource?): ByteArray? =
        withContext(Dispatchers.IO) {
            ohosLoadImageBytes(url, book, bookSource)
        }
}

/** 按 scheme 取图片原始字节 ([ImageBitmapLoader] 的解码前一步, 动图路径直接复用)。 */
private suspend fun ohosLoadImageBytes(
    url: String,
    book: Book?,
    bookSource: BookSource?,
): ByteArray? = when {
    url.startsWith("cbz://") -> loadCbzEntryBytes(url, book?.bookUrl)
    url.startsWith("file://") -> runCatching {
        File(url.removePrefix("file://")).readBytes()
    }.getOrNull()
    url.startsWith("/") -> runCatching {
        File(url).readBytes()
    }.getOrNull()
    url.startsWith("http://") || url.startsWith("https://") ->
        ohosDownloadImageBytes(url, book, bookSource)
    else -> null
}

/**
 * 网络图片取字节流 (鸿蒙端共用, [ImageBitmapLoader] 与 OhosBookCover 磁盘缓存都走这里)。
 * 本地书 / 无书源直接 KmpHttpClient GET; 网络书用 [AnalyzeUrlCore] 带书源 header/cookie/charset/JS (防盗链),
 * 下载后过共享 [ImageUtils.decode] 解密 ([isCover] 选 coverDecodeJs / imageDecode 规则; 无规则原样返回,
 * 解密失败返回 null 走占位, 对齐 app 端语义)。
 */
internal suspend fun ohosDownloadImageBytes(
    url: String,
    book: Book?,
    bookSource: BookSource?,
    isCover: Boolean = false,
): ByteArray? {
    if (bookSource == null || book?.isLocal == true) {
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
    return runCatching {
        val analyzeUrl = AnalyzeUrlCore(
            rawUrl = url,
            source = bookSource,
            coroutineContext = currentCoroutineContext(),
        )
        val raw = analyzeUrl.getByteArrayAwait()
        runScriptWithContext {
            ImageUtils.decode(url, raw, isCover, bookSource, book)
        }
    }.getOrNull()
}

/** Skia 解码字节数组为 [ImageBitmap] (与 iOS 端 IosBookCover uiImageToBitmap 解码路径一致)。 */
internal fun ohosDecodeImageBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
