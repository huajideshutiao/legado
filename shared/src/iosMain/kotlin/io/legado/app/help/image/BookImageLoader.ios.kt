package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.ktor3.asNetworkClient
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.manga.MangaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.buffer

/**
 * [BookImageLoader] 的 iOS Coil3 实现 (对照 androidMain BookImageLoader.android.kt /
 * jvmMain BookImageLoader.jvm.kt, 网络后端差异: OkHttp → Ktor3)。
 *
 * - ImageLoader 用 Coil3 + Ktor3 网络后端 (手搜 NetworkFetcher.Factory + ktorClient.asNetworkClient()),
 *   HttpClient 复用 [io.legado.app.help.http.NativeHttpProvider] 经 [OkHttpClientProviders]
 *   注册的 KmpHttpClient 内部 Ktor client (CIO engine, 继承 timeout 配置)。
 * - 书源防盗链 header + header 规则 JS 改写后的 url: 网络层自动解析注入 (与 android/desktop
 *   同语义, 解析实现见 nonOhosUiMain/SourceImageHeaders.kt; 内存/磁盘命中零查源, 真发请求时才解析)。
 * - diskCache: `{AppFilesDirs.cacheDir}/image_cache` (Library/Caches 下, 系统可清理);
 *   memoryCache: 默认 maxSizePercent (与 android/desktop 的 Coil3 默认策略一致)。
 * - coverDecodeJs 封面解密: [SourceDecodeCacheStrategy] 在磁盘缓存写入前解密响应字节
 *   (对齐原版 Glide DATA 策略缓存解密后字节), 书架封面落持久区; 冷启动磁盘命中零下载零 JS。
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
    ): ImageBitmap? =
        execute(url, sourceOrigin, widthPx, heightPx, persistent = false)

    override suspend fun loadCoverOrNull(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? =
        execute(url, sourceOrigin, widthPx, heightPx, persistent = true)

    /**
     * 仅读 Coil3 磁盘缓存字节（不触发网络/解码）：查裸 url key（解密书源的字节为解密后内容,
     * 由 [SourceDecodeCacheStrategy] 写入; 书架封面 #covers 持久区由 [MultiDiskCache] 裸 key
     * miss 自动兜底）。
     */
    override suspend fun loadDiskCachedBytes(
        url: String,
        sourceOrigin: String?,
    ): ByteArray? =
        BookImageLoadDedup.singleFlight("diskCached\u0000$url\u0000${sourceOrigin ?: ""}") {
            val diskCache = iosCoilImageLoader.diskCache ?: return@singleFlight null
            val snapshot = diskCache.openSnapshot(url) ?: return@singleFlight null
            try {
                val bytes = FileSystem.SYSTEM.source(snapshot.data).buffer().readByteArray()
                if (bytes.isNotEmpty()) return@singleFlight bytes
            } finally {
                snapshot.close()
            }
            null
        }

    /** [persistent] 为 true 时改写 diskCacheKey, 由 [MultiDiskCache] 分流到封面持久区 (对齐 jvm/android)。 */
    private suspend fun execute(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
    ): ImageBitmap? =
        // 同 URL 并发请求经 BookImageLoadDedup 单飞去重 (I6, 与 jvm/android 端一致)
        BookImageLoadDedup.singleFlight(
            "${url}\u0000${sourceOrigin ?: ""}\u0000${widthPx}x$heightPx\u0000$persistent"
        ) {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(url)
                .sourceOrigin(sourceOrigin)
                .apply {
                    if (persistent) {
                        diskCacheKey(coverDiskCacheKey(url))
                    }
                    // 按显示尺寸降采样; FILL 对齐消费端 ContentScale.Crop, INEXACT 允许复用更大的内存缓存项
                    if (widthPx > 0 && heightPx > 0) {
                        size(widthPx, heightPx)
                        scale(Scale.FILL)
                        precision(Precision.INEXACT)
                    }
                }
                .build()
            val result = iosCoilImageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@singleFlight null
            bitmap.asComposeImageBitmap()
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

/** 构建 iOS Coil3 ImageLoader (注册防盗链 fetcher + Ktor3 网络后端 + 磁盘/内存缓存)。 */
private fun buildIosBookImageLoader(): ImageLoader {
    return ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            // 防盗链 header + URL 重写 + 解密落盘 + 网络层守卫: 同 android/desktop (书源 header 与
            // JS 改写后的 url 由 SourceHeaderNetworkClient 在 Coil3 磁盘查询之后注入;
            // SourceDecodeCacheStrategy 解密落盘对齐 Glide DATA; ImageGuardNetworkClient 包最外层
            // 按原始 url 拦 failUrls)。
            // 直接构 NetworkFetcher.Factory 包 NetworkClient (KtorNetworkFetcherFactory 不接受
            // NetworkClient), Ktor client 复用 NativeHttpProvider 的 KmpHttpClient 内部 client
            // (internal 字段同模块可见; lambda 惰性求值, ImageLoader 构建时不触发网络栈初始化)
            // 漫画页: MangaModel 走完整取图链路 (图片缓存 → 本地书 FileBook → AnalyzeUrl 防盗链
            // header 下载 → ImageUtils.decode 解密), 与 android/desktop 同源
            add(MangaModelKeyer(), MangaModel::class)
            add(MangaModelFetcher.Factory())
            // 漫画页解码: 与 desktop 同源 (MangaPageCoil.kt), 预载与翻页共用同一条内存缓存
            add(MangaPageDecoder.Factory())
            add(
                coil3.network.NetworkFetcher.Factory(
                    networkClient = {
                        val ktorClient = requireNotNull(OkHttpClientProviders.get().okHttpClient.ktorClient) {
                            "KmpHttpClient 未初始化 (需经 KmpHttpClientBuilder.build 创建)"
                        }
                        ImageGuardNetworkClient(
                            SourceHeaderNetworkClient(
                                ktorClient.asNetworkClient(),
                                ::resolveSourceRequest,
                                keepCookieJarMarker = false,
                            )
                        )
                    },
                    cacheStrategy = { SourceDecodeCacheStrategy },
                )
            )
        }
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(PlatformContext.INSTANCE).build()
        }
        .diskCache {
            // 双区 (MultiDiskCache): 封面落持久区 Documents/covers (commonMain bookCoverCacheDir
            // 默认实现, NativeDataStorage 未 override), 其余图落 Library/Caches/image_cache;
            // 与 android/desktop 同语义 (对齐原版 Glide MultiDiskCacheFactory)
            buildImageDiskCache("${AppFilesDirs.get().cacheDir.trimEnd('/')}/image_cache")
        }
        .build()
}

/**
 * iOS 宿主启动早期注册图片加载器:
 * 1. [BookImageLoaders.register] 注入 [IosBookImageLoader] (sharedUiMain 回调式共享面);
 * 2. [SingletonImageLoader.setSafe] 让 AsyncImage / rememberAsyncImagePainter 默认走
 *    带防盗链 fetcher 的共享 ImageLoader (对齐 app 端 App.onCreate 的 setSafe)。
 *
 * 调用时机: registerIosProviders 内, OkHttpClientProviders 注册之后 (loader lazy 构建,
 * 注册本身不触发依赖读取)。
 */
fun registerIosBookImageLoader() {
    BookImageLoaders.register(IosBookImageLoader())
    SingletonImageLoader.setSafe { iosCoilImageLoader }
}
