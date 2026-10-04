// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.network

import android.content.Context
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.http.okHttpClient
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// 包住宿主共享 OkHttpClient; KeiSource(扩展 APK 内)按 simpleName 校验
// UncaughtExceptionInterceptor/UserAgentInterceptor/CloudflareInterceptor 必须在拦截器链上
class NetworkHelper(context: Context) {

    val cookieJar = AndroidCookieJar()

    fun defaultUserAgentProvider(): String = UserAgentProviders.get()

    val client: OkHttpClient = okHttpClient.newBuilder()
        // 扩展大图响应慢, 沿用 Mihon 语义不设整体 callTimeout (宿主共享 client 为 15s)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .cookieJar(cookieJar)
        .apply {
            // okhttp Builder 无 interceptors(List) setter, 只能就地前插
            interceptors().addAll(
                0,
                listOf(
                    UncaughtExceptionInterceptor(),
                    UserAgentInterceptor { defaultUserAgentProvider() },
                    CloudflareInterceptor(context, cookieJar) { defaultUserAgentProvider() },
                ),
            )
        }
        .build()

    // 1.4 契约: 无 Cloudflare 挑战处理的客户端
    val cloudflareClient: OkHttpClient = client.newBuilder()
        .apply { interceptors().removeAll { it is CloudflareInterceptor } }
        .build()
}
