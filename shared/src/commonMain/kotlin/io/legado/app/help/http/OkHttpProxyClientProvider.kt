package io.legado.app.help.http

/**
 * OkHttp 代理客户端工厂注入接口（shared commonMain）。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.OkHttpClient`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpHttpClient] 跨平台抽象
 * (jvmAndAndroidMain 经 typealias 等价 okhttp3.OkHttpClient; iOS/鸿蒙 stub)。
 *
 * `okHttpClient`/`getProxyClient` 单例实现依赖 app-only 模块
 * (AppConfig/Cronet/CookieManager/ProgressManager/SSLHelper 等), 故通过 provider 注入解耦。
 *
 * app 端在 [io.legado.app.model.script.registerAndroidJsEngines] 中注册实现, 转发给
 * [io.legado.app.help.http.getProxyClient]。
 */
interface OkHttpProxyClientProvider {
    fun getProxyClient(proxy: String?): KmpHttpClient
}

object OkHttpProxyClientProviders {
    @Volatile
    var impl: OkHttpProxyClientProvider? = null

    /** 取已注册实现；未注册抛出 IllegalStateException 帮助早期发现初始化遗漏。 */
    fun get(): OkHttpProxyClientProvider =
        impl ?: error("OkHttpProxyClientProviders.impl not registered; call registerAndroidJsEngines() in App.onCreate first")
}
