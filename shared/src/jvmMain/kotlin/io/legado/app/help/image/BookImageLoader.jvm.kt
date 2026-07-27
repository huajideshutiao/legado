package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.ImageLoader
import coil3.PlatformContext
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

/**
 * [BookImageLoader] 的桌面 JVM Coil3 实现。
 *
 * - ImageLoader 用 Coil3 + OkHttp 网络后端 (OkHttpNetworkFetcherFactory),
 *   OkHttpClient 走项目共享 [OkHttpClientProviders] (继承 CookieJar/限流)。
 * - 书源防盗链 header: 通过 [SourceOriginHeaderInterceptor] 在 Coil3 chain 内自动解析注入
 *   (消费点 [loadImage] / AsyncImage 只传 sourceOrigin, Interceptor suspend 解析 headerMap),
 *   对齐 app 端 Glide OkHttpModelLoader.sourceOriginOption 行为。
 * - 成功结果转 [ImageBitmap] 回调 (Image.toBitmap → skia Bitmap.asComposeImageBitmap)。
 * - 桌面无 Android Context, 用 [PlatformContext.INSTANCE] 单例。
 *
 * 注册: desktop Main.kt 调用 [registerJvmBookImageLoader]。
 */
class JvmBookImageLoader : BookImageLoader {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val imageLoader: ImageLoader by lazy { buildImageLoader() }

    private fun buildImageLoader(): ImageLoader {
        val sharedClient = OkHttpClientProviders.get().okHttpClient as OkHttpClient
        return ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(SourceOriginHeaderInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { sharedClient }))
            }
            .build()
    }

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
                val result = imageLoader.execute(request)
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
 * 桌面宿主启动早期注册 [BookImageLoader] 的 actual 实现。
 *
 * 调用时机: desktop Main.kt, 在任何 Composable 图片加载之前。
 */
fun registerJvmBookImageLoader() {
    BookImageLoaders.register(JvmBookImageLoader())
}
