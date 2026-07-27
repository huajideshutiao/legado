package io.legado.app.help.http

/**
 * Android 端 [CookieStoreProvider] 实现: 委托给现有 [CookieStore] / [CookieManager] 单例。
 *
 * # 背景
 * app 端 [CookieStore] / [CookieManager] 依赖 Android 专属资源 (appDb.cookieDao /
 * CacheManager 内存缓存 / android.webkit.CookieManager), 保留原实现不动
 * (硬约束: app 端现有调用点不变)。本类仅做薄包装, 把 [CookieStoreProvider] 接口方法
 * 委托到现有单例, 供 shared/commonMain 通过 [CookieStoreProviders.get] 跨平台访问。
 *
 * # 方法映射
 * - [setCookie] / [replaceCookie] / [getCookie] / [getKey] / [removeCookie] / [clear]
 *   → [CookieStore] 对应方法 (业务层 cookie 读写, 含 WebView 同步)
 * - [removeCookie] (url, key) / [getCookieNoSession] / [getSessionCookie]
 *   → [CookieManager] 对应方法 (session/persistent cookie 细粒度管理)
 *
 * # 注册
 * 通过 [registerAndroidCookieStoreProvider] 在 App.onCreate 中调用一次
 * (紧跟 [CookieJarBridgeHolder.register] 之后)。
 *
 * 模式参考 [io.legado.app.help.DirectLinkUpload] (app 端 object 实现 Provider 后注册)。
 */
object AndroidCookieStoreProvider : CookieStoreProvider {

    override fun setCookie(url: String, cookie: String?) =
        CookieStore.setCookie(url, cookie)

    override fun replaceCookie(url: String, cookie: String) =
        CookieStore.replaceCookie(url, cookie)

    override fun getCookie(url: String): String =
        CookieStore.getCookie(url)

    override fun getKey(url: String, key: String): String =
        CookieStore.getKey(url, key)

    override fun removeCookie(url: String) =
        CookieStore.removeCookie(url)

    override fun removeCookie(url: String, key: String) =
        CookieManager.removeCookie(url, key)

    override fun getCookieNoSession(url: String): String =
        CookieManager.getCookieNoSession(url)

    override fun getSessionCookie(domain: String): String? =
        CookieManager.getSessionCookie(domain)

    override fun clear() =
        CookieStore.clear()
}

/**
 * Android 端 [CookieStoreProvider] 注册入口。
 *
 * 在 App.onCreate 中调用一次 (紧跟 [CookieJarBridgeHolder.register] 之后),
 * 把 [AndroidCookieStoreProvider] 注册到 [CookieStoreProviders]。
 *
 * 对应 desktop `registerDefaultJvmCookieStoreProvider` /
 * iOS `registerDefaultIosCookieStoreProvider` /
 * 鸿蒙 `registerDefaultOhosCookieStoreProvider`。
 */
fun registerAndroidCookieStoreProvider() {
    CookieStoreProviders.register(AndroidCookieStoreProvider)
}
