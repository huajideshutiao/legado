package io.legado.app.help.image

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.coverBakedCacheDir
import io.legado.app.model.coverOriginalDir
import io.legado.app.model.manga.MangaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.buffer
import java.io.File

/**
 * [BookImageLoader] 的 Android Coil3 实现。
 *
 * - ImageLoader 用 Coil3 + OkHttp 网络后端 (OkHttpNetworkFetcherFactory),
 *   OkHttpClient 走项目共享 [OkHttpClientProviders] (继承 CookieJar/限流/Cronet)。
 * - 书源防盗链 header: 在 fetcher 层自动解析注入 (对齐原 Glide OkHttpModelLoader:
 *   消费点 [loadImage] / AsyncImage 只传 sourceOrigin, 缓存命中不解析, 取数据时跑 IO 线程)。
 * - 成功结果转 [ImageBitmap] 回调 (Image.toBitmap → Bitmap.asImageBitmap)。
 *
 * 注册: app 端 App.onCreate 调用 [registerAndroidBookImageLoader]。
 */
class AndroidBookImageLoader(
    private val context: Context
) : BookImageLoader {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val imageLoader: ImageLoader by lazy { androidBookImageLoader(context) }

    override fun loadImage(
        url: String,
        sourceOrigin: String?,
        onSuccess: (ImageBitmap) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        coroutineScope.launch {
            try {
                onSuccess(
                    loadImageOrNull(url, sourceOrigin) ?: error("Coil3 加载失败: $url")
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    override suspend fun loadImageOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        loadOnlyWifi: Boolean,
    ): ImageBitmap? = execute(url, sourceOrigin, widthPx, heightPx, persistent = false, loadOnlyWifi = loadOnlyWifi)

    override suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        loadOnlyWifi: Boolean,
    ): ImageBitmap? = execute(url, sourceOrigin, widthPx, heightPx, persistent = true, loadOnlyWifi = loadOnlyWifi)

    /**
     * 仅读 Coil3 磁盘缓存字节（不触发网络/解码）：先查封面解密 key（"coverDecode:$url"），
     * 再查网络 fetcher 默认 key（裸 url）；MultiDiskCache 临时/covers 双区自动兜底。
     */
    override suspend fun loadDiskCachedBytes(
        url: String,
        sourceOrigin: String?,
    ): ByteArray? =
        BookImageLoadDedup.singleFlight("diskCached\u0000$url\u0000${sourceOrigin ?: ""}") {
            val diskCache = imageLoader.diskCache ?: return@singleFlight null
            for (key in listOf("coverDecode:$url", url)) {
                val snapshot = diskCache.openSnapshot(key) ?: continue
                snapshot.use { snapshot ->
                    val bytes = FileSystem.SYSTEM.source(snapshot.data).buffer().readByteArray()
                    if (bytes.isNotEmpty()) return@singleFlight bytes
                }
            }
            null
        }

    /**
     * 手动封面图集引用 → 展示路径: 本地图集文件 (covers/... 相对引用或 customImg/covers
     * 绝对路径) 优先读缓存烘焙产物, 按目标尺寸比例推断 novel/video (先精确 ratio, 再试另一个);
     * 产物缺失/非图集文件 (网络 URL/其他本地路径) 原样返回。
     */
    private fun resolveCoverBakedForDisplay(url: String, widthPx: Int, heightPx: Int): String {
        val abs = resolveImagePath(url) ?: return url
        if (!abs.startsWith(coverOriginalDir())) return url
        val sep = if (abs.contains('\\')) '\\' else '/'
        val stem = abs.substringAfterLast(sep).substringBeforeLast('.', "")
        val tag =
            if (widthPx > 0 && heightPx > 0 && widthPx * 9 > heightPx * 16) "video" else "novel"
        for (t in if (tag == "video") listOf("video", "novel") else listOf("novel", "video")) {
            val baked = FileUtilsCommon.getPath(coverBakedCacheDir(), "${stem}_$t.webp")
            if (FileUtilsCommon.exist(baked)) return baked
        }
        return abs
    }

    /** [persistent] 为 true 时改写 diskCacheKey, 由 [MultiDiskCache] 分流到封面持久区。
     * 同 URL 并发请求经 [BookImageLoadDedup] 单飞去重 (I6)。 */
    private suspend fun execute(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
        loadOnlyWifi: Boolean = false,
    ): ImageBitmap? =
        BookImageLoadDedup.singleFlight(
            "${url}\u0000${sourceOrigin ?: ""}\u0000${widthPx}x$heightPx\u0000$persistent\u0000$loadOnlyWifi"
        ) {
            // 手动封面 (图集引用) 优先读缓存烘焙产物, 减轻大图原图解码
            val displayUrl = resolveCoverBakedForDisplay(url, widthPx, heightPx)
            val request = ImageRequest.Builder(context)
                .data(displayUrl)
                .sourceOrigin(sourceOrigin)
                .apply {
                    if (persistent) {
                        diskCacheKey(coverDiskCacheKey(url))
                        extras[PersistentCoverKey] = true
                    }
                    // 非 wifi 且 loadOnlyWifi 时 fetcher 层拦网络获取 (对齐原版 loadOnlyWifiOption)
                    if (loadOnlyWifi) {
                        extras[LoadOnlyWifiKey] = true
                    }
                    // 按显示尺寸降采样; FILL 对齐消费端 ContentScale.Crop, INEXACT 允许复用更大的内存缓存项
                    if (widthPx > 0 && heightPx > 0) {
                        size(widthPx, heightPx)
                        scale(Scale.FILL)
                        precision(Precision.INEXACT)
                    }
                }
                .build()
            val result = imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@singleFlight null
            bitmap.asImageBitmap()
        }

}

/**
 * Android 端共享 Coil3 ImageLoader 单例入口 (对照 iOS `iosCoilImageLoader` /
 * 桌面 `jvmBookImageLoader`): [AndroidBookImageLoader] 与 app 端 SingletonImageLoader.Factory
 * 必须共用同一实例 —— 同目录两个 DiskCache 各写一份 journal, 会互相驱逐对方的条目。
 *
 * 惰性构建 (首次取用才装配, 不把 OkHttp 初始化提前到 App.onCreate); 一律用 applicationContext,
 * 避免首次 `SingletonImageLoader.get(activity)` 把 Activity 引用留在进程级 loader 里。
 */
@Volatile
private var sharedImageLoader: ImageLoader? = null
private val sharedImageLoaderLock = Any()

fun androidBookImageLoader(context: Context): ImageLoader =
    sharedImageLoader ?: synchronized(sharedImageLoaderLock) {
        sharedImageLoader ?: buildBookImageLoader(context.applicationContext).also {
            sharedImageLoader = it
        }
    }

/**
 * 安卓宿主启动早期注册 [BookImageLoader] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 Composable 图片加载之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 内部只用做 ApplicationContext。
 */
fun registerAndroidBookImageLoader(context: Context) {
    BookImageLoaders.register(AndroidBookImageLoader(context.applicationContext))
}

/**
 * 构建共享 Coil3 ImageLoader (fetcher 层注册防盗链 header + 共享 OkHttpClient),
 * 供 [AndroidBookImageLoader] 和 app 端 [coil3.SingletonImageLoader.Factory] 共用。
 *
 * app 端 AsyncImage 默认走 SingletonImageLoader, 需在 App.onCreate 设置 Factory 返回此 loader,
 * 让不显式传 imageLoader 的 AsyncImage 也有防盗链 header 注入。
 *
 * 不要直接调用: 唯一入口是 [androidBookImageLoader] (同目录 DiskCache 只能有一个实例)。
 *
 * [additionalComponents] 必须在这里追加到同一个注册表；对返回的 loader 调
 * `newBuilder().components { ... }` 会替换已有注册表，导致封面解密等基础组件失效。
 *
 * diskCache 走双区 [buildImageDiskCache]: 书架封面落 `filesDir/covers` (与原版 Glide
 * `MultiDiskCacheFactory` 同址), 其余图片落 `cacheDir/image_cache`。
 */
internal fun buildBookImageLoader(
    context: Context,
    additionalComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val sharedClient = OkHttpClientProviders.get().okHttpClient
    return ImageLoader.Builder(context)
        .components {
            // 封面解密 + 失败 url 跳过 + 防盗链 header: 全部下沉 fetcher 层 (对齐原 Glide
            // OkHttpStreamFetcher: 缓存命中不解析不解密, 取数据时跑 IO 线程)。
            // 外层 CoverDecodeFetcher → 中层 SourceOriginHeaderFetcher → 内层 OkHttp 网络 fetcher
            add(DecodedCoverKeyer(), DecodedCoverBytes::class)
            add(DecodedCoverFetcher.Factory(), DecodedCoverBytes::class)
            // 漫画页: 经 BookHelp 缓存 + AnalyzeUrl 下载 + 解密取字节 (裸 url 走不通防盗链/解密站点)
            add(MangaModelKeyer(), MangaModel::class)
            add(MangaModelFetcher.Factory())
            add(
                CoverDecodeFetcher.Factory(
                    SourceOriginHeaderFetcher.Factory(
                        OkHttpNetworkFetcherFactory(callFactory = { sharedClient })
                    )
                )
            )
            // GIF: API 28+ 用 AnimatedImageDecoder(还支持 animated WebP/HEIF), 低版本用 GifDecoder(Movie)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            additionalComponents()
        }
        .diskCache {
            buildImageDiskCache(File(context.cacheDir, "image_cache").absolutePath)
        }
        .build()
}
