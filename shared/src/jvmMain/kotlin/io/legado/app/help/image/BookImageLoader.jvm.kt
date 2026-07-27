package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.File

/**
 * 桌面 JVM 共享 Coil3 ImageLoader (对照 androidMain [buildBookImageLoader])。
 *
 * - 拦截器/Fetcher/Keyer 全套复用 jvmAndAndroidMain 现成件 (失败跳过/封面解密/防盗链 header)
 * - 网络层: OkHttp 后端, callFactory 惰性取 [OkHttpClientProviders] 共享 client
 *   (继承 CookieJar/限流; 惰性求值避免装配时机早于 HTTP provider 注册)
 * - diskCache: [desktopAppCacheDir]/image_cache (Coil3 JVM 默认落系统临时目录 coil3_disk_cache,
 *   显式定向到应用缓存目录; 尺寸走 Builder 默认策略)
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
            DiskCache.Builder()
                .directory(File(desktopAppCacheDir(), "image_cache").absolutePath.toPath())
                .build()
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
                val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(url)
                    .sourceOrigin(sourceOrigin)
                    .build()
                val result = jvmBookImageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image?.toBitmap()
                        ?: error("Coil3 SuccessResult.image 为 null")
                    onSuccess(bitmap.asComposeImageBitmap())
                } else {
                    error("Coil3 加载失败: $result")
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
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
