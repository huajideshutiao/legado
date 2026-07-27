package io.legado.desktop.http

import io.legado.app.help.http.CookieJarBridgeHolder
import io.legado.app.help.http.HttpClients
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.OkHttpHttpClient
import io.legado.app.help.http.OkHttpProxyClientProviders
import io.legado.app.help.http.registerDefaultJvmCookieStoreProvider

/**
 * 桌面端 HTTP 层注册入口 (KP1.3)。
 *
 * 在 desktop Main 启动早期调用一次, 完成四件事:
 * 1. 构造 [DesktopHttpProvider] 并注册到 [OkHttpClientProviders] / [OkHttpProxyClientProviders]
 *    (供 shared 中通过 provider 取 OkHttpClient 的代码使用);
 * 2. 用 provider 的 okHttpClient 包装成 [OkHttpHttpClient], 注册到 [HttpClients]
 *    (供 shared 中跨平台 HttpClient 抽象层使用);
 * 3. 注册 [DesktopCookieJarBridge] 到 [CookieJarBridgeHolder]
 *    (供 OkHttp 头注入拦截器在请求头带 cookieJarHeader 时自动加载/保存 cookie);
 * 4. 注册 [io.legado.app.help.http.SharedCookieStore] 到 [io.legado.app.help.http.CookieStoreProviders]
 *    (供 shared 业务层跨平台调用 cookie 读写 API, Room cookieDao 持久化)。
 *
 * 对应 Android 端在 App.onCreate 中调用的 registerAndroidWebBookProviders / registerAndroidJsEngines。
 */
fun registerDesktopHttpProvider() {
    val provider = DesktopHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
    HttpClients.register(OkHttpHttpClient(provider.okHttpClient))
    CookieJarBridgeHolder.register(DesktopCookieJarBridge())
    // 注册业务层 CookieStoreProvider (commonMain SharedCookieStore, Room cookieDao 持久化)
    registerDefaultJvmCookieStoreProvider()
}
