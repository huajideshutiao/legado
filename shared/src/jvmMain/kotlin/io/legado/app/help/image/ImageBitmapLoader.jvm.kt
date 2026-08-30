package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

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
 * - `cbz://`: [CbzFile.getImage] 读取 zip 内嵌条目图片流
 * - GIF: 静态读取时取首帧; 需要动图的消费点改用 [loadBytes] 取裸字节走 [rememberAnimatedImageBitmap]
 *
 * 全面基于 Skia [Codec.makeFromData] 采样解码 + [DecodedBitmapCache] 进程级 LRU,
 * 零 SPI 反射查找卡顿, 原生支持 WebP / GIF / PNG / JPEG / BMP。
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


    actual suspend fun loadBytes(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    // data: URI 内联图 (与 loadBitmap 的 data: 分支对齐, 原 app PhotoDialog
                    // 的 base64 SVG 分支同源)
                    url.startsWith("data:") -> parseDataUriBytes(url)
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
                    response.body.bytes()
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

/**
 * 带目标长边上限解码 (maxDim<=0 按 2048 兜底)。
 *
 * Skia [Codec] 只接受它自己声明的采样档 (JPEG 只有 N/8, PNG 一档都没有), 传任意目标尺寸会抛
 * "Invalid scale" 让整张图解不出来, 故一律按原尺寸解, 超上限时再重采样一次到目标长边。
 * 解码结果转不可变后所有权交给 [ImageBitmap]: 零中间拷贝, 且绘制时能复用同一张纹理。
 */
actual fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val target = if (maxDim > 0) maxDim else 2048
    return runCatching {
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                val info = codec.imageInfo
                if (info.width <= 0 || info.height <= 0) return@runCatching null
                val decodeInfo = ImageInfo.makeN32(info.width, info.height, ColorAlphaType.PREMUL)
                val bitmap = Bitmap()
                if (!bitmap.allocPixels(decodeInfo)) return@runCatching null
                codec.readPixels(bitmap, 0, -1)
                bitmap.setImmutable()
                if (max(info.width, info.height) <= target) {
                    bitmap.asComposeImageBitmap()
                } else {
                    bitmap.use { src -> resampledToLongSide(src, target) }
                }
            }
        }
    }.getOrNull()
}

/** 长边重采样到 [target] (只缩不放, Mitchell 滤波); 结果位图所有权交给返回的 [ImageBitmap]。 */
private fun resampledToLongSide(src: Bitmap, target: Int): ImageBitmap? {
    val scale = target.toFloat() / max(src.width, src.height)
    val width = (src.width * scale).roundToInt().coerceAtLeast(1)
    val height = (src.height * scale).roundToInt().coerceAtLeast(1)
    val dst = Bitmap()
    if (!dst.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))) return null
    // src 已不可变, makeFromBitmap 直接共享像素不再拷贝
    Image.makeFromBitmap(src).use { image ->
        Canvas(dst).use { canvas ->
            canvas.drawImageRect(
                image,
                Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                SamplingMode.MITCHELL,
                null,
                true,
            )
        }
    }
    dst.setImmutable()
    return dst.asComposeImageBitmap()
}

/**
 * SVG 兜底解码 (jsvg, 复用 jvmMain 已有 SvgRasterizer 栅格化后由 Skia 直接生成 ImageBitmap)。
 */
actual fun decodeSvgFallback(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    val png = SvgRasterizer.toPng(bytes, if (maxDim > 0) maxDim else 2048) ?: return null
    return runCatching {
        Image.makeFromEncoded(png).use { it.toComposeImageBitmap() }
    }.getOrNull()
}

/**
 * AWT [BufferedImage] → Skia [Image], 给只会画 Java2D 的渲染器 (jsvg / PDFBox) 当出口。
 *
 * `TYPE_INT_RGB/ARGB` 的 raster 本身就是每像素一个 `0xAARRGGBB` int, 按小端拆字节即
 * B,G,R,A —— 正好是 Skia 的 [ColorType.BGRA_8888], 故整块搬一次即可, 不必逐像素 getRGB
 * 再拆装通道 (compose 自带的 `BufferedImage.toComposeImageBitmap` 就是逐像素 getRGB)。
 *
 * alpha 一律按未预乘交出: 预乘留给 Skia 编码器, 免掉手写预乘再被编码器还原的两次取整误差。
 * [Image.makeRaster] 内部复制像素, 返回的 Image 不再引用本地字节数组。
 */
fun BufferedImage.toSkiaImage(): Image {
    val count = width * height
    val pixels = ByteArray(count * 4)
    // 小端序视图上的整型批量 put 走 copyMemory (单次 memcpy), 不是逐元素循环
    ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        .put(packedPixels(), 0, count)
    val info = ImageInfo(
        width,
        height,
        ColorType.BGRA_8888,
        if (colorModel.hasAlpha()) ColorAlphaType.UNPREMUL else ColorAlphaType.OPAQUE,
    )
    return Image.makeRaster(info, pixels, width * 4)
}

/** 每像素 `0xAARRGGBB` 的整型像素: 布局吻合时直接用 raster 自身数组 (零拷贝), 否则批量 getRGB。 */
private fun BufferedImage.packedPixels(): IntArray {
    val buffer = raster.dataBuffer
    val model = sampleModel
    if ((type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB) &&
        buffer is DataBufferInt && buffer.numBanks == 1 && buffer.offset == 0 &&
        buffer.size == width * height &&
        model is SinglePixelPackedSampleModel && model.scanlineStride == width
    ) {
        return buffer.data
    }
    return getRGB(0, 0, width, height, null, 0, width)
}
