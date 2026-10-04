// Copyright The Keiyoushi Contributors. Apache-2.0.
// 漫画扩展 (eu.kanade.tachiyomi.*) 兼容层的 Injekt 注册点; 在 App.onCreate 早期调用一次,
// 必须先于任何扩展类加载 (keiyoushi.utils 的顶层属性会在 <clinit> 里 Injekt.get)
package eu.kanade.tachiyomi

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.ExtensionInterceptorsProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

fun registerExtensionCompat(application: Application) {
    Injekt.addSingleton(application)
    Injekt.addSingletonFactory<Json> {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    // 拦截器链差异段 (Android 端含 Cloudflare 挑战处理, WebView 体系留在本模块; JVM 端另注册无 WebView 链):
    // 语义与下沉前 NetworkHelper 内联装配逐字一致 (Uncaught/UserAgent/Cloudflare 前插)
    Injekt.addSingletonFactory<ExtensionInterceptorsProvider> {
        object : ExtensionInterceptorsProvider {
            override fun clientInterceptors(
                context: Context,
                cookieJar: ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ): List<Interceptor> = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
                CloudflareInterceptor(context, cookieJar, defaultUserAgent),
            )

            override fun cloudflareClientInterceptors(
                context: Context,
                cookieJar: ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ): List<Interceptor> = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
            )
        }
    }
    Injekt.addSingletonFactory { NetworkHelper(application) }
}
