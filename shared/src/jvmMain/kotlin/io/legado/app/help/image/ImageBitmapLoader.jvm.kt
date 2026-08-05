package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.RemoteAssetsUtils
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
 *     (对照 app 端 `AnalyzeUrl(imageUrl, source=bookSource).getByteArrayAwait()`),
 *     下载后按 [isCover] 跑共享 [ImageUtils.decode] 响应字节解密 (规则为空原样返回)
 * - `cbz://`: [CbzFile.getImage] 读取 zip 内嵌条目图片流
 * - GIF: [ImageIO] 不支持动图, [loadBitmap] 仅取静态首帧; 需要动图的消费点改用 [loadBytes]
 *   取裸字节走 [rememberAnimatedImageBitmap] (skiko Codec 逐帧解码)
 */
actual class ImageBitmapLoader actual constructor() {

    companion object {
        /** 失败 url 跳过表 (对照原版 Glide OkHttpStreamFetcher.companion failUrl: 非 2xx/解密失败进表)。 */
        private val failUrls = java.util.Collections.synchronizedSet(HashSet<String>())
    }

    actual suspend fun loadBitmap(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val image = when {
                url.startsWith("bg://") -> {
                    val fileName = url.removePrefix("bg://")
                    // 优先 composeResources 打包原图 (四端离线可用), 其次本地缓存/CDN 兜底
                    RemoteAssetsUtils.getBgBytes(fileName)
                        ?.let { ImageIO.read(ByteArrayInputStream(it)) }
                }
                url.startsWith("cbz://") && book != null -> {
                    // 本地 cbz/zip 漫画: 从 zip 内嵌条目读取图片流
                    val entryName = url.removePrefix("cbz://")
                    CbzFile.getImage(book, entryName)?.use { input -> ImageIO.read(input) }
                }
                url.startsWith("file://") -> ImageIO.read(File(url.removePrefix("file://")))
                url.startsWith("/") -> ImageIO.read(File(url))
                url.startsWith("http://") || url.startsWith("https://") -> {
                    if (failUrls.contains(url)) {
                        // 跳过加载失败的图片 (原版 OkHttpStreamFetcher 同语义)
                        null
                    } else {
                        val bytes = loadNetworkBytes(url, bookSource, book, isCover)
                        bytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                    }
                }
                // Windows 盘符 (C:\...) / 相对路径: 对照原 DesktopPhotoDialog else 分支 File(src) 直读
                else -> File(url).takeIf { it.isFile }?.let { ImageIO.read(it) }
            }
            image?.toComposeImageBitmap()
        }

    actual suspend fun loadBytes(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("bg://") -> {
                        val fileName = url.removePrefix("bg://")
                        // 优先 composeResources 打包原图, 其次本地缓存/CDN 兜底
                        RemoteAssetsUtils.getBgBytes(fileName)
                    }
                    url.startsWith("cbz://") && book != null ->
                        CbzFile.getImage(book, url.removePrefix("cbz://"))?.use { it.readBytes() }
                    url.startsWith("file://") -> File(url.removePrefix("file://")).readBytes()
                    url.startsWith("/") -> File(url).readBytes()
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        if (failUrls.contains(url)) {
                            // 跳过加载失败的图片 (原版 OkHttpStreamFetcher 同语义)
                            null
                        } else {
                            loadNetworkBytes(url, bookSource, book, isCover)
                        }
                    }
                    // Windows 盘符 / 相对路径: 与 loadBitmap else 分支同规则
                    else -> File(url).takeIf { it.isFile }?.readBytes()
                }
            }.getOrNull()
        }

    /**
     * 网络图字节加载: 先查进程内/磁盘缓存 (对齐原版 PhotoDialog.loadByGlide 的
     * onlyRetrieveFromCache 优先语义与 Glide DiskCacheStrategy.DATA 磁盘缓存),
     * 未命中才下载 + 解密, 成功后回写缓存 (同一 URL 二次打开零重复下载/解密)。
     */
    private suspend fun loadNetworkBytes(
        url: String,
        bookSource: BookSource?,
        book: Book?,
        isCover: Boolean,
    ): ByteArray? =
        ImageBytesCache.get(url, isCover) ?: run {
            val bytes = if (bookSource == null || book?.isLocal == true) {
                // 本地书 / 无书源: 直接 OkHttp GET
                downloadBytesSimple(url)
            } else {
                // 网络书: AnalyzeUrlCore 带书源 header/cookie/charset/JS + 解密
                downloadBytesWithSource(url, bookSource, book, isCover)
            }
            if (bytes != null) ImageBytesCache.put(url, isCover, bytes)
            bytes
        }

    /** 简单 OkHttp GET 取字节流 (本地书 / 无书源用); 非 2xx 进失败表不再重试。 */
    private fun downloadBytesSimple(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failUrls.add(url)
                    null
                } else {
                    response.body?.bytes()
                }
            }
        }.getOrNull()
    }

    /**
     * 用 [AnalyzeUrlCore] 发请求带书源 header/cookie/charset/JS (网络书用),
     * 下载后按 [isCover] 跑共享 [ImageUtils.decode] 响应字节解密 (规则为空原样返回,
     * 解密失败进失败表返回 null, 对齐原版 OkHttpStreamFetcher "封面二次解密失败")。
     */
    private suspend fun downloadBytesWithSource(
        url: String,
        bookSource: BookSource?,
        book: Book?,
        isCover: Boolean,
    ): ByteArray? {
        if (bookSource == null) return downloadBytesSimple(url)
        return runCatching {
            val bytes = AnalyzeUrlCore(
                rawUrl = url,
                source = bookSource,
                coroutineContext = currentCoroutineContext(),
            ).getByteArrayAwait()
            runScriptWithContext {
                ImageUtils.decode(url, bytes, isCover, bookSource, book)
            } ?: run {
                failUrls.add(url)
                null
            }
        }.getOrNull()
    }
}
