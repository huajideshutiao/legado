package com.github.catvod.net

import androidx.collection.ArrayMap
import com.github.catvod.net.interceptor.AuthInterceptor
import com.github.catvod.net.interceptor.RequestInterceptor
import com.github.catvod.net.interceptor.ResponseInterceptor
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TVBox 壳网络门面: 静态 API 面逐字对齐 FongMi catvod 模块的 net.OkHttp
 * (jar 内常以 OkHttp.string/newCall/client/player/dns 直调; FQCN 与方法签名/返回类型必须一致,
 * 例如 Spider.safeDns() 返回 OkHttp.dns() 的类型是 OkDns 而非 okhttp3.Dns)。
 *
 * 与 FongMi 的差异仅一处且不改变任何签名:
 * FongMi 的 getBuilder() 里建了一个 HttpLoggingInterceptor 局部变量 (唯一用途是紧跟着一行
 * 被注释掉的 addNetworkInterceptor(logging)), 本宿主不引 logging-interceptor 依赖,
 * 故删掉该未使用局部变量 —— 它不参与任何公开 API, 删掉后 client()/player() 行为与 FongMi 一致。
 */
object OkHttp {

    private val TIMEOUT: Long = TimeUnit.SECONDS.toMillis(30)

    private var responseInterceptor: ResponseInterceptor? = null
    private var requestInterceptor: RequestInterceptor? = null
    private var authInterceptor: AuthInterceptor? = null
    private var authenticator: OkAuthenticator? = null
    private var selector: OkProxySelector? = null
    private var client: OkHttpClient? = null
    private var player: OkHttpClient? = null
    private var dns: OkDns? = null

    @JvmStatic
    fun get(): OkHttp = this

    @JvmStatic
    fun dns(): OkDns {
        get().dns?.let { return it }
        return OkDns().also { get().dns = it }
    }

    @JvmStatic
    fun responseInterceptor(): ResponseInterceptor {
        get().responseInterceptor?.let { return it }
        return ResponseInterceptor().also { get().responseInterceptor = it }
    }

    @JvmStatic
    fun requestInterceptor(): RequestInterceptor {
        get().requestInterceptor?.let { return it }
        return RequestInterceptor().also { get().requestInterceptor = it }
    }

    @JvmStatic
    fun authInterceptor(): AuthInterceptor {
        get().authInterceptor?.let { return it }
        return AuthInterceptor().also { get().authInterceptor = it }
    }

    @JvmStatic
    fun authenticator(): OkAuthenticator {
        get().authenticator?.let { return it }
        return OkAuthenticator(selector()).also { get().authenticator = it }
    }

    @JvmStatic
    fun selector(): OkProxySelector {
        get().selector?.let { return it }
        return OkProxySelector().also { get().selector = it }
    }

    @JvmStatic
    @Synchronized
    fun client(): OkHttpClient {
        get().client?.let { return it }
        return getBuilder().build().also { get().client = it }
    }

    @JvmStatic
    @Synchronized
    fun player(): OkHttpClient {
        get().player?.let { return it }
        return getBuilder().build().also { get().player = it }
    }

    @JvmStatic
    fun client(timeout: Long): OkHttpClient =
        client().newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()

    @JvmStatic
    fun noRedirect(): OkHttpClient = noRedirect(TIMEOUT)

    @JvmStatic
    fun noRedirect(timeout: Long): OkHttpClient {
        val builder = client().newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
        builder.interceptors().removeIf { item -> item is ProxyRedirectInterceptor }
        return builder.build()
    }

    @JvmStatic
    fun client(redirect: Boolean, timeout: Long): OkHttpClient =
        if (redirect) client(timeout) else noRedirect(timeout)

    @JvmStatic
    fun string(url: String): String {
        if (!url.startsWith("http")) return ""
        return try {
            newCall(url).execute().use { res -> res.body.string() }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @JvmStatic
    fun string(url: String, headers: Map<String, String>): String {
        if (!url.startsWith("http")) return ""
        return try {
            newCall(url, headers).execute().use { res -> res.body.string() }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    @JvmStatic
    fun newCall(url: String): Call = client().newCall(Request.Builder().url(url).build())

    @JvmStatic
    fun newCall(url: String, tag: String): Call =
        client().newCall(Request.Builder().url(url).tag(tag).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String): Call =
        client.newCall(Request.Builder().url(url).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, tag: String): Call =
        client.newCall(Request.Builder().url(url).tag(tag).build())

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>): Call =
        client().newCall(Request.Builder().url(url).headers(headers.toHeaders()).build())

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>, params: ArrayMap<String, String>): Call =
        client().newCall(Request.Builder().url(buildUrl(url, params)).headers(headers.toHeaders()).build())

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>, body: RequestBody): Call =
        client().newCall(Request.Builder().url(url).headers(headers.toHeaders()).post(body).build())

    @JvmStatic
    fun newCall(url: String, body: RequestBody, tag: String): Call =
        client().newCall(Request.Builder().url(url).post(body).tag(tag).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        client.newCall(Request.Builder().url(url).post(body).build())

    @JvmStatic
    fun cancel(tag: String) = cancel(client(), tag)

    @JvmStatic
    fun cancel(client: OkHttpClient, tag: String) {
        for (call in client.dispatcher.queuedCalls()) if (tag == call.request().tag()) call.cancel()
        for (call in client.dispatcher.runningCalls()) if (tag == call.request().tag()) call.cancel()
    }

    @JvmStatic
    fun cancelAll() = cancelAll(client())

    @JvmStatic
    fun cancelAll(client: OkHttpClient) {
        client.dispatcher.cancelAll()
    }

    @JvmStatic
    fun toBody(params: ArrayMap<String, String>): FormBody {
        val body = FormBody.Builder()
        for ((key, value) in params) body.add(key, value)
        return body.build()
    }

    private fun buildUrl(url: String, params: ArrayMap<String, String>): HttpUrl {
        val builder = url.toHttpUrlOrNull()!!.newBuilder()
        for ((key, value) in params) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private fun getBuilder(): OkHttpClient.Builder {
        val selector = selector()
        val builder = OkHttpClient.Builder()
            .addInterceptor(requestInterceptor())
            .addInterceptor(authInterceptor())
            .addInterceptor(ProxyRedirectInterceptor(selector))
            .addNetworkInterceptor(responseInterceptor())
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .dns(dns())
            .hostnameVerifier { _, _ -> true }
            .sslSocketFactory(getSSLContext().socketFactory, trustAllCertificates())
            .followRedirects(false)
        builder.proxyAuthenticator(authenticator())
        builder.proxySelector(selector)
        return builder
    }

    private fun getSSLContext(): SSLContext = try {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllCertificates()), SecureRandom())
        }
    } catch (e: Throwable) {
        throw IllegalStateException(e)
    }

    private fun trustAllCertificates(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun clear() {
        cancelAll()
        dns().clear()
        selector().clear()
        authInterceptor().clear()
        requestInterceptor().clear()
        responseInterceptor().clear()
    }
}
