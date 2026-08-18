package io.legado.app.help.image

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.File
import io.legado.app.utils.ImageUtils
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext

/** 失败 url 进程级跳过表 (对照原版 Glide OkHttpStreamFetcher.companion failUrl: 非 2xx/解密失败进表)。
 * key 带书源维度 (origin+url): 不同书源同 URL 互不影响, 换源/无源→有源切换后不再被
 * 旧失败记录拦截可重新加载; 无书源 (裸 GET) 时 key 即 url, 保持原死链跳过语义。 */
private val iosFailUrlsLock = SynchronizedObject()
private val iosFailUrls = HashSet<String>()

private fun iosFailKey(origin: String?, url: String): String =
    if (origin.isNullOrEmpty()) url else "$origin\u0000$url"

private fun isIosFailUrl(origin: String?, url: String): Boolean =
    synchronized(iosFailUrlsLock) { iosFailUrls.contains(iosFailKey(origin, url)) }

private fun markIosFailUrl(origin: String?, url: String) {
    synchronized(iosFailUrlsLock) { iosFailUrls.add(iosFailKey(origin, url)) }
}

/**
 * [ImageBitmapLoader] 的 iOS 实现（自下载链路, 与 android/jvm/ohos 四端同构）。
 *
 * - `file://` / 绝对路径: 直接读文件字节 → Skia 解码
 * - `bg://`: 转 CDN 下载 URL 直下 (原版全图不随包远程下载语义; 字节进 [ImageBytesCache] 磁盘缓存,
 *   下载过即本地可用)
 * - `cbz://`: 前置直解, 不经网络 ([loadCbzEntryBytes] 经 ArchiveProviders 抽压缩包条目字节 → Skia 解码)
 * - `http(s)://`: 自下载 (本地书/无书源 → Ktor 直 GET; 网络书 → [AnalyzeUrlCore] 带书源
 *   header/cookie/charset/JS) → 按 [isCover] 跑共享 [ImageUtils.decode] 响应字节解密
 *   (true=coverDecodeJs 封面, false=imageDecode 正文) → 字节进 [ImageBytesCache]
 *   (key 含 isCover, 正文图缓存与其他图片隔离); 非 2xx/解密失败进进程级失败表
 *
 * # 双链路设计 (2026-08 拍板, 对齐 jvm/android)
 *
 * 正文图/图片预览/字节消费方走本自下载链路 ([ImageBytesCache] 独立缓存, 与其他图片隔离);
 * 书架封面等常规组件仍走 Coil3 共享管线 (BookImageLoader.ios → CoverDecodeFetcher,
 * 磁盘缓存 + 防盗链), 两条链路互不共享缓存, 正文图缓存不受封面换源/重试影响。
 *
 * 失败: 返回 null (调用方负责占位/日志)。
 */
