package io.legado.app.help.image

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.manga.MangaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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

    private val imageLoader: ImageLoader by lazy { buildBookImageLoader(context) }

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
    ): ImageBitmap? = execute(url, sourceOrigin, widthPx, heightPx, persistent = false)

    override suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? = execute(url, sourceOrigin, widthPx, heightPx, persistent = true)

    /** [persistent] 为 true 时改写 diskCacheKey, 由 [MultiDiskCache] 分流到封面持久区。
     * 同 URL 并发请求经 [BookImageLoadDedup] 单飞去重 (I6)。 */
    private suspend fun execute(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
    ): ImageBitmap? =
        BookImageLoadDedup.singleFlight(
            "${url}\u0000${sourceOrigin ?: ""}\u0000${widthPx}x$heightPx\u0000$persistent"
        ) {
            val request = ImageRequest.Builder(context as PlatformContext)
                .data(url)
                .sourceOrigin(sourceOrigin)
                .apply {
                    if (persistent) {
                        diskCacheKey(coverDiskCacheKey(url))
                        extras.set(PersistentCoverKey, true)
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
 * [additionalComponents] 必须在这里追加到同一个注册表；对返回的 loader 调
 * `newBuilder().components { ... }` 会替换已有注册表，导致封面解密等基础组件失效。
 *
 * diskCache 走双区 [buildImageDiskCache]: 书架封面落 `filesDir/covers` (与原版 Glide
 * `MultiDiskCacheFactory` 同址), 其余图片落 `cacheDir/image_cache`。
 */
fun buildBookImageLoader(
    context: Context,
    additionalComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val sharedClient = OkHttpClientProviders.get().okHttpClient as OkHttpClient
    return ImageLoader.Builder(context as PlatformContext)
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
