package io.legado.app.help.http

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * iOS / 鸿蒙共用的 HTTP provider 实现 (两端逐字相同, 故落在 nativeMain)。
 *
 * # 设计动机
 *
 * commonMain 中 [OkHttpClientProviders] / [OkHttpProxyClientProviders] 是 provider 注入容器,
 * 桌面端注册 [io.legado.desktop.http.DesktopHttpProvider] (okhttp3.OkHttpClient), Android 端注册
 * app 端 okHttpClient 单例 (含 Cronet/Glide/cookieJar)。两端 target 都没有 OkHttp 变体
 * (OkHttp 仅发布 common + android + jvm), 但 KmpHttpTypes.ios.kt / KmpHttpTypes.ohos.kt 已包装出
 * 等价的 [KmpHttpClient] (iOS: Ktor 3.1.0 CIO engine; 鸿蒙: napi 桥接 @ohos.net.http, 经
 * OhosNativeBridge.invokeHttpSync dispatch 到 ArkTS 主线程)。本类把它注册到两个容器,
 * 让 commonMain 的 [AnalyzeUrlCore] / BookCover / OkHttpUtils 等取到可用客户端。
 *
 * # 与桌面端 DesktopHttpProvider 的区别 (两端同款)
 *
 * - 拦截器: desktop 挂 OkHttpExceptionInterceptor / 头注入 / DecompressInterceptor; 两端 KmpInterceptor
 *   是编译占位, 异常处理由各自引擎完成, 头注入 / UA / CookieJar 桥接在 KmpRequest.prepareForSend
 *   内等价实现 (与 Android app 拦截器行为对齐);
 * - SSL: desktop 用 SSLHelper.unsafeSSLSocketFactory; 两端走系统信任库, 无 unsafe 模式;
 * - 代理: desktop 解析协议串构造 Proxy; 两端 http/https 经 [buildNativeProxyClient] 各自引擎实现,
 *   socks4/socks5 两端引擎均不支持 (回退主 client 直连, 与 Android 构造 SOCKS 代理的行为差异)。
 *
 * # 调用时机
 *
 * 宿主启动早期经 [registerNativeHttpProvider] 注册 (在任何 commonMain HTTP 调用之前),
 * 详见 registerIosProviders / registerOhosProviders 的注册顺序约束。
 */
class NativeHttpProvider : OkHttpClientProvider, OkHttpProxyClientProvider {

    /**
     * [KmpHttpClient] 单例 (iOS Ktor CIO engine / 鸿蒙 @ohos.net.http napi 桥接)。
     *
     * lazy 构造: 首次访问时调 [KmpHttpClientBuilder.build]。
     * 与桌面端 `DesktopHttpProvider.okHttpClient` by lazy 行为一致。
     */
    override val okHttpClient: KmpHttpClient by lazy {
        KmpHttpClientBuilder().build()
    }

    /** 代理客户端缓存 (按 proxy 字符串, 与 Android HttpHelper.proxyClientCache 同语义) */
    private val proxyClientCache = HashMap<String, KmpHttpClient>()
    private val cacheLock = SynchronizedObject()

    /**
     * 取代理客户端 — 解析代理串并构造代理客户端 (与 Android HttpHelper.getProxyClient 同正则/语义)。
     *
     * - http/https 代理: 经 [buildNativeProxyClient] 走各端引擎 (iOS Ktor CIO 的 CONNECT 隧道 +
     *   Proxy-Authorization 头预置; 鸿蒙 @ohos.net.http HttpProxy, API 12+ 原生带账号密码);
     * - socks4/socks5: 两端引擎都不支持 SOCKS, 回退主 client 直连
     *   (与 Android 行为差异: Android 会构造 SOCKS 代理, 已说明)。
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
            return buildNativeProxyClient(host, port, usernameOrNull, passwordOrNull)
        }
        // socks4/socks5: 两端引擎不支持 SOCKS, 回退主 client 直连 (见类注释)
        return okHttpClient
    }
}

/**
 * 构造 http/https 代理客户端。
 *
 * 代理配置只在两端 actual builder 上 (commonMain 的 expect class [KmpHttpClientBuilder] 未声明
 * proxy 成员, jvmAndAndroid 的 typealias actual 也没有同签名重载), 故经本 expect 分派。
 */
internal expect fun buildNativeProxyClient(
    host: String,
    port: Int,
    username: String?,
    password: String?,
): KmpHttpClient

/**
 * 宿主启动早期注册 HTTP provider 的入口。
 *
 * 完成两件事 (对齐 desktop `registerDesktopHttpProvider`):
 * 1. 构造 [NativeHttpProvider] 并注册到 [OkHttpClientProviders] (供 BookCover / OkHttpUtils 等取客户端);
 * 2. 同一实例注册到 [OkHttpProxyClientProviders] (供 [AnalyzeUrlCore] 取代理客户端)。
 *
 * 注: 桌面端还会注册 HttpClients + CookieJarBridgeHolder, 两端 P0 阶段:
 * - HttpClients (HttpClient 接口) 在 commonMain 中暂无调用方, 不注册;
 * - CookieJarBridge (cookieJarHeader 桥接) 经 registerSharedCookieJarBridge 注册 commonMain
 *   SharedCookieJarBridge (在 registerIosProviders / registerOhosProviders 中调用)。
 */
fun registerNativeHttpProvider() {
    val provider = NativeHttpProvider()
    OkHttpClientProviders.register(provider)
    OkHttpProxyClientProviders.impl = provider
}
