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
import io.legado.app.utils.SvgRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import kotlin.math.max

/**
 * [ImageBitmapLoader] 的 JVM (desktop) 实现。
 *
 * 合并自 desktop AudioPlayScreen.loadAudioCover + MangaReaderScreen.loadMangaImage,
 * 消除两端 OkHttp + ImageIO + toComposeImageBitmap 重复样板。
 *
 * - 本地路径 (`file://` / `/...`): 读文件字节解码
 * - 网络路径 (`http(s)://`):
 *   - 本地书 / 无书源: [OkHttpClientProviders] 直接 GET (无书源 header 配置)
 *   - 网络书: [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS
 *     (对照 app 端 `AnalyzeUrl(imageUrl, source=bookSource).getByteArrayAwait()`),
 *     下载后按 [isCover] 跑共享 [ImageUtils.decode] 响应字节解密 (规则为空原样返回)
 * - `cbz://`: [CbzFile.getImage] 读取 zip 内嵌条目图片流
 * - GIF: [ImageIO] 不支持动图, [loadBitmap] 仅取静态首帧; 需要动图的消费点改用 [loadBytes]
 *   取裸字节走 [rememberAnimatedImageBitmap] (skiko Codec 逐帧解码)
 *
 * # 结构 (2026 图片加载深度优化 I1/I7)
 *
 * [loadBitmap] 与 [loadBytes] 共用同一条字节链路 (含 [ImageBytesCache] 内存/磁盘缓存 +
 * failUrl 跳过表), 对齐 android/ios/ohos; 解码经 [decodeBytesSampled] (ImageIO
 * setSourceSubsampling 解码前采样, 内存收益在解码峰值) + [DecodedBitmapCache] 进程级 LRU,
 * 同 URL 二次打开零重复解码。
 */
actual class ImageBitmapLoader actual constructor() {

    companion object {
        /** 失败 url 跳过表 (对照原版 Glide OkHttpStreamFetcher.companion failUrl: 非 2xx/解密失败进表)。
         * key 带书源维度 (origin+url): 不同书源同 URL 互不影响, 换源/无源→有源切换后不再被
         * 旧失败记录拦截可重新加载; 无书源 (裸 GET) 时 key 即 url, 保持原死链跳过语义。 */
        private val failUrls = java.util.Collections.synchronizedSet(HashSet<String>())

        private fun failKey(origin: String?, url: String): String =
            if (origin.isNullOrEmpty()) url else "$origin\u0000$url"
    }

    actual suspend fun loadBitmap(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
        widthPx: Int,
        heightPx: Int,
        useBitmapCache: Boolean,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            // 字节路径与 loadBytes 合一 (含 ImageBytesCache + failUrl 跳过表), 对齐四端结构;
            // 失败/不支持的 scheme 返回 null (调用方占位, 原 else 分支抛异常语义收敛为 null)。
            val bytes = loadBytes(url, book, bookSource, isCover) ?: return@withContext null
            val key = if (useBitmapCache) {
                DecodedBitmapCache.cacheKey(url, bookSource?.bookSourceUrl, isCover, widthPx, heightPx)
            } else null
            val cached = key?.let { DecodedBitmapCache.get(it) }
            if (cached != null) return@withContext cached
            val maxDim = maxOf(widthPx, heightPx)
            val bitmap = decodeBytesSampled(bytes, maxDim) ?: decodeSvgFallback(bytes, maxDim)
            if (bitmap != null && key != null) DecodedBitmapCache.put(key, bitmap)
            bitmap
        }

    /**
     * 解码字节为 [java.awt.image.BufferedImage]; [maxDim]>0 时解码前采样
     * (ImageReader.setSourceSubsampling, 对齐原版 Glide Downsampler 的解码前采样语义,
     * 内存收益在解码峰值; 采样因子取 2 的幂, 解码后长边 ≥ maxDim/2)。
     * 无法识别 (如 WEBP 无 reader) / 解码失败返回 null, 由调用方回落 skia 解码或占位。
     */
    internal fun decodeBufferedImage(bytes: ByteArray, maxDim: Int): java.awt.image.BufferedImage? {
        if (maxDim <= 0) return ImageIO.read(ByteArrayInputStream(bytes))
        return runCatching {
            val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0) return null
                var sample = 1
                while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
                if (sample > 1) {
                    val param = ImageReadParam().apply {
                        setSourceSubsampling(sample, sample, 0, 0)
                    }
                    reader.read(0, param)
                } else {
                    reader.read(0)
                }
            } finally {
                reader.dispose()
                input.close()
            }
        }.getOrNull()
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
                        if (failUrls.contains(failKey(bookSource?.bookSourceUrl, url))) {
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
        ImageBytesCache.get(url, bookSource?.bookSourceUrl, isCover) ?: run {
            val bytes = if (bookSource == null || book?.isLocal == true) {
                // 本地书 / 无书源: 直接 OkHttp GET
                downloadBytesSimple(url)
            } else {
                // 网络书: AnalyzeUrlCore 带书源 header/cookie/charset/JS + 解密
                downloadBytesWithSource(url, bookSource, book, isCover)
            }
            if (bytes != null) {
                ImageBytesCache.put(url, bookSource?.bookSourceUrl, isCover, bytes)
            }
            bytes
        }

    /** 简单 OkHttp GET 取字节流 (本地书 / 无书源用); 非 2xx 进失败表不再重试。 */
    private fun downloadBytesSimple(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder().url(url).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failUrls.add(failKey(null, url))
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
                failUrls.add(failKey(bookSource.bookSourceUrl, url))
                null
            }
        }.getOrNull()
    }
}

/** 带目标长边上限解码: ImageIO ImageReader 解码前采样 (maxDim<=0 全尺寸)。 */
actual fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap? =
    ImageBitmapLoader().decodeBufferedImage(bytes, maxDim)?.toComposeImageBitmap()

/**
 * SVG 兜底解码 (jsvg, 复用 jvmMain 已有 SvgRasterizer 栅格化)。
 *
 * 栅格解码失败后按 SVG 渲染: [SvgRasterizer.toPng] 按长边 [maxDim] (只缩不放, 内部
 * MAX_EDGE=2048 默认) 渲染 PNG 字节, 再 ImageIO 解码回位图。
 */
actual fun decodeSvgFallback(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    val png = SvgRasterizer.toPng(bytes, if (maxDim > 0) maxDim else 2048) ?: return null
    return ImageIO.read(ByteArrayInputStream(png))?.toComposeImageBitmap()
}
