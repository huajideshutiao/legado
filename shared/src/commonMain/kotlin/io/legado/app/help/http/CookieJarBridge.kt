package io.legado.app.help.http

/**
 * ObsoleteUrlFactory 下沉 shared (jvmAndAndroidMain) 后, 无法直接引用 app 端 CookieManager object
 * (CookieManager 依赖 appDb/CacheManager/android.webkit, 整体下沉成本过大)。
 * 采用与 JsExtProviders / AppDbProviders 一致的注入模式: app 端启动时注册实现,
 * shared 内 ObsoleteUrlFactory 经 [CookieJarBridgeHolder.get] 取得桥接。
 *
 * P0-0c: 为 ObsoleteUrlFactory 下沉 shared 做前置。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.Request/Response`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpRequest]/[KmpResponse] 跨平台抽象
 * (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 stub)。
 */
interface CookieJarBridge {
    /** 对应 app 端 CookieManager.loadRequest */
    fun loadRequest(request: KmpRequest): KmpRequest
    /** 对应 app 端 CookieManager.saveResponse */
    fun saveResponse(response: KmpResponse)
}

object CookieJarBridgeHolder {
    @Volatile
    private var impl: CookieJarBridge? = null

    /** app 端启动早期注册一次(任何 ObsoleteUrlFactory 调用之前)。 */
    fun register(impl: CookieJarBridge) {
        this.impl = impl
    }

    /** 已注册则返回实现, 未注册返回 null (ObsoleteUrlFactory 拦截器无 cookie jar 时跳过)。 */
    fun get(): CookieJarBridge? = impl
}
