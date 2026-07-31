package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * [BookImageLoader] 的 iOS Coil3 实现 (对照 androidMain BookImageLoader.android.kt /
 * jvmMain BookImageLoader.jvm.kt, 网络后端差异: OkHttp → Ktor3)。
 *
 * - ImageLoader 用 Coil3 + Ktor3 网络后端 ([KtorNetworkFetcherFactory]),
 *   HttpClient 复用 [io.legado.app.help.http.IosHttpProvider] 经 [OkHttpClientProviders]
 *   注册的 KmpHttpClient 内部 Ktor client (CIO engine, 继承 timeout 配置)。
 * - 书源防盗链 header: [SourceOriginHeaderInterceptor] 在 chain 内自动解析注入
 *   (与 android/desktop 同语义, iOS 版见 SourceImageHeaders.ios.kt)。
 * - diskCache: `{AppFilesDirs.cacheDir}/image_cache` (Library/Caches 下, 系统可清理);
 *   memoryCache: 默认 maxSizePercent (与 android/desktop 的 Coil3 默认策略一致)。
 * - coverDecodeJs 封面解密: [CoverDecodeInterceptor] 下载原始字节跑共享
 *   ImageUtils.decode (native QuickJs) 后经 [DecodedCoverBytes] 回灌 (CoverDecodeInterceptor.ios.kt)。
 *
 * 注册: [io.legado.app.help.config.registerIosProviders] 调用 [registerIosBookImageLoader]。
 */
class IosBookImageLoader : BookImageLoader {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    ): ImageBitmap? {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .sourceOrigin(sourceOrigin)
            .apply {
                // 按显示尺寸降采样; FILL 对齐消费端 ContentScale.Crop, INEXACT 允许复用更大的内存缓存项
                if (widthPx > 0 && heightPx > 0) {
                    size(widthPx, heightPx)
                    scale(Scale.FILL)
                    precision(Precision.INEXACT)
                }
            }
            .build()
        val result = iosCoilImageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        return bitmap.asComposeImageBitmap()
    }
}

/**
 * iOS 端共享 Coil3 ImageLoader 单例 (lazy: 首次图片加载时构建, 依赖
 * OkHttpClientProviders/AppFilesDirs 已注册)。
 *
 * [IosBookImageLoader] 与 SingletonImageLoader (AsyncImage/rememberAsyncImagePainter 默认取用)
 * 共用同一实例, 内存/磁盘缓存全局唯一。
 */
internal val iosCoilImageLoader: ImageLoader by lazy { buildIosBookImageLoader() }

/** 构建 iOS Coil3 ImageLoader (注册防盗链 Interceptor + Ktor3 网络后端 + 磁盘/内存缓存)。 */
private fun buildIosBookImageLoader(): ImageLoader {
    return ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            // 失败 url 跳过 + coverDecodeJs 解密: 复刻原 Glide OkHttpStreamFetcher 语义
            // (CoverDecodeInterceptor.ios.kt, 与 jvmAndAndroid 版注册顺序一致)
            add(FailedUrlSkipInterceptor())
            add(CoverDecodeInterceptor())
            add(DecodedCoverKeyer(), DecodedCoverBytes::class)
            add(DecodedCoverFetcher.Factory(), DecodedCoverBytes::class)
            add(SourceOriginHeaderInterceptor())
            // 漫画页: MangaModel 走完整取图链路 (图片缓存 → 本地书 FileBook → AnalyzeUrl 防盗链
            // header 下载 → ImageUtils.decode 解密), 与 android/desktop 同源
            add(MangaModelFetcher.Factory())
            // 网络后端: 复用 IosHttpProvider 的 Ktor HttpClient (KmpHttpClient 内部 client,
            // internal 字段同模块可见; lambda 惰性求值, ImageLoader 构建时不触发网络栈初始化)
            add(KtorNetworkFetcherFactory(httpClient = {
                requireNotNull(OkHttpClientProviders.get().okHttpClient.ktorClient) {
                    "KmpHttpClient 未初始化 (需经 KmpHttpClientBuilder.build 创建)"
                }
            }))
        }
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(PlatformContext.INSTANCE).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory("${AppFilesDirs.get().cacheDir.trimEnd('/')}/image_cache".toPath())
                .build()
        }
        .build()
}

/**
 * iOS 宿主启动早期注册图片加载器:
 * 1. [BookImageLoaders.register] 注入 [IosBookImageLoader] (sharedUiMain 回调式共享面);
 * 2. [SingletonImageLoader.setSafe] 让 AsyncImage / rememberAsyncImagePainter 默认走
 *    带防盗链 Interceptor 的共享 ImageLoader (对齐 app 端 App.onCreate 的 setSafe)。
 *
 * 调用时机: registerIosProviders 内, OkHttpClientProviders 注册之后 (loader lazy 构建,
 * 注册本身不触发依赖读取)。
 */
fun registerIosBookImageLoader() {
    BookImageLoaders.register(IosBookImageLoader())
    SingletonImageLoader.setSafe { iosCoilImageLoader }
}
