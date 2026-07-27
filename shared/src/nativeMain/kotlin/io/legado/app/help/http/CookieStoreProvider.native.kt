package io.legado.app.help.http

/**
 * 鸿蒙 (OpenHarmony) 端默认 [CookieStoreProvider] 注册入口。
 *
 * 原 NativeCookieStoreProvider (`{filesDir}/cookies.json` 自写文件持久化) 已删除,
 * 改注册 commonMain [SharedCookieStore] (app 端 CookieStore/CookieManager 原版语义下沉:
 * Room cookieDao 持久化 + CacheManager 内存热层), 与 desktop/iOS 共用同一实现。
 *
 * 在 [io.legado.app.help.config.registerOhosProviders] 中调用一次 (注册本身无 DB 访问,
 * 首次 cookie 读写才触发 AppDbProviders)。
 * 对应 app 端 `registerAndroidCookieStoreProvider` / desktop `registerDefaultJvmCookieStoreProvider` /
 * iOS `registerDefaultIosCookieStoreProvider`。
 */
fun registerDefaultOhosCookieStoreProvider() {
    CookieStoreProviders.register(SharedCookieStore)
}
