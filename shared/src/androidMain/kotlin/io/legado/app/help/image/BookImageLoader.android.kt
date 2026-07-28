package io.legado.app.help.image

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import androidx.compose.ui.graphics.ImageBitmap

/**
 * [BookImageLoader] 的 Android Coil3 实现。
 *
 * - ImageLoader 用 Coil3 + OkHttp 网络后端 (OkHttpNetworkFetcherFactory),
 *   OkHttpClient 走项目共享 [OkHttpClientProviders] (继承 CookieJar/限流/Cronet)。
 * - 书源防盗链 header: 通过 [SourceOriginHeaderInterceptor] 在 Coil3 chain 内自动解析注入
 *   (消费点 [loadImage] / AsyncImage 只传 sourceOrigin, Interceptor suspend 解析 headerMap),
 *   对齐 app 端 Glide OkHttpModelLoader.sourceOriginOption 行为。
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
                val request = ImageRequest.Builder(context as PlatformContext)
                    .data(url)
                    .sourceOrigin(sourceOrigin)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    onSuccess(bitmap.asImageBitmap())
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
 * 构建共享 Coil3 ImageLoader (注册 [SourceOriginHeaderInterceptor] + 共享 OkHttpClient),
 * 供 [AndroidBookImageLoader] 和 app 端 [coil3.SingletonImageLoader.Factory] 共用。
 *
 * app 端 AsyncImage 默认走 SingletonImageLoader, 需在 App.onCreate 设置 Factory 返回此 loader,
 * 让不显式传 imageLoader 的 AsyncImage 也有防盗链 header 注入。
 *
 * [additionalComponents] 必须在这里追加到同一个注册表；对返回的 loader 调
 * `newBuilder().components { ... }` 会替换已有注册表，导致封面解密等基础组件失效。
 */
fun buildBookImageLoader(
    context: Context,
    additionalComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val sharedClient = OkHttpClientProviders.get().okHttpClient as OkHttpClient
    return ImageLoader.Builder(context as PlatformContext)
        .components {
            // 失败 url 跳过 + coverDecodeJs 解密: 复刻原 Glide OkHttpStreamFetcher 语义
            add(FailedUrlSkipInterceptor())
            add(CoverDecodeInterceptor())
            add(DecodedCoverKeyer(), DecodedCoverBytes::class)
            add(DecodedCoverFetcher.Factory(), DecodedCoverBytes::class)
            add(SourceOriginHeaderInterceptor())
            add(OkHttpNetworkFetcherFactory(callFactory = { sharedClient }))
            // GIF: API 28+ 用 AnimatedImageDecoder(还支持 animated WebP/HEIF), 低版本用 GifDecoder(Movie)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            additionalComponents()
        }
        .build()
}
