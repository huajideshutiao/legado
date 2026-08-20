package io.legado.app.help.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext

/** 失败 url 跳过表 (对照原版 Glide OkHttpStreamFetcher.companion failUrl: 非 2xx/解密失败进表)。
 * key 带书源维度 (origin+url): 不同书源同 URL 互不影响, 换源/无源→有源切换后不再被
 * 旧失败记录拦截可重新加载; 无书源 (裸 GET) 时 key 即 url, 保持原死链跳过语义。 */
private val ohosFailUrls = HashSet<String>()
private val ohosFailUrlsMutex = Mutex()

private fun ohosFailKey(origin: String?, url: String): String =
    if (origin.isNullOrEmpty()) url else "$origin\u0000$url"

private suspend fun ohosFailUrlsContains(origin: String?, url: String): Boolean =
    ohosFailUrlsMutex.withLock { ohosFailUrls.contains(ohosFailKey(origin, url)) }

private suspend fun ohosFailUrlsAdd(origin: String?, url: String) {
    ohosFailUrlsMutex.withLock { ohosFailUrls.add(ohosFailKey(origin, url)) }
}


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
            val bytes = ohosLoadImageBytes(url, book, bookSource, isCover) ?: return@withContext null
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
        withContext(IoDispatcher) {
            ohosLoadImageBytes(url, book, bookSource, isCover)
        }
}

/**
 * 带目标长边上限解码 (鸿蒙版, 与 iOS 端 [decodeBytesSampled] 同实现)。
 *
 * CPF 融合渲染的解码门面无解码前采样参数, 故同 iOS: 全量解码后按 Canvas 缩放
 * (省常驻内存与绘制带宽, 解码峰值内存不变)。缩放失败 (CPF 桥未接 raster Canvas)
 * 由 [downscaled] 内部 runCatching 退回全尺寸位图, 即改动前的行为。
 */
actual fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    val bitmap = ohosDecodeImageBytes(bytes) ?: return null
    return if (maxDim > 0) bitmap.downscaled(maxDim) else bitmap
}

/**
 * Skia 解码后的位图按长边缩放 (双线性, Compose Canvas 绘制到新位图), 与 iOS 端逐行同实现。
 * 缩放路径依赖 CPF ohos ui-graphics 的 raster Canvas 桥, 失败即退回原位图不影响加载。
 */
private fun ImageBitmap.downscaled(maxDim: Int): ImageBitmap {
    val max = maxOf(width, height)
    if (max <= maxDim) return this
    val scale = maxDim.toFloat() / max
    val nw = (width * scale).toInt().coerceAtLeast(1)
    val nh = (height * scale).toInt().coerceAtLeast(1)
    return runCatching {
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
        out
    }.getOrDefault(this)
}

/** 按 scheme 取图片原始字节 ([ImageBitmapLoader] 的解码前一步, 动图路径直接复用)。 */
private suspend fun ohosLoadImageBytes(
    url: String,
    book: Book?,
    bookSource: BookSource?,
    isCover: Boolean,
): ByteArray? = when {
    // bg:// 内置背景图: 原版远程下载语义 (全图不随包, 本地缓存一级兜底)
    url.startsWith("bg://") -> ohosLoadBgBytes(url.removePrefix("bg://"))

    url.startsWith("cbz://") -> loadCbzEntryBytes(url, book?.bookUrl)
    url.startsWith("file://") -> runCatching {
        File(url.removePrefix("file://")).readBytes()
    }.getOrNull()
    url.startsWith("/") -> runCatching {
        File(url).readBytes()
    }.getOrNull()
    url.startsWith("http://") || url.startsWith("https://") ->
        ohosLoadNetworkImageBytes(url, book, bookSource, isCover)
    else -> null
}

/**
 * 网络图字节加载: 死链跳过 (原版 failUrl 语义) + 进程内/磁盘缓存优先
 * (对齐原版 PhotoDialog.loadByGlide 的 onlyRetrieveFromCache 优先语义),
 * 未命中才下载 + 解密, 成功后回写缓存 (同一 URL 二次打开零重复下载/解密)。
 */
private suspend fun ohosLoadNetworkImageBytes(
    url: String,
    book: Book?,
    bookSource: BookSource?,
    isCover: Boolean,
): ByteArray? {
    if (ohosFailUrlsContains(bookSource?.bookSourceUrl, url)) return null
    return ImageBytesCache.get(url, bookSource?.bookSourceUrl, isCover) ?: run {
        val bytes = ohosDownloadImageBytes(url, book, bookSource, isCover)
        if (bytes != null) {
            ImageBytesCache.put(url, bookSource?.bookSourceUrl, isCover, bytes)
        }
        bytes
    }
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
                if (!response.isSuccessful) {
                    ohosFailUrlsAdd(bookSource?.bookSourceUrl, url)
                    null
                } else {
                    response.body.bytes()
                }
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
        } ?: run {
            ohosFailUrlsAdd(bookSource.bookSourceUrl, url)
            null
        }
    }.getOrNull()
}

/**
 * 内置背景图 (bg://) 本地缓存读取 + CDN 下载回填 (对照原版 RemoteAssetsUtils 的
 * getBgCachePath/downloadBgIfNeeded 语义: 下载过即本地可用, 离线/重复选择零网络)。
 * 缓存位置 `{cacheDir}/remote_assets/bg/{fileName}`, 与 jvm/android 同目录名。
 */
private suspend fun ohosLoadBgBytes(fileName: String): ByteArray? {
    val cacheDir = AppFilesDirs.get().cacheDir
    val bgDir = "$cacheDir/remote_assets/bg"
    val cachePath = "$bgDir/$fileName"
    if (FileUtilsCommon.exist(cachePath)) {
        val cached = runCatching { FileUtilsCommon.readBytes(cachePath) }.getOrNull()
        if (cached != null && cached.isNotEmpty()) return cached
    }
    val bytes = bgCdnUrl(fileName).let { ohosDownloadImageBytes(it, null, null) } ?: return null
    runCatching {
        FileUtilsCommon.createFolderIfNotExist(bgDir)
        FileUtilsCommon.writeBytes(cachePath, bytes)
    }
    return bytes
}

/** 通过 CPF OHOS 图形兼容门面将编码字节解码为融合渲染可绘制的 [ImageBitmap]。 */
internal fun ohosDecodeImageBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/**
 * SVG 兜底解码 (skiko SVGDOM, 与 iOS 同实现)。
 *
 * CPF fork 的 skiko klib 含 org.jetbrains.skia.svg 包 (与 Image 同门面, 见 [ohosDecodeImageBytes]),
 * 故按兼容门面 API 实现; 渲染失败 (如 fork 未桥接 SVG native 符号) 由 runCatching 兜住返回 null,
 * 调用方走失败占位, 不影响栅格图路径。
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
