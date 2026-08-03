package io.legado.app.help.http

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
 *   异常处理由 Ktor 内部完成, 头注入 / UA / CookieJar 桥接在 KmpHttpTypes.ios.kt 的
 *   [KmpRequest.prepareForSend] 内等价实现 (与 Android app 拦截器行为对齐);
 * - SSL: desktop 用 SSLHelper.unsafeSSLSocketFactory, iOS 端 Ktor CIO 默认走系统信任库
 *   (iOS 系统级证书验证, 无 unsafe 模式; 后续如需支持自签名证书可配置 Ktor engine);
 * - 代理: desktop 解析 "http(s)|socks4|socks5://host:port(@user@pass)?" 协议串构造 Proxy;
 *   iOS 端支持 http/https 代理 (Ktor CIO 引擎配置, 含基础认证), socks4/socks5 回退主 client
 *   (CIO 不支持 SOCKS, 与 Android 行为差异已说明)。
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

    /** 代理客户端缓存 (按 proxy 字符串, 与 Android HttpHelper.proxyClientCache 同语义) */
    private val proxyClientCache = HashMap<String, KmpHttpClient>()
    private val cacheLock = SynchronizedObject()

    /**
     * 取代理客户端 — 解析代理串并构造 Ktor 代理客户端 (与 Android HttpHelper.getProxyClient 同正则/语义)。
     *
     * - http/https 代理: Ktor CIO 支持 (CONNECT 隧道), 经 [KmpHttpClientBuilder.proxy] 配置;
     * - 认证: CIO 无 CONNECT 级认证 API, 用 Proxy-Authorization 请求头预置 (CIO startTunnel
     *   会透传该头到 CONNECT; 该头也会随请求到达目标站, 与 OkHttp ProxyAuthenticator 407
     *   挑战式认证不同, 属于尽力而为);
     * - socks4/socks5: Ktor CIO 引擎不支持 SOCKS (ProxyType.SOCKS 被引擎忽略), 回退主 client
     *   直连 (与 Android 行为差异: Android 会构造 SOCKS 代理, 已说明)。
     *
     * 代理串格式非法时抛异常 (与 Android 对非法格式 fail-loud 一致, 避免用户配置的代理被静默绕过)。
     */
    override fun getProxyClient(proxy: String?): KmpHttpClient {
        if (proxy.isNullOrBlank()) {
            return okHttpClient
        }
        synchronized(cacheLock) {
            proxyClientCache[proxy]?.let { return it }
        }
        val client = createProxyClient(proxy)
        synchronized(cacheLock) {
            proxyClientCache[proxy] = client
        }
        return client
    }

    private fun createProxyClient(proxy: String): KmpHttpClient {
        // 与 Android HttpHelper 同正则: (http|https|socks4|socks5)://host:port(@user@pass)?
        val r = Regex("(http|https|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
        val group = r.find(proxy)
            ?: throw IllegalArgumentException("代理格式错误: $proxy (应为 http(s)|socks4|socks5://host:port(@user@pass)?)")
        val type = group.groupValues[1]
        val host = group.groupValues[2]
        val port = group.groupValues[3].toInt()
        var username = ""
        var password = ""
        if (group.groupValues[4] != "") {
            username = group.groupValues[4].split("@")[1]
            password = group.groupValues[4].split("@")[2]
        }
        if (host.isEmpty()) {
            // 与 Android 一致: host 为空回退主 client
            return okHttpClient
        }
        if (type.startsWith("http")) {
            val usernameOrNull = username.ifEmpty { null }
            val passwordOrNull = password.ifEmpty { null }
            return KmpHttpClientBuilder()
                .proxy(host, port, usernameOrNull, passwordOrNull)
                .build()
        }
        // socks4/socks5: Ktor CIO 不支持 SOCKS, 回退主 client 直连 (见类注释)
        return okHttpClient
    }
}

/**
 * iOS 宿主启动早期注册 HTTP provider 的入口。
 *
 * 完成两件事 (对齐 desktop `registerDesktopHttpProvider`):
 * 1. 构造 [IosHttpProvider] 并注册到 [OkHttpClientProviders] (供 [IosBookCover] 等取客户端);
 * 2. 同一实例注册到 [OkHttpProxyClientProviders] (供 [AnalyzeUrlCore] 取代理客户端,
 *    http/https 代理经 Ktor CIO 支持, socks 回退主 client)。
 *
 * 注: 桌面端还会注册 HttpClients + CookieJarBridgeHolder, iOS P0 阶段:
 * - HttpClients (HttpClient 接口) 在 commonMain 中暂无调用方 (IosBookCover 直接用 OkHttpClientProviders),
 *   不注册;
 * - CookieJarBridge (cookieJarHeader 桥接) iOS 端经 registerSharedCookieJarBridge 注册
 *   commonMain SharedCookieJarBridge (在 registerIosProviders 中调用)。
 */
fun registerIosHttpProvider() {
    val provider = IosHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
}
