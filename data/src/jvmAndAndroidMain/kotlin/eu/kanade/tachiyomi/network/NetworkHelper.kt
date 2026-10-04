// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.network

import android.content.Context
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.http.okHttpClient
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

// 包住宿主共享 OkHttpClient; KeiSource(扩展 APK 内)按 simpleName 校验
// UncaughtExceptionInterceptor/UserAgentInterceptor/CloudflareInterceptor 必须在拦截器链上
// (CloudflareInterceptor 与 WebView 体系留在 app 模块, 差异段经 ExtensionInterceptorsProvider 注入)
class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    fun defaultUserAgentProvider(): String = UserAgentProviders.get()

    private val interceptorProvider: ExtensionInterceptorsProvider by injectLazy()

    private fun newClientBuilder(): OkHttpClient.Builder = okHttpClient.newBuilder()
        // 扩展大图响应慢, 沿用 Mihon 语义不设整体 callTimeout (宿主共享 client 为 15s)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .cookieJar(cookieJar)

    val client: OkHttpClient = newClientBuilder()
        .apply {
            // okhttp Builder 无 interceptors(List) setter, 只能就地前插
            interceptors().addAll(
                0,
                interceptorProvider.clientInterceptors(context, cookieJar, ::defaultUserAgentProvider),
            )
        }
        .build()

    // 1.4 契约: 无 Cloudflare 挑战处理的客户端
    val cloudflareClient: OkHttpClient = newClientBuilder()
        .apply {
            interceptors().addAll(
                0,
                interceptorProvider.cloudflareClientInterceptors(context, cookieJar, ::defaultUserAgentProvider),
            )
        }
        .build()
}
