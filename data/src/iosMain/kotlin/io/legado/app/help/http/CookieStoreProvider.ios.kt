package io.legado.app.help.http

/**
 * iOS 端默认 [CookieStoreProvider] 注册入口。
 *
 * 原 IosCookieStoreProvider (NSHTTPCookieStorage 系统存储, 与 app 端 DB 语义不一致) 已删除,
 * 改注册 commonMain [SharedCookieStore] (app 端 CookieStore/CookieManager 原版语义下沉:
 * Room cookieDao 持久化 + CacheManager 内存热层), 与 desktop/鸿蒙共用同一实现。
 *
 * 在 [io.legado.app.help.config.registerIosProviders] 中调用一次 (须在 AppDbProviders 可用前提下,
 * 注册本身无 DB 访问, 首次 cookie 读写才触发)。
 * 对应 app 端 `registerAndroidCookieStoreProvider` / desktop `registerDefaultJvmCookieStoreProvider` /
 * 鸿蒙 `registerDefaultOhosCookieStoreProvider`。
 */
fun registerDefaultIosCookieStoreProvider() {
    CookieStoreProviders.register(SharedCookieStore)
}
