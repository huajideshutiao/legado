package org.jsoup.internal

import org.jsoup.Connection
import org.jsoup.Connection.Method
import java.net.MalformedURLException
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * [Connection.Request] 的可变实现,被 [HttpConnection] 持有以累积请求配置。
 *
 * 内部维护多值 header 表、cookie 表、SSL 配置、超时等。所有 setter 返回 this 以支持链式调用。
 */
class HttpConnectionRequest : Connection.Request {

    private var url: URL? = null
    private var method: Method = Method.GET

    /** 多值 header 表,保持插入顺序 */
    private val headers: MutableMap<String, MutableList<String>> = LinkedHashMap()

    /** cookie 表 */
    private val cookies: MutableMap<String, String> = LinkedHashMap()

    /** 表单字段 */
    private val data: MutableList<HttpKeyVal> = mutableListOf()

    private var timeout = 30_000
    private var maxBodySize = 1024 * 1024 * 2
    private var followRedirects = true
    private var ignoreHttpErrors = false
    private var ignoreContentType = false
    private var sslSocketFactory: SSLSocketFactory? = null
    private var sslContext: SSLContext? = null
    private var requestBody: String? = null

    /** 表单编码,默认 UTF-8 */
    var postDataCharset: String = "UTF-8"

    init {
        // 与原版 jsoup 对齐的默认 UA。
        // 不加 jsoup 的 "Accept-Encoding: gzip":OkHttp 只在调用方未显式设置该头时
        // 才透明压缩并自动解压,手动设置反而拿到未解压的原始字节。
        addHeader("User-Agent", DEFAULT_USER_AGENT)
    }

    companion object {
        /** 同 jsoup HttpConnection.DEFAULT_UA */
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    }

    /** 由 [HttpConnection.execute] 写回 */
    var response: Connection.Response? = null

    override fun url(): URL? = url

    override fun url(url: URL): HttpConnectionRequest = apply { this.url = url }

    override fun url(url: String): HttpConnectionRequest = apply {
        this.url = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("Invalid URL: $url", e)
        }
    }

    override fun method(): Method = method
    override fun method(method: Method): HttpConnectionRequest = apply { this.method = method }

    /** 大小写不敏感地找到已存在的 header 名,与 jsoup 一致避免 "accept-encoding"/"Accept-Encoding" 并存 */
    private fun findHeaderName(name: String): String? =
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }

    override fun header(name: String): String? {
        return findHeaderName(name)?.let { headers[it]?.firstOrNull() }
    }

    override fun headers(name: String): List<String> {
        return findHeaderName(name)?.let { headers[it]?.toList() } ?: emptyList()
    }

    override fun header(name: String, value: String): HttpConnectionRequest = apply {
        removeHeader(name)
        headers[name] = mutableListOf(value)
    }

    override fun addHeader(name: String, value: String): HttpConnectionRequest = apply {
        val key = findHeaderName(name) ?: name
        headers.getOrPut(key) { mutableListOf() }.add(value)
    }

    override fun hasHeader(name: String): Boolean =
        findHeaderName(name) != null

    override fun hasHeaderWithValue(name: String, value: String): Boolean =
        headers(name).any { it.equals(value, ignoreCase = true) }

    override fun removeHeader(name: String): HttpConnectionRequest = apply {
        findHeaderName(name)?.let { headers.remove(it) }
    }

    override fun headers(): Map<String, String> {
        return headers.mapValues { it.value.firstOrNull() ?: "" }
    }

    override fun multiHeaders(): Map<String, List<String>> {
        return headers.mapValues { it.value.toList() }
    }

    override fun cookie(name: String): String? = cookies[name]

    override fun cookie(name: String, value: String): HttpConnectionRequest = apply {
        cookies[name] = value
    }

    override fun hasCookie(name: String): Boolean = cookies.containsKey(name)

    override fun removeCookie(name: String): HttpConnectionRequest = apply {
        cookies.remove(name)
    }

    override fun cookies(): Map<String, String> = cookies.toMap()

    override fun timeout(): Int = timeout
    override fun timeout(millis: Int): HttpConnectionRequest = apply { this.timeout = millis }

    override fun maxBodySize(): Int = maxBodySize
    override fun maxBodySize(bytes: Int): HttpConnectionRequest = apply { this.maxBodySize = bytes }

    override fun followRedirects(): Boolean = followRedirects
    override fun followRedirects(followRedirects: Boolean): HttpConnectionRequest = apply {
        this.followRedirects = followRedirects
    }

    override fun ignoreHttpErrors(): Boolean = ignoreHttpErrors
    override fun ignoreHttpErrors(ignoreHttpErrors: Boolean): HttpConnectionRequest = apply {
        this.ignoreHttpErrors = ignoreHttpErrors
    }

    override fun ignoreContentType(): Boolean = ignoreContentType
    override fun ignoreContentType(ignoreContentType: Boolean): HttpConnectionRequest = apply {
        this.ignoreContentType = ignoreContentType
    }

    override fun sslSocketFactory(): SSLSocketFactory? = sslSocketFactory
    override fun sslSocketFactory(sslSocketFactory: SSLSocketFactory?): HttpConnectionRequest =
        apply {
            this.sslSocketFactory = sslSocketFactory
        }

    override fun sslContext(): SSLContext? = sslContext
    override fun sslContext(sslContext: SSLContext?): HttpConnectionRequest = apply {
        this.sslContext = sslContext
    }

    override fun data(): Collection<Connection.KeyVal> = data.toList()

    override fun data(keyval: Connection.KeyVal): HttpConnectionRequest = apply {
        require(keyval is HttpKeyVal) { "keyval must be HttpKeyVal" }
        data.add(keyval)
    }

    /** 直接添加 [HttpKeyVal],供 [HttpConnection] 内部使用 */
    fun data(keyval: HttpKeyVal) {
        data.add(keyval)
    }

    override fun requestBody(): String? = requestBody
    override fun requestBody(body: String?): HttpConnectionRequest = apply {
        this.requestBody = body
    }
}
