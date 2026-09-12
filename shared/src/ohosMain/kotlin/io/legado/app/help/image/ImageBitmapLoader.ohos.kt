package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext

/** 失败 url 跳过表已收敛至共用的 [isImageLoadFailed] / [markImageLoadFailed]
 * (带 TTL + 容量上界, 修原版 failUrl 永久拉黑 + 无界增长问题; 与 android/ios/jvm 三端同一张表,
 * 否则鸿蒙端「清除封面缓存」清不到本端拉黑记，死链会到进程结束都不恢复)。
 * key 仍带书源维度 (origin+url): 不同书源同 URL 互不影响, 换源/无源→有源切换后不再被
 * 旧失败记录拦截可重新加载; 无书源 (裸 GET) 时 key 即 url, 保持原死链跳过语义。 */
private fun ohosFailKey(origin: String?, url: String): String =
    if (origin.isNullOrEmpty()) url else "$origin\u0000$url"

private fun ohosFailUrlsContains(origin: String?, url: String): Boolean =
    isImageLoadFailed(ohosFailKey(origin, url))

private fun ohosFailUrlsAdd(origin: String?, url: String) {
    markImageLoadFailed(ohosFailKey(origin, url))
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
    ): ImageBitmap? = loadBitmapImpl(
        url, book, bookSource, isCover, widthPx, heightPx, useBitmapCache,
        // 既有语义原样保留: 直接经 loadBitmap 的调用点 (漫画/大图/背景) 仍以 isCover 兼作分区
        persistent = isCover,
    )

    /**
     * 带字节分区选择的封面加载入口 (本端专有, 不在 commonMain expect 内)。
     *
     * [persistent] 决定解密后字节落封面持久区还是临时区, 与 [isCover] (coverDecodeJs /
     * imageDecode 解密规则选择) 解耦。存在理由: Coil 端由 `diskCacheKey = url#covers` 分流
     * 持久区, 鸿蒙无 Coil3 变体、分区只能本端显式决定; 旧实现把两件事绑成
     * `persistent = isCover`, 而封面链上 isCover 恒为 true (解密规则需要), 于是凡经
     * [BookImageLoaders] 的图 (非书架书封面/书评头像/歌词取色/默认封面占位) 全部永久落进
     * 封面持久区 —— 用户清缓存清不掉, 塞满后又触发淘汰、反复重下。
     *
     * android/ios/jvm 的自下载链路没有需要持久区的调用方 (封面全走 Coil), 故不进 expect,
     * 免给三端造无人使用的参数。
     */
    suspend fun loadCoverBitmap(
        url: String,
        bookSource: BookSource?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
    ): ImageBitmap? = loadBitmapImpl(
        url = url,
        book = null,
        bookSource = bookSource,
        // 封面解密规则不变 (coverDecodeJs), 只改字节分区
        isCover = true,
        widthPx = widthPx,
        heightPx = heightPx,
        useBitmapCache = true,
        persistent = persistent,
    )

    private suspend fun loadBitmapImpl(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean,
        widthPx: Int,
        heightPx: Int,
        useBitmapCache: Boolean,
        persistent: Boolean,
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
            val bytes = ohosLoadImageBytes(url, book, bookSource, isCover, useBitmapCache, persistent)
                ?: return@withContext null
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
            ohosLoadImageBytes(url, book, bookSource, isCover, useBytesCache = true)
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

/** 按 scheme 取图片原始字节 ([ImageBitmapLoader] 的解码前一步, 动图路径直接复用)。 */
private suspend fun ohosLoadImageBytes(
    url: String,
    book: Book?,
    bookSource: BookSource?,
    isCover: Boolean,
    useBytesCache: Boolean,
    persistent: Boolean = isCover,
): ByteArray? = when {
    // data: URI 内联图 (与 loadBitmap 的 data: 分支对齐)
    url.startsWith("data:") -> parseDataUriBytes(url)

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
        ohosLoadNetworkImageBytes(url, book, bookSource, isCover, useBytesCache, persistent)
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
    useBytesCache: Boolean,
    persistent: Boolean,
): ByteArray? {
    if (useBytesCache) {
        // 字节分区由 [persistent] 单独决定 (与解密规则 [isCover] 无关): 书架封面落持久区
        // (系统清缓存清不掉, 对齐 Coil 端 #covers 语义), 其余落临时区。
        // 死链跳过只拦真下载, 不得挡缓存命中。
        ImageBytesCache.get(url, bookSource?.bookSourceUrl, isCover, persistent = persistent)
            ?.let { return it }
        if (ohosFailUrlsContains(bookSource?.bookSourceUrl, url)) return null
    }
    val bytes = ohosDownloadImageBytes(
        url, book, bookSource, isCover, recordFailure = useBytesCache
    )
    if (bytes != null && useBytesCache) {
        ImageBytesCache.put(url, bookSource?.bookSourceUrl, isCover, bytes, persistent = persistent)
    }
    return bytes
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
    recordFailure: Boolean = true,
): ByteArray? {
    if (bookSource == null || book?.isLocal == true) {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = KmpRequestBuilder().url(url).get().build()
        return runCatching {
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    if (recordFailure) ohosFailUrlsAdd(bookSource?.bookSourceUrl, url)
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
            if (recordFailure) ohosFailUrlsAdd(bookSource.bookSourceUrl, url)
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
