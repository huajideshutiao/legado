package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext


/**
 * [ImageBitmapLoader] 的鸿蒙实现。
 *
 * 静态图片解码使用 CPF 融合渲染变体随 `ui-graphics-ohosarm64` 解析到的编码图像桥接；
 * 该变体最终由 OHOS `image_source`/`pixelmap`/`native_drawing` 管线绘制，不创建
 * XComponent、EGL Surface 或 Skia GPU 自渲染面。这里保留 `org.jetbrains.skia.Image` API
 * 仅作为 CPF 对 Compose [ImageBitmap] 的兼容解码门面，不代表启用 Skia Renderer。
 *
 * - 本地路径 (`file://` / `/...`): [File.readBytes] 读文件后解码
 *   (鸿蒙端 [kotlin.io.File] 基于 POSIX fs, 行为与 JVM java.io.File 等价)
 * - 网络路径 (`http(s)://`):
 *   - 本地书 / 无书源: [OkHttpClientProviders] 取 [io.legado.app.help.http.KmpHttpClient] 直接 GET
 *     (鸿蒙端 KmpHttpClient 经 napi 桥接 @ohos.net.http, API 与 OkHttp 一致;
 *     okhttp3.Request 在 ohosMain 不可用, 改用 [KmpRequestBuilder])
 *   - 网络书: [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS
 * - `cbz://`: [loadCbzEntryBytes] 经 ArchiveProviders 抽压缩包条目字节后解码
 *   (支持 `cbz://{entry}` + Book 与 `cbz://{path}#{entry}` 自含两种形式)
 * - GIF: [loadBitmap] 当前只取静态首帧；融合渲染不直接调用 Skia Codec，
 *   [rememberAnimatedImageBitmap] 在接入 CPF Coil OHOS 动图解码器前安全退化为静态图
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? =
        withContext(IoDispatcher) {
            ohosLoadImageBytes(url, book, bookSource)?.let { ohosDecodeImageBytes(it) }
        }

    actual suspend fun loadBytes(url: String, book: Book?, bookSource: BookSource?): ByteArray? =
        withContext(IoDispatcher) {
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

/** 通过 CPF OHOS 图形兼容门面将编码字节解码为融合渲染可绘制的 [ImageBitmap]。 */
internal fun ohosDecodeImageBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
