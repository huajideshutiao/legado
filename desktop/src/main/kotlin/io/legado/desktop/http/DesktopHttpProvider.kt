package io.legado.desktop.http

import io.legado.app.constant.AppConst
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.http.CookieJarBridgeHolder
import io.legado.app.help.http.DecompressInterceptor
import io.legado.app.help.http.OkHttpClientProvider
import io.legado.app.help.http.OkHttpExceptionInterceptor
import io.legado.app.help.http.OkHttpProxyClientProvider
import io.legado.app.help.http.OkhttpUncaughtExceptionHandler
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.cookieJarHeader
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 桌面端 OkHttpClient / 代理 OkHttpClient Provider 实现 (KP1.3)。
 *
 * 参考 [io.legado.app.help.http.okHttpClient] 的构造逻辑, 桌面端裁剪:
 * - 不挂 Cronet 拦截器 (Cronet 是 Android 专属);
 * - 不挂 ProgressResponseBody 进度包装: 唯一消费方是 app 端漫画阅读器 MangaPageImageView,
 *   桌面端无 ProgressManager.addListener 调用, 包上去每个响应都是白付一次 map 查询;
 *   若将来桌面漫画要显示单页下载进度, 需先把 ProgressResponseBody 下沉到 shared;
 * - cookieJar 通过 [CookieJarBridgeHolder] 注入桥接, 未注册则跳过 (桌面端可注册 [DesktopCookieJarBridge] 内存版);
 * - SSL/超时/重试/重定向/拦截器 (异常/头注入/解压) 与 Android 端语义一致。
 *
 * 实现接口:
 * - [OkHttpClientProvider]: 暴露单例 [okHttpClient];
 * - [OkHttpProxyClientProvider]: 暴露 [getProxyClient] 工厂 + 缓存。
 */
class DesktopHttpProvider(
    /**
     * 每次请求现取, 对照 app 端拦截器内直接读 `AppConfig.userAgent`:
     * 用户在设置里改 UA 后立即生效, 不需要重启 (构造期常量化会锁死旧值)。
     */
    private val userAgent: () -> String = { UserAgentProviders.impl?.get() ?: DEFAULT_UA },
) : OkHttpClientProvider, OkHttpProxyClientProvider {

    override val okHttpClient: OkHttpClient by lazy { createOkHttpClient() }

    private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> = ConcurrentHashMap()

    override fun getProxyClient(proxy: String?): OkHttpClient {
        // 空 proxy 回落主客户端, 与 Android 端 getProxyClient 一致
        if (proxy.isNullOrBlank()) {
            return okHttpClient
        }
        proxyClientCache[proxy]?.let { return it }

        // 解析 "http(s)|socks4|socks5://host:port(@user@pass)?" 协议串
        val r = Regex("(http|https|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
        val ms = r.findAll(proxy)
        val group = ms.first()
        var username = ""
        var password = ""
        val type = if (group.groupValues[1].startsWith("http")) "http" else "socks"
        val host = group.groupValues[2]
        val port = group.groupValues[3].toInt()
        if (group.groupValues[4] != "") {
            username = group.groupValues[4].split("@")[1]
            password = group.groupValues[4].split("@")[2]
        }
        if (host == "") {
            return okHttpClient
        }

        // newBuilder 派生: 复用主客户端的拦截器/SSL/超时等配置, 仅替换 proxy + proxyAuthenticator
        val builder = okHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            builder.proxyAuthenticator { _, response ->
                val credential: String = Credentials.basic(username, password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }

    private fun createOkHttpClient(): OkHttpClient {
        val specs = arrayListOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS,
            ConnectionSpec.CLEARTEXT
        )

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            // unsafe SSL: 接受任意服务器证书, 与 Android 端一致 (书源站点证书参差不齐)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
            .retryOnConnectionFailure(true)
            .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
            .connectionSpecs(specs)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(OkHttpExceptionInterceptor)
            .addInterceptor { chain ->
                // 头注入拦截器: UA + Keep-Alive/Connection/Cache-Control + 可选 cookieJar
                var request = chain.request()
                val builder = request.newBuilder()
                if (request.header(AppConst.UA_NAME) == null) {
                    builder.addHeader(AppConst.UA_NAME, userAgent())
                } else if (request.header(AppConst.UA_NAME) == "null") {
                    builder.removeHeader(AppConst.UA_NAME)
                }
                builder.addHeader("Keep-Alive", "300")
                builder.addHeader("Connection", "Keep-Alive")
                builder.addHeader("Cache-Control", "no-cache")

                // cookieJar: 请求头带 cookieJarHeader 标记启用; 桥接未注册则跳过 (桌面端可后注册)
                val bridge = CookieJarBridgeHolder.get()
                val enableCookieJar = request.header(cookieJarHeader) != null
                if (enableCookieJar) {
                    builder.removeHeader(cookieJarHeader)
                    request = if (bridge != null) bridge.loadRequest(builder.build()) else builder.build()
                } else {
                    request = builder.build()
                }

                val response = chain.proceed(request)
                if (enableCookieJar && bridge != null) {
                    bridge.saveResponse(response)
                }
                response
            }
            .addInterceptor(DecompressInterceptor)

        return builder.build().apply {
            // Dispatcher 线程命名 + 未捕获异常处理, 与 Android 端一致
            val okHttpName =
                OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")
            val executor = dispatcher.executorService as ThreadPoolExecutor
            val threadName = "$okHttpName Dispatcher"
            executor.threadFactory = ThreadFactory { runnable ->
                Thread(runnable, threadName).apply {
                    isDaemon = false
                    uncaughtExceptionHandler = OkhttpUncaughtExceptionHandler
                }
            }
        }
    }

    companion object {
        // 与 Android 端 getPrefUserAgent 默认值对齐 (Chrome 桌面 UA)
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
