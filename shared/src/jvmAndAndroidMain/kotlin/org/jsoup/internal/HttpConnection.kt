@file:Suppress("unused")

package org.jsoup.internal

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import org.jsoup.Connection
import org.jsoup.Connection.Method
import org.jsoup.HttpStatusException
import java.io.InputStream
import java.net.URL
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
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

        // 与原版 jsoup 行为一致:4xx/5xx 抛异常(除非 ignoreHttpErrors=true)
        val code = okResponse.code
        if ((code !in 200..<400) && !requestDelegate.ignoreHttpErrors()) {
            okResponse.close()
            throw HttpStatusException(
                "HTTP error fetching URL", code, okResponse.request.url.toString()
            )
        }

        val resp = HttpResponse(okResponse, requestDelegate)
        requestDelegate.response = resp
        return resp
    }

    private fun buildOkClient(): OkHttpClient {
        // 宿主注入了共享 client 则 newBuilder 派生(继承拦截器/连接池),否则裸建
        val shared = org.jsoup.Jsoup.clientFactory?.invoke()
        val builder = (shared?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(requestDelegate.timeout().toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(requestDelegate.timeout().toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(requestDelegate.followRedirects())
            .followSslRedirects(requestDelegate.followRedirects())

        if (shared != null) {
            // 共享 client 带 callTimeout(15s),会盖过上面 per-request 的 connect/read 超时,清掉
            builder.callTimeout(0, TimeUnit.MILLISECONDS)
        } else {
            builder.hostnameVerifier(AllHostHostnameVerifier)
        }

        requestDelegate.sslSocketFactory()?.let { factory ->
            builder.sslSocketFactory(factory, UnsafeTrustManager)
        } ?: requestDelegate.sslContext()?.let { ctx ->
            builder.sslSocketFactory(ctx.socketFactory, UnsafeTrustManager)
        }

        return builder.build()
    }

    private fun buildOkRequest(): Request {
        val origUrl = requestDelegate.url()
            ?: throw IllegalStateException("url must be set before execute()")
        val method = requestDelegate.method()
        val body = requestDelegate.requestBody()
        var dataCollection = requestDelegate.data()
        val supportsBody = method.hasBody

        require(supportsBody || body == null) {
            "Cannot set a request body for HTTP method $method"
        }

        // data → query string:方法无 body,或 body 已被显式 requestBody 占用
        // (同 jsoup serialiseRequestUrl,含 "GET 不接受流字段" 校验)
        var finalUrl = origUrl
        if (dataCollection.isNotEmpty() && (!supportsBody || body != null)) {
            require(dataCollection.none { it.hasInputStream() }) {
                "InputStream data not supported in URL query string."
            }
            finalUrl = serializeToUrl(origUrl, dataCollection, requestDelegate.postDataCharset)
            dataCollection = emptyList()
        }

        val builder = Request.Builder().url(finalUrl)

        // Cookie 头:cookies() 表与 header("Cookie") 并存都要发出,合并成单个头。
        // 顺序对齐原版 jsoup(UrlConnectionExecutor):先 cookies() 表,再 header 表的值。
        val cookieParts = mutableListOf<String>()
        requestDelegate.cookies().forEach { (k, v) -> cookieParts.add("$k=$v") }
        requestDelegate.headers("Cookie").forEach { if (it.isNotEmpty()) cookieParts.add(it) }
        if (cookieParts.isNotEmpty()) {
            builder.header("Cookie", cookieParts.joinToString("; "))
        }
        val userContentType = requestDelegate.header("Content-Type")
        requestDelegate.multiHeaders().forEach { (name, values) ->
            if (name.equals("Cookie", ignoreCase = true)) return@forEach
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            values.forEach { builder.addHeader(name, it) }
        }

        if (supportsBody) {
            builder.method(
                method.name,
                buildRequestBody(builder, body, dataCollection, userContentType)
            )
        } else {
            builder.method(method.name, null)
        }
        return builder.build()
    }

    /**
     * 构建请求 body,决策表同 jsoup setOutputContentType + implWritePost:
     * 1. 有流字段或用户显式 multipart → multipart/form-data
     * 2. 显式 requestBody → 原样发送,CT 缺省 form-urlencoded
     * 3. 其余(含空 data 的 POST) → form-urlencoded
     *
     * body 一律先按 [HttpConnectionRequest.postDataCharset] 编码为字节再包装,
     * 避免 OkHttp 的 String.toRequestBody 改写 charset。
     */
    private fun buildRequestBody(
        builder: Request.Builder,
        body: String?,
        data: Collection<Connection.KeyVal>,
        userContentType: String?,
    ): okhttp3.RequestBody {
        val charsetName = requestDelegate.postDataCharset
        val needsMultipart = data.any { it.hasInputStream() } ||
            userContentType?.contains("multipart/form-data", ignoreCase = true) == true

        if (needsMultipart) {
            // 用户 CT 自带 boundary 则复用,否则生成;header 与 body 必须用同一个 boundary
            val boundary = userContentType?.let { parseBoundary(it) }
                ?: UUID.randomUUID().toString()
            builder.header(
                "Content-Type",
                userContentType?.takeIf { parseBoundary(it) != null }
                    ?: "multipart/form-data; boundary=$boundary"
            )
            return buildMultipartBody(data, boundary)
        }

        val ct = userContentType
            ?: "application/x-www-form-urlencoded; charset=$charsetName"
        val content = body ?: buildFormBody(data, charsetName)
        return content.toByteArray(Charset.forName(charsetName))
            .toRequestBody(ct.toMediaTypeOrNull())
    }

    /** 将 data 序列化到 URL query string,同 jsoup serialiseRequestUrl;query 插在 fragment 之前 */
    private fun serializeToUrl(
        origUrl: URL,
        data: Collection<Connection.KeyVal>,
        charset: String
    ): URL {
        val params = buildFormBody(data, charset)
        val external = origUrl.toExternalForm()
        val hashIdx = external.indexOf('#')
        val base = if (hashIdx >= 0) external.substring(0, hashIdx) else external
        val fragment = if (hashIdx >= 0) external.substring(hashIdx) else ""
        val sep = if (origUrl.query.isNullOrEmpty()) "?" else "&"
        return URL("$base$sep$params$fragment")
    }

    /** 构建 application/x-www-form-urlencoded body 字符串 */
    private fun buildFormBody(
        data: Collection<Connection.KeyVal>,
        charset: String
    ): String {
        return data.joinToString("&") { kv ->
            "${urlEncode(kv.key(), charset)}=${urlEncode(kv.value(), charset)}"
        }
    }

    /** 构建 multipart/form-data body;boundary 必须与 Content-Type 头一致 */
    private fun buildMultipartBody(
        data: Collection<Connection.KeyVal>,
        boundary: String
    ): okhttp3.RequestBody {
        val body = okhttp3.MultipartBody.Builder(boundary)
            .setType(okhttp3.MultipartBody.FORM)

        for (kv in data) {
            val stream = kv.inputStream()
            if (stream != null) {
                val contentType = kv.contentType() ?: "application/octet-stream"
                val fileBody = object : okhttp3.RequestBody() {
                    override fun contentType() = contentType.toMediaTypeOrNull()
                    override fun contentLength() = -1L

                    // 流只能消费一次,禁止 OkHttp 在重试/重定向时二次 writeTo
                    override fun isOneShot() = true
                    override fun writeTo(sink: okio.BufferedSink) {
                        stream.source().use { sink.writeAll(it) }
                    }
                }
                // jsoup 语义:文件字段的 value() 即 filename
                body.addFormDataPart(kv.key(), kv.value(), fileBody)
            } else {
                body.addFormDataPart(kv.key(), kv.value())
            }
        }

        return body.build()
    }

    /** 从用户 Content-Type 中取已有 boundary,没有则返回 null */
    private fun parseBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=", ignoreCase = true)
        if (idx < 0) return null
        return contentType.substring(idx + "boundary=".length)
            .split(';', limit = 2)[0]
            .trim()
            .removeSurrounding("\"")
            .takeIf { it.isNotEmpty() }
    }

    private fun urlEncode(value: String, charset: String): String {
        return java.net.URLEncoder.encode(value, charset)
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