actual class ImageBitmapLoader actual constructor() {

    actual suspend fun loadBitmap(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
        widthPx: Int,
        heightPx: Int,
        useBitmapCache: Boolean,
    ): ImageBitmap? =
        withContext(IoDispatcher) {
            // data: URI 早返回: 内联 svg/图片直接解析内容, 不走网络/文件加载 (简介图等)
            if (url.startsWith("data:")) {
                val bytes = parseDataUriBytes(url) ?: return@withContext null
                val maxDim = maxOf(widthPx, heightPx)
                val key = if (useBitmapCache) {
                    DecodedBitmapCache.cacheKey(
                        url,
                        bookSource?.bookSourceUrl,
                        isCover,
                        widthPx,
                        heightPx
                    )
                } else null
                val cached = key?.let { DecodedBitmapCache.get(it) }
                if (cached != null) return@withContext cached
                val bitmap = decodeBytesSampled(bytes, maxDim) ?: decodeSvgFallback(bytes, maxDim)
                if (bitmap != null && key != null) DecodedBitmapCache.put(key, bitmap)
                return@withContext bitmap
            }
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
     * 同 [loadBitmap], 返回原始字节 (动图/需要原始数据的消费点用)。
     * 网络图同样自下载 + 按 [isCover] 解密 (见 [loadNetworkBytes]);
     * scheme 支持范围与 [loadBitmap] 一致。
     */
    actual suspend fun loadBytes(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
    ): ByteArray? =
        withContext(IoDispatcher) {
            runCatching {
                when {
                    url.startsWith("bg://") -> {
                        // bg:// 内置背景图: 原版远程下载语义, 转 CDN URL 直下 (字节进 ImageBytesCache)
                        downloadBytesSimple(bgCdnUrl(url.removePrefix("bg://")))
                    }

                    url.startsWith("cbz://") -> loadCbzEntryBytes(url, book?.bookUrl)
                    url.startsWith("file://") -> File(url.removePrefix("file://")).readBytes()
                    url.startsWith("/") -> File(url).readBytes()
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        if (isIosFailUrl(bookSource?.bookSourceUrl, url)) {
                            // 跳过加载失败的图片 (原版 OkHttpStreamFetcher 同语义)
                            null
                        } else {
                            loadNetworkBytes(url, bookSource, book, isCover)
                        }
                    }

                    else -> null
                }
            }.getOrNull()
        }

    /**
     * 网络图字节加载: 先查 [ImageBytesCache] (进程内 LRU + 磁盘, key 含 isCover,
     * 正文图与封面/其他图片隔离), 未命中才下载 + 解密, 成功后回写缓存。
     */
    private suspend fun loadNetworkBytes(
        url: String,
        bookSource: BookSource?,
        book: Book?,
        isCover: Boolean,
    ): ByteArray? =
        ImageBytesCache.get(url, bookSource?.bookSourceUrl, isCover) ?: run {
            val bytes = if (bookSource == null || book?.isLocal == true) {
                downloadBytesSimple(url)
            } else {
                downloadBytesWithSource(url, bookSource, book, isCover)
            }
            if (bytes != null) {
                ImageBytesCache.put(url, bookSource?.bookSourceUrl, isCover, bytes)
            }
            bytes
        }

    /** 简单 GET 取字节流 (本地书 / 无书源用); 非 2xx 进失败表不再重试。 */
    private suspend fun downloadBytesSimple(url: String): ByteArray? {
        val client = OkHttpClientProviders.get().okHttpClient.ktorClient ?: return null
        return runCatching {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                markIosFailUrl(null, url)
                null
            } else {
                response.bodyAsBytes()
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
                markIosFailUrl(bookSource.bookSourceUrl, url)
                null
            }
        }.getOrNull()
    }
}

/**
 * 带目标长边上限解码: Skia 全量解码后按 Canvas 缩放 (省常驻内存与绘制带宽,
 * 解码峰值内存不变; iOS 无解码前采样 API, 见 [downscaled])。
 */
actual fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    val bitmap = runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull() ?: return null
    return if (maxDim > 0) bitmap.downscaled(maxDim) else bitmap
}

/**
 * Skia 解码后的位图按长边缩放 (双线性, Compose Canvas 绘制到新位图)。
 * 仅省常驻内存与绘制带宽, 解码峰值内存不变 (iOS 无 ImageIO 式解码前采样)。
 */
private fun ImageBitmap.downscaled(maxDim: Int): ImageBitmap {
    val max = maxOf(width, height)
    if (max <= maxDim) return this
    val scale = maxDim.toFloat() / max
    val nw = (width * scale).toInt().coerceAtLeast(1)
    val nh = (height * scale).toInt().coerceAtLeast(1)
    val out = ImageBitmap(nw, nh)
    val canvas = Canvas(out)
    canvas.drawImageRect(
        image = this,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(width, height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(nw, nh),
        paint = Paint().apply { filterQuality = FilterQuality.Low },
    )
    return out
}

/**
 * SVG 兜底解码 (skiko SVGDOM, commonMain API 四端一致)。
 *
 * 栅格解码失败后按 SVG 渲染: 字节→SVGDOM, 固有尺寸取 root.getIntrinsicSize
 * (width/height → viewBox 兜底, 百分比按容器 2048 hint 解析), 按长边 [maxDim] 等比缩放
 * (只缩不放, maxDim<=0 按 2048) 后 setContainerSize 并 Surface 离屏渲染成 Compose 位图。
 */
actual fun decodeSvgFallback(bytes: ByteArray, maxDim: Int): ImageBitmap? = runCatching {
    val dom = SVGDOM(Data.makeFromBytes(bytes))
    val root = dom.root ?: return null
    val intrinsic = root.getIntrinsicSize(SVGLengthContext(2048f, 2048f, 90f))
    val srcW = intrinsic.x
    val srcH = intrinsic.y
    if (srcW <= 0f || srcH <= 0f) return null
    val target = if (maxDim > 0) maxDim else 2048
    val ratio = minOf(1f, target.toFloat() / maxOf(srcW, srcH))
    val w = (srcW * ratio).toInt().coerceAtLeast(1)
    val h = (srcH * ratio).toInt().coerceAtLeast(1)
    dom.setContainerSize(w.toFloat(), h.toFloat())
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.clear(Color.TRANSPARENT)
    dom.render(surface.canvas)
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()
