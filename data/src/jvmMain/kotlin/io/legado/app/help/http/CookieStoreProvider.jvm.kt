package io.legado.app.help.http

/**
 * 桌面/JVM 端默认 [CookieStoreProvider] 注册入口。
 *
 * 原 JvmCookieStoreProvider (java.net.CookieManager 进程内存储, 重启即丢) 已删除,
 * 改注册 commonMain [SharedCookieStore] (app 端 CookieStore/CookieManager 原版语义下沉:
 * Room cookieDao 持久化 + CacheManager 内存热层), cookie 跨重启保留。
 *
 * 在 desktop Main 启动早期调用一次 (io.legado.desktop.http.registerDesktopHttpProvider)。
 * 对应 app 端 `registerAndroidCookieStoreProvider` / iOS `registerDefaultIosCookieStoreProvider` /
 * 鸿蒙 `registerDefaultOhosCookieStoreProvider`。
 */
fun registerDefaultJvmCookieStoreProvider() {
    CookieStoreProviders.register(SharedCookieStore)
}
