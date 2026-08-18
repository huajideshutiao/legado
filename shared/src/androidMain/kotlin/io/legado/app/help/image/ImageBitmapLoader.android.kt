package io.legado.app.help.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
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
 *
 * 网络图响应字节解密: 对齐 app 端 Glide OkHttpStreamFetcher——下载后按 [isCover]
 * 跑共享 [ImageUtils.decode] (true=coverDecodeJs 封面解密; false=imageDecode 正文解密),
 * 规则为空原样返回; 非 2xx / 解密失败记入进程级失败表, 后续不再反复请求死链。
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
                val bitmap =
                    runCatching { decodeBytes(bytes, maxOf(widthPx, heightPx)) }.getOrNull()
                        ?: decodeSvgFallback(bytes, maxOf(widthPx, heightPx))
                if (bitmap != null && key != null) DecodedBitmapCache.put(key, bitmap)
                return@withContext bitmap
            }
            val bytes = loadBytes(url, book, bookSource, isCover) ?: return@withContext null
            val key = if (useBitmapCache) {
                DecodedBitmapCache.cacheKey(url, bookSource?.bookSourceUrl, isCover, widthPx, heightPx)
            } else null
            val cached = key?.let { DecodedBitmapCache.get(it) }
            if (cached != null) return@withContext cached
            val bitmap = runCatching { decodeBytes(bytes, maxOf(widthPx, heightPx)) }.getOrNull()
                ?: decodeSvgFallback(bytes, maxOf(widthPx, heightPx))
            if (bitmap != null && key != null) DecodedBitmapCache.put(key, bitmap)
            bitmap
        }

    /** 同 [loadBitmap], 返回原始字节 (动图/需要原始数据的消费点用)。 */
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
                        // 优先 composeResources 打包原图 (四端离线可用), 其次本地缓存/CDN 兜底
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
                    // Windows 盘符 (C:\...) / 相对路径: 与 loadBitmap else 分支同规则
                    else -> File(url).takeIf { it.isFile }?.readBytes()
                }
            }.getOrNull()
        }

    /**
     * BitmapFactory 解码, 先读边界再按目标尺寸降采样 (inSampleSize 取 2 的幂,
     * 解码后长边 ≥ target/2); 未显式指定目标尺寸时按长边 ≤2048 防大图解码 OOM (原语义)。
     */
    internal fun decodeBytes(bytes: ByteArray, maxDim: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcMax = max(bounds.outWidth, bounds.outHeight)
        if (srcMax <= 0) return null
        val target = if (maxDim > 0) maxDim else 2048
        var inSampleSize = 1
        while (srcMax / 2 / inSampleSize >= target) inSampleSize *= 2
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
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
                downloadBytesSimple(url)
            } else {
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

/** 带目标长边上限解码: BitmapFactory 解码前采样 (maxDim<=0 保持长边 ≤2048 防 OOM)。 */
actual fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap? =
    ImageBitmapLoader().decodeBytes(bytes, maxDim)

/**
 * SVG 兜底解码 (androidsvg, 依赖 shared androidMain 已有 libs.androidsvg)。
 *
 * 栅格解码失败后按 SVG 渲染, 渲染参数对齐 app 端 SvgUtils.createBitmap / shared
 * ImageProvider.android.kt SvgDecode: 固有尺寸取 documentWidth/Height (androidsvg 已换算 px),
 * 缺失回落 viewBox; 无 viewBox 时补一个与固有尺寸一致的 viewBox 再以 100% 画布渲染。
 * 长边受 [maxDim] 约束只缩不放 (对齐 createBitmap 的 ratio 语义), maxDim<=0 按 2048 防大 viewBox 撑爆内存。
 */
actual fun decodeSvgFallback(bytes: ByteArray, maxDim: Int): ImageBitmap? = runCatching {
    val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
    val srcW = svg.documentWidth.toInt().takeIf { it > 0 }
        ?: svg.documentViewBox?.let { (it.right - it.left).toInt() } ?: return null
    val srcH = svg.documentHeight.toInt().takeIf { it > 0 }
        ?: svg.documentViewBox?.let { (it.bottom - it.top).toInt() } ?: return null
    if (srcW <= 0 || srcH <= 0) return null
    val target = if (maxDim > 0) maxDim else 2048
    val ratio = minOf(1f, target.toFloat() / maxOf(srcW, srcH))
    val w = (srcW * ratio).toInt().coerceAtLeast(1)
    val h = (srcH * ratio).toInt().coerceAtLeast(1)
    if (svg.documentViewBox == null) {
        svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
    }
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()
