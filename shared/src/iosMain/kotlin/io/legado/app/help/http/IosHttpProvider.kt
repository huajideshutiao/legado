package io.legado.app.help.http

/**
 * iOS 端 HTTP provider 实现 (KP3 补完)。
 *
 * # 设计动机
 *
 * commonMain 中 [OkHttpClientProviders] / [OkHttpProxyClientProviders] 是 provider 注入容器,
 * 桌面端在 `registerDesktopHttpProvider()` 中注册 [io.legado.desktop.http.DesktopHttpProvider]
 * (基于 okhttp3.OkHttpClient); Android 端在 `registerAndroidWebBookProviders()` 中注册
 * (基于 app 端 okHttpClient 单例, 含 Cronet/Glide/cookieJar)。
 *
 * iOS target 没有 OkHttp 5.3.2 变体 (OkHttp 仅发布 common + android + jvm),
 * 但 [KmpHttpTypes.ios.kt] 已用 Ktor 3.1.0 CIO engine 包装出等价的 [KmpHttpClient]
 * (纯 Kotlin 跨平台, 无 native 依赖)。本类把 Ktor 包装的 [KmpHttpClient] 注册到
 * [OkHttpClientProviders] / [OkHttpProxyClientProviders], 让 commonMain 中的
 * [AnalyzeUrlCore] / [IosBookCover] / [OkHttpUtils] 等通过 provider 取到可用客户端。
 *
 * # 与桌面端 [io.legado.desktop.http.DesktopHttpProvider] 区别
 *
 * - 引擎: desktop 用 okhttp3.OkHttpClient (JVM), iOS 用 Ktor HttpClient (CIO);
 * - 拦截器: desktop 挂 OkHttpExceptionInterceptor / 头注入 / DecompressInterceptor,
 *   iOS 端 KmpInterceptor 是编译占位 (KmpHttpTypes.ios.kt 中 KmpInterceptor.intercept 不会被调用),
 *   异常处理由 Ktor 内部完成, 头注入由 AnalyzeUrlCore 在构造 KmpRequest 时直接写入 headers;
 * - SSL: desktop 用 SSLHelper.unsafeSSLSocketFactory, iOS 端 Ktor CIO 默认走系统信任库
 *   (iOS 系统级证书验证, 无 unsafe 模式; 后续如需支持自签名证书可配置 Ktor engine);
 * - 代理: desktop 解析 "http(s)|socks4|socks5://host:port(@user@pass)?" 协议串构造 Proxy,
 *   iOS 端 P0 阶段不支持代理 ([getProxyClient] 直接返回主 client), 后续如需可用 Ktor engine
 *   配置 proxy (https://ktor.io/docs/http-client-engines.html#configure-proxy)。
 *
 * # 调用时机
 *
 * iOS 宿主启动早期经 [registerIosHttpProvider] 注册 (在任何 commonMain HTTP 调用之前),
 * 详见 [io.legado.app.help.config.IosProviderRegistry] 注册顺序约束。
 *
 * 模式参考 desktop `registerDesktopHttpProvider` (Main.kt line 72)。
 */
class IosHttpProvider : OkHttpClientProvider, OkHttpProxyClientProvider {

    /**
     * iOS 端 [KmpHttpClient] 单例 (基于 Ktor CIO engine)。
     *
     * lazy 构造: 首次访问时调 [KmpHttpClientBuilder.build] 创建 Ktor HttpClient。
     * 与桌面端 `DesktopHttpProvider.okHttpClient` by lazy 行为一致。
     */
    override val okHttpClient: KmpHttpClient by lazy {
        KmpHttpClientBuilder().build()
    }

    /**
     * 取代理客户端 — iOS P0 阶段不支持代理, 直接返回主 [okHttpClient]。
     *
     * 调用方 [io.legado.app.model.analyzeRule.AnalyzeUrlCore.getClient] 在 `proxy` 为空时
     * 本就走主 client, 非空时桌面端按协议串构造 Proxy; iOS 端 P0 阶段忽略 proxy 参数,
     * 一律走主 client (让书源规则解析能跑通, 代理能力待后续 Ktor engine 配置补)。
     */
    override fun getProxyClient(proxy: String?): KmpHttpClient {
        // proxy 非空时记录日志 (iOS 端无统一日志, 调用方按需 debug);
        // P0 阶段直接返回主 client, 不抛异常 (避免书源配置了 proxy 时整章解析失败)
        return okHttpClient
    }
}

/**
 * iOS 宿主启动早期注册 HTTP provider 的入口。
 *
 * 完成两件事 (对齐 desktop `registerDesktopHttpProvider`):
 * 1. 构造 [IosHttpProvider] 并注册到 [OkHttpClientProviders] (供 [IosBookCover] 等取客户端);
 * 2. 同一实例注册到 [OkHttpProxyClientProviders] (供 [AnalyzeUrlCore] 取代理客户端,
 *    iOS P0 不支持代理, 一律返回主 client)。
 *
 * 注: 桌面端还会注册 HttpClients + CookieJarBridgeHolder, iOS P0 阶段:
 * - HttpClients (HttpClient 接口) 在 commonMain 中暂无调用方 (IosBookCover 直接用 OkHttpClientProviders),
 *   不注册;
 * - CookieJarBridge (cookieJarHeader 桥接) iOS 端暂无实现, 调用方 AnalyzeUrlCore 在桥接未注册时
 *   自动跳过 cookie 注入 (与桌面端 bridge=null 行为一致), 不阻塞主流程。
 */
fun registerIosHttpProvider() {
    val provider = IosHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
}
