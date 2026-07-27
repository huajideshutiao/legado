package io.legado.app.help.http

/**
 * OkHttpClient 单例注入接口（shared commonMain）。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.OkHttpClient`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpHttpClient] 跨平台抽象
 * (jvmAndAndroidMain 经 typealias 等价 okhttp3.OkHttpClient; iOS/鸿蒙 stub)。
 *
 * `okHttpClient` 单例实现依赖 app-only 模块 (AppConfig/Cronet/CookieManager/
 * ProgressManager/SSLHelper 等), 故通过 provider 注入解耦。
 *
 * app 端在 [io.legado.app.model.webBook.registerAndroidWebBookProviders] 中
 * 注册实现, 转发给 [io.legado.app.help.http.okHttpClient]。
 *
 * 模式参考 [OkHttpProxyClientProvider]。
 */
interface OkHttpClientProvider {
    val okHttpClient: KmpHttpClient
}

object OkHttpClientProviders {
    @Volatile
    private var impl: OkHttpClientProvider? = null

    /** 宿主启动早期注册一次(任何 shared 调用之前)。 */
    fun register(impl: OkHttpClientProvider) {
        this.impl = impl
    }

    /** 取已注册实现；未注册抛出 IllegalStateException 帮助早期发现初始化遗漏。 */
    fun get(): OkHttpClientProvider =
        impl ?: error("OkHttpClientProviders.impl not registered; call registerAndroidWebBookProviders() in App.onCreate first")
}
