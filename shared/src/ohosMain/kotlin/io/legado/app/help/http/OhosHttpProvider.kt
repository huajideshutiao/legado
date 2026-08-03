package io.legado.app.help.http

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
 *   异常处理由 OhosKmpCall.execute 转 okio.IOException, 头注入 / UA / CookieJar 桥接在
 *   KmpHttpTypes.ohos.kt 的 [KmpRequest.prepareForSend] 内等价实现 (与 Android app 拦截器行为对齐);
 * - SSL: desktop 用 SSLHelper.unsafeSSLSocketFactory, 鸿蒙端 @ohos.net.http 默认走系统信任库
 *   (鸿蒙系统级证书验证, 无 unsafe 模式);
 * - 代理: desktop 解析 "http(s)|socks4|socks5://host:port(@user@pass)?" 协议串构造 Proxy;
 *   鸿蒙端支持 http/https 代理 (@ohos.net.http HttpProxy, 含账号密码), socks4/socks5 回退主 client
 *   (@ohos.net.http 不支持 SOCKS, 与 Android 行为差异已说明)。
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

    /** 代理客户端缓存 (按 proxy 字符串, 与 Android HttpHelper.proxyClientCache 同语义) */
    private val proxyClientCache = HashMap<String, KmpHttpClient>()
    private val cacheLock = SynchronizedObject()

    /**
     * 取代理客户端 — 解析代理串并构造 @ohos.net.http 代理客户端 (与 Android HttpHelper.getProxyClient 同正则/语义)。
     *
     * - http/https 代理: @ohos.net.http 支持 HttpProxy (host/port/username/password, API 12+),
     *   经 HttpRequestPayload 透传, ArkTS 侧设 options.usingProxy;
     * - socks4/socks5: @ohos.net.http 只支持 HttpProxy (HTTP 代理), 不支持 SOCKS, 回退主 client
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
        // socks4/socks5: @ohos.net.http 不支持 SOCKS, 回退主 client 直连 (见类注释)
        return okHttpClient
    }
}

/**
 * 鸿蒙宿主启动早期注册 HTTP provider 的入口。
 *
 * 完成两件事 (对齐 iOS `registerIosHttpProvider`):
 * 1. 构造 [OhosHttpProvider] 并注册到 [OkHttpClientProviders] (供 [OkHttpUtils] 等取客户端);
 * 2. 同一实例注册到 [OkHttpProxyClientProviders] (供 [AnalyzeUrlCore] 取代理客户端,
 *    http/https 代理经 @ohos.net.http HttpProxy 支持, socks 回退主 client)。
 *
 * 注: 桌面端还会注册 HttpClients + CookieJarBridgeHolder, 鸿蒙 P0 阶段:
 * - HttpClients (HttpClient 接口) 在 commonMain 中暂无调用方, 不注册;
 * - CookieJarBridge (cookieJarHeader 桥接) 鸿蒙端经 registerSharedCookieJarBridge 注册
 *   commonMain SharedCookieJarBridge (在 registerOhosProviders 中调用)。
 */
fun registerOhosHttpProvider() {
    val provider = OhosHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
}
