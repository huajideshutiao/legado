package io.legado.app.help.http

/**
 * 鸿蒙端 HTTP provider 实现。
 *
 * # 设计动机
 *
 * commonMain 中 [OkHttpClientProviders] / [OkHttpProxyClientProviders] 是 provider 注入容器,
 * 桌面端在 `registerDesktopHttpProvider()` 中注册 [io.legado.desktop.http.DesktopHttpProvider]
 * (基于 okhttp3.OkHttpClient); Android 端在 `registerAndroidWebBookProviders()` 中注册
 * (基于 app 端 okHttpClient 单例, 含 Cronet/Glide/cookieJar)。
 *
 * 鸿蒙 target 没有 OkHttp 5.3.2 变体 (OkHttp 仅发布 common + android + jvm),
 * 但 [KmpHttpTypes.ohos.kt] 已用 napi 桥接 @ohos.net.http 包装出等价的 [KmpHttpClient]
 * (经 [OhosNativeBridge.invokeHttpSync] dispatch 到 ArkTS 主线程执行真实请求)。
 * 本类把 napi 桥接的 [KmpHttpClient] 注册到 [OkHttpClientProviders] / [OkHttpProxyClientProviders],
 * 让 commonMain 中的 [AnalyzeUrlCore] / [OkHttpUtils] 等通过 provider 取到可用客户端。
 *
 * # 与桌面端 [io.legado.desktop.http.DesktopHttpProvider] 区别
 *
 * - 引擎: desktop 用 okhttp3.OkHttpClient (JVM), 鸿蒙用 @ohos.net.http (ArkTS API, napi 桥接);
 * - 拦截器: desktop 挂 OkHttpExceptionInterceptor / 头注入 / DecompressInterceptor,
 *   鸿蒙端 KmpInterceptor 是编译占位 (KmpHttpTypes.ohos.kt 中 KmpInterceptor.intercept 不会被调用),
 *   异常处理由 OhosKmpCall.execute 转 okio.IOException, 头注入由 AnalyzeUrlCore 在构造 KmpRequest 时直接写入 headers;
 * - SSL: desktop 用 SSLHelper.unsafeSSLSocketFactory, 鸿蒙端 @ohos.net.http 默认走系统信任库
 *   (鸿蒙系统级证书验证, 无 unsafe 模式);
 * - 代理: desktop 解析 "http(s)|socks4|socks5://host:port(@user@pass)?" 协议串构造 Proxy,
 *   鸿蒙端 P0 阶段不支持代理 ([getProxyClient] 直接返回主 client), 后续如需可配置 @ohos.net.http proxy。
 *
 * # 调用时机
 *
 * 鸿蒙宿主启动早期经 [registerOhosHttpProvider] 注册 (在任何 commonMain HTTP 调用之前),
 * 详见 [io.legado.app.help.config.OhosProviderRegistry] 注册顺序约束。
 *
 * 模式参考 iOS `IosHttpProvider` (与 iOS P0 阶段一致, getProxyClient 返回主 client)。
 */
class OhosHttpProvider : OkHttpClientProvider, OkHttpProxyClientProvider {

    /**
     * 鸿蒙端 [KmpHttpClient] 单例 (基于 @ohos.net.http napi 桥接)。
     *
     * lazy 构造: 首次访问时调 [KmpHttpClientBuilder.build] 创建 KmpHttpClient。
     * 与桌面端 `DesktopHttpProvider.okHttpClient` by lazy 行为一致。
     */
    override val okHttpClient: KmpHttpClient by lazy {
        KmpHttpClientBuilder().build()
    }

    /**
     * 取代理客户端 — 鸿蒙 P0 阶段不支持代理, 直接返回主 [okHttpClient]。
     *
     * 调用方 [io.legado.app.model.analyzeRule.AnalyzeUrlCore.getClient] 在 `proxy` 为空时
     * 本就走主 client, 非空时桌面端按协议串构造 Proxy; 鸿蒙端 P0 阶段忽略 proxy 参数,
     * 一律走主 client (让书源规则解析能跑通, 代理能力待后续 @ohos.net.http 配置补)。
     */
    override fun getProxyClient(proxy: String?): KmpHttpClient {
        // P0 阶段直接返回主 client, 不抛异常 (避免书源配置了 proxy 时整章解析失败)
        return okHttpClient
    }
}

/**
 * 鸿蒙宿主启动早期注册 HTTP provider 的入口。
 *
 * 完成两件事 (对齐 iOS `registerIosHttpProvider`):
 * 1. 构造 [OhosHttpProvider] 并注册到 [OkHttpClientProviders] (供 [OkHttpUtils] 等取客户端);
 * 2. 同一实例注册到 [OkHttpProxyClientProviders] (供 [AnalyzeUrlCore] 取代理客户端,
 *    鸿蒙 P0 不支持代理, 一律返回主 client)。
 *
 * 注: 桌面端还会注册 HttpClients + CookieJarBridgeHolder, 鸿蒙 P0 阶段:
 * - HttpClients (HttpClient 接口) 在 commonMain 中暂无调用方, 不注册;
 * - CookieJarBridge (cookieJarHeader 桥接) 鸿蒙端由 registerDefaultOhosCookieStoreProvider
 *   单独注册 stub, 不在此处处理。
 */
fun registerOhosHttpProvider() {
    val provider = OhosHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
}
