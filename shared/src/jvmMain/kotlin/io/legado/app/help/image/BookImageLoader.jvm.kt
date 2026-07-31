package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * 桌面 JVM 共享 Coil3 ImageLoader (对照 androidMain [buildBookImageLoader])。
 *
 * - 拦截器/Fetcher/Keyer 全套复用 jvmAndAndroidMain 现成件 (失败跳过/封面解密/防盗链 header)
 * - 网络层: OkHttp 后端, callFactory 惰性取 [OkHttpClientProviders] 共享 client
 *   (继承 CookieJar/限流; 惰性求值避免装配时机早于 HTTP provider 注册)
 * - diskCache: 双区 [buildImageDiskCache] —— 书架封面落数据目录 `filesDir/covers`,
 *   其余图片落 [desktopAppCacheDir]/image_cache (Coil3 JVM 默认落系统临时目录, 显式定向)
 *
 * 进程内单实例: DiskCache 同目录不可多实例 (okio 文件锁冲突),
 * [SingletonImageLoader] 与 [JvmBookImageLoader] 共用本 lazy。
 */
private val jvmBookImageLoader: ImageLoader by lazy {
    ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            // 失败 url 跳过 + coverDecodeJs 解密: 复刻原 Glide OkHttpStreamFetcher 语义
            add(FailedUrlSkipInterceptor())
            add(CoverDecodeInterceptor())
            add(DecodedCoverKeyer(), DecodedCoverBytes::class)
            add(DecodedCoverFetcher.Factory(), DecodedCoverBytes::class)
            add(SourceOriginHeaderInterceptor())
            add(OkHttpNetworkFetcherFactory(callFactory = {
                OkHttpClientProviders.get().okHttpClient as OkHttpClient
            }))
        }
        .diskCache {
            buildImageDiskCache(File(desktopAppCacheDir(), "image_cache").absolutePath)
        }
        .build()
}

/**
 * [BookImageLoader] 的桌面 JVM Coil3 实现。
 *
 * - ImageLoader 共用进程级 [jvmBookImageLoader] (拦截器/缓存配置见其 KDoc)
 * - 书源防盗链 header: 通过 [SourceOriginHeaderInterceptor] 在 Coil3 chain 内自动解析注入
 *   (消费点 [loadImage] / AsyncImage 只传 sourceOrigin), 对齐 app 端行为
 * - 成功结果转 [ImageBitmap] 回调 (Image.toBitmap → skia Bitmap.asComposeImageBitmap)
 *
 * 注册: desktop Main.kt 调用 [registerJvmBookImageLoader]。
 */
class JvmBookImageLoader : BookImageLoader {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** [persistent] 为 true 时改写 diskCacheKey, 由 [MultiDiskCache] 分流到封面持久区。 */
    private suspend fun execute(
        url: String,
        sourceOrigin: String?,
        widthPx: Int,
        heightPx: Int,
        persistent: Boolean,
    ): ImageBitmap? {
        val request = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .sourceOrigin(sourceOrigin)
            .apply {
                if (persistent) diskCacheKey(coverDiskCacheKey(url))
                // 按显示尺寸降采样; FILL 对齐消费端 ContentScale.Crop, INEXACT 允许复用更大的内存缓存项
                if (widthPx > 0 && heightPx > 0) {
                    size(widthPx, heightPx)
                    scale(Scale.FILL)
                    precision(Precision.INEXACT)
                }
            }
            .build()
        val result = jvmBookImageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        return bitmap.asComposeImageBitmap()
    }
}

/**
 * 桌面宿主启动早期注册 [BookImageLoader] + [SingletonImageLoader] Factory。
 *
 * 调用时机: desktop Main.kt 阶段1 (首个 Composable 图片加载之前) —
 * setSafe 在默认 loader 已被 get 创建后调用会抛 IllegalStateException,
 * 故必须先于任何 AsyncImage / rememberAsyncImagePainter 组合。
 * 注册本身零开销 (ImageLoader lazy 构建, OkHttpClient 惰性到首次网络 fetch)。
 */
fun registerJvmBookImageLoader() {
    SingletonImageLoader.setSafe { jvmBookImageLoader }
    BookImageLoaders.register(JvmBookImageLoader())
}
