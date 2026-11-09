package io.legado.desktop.extension

import android.app.Application
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

/**
 * Tachiyomi 扩展兼容层的 Injekt 运行时绑定 (对照 app 端 App.onCreate 的
 * registerExtensionCompat, 逐项对齐: Application / Json / 拦截器链 / NetworkHelper)。
 *
 * 必须先于任何扩展类加载调用 (keiyoushi.utils 的顶层属性会在 <clinit> 里 Injekt.get);
 * 桌面端注册点: DesktopCore.registerRestProviders。Application 为 JVM 存根实例
 * (android.app.Application JVM stub, 行为走 Context stub)。
 */
private val applicationStub: Application = object : Application() {}

/**
 * 桌面端拦截器链差异段 (显式声明接口类型: Injekt 按 reified 类型落键, 匿名对象类型
 * 会注册错键)。client 链 = Uncaught/UserAgent/Cloudflare (挑战求解委托桌面引擎);
 * cloudflareClient 链无挑战处理段 (1.4 契约, 与 app 端一致)。
 */
private val desktopInterceptorsProvider: ExtensionInterceptorsProvider =
    object : ExtensionInterceptorsProvider {
        override fun clientInterceptors(
            context: android.content.Context,
            cookieJar: ChallengeCookieResolver,
            defaultUserAgent: () -> String,
        ): List<Interceptor> = listOf(
            UncaughtExceptionInterceptor(),
            UserAgentInterceptor(defaultUserAgent),
            CloudflareInterceptor(),
        )

        override fun cloudflareClientInterceptors(
            context: android.content.Context,
            cookieJar: ChallengeCookieResolver,
            defaultUserAgent: () -> String,
        ): List<Interceptor> = listOf(
            UncaughtExceptionInterceptor(),
            UserAgentInterceptor(defaultUserAgent),
        )
    }

fun registerDesktopExtensionCompat() {
    Injekt.addSingleton(applicationStub)
    Injekt.addSingletonFactory<Json> {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    Injekt.addSingletonFactory { desktopInterceptorsProvider }
    Injekt.addSingletonFactory { NetworkHelper(applicationStub) }
}
