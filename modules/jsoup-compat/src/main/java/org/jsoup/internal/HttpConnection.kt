@file:Suppress("unused")

package org.jsoup.internal

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Connection
import org.jsoup.Connection.Method
import java.io.InputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * jsoup Connection 兼容实现,底层使用 OkHttp 完成实际请求。
 *
 * 链式 API 与原 jsoup HttpConnection 保持一致,JsExtensions 调用形如:
 * ```
 * Jsoup.connect(url)
 *     .sslContext(sslContext)
 *     .ignoreContentType(true)
 *     .followRedirects(false)
 *     .headers(headers)
 *     .method(Connection.Method.GET)
 *     .execute()
 * ```
 * 全部继续可用,无需改动调用方。
 */
class HttpConnection : Connection {

    private val requestDelegate = HttpConnectionRequest()

    override fun url(url: URL): Connection = apply { requestDelegate.url(url) }
    override fun url(url: String): Connection = apply { requestDelegate.url(url) }

    override fun userAgent(userAgent: String): Connection = apply {
        requestDelegate.header("User-Agent", userAgent)
    }

    override fun timeout(millis: Int): Connection = apply { requestDelegate.timeout(millis) }
    override fun maxBodySize(bytes: Int): Connection = apply { requestDelegate.maxBodySize(bytes) }
    override fun referrer(referrer: String): Connection = apply {
        requestDelegate.header("Referer", referrer)
    }

    override fun followRedirects(followRedirects: Boolean): Connection = apply {
        requestDelegate.followRedirects(followRedirects)
    }

    override fun method(method: Method): Connection = apply { requestDelegate.method(method) }
    override fun ignoreHttpErrors(ignoreHttpErrors: Boolean): Connection = apply {
        requestDelegate.ignoreHttpErrors(ignoreHttpErrors)
    }

    override fun ignoreContentType(ignoreContentType: Boolean): Connection = apply {
        requestDelegate.ignoreContentType(ignoreContentType)
    }

    override fun sslSocketFactory(sslSocketFactory: SSLSocketFactory): Connection = apply {
        requestDelegate.sslSocketFactory(sslSocketFactory)
    }

    override fun sslContext(sslContext: SSLContext): Connection = apply {
        requestDelegate.sslContext(sslContext)
    }

    override fun data(key: String, value: String): Connection = apply {
        requestDelegate.data(HttpKeyVal.create(key, value))
    }

    override fun data(key: String, filename: String, inputStream: InputStream): Connection =
        apply { requestDelegate.data(HttpKeyVal.create(key, filename, inputStream)) }

    override fun data(
        key: String, filename: String, inputStream: InputStream, contentType: String?
    ): Connection = apply {
        requestDelegate.data(HttpKeyVal.create(key, filename, inputStream, contentType))
    }

    override fun data(data: Collection<Connection.KeyVal>): Connection = apply {
        data.forEach { requestDelegate.data(it as HttpKeyVal) }
    }

    override fun data(data: Map<String, String>): Connection = apply {
        data.forEach { (k, v) -> requestDelegate.data(HttpKeyVal.create(k, v)) }
    }

    override fun data(vararg keyvals: String): Connection = apply {
        require(keyvals.size % 2 == 0) { "data keyvals must be pairs" }
        var i = 0
        while (i < keyvals.size) {
            requestDelegate.data(HttpKeyVal.create(keyvals[i], keyvals[i + 1]))
            i += 2
        }
    }

    override fun requestBody(body: String): Connection = apply { requestDelegate.requestBody(body) }
    override fun header(name: String, value: String): Connection = apply {
        requestDelegate.header(name, value)
    }

    override fun headers(headers: Map<String, String>): Connection = apply {
        headers.forEach { (k, v) -> requestDelegate.header(k, v) }
    }

    override fun cookie(name: String, value: String): Connection = apply {
        requestDelegate.cookie(name, value)
    }

    override fun cookies(cookies: Map<String, String>): Connection = apply {
        cookies.forEach { (k, v) -> requestDelegate.cookie(k, v) }
    }

    override fun postDataCharset(charset: String): Connection = apply {
        requestDelegate.postDataCharset = charset
    }

    override fun request(): Connection.Request = requestDelegate

    override fun response(): Connection.Response? = requestDelegate.response

    override fun execute(): Connection.Response {
        val okRequest = buildOkRequest()
        val okClient = buildOkClient()
        val okResponse = okClient.newCall(okRequest).execute()
        val resp = HttpResponse(okResponse, requestDelegate)
        requestDelegate.response = resp
        return resp
    }

    private fun buildOkClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(requestDelegate.timeout().toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(requestDelegate.timeout().toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(requestDelegate.followRedirects())
            .followSslRedirects(requestDelegate.followRedirects())
            .hostnameVerifier(AllHostHostnameVerifier)

        requestDelegate.sslSocketFactory()?.let { factory ->
            builder.sslSocketFactory(factory, UnsafeTrustManager)
        } ?: requestDelegate.sslContext()?.let { ctx ->
            builder.sslSocketFactory(ctx.socketFactory, UnsafeTrustManager)
        }

        return builder.build()
    }

    private fun buildOkRequest(): Request {
        val url = requestDelegate.url()
            ?: throw IllegalStateException("url must be set before execute()")
        val builder = Request.Builder().url(url)

        // 写入所有 headers (含 cookies 合并到 Cookie 头)
        val cookies = requestDelegate.cookies()
        if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            builder.header("Cookie", cookieHeader)
        }
        requestDelegate.multiHeaders().forEach { (name, values) ->
            // 跳过 Cookie,已单独处理
            if (name.equals("Cookie", ignoreCase = true)) return@forEach
            values.forEach { builder.addHeader(name, it) }
        }

        val method = requestDelegate.method()
        val body = requestDelegate.requestBody()
        val dataCollection = requestDelegate.data()
        val hasBody = method.hasBody

        when {
            // 显式 body 优先
            body != null -> {
                val mediaType = ("text/plain; charset=${requestDelegate.postDataCharset}")
                    .toMediaTypeOrNull()
                builder.method(method.name, body.toRequestBody(mediaType))
            }
            // 表单数据
            dataCollection.isNotEmpty() && hasBody -> {
                val formBuilder = okhttp3.FormBody.Builder()
                dataCollection.forEach { formBuilder.add(it.key(), it.value()) }
                builder.method(method.name, formBuilder.build())
            }

            hasBody -> {
                builder.method(method.name, "".toRequestBody(null))
            }

            else -> {
                builder.method(method.name, null)
            }
        }

        return builder.build()
    }

    companion object {
        @JvmStatic
        fun connect(url: String): Connection = HttpConnection().url(url)

        @JvmStatic
        fun connect(url: URL): Connection = HttpConnection().url(url)
    }
}

/** 兼容所有 host 的 HostnameVerifier,与 jsoup 默认行为对齐 */
private object AllHostHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: javax.net.ssl.SSLSession): Boolean = true
}

/**
 * 信任所有证书的 TrustManager,仅在调用方主动传入 [SSLContext] / [SSLSocketFactory] 时使用
 * (JsExtensions 传入的 SSLHelper.unsafeSslContext 即期望信任全部)
 */
private object UnsafeTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** 私有可用作 SSLContext 默认初始化的工厂 (内部使用) */
internal val UnsafeSslContext: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(UnsafeTrustManager), SecureRandom())
    }
}
