@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.nodes.Document
import org.jsoup.internal.HttpConnection
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * jsoup 兼容层 Connection 接口
 *
 * 该接口保留 jsoup 1.22.2 中 [org.jsoup.Connection] 的对外 API 形态,供用户 js 脚本继续使用
 * `Connection.Response`、`Connection.Method` 等类型。底层实现改为 OkHttp,不再依赖 jsoup jar。
 *
 * 仅暴露 js 调用常用的方法,内部实现位于 [HttpConnection]。
 */
interface Connection {

    /** HTTP 方法枚举,与 jsoup 保持一致 */
    enum class Method(val hasBody: Boolean) {
        GET(false),
        POST(true),
        PUT(true),
        DELETE(true),
        PATCH(true),
        HEAD(false),
        OPTIONS(false),
        TRACE(false)
    }

    /** Connection 共享基础接口,涵盖 url/method/header/cookie 等通用读写 */
    interface Base<T : Base<T>> {
        fun url(): URL?
        fun url(url: URL): T
        fun url(url: String): T
        fun method(): Method
        fun header(name: String): String?
        fun headers(name: String): List<String>
        fun header(name: String, value: String): T
        fun addHeader(name: String, value: String): T
        fun hasHeader(name: String): Boolean
        fun hasHeaderWithValue(name: String, value: String): Boolean
        fun removeHeader(name: String): T
        fun headers(): Map<String, String>
        fun multiHeaders(): Map<String, List<String>>
        fun cookie(name: String): String?
        fun cookie(name: String, value: String): T
        fun hasCookie(name: String): Boolean
        fun removeCookie(name: String): T
        fun cookies(): Map<String, String>
    }

    /** 请求侧接口,沿用 jsoup Request 形状但只暴露 js 可能用到的字段 */
    interface Request : Base<Request> {
        fun method(method: Method): Request
        fun timeout(): Int
        fun timeout(millis: Int): Request
        fun maxBodySize(): Int
        fun maxBodySize(bytes: Int): Request
        fun followRedirects(): Boolean
        fun followRedirects(followRedirects: Boolean): Request
        fun ignoreHttpErrors(): Boolean
        fun ignoreHttpErrors(ignoreHttpErrors: Boolean): Request
        fun ignoreContentType(): Boolean
        fun ignoreContentType(ignoreContentType: Boolean): Request
        fun sslSocketFactory(): SSLSocketFactory?
        fun sslSocketFactory(sslSocketFactory: SSLSocketFactory?): Request
        fun sslContext(): SSLContext?
        fun sslContext(sslContext: SSLContext?): Request
        fun data(): Collection<KeyVal>
        fun data(keyval: KeyVal): Request
        fun requestBody(): String?
        fun requestBody(body: String?): Request
    }

    /** 响应侧接口,保留 jsoup Response 全部读取方法 */
    interface Response : Base<Response> {
        fun statusCode(): Int
        fun statusMessage(): String
        fun charset(): String?
        fun charset(charset: String?): Response
        fun contentType(): String?

        @Throws(IOException::class)
        fun parse(): Document
        fun body(): String
        fun bodyAsBytes(): ByteArray
        fun readFully(): Response
        fun bufferUp(): Response
        fun bodyStream(): BufferedInputStream
    }

    /** 键值对,jsoup 原版 KeyVal 接口 */
    interface KeyVal {
        fun key(): String
        fun key(key: String): KeyVal
        fun value(): String
        fun value(value: String): KeyVal
        fun inputStream(): InputStream?
        fun inputStream(inputStream: InputStream): KeyVal
        fun hasInputStream(): Boolean
        fun contentType(contentType: String): KeyVal
        fun contentType(): String?
    }

    // --- 链式配置方法 ---
    fun url(url: URL): Connection
    fun url(url: String): Connection
    fun userAgent(userAgent: String): Connection
    fun timeout(millis: Int): Connection
    fun maxBodySize(bytes: Int): Connection
    fun referrer(referrer: String): Connection
    fun followRedirects(followRedirects: Boolean): Connection
    fun method(method: Method): Connection
    fun ignoreHttpErrors(ignoreHttpErrors: Boolean): Connection
    fun ignoreContentType(ignoreContentType: Boolean): Connection
    fun sslSocketFactory(sslSocketFactory: SSLSocketFactory): Connection
    fun sslContext(sslContext: SSLContext): Connection
    fun data(key: String, value: String): Connection
    fun data(key: String, filename: String, inputStream: InputStream): Connection
    fun data(
        key: String,
        filename: String,
        inputStream: InputStream,
        contentType: String?
    ): Connection

    fun data(data: Collection<KeyVal>): Connection
    fun data(data: Map<String, String>): Connection
    fun data(vararg keyvals: String): Connection
    fun requestBody(body: String): Connection
    fun header(name: String, value: String): Connection
    fun headers(headers: Map<String, String>): Connection
    fun cookie(name: String, value: String): Connection
    fun cookies(cookies: Map<String, String>): Connection
    fun postDataCharset(charset: String): Connection

    /** 执行请求,返回 [Response] */
    @Throws(IOException::class)
    fun execute(): Response

    /** 当前请求对象 */
    fun request(): Request

    /** 当前响应(若已执行) */
    fun response(): Response?

    companion object {
        /** 创建一个新的 [Connection],底层使用 OkHttp */
        @JvmStatic
        fun connect(url: String): Connection = HttpConnection().url(url)

        /** 创建一个新的 [Connection],底层使用 OkHttp */
        @JvmStatic
        fun connect(url: URL): Connection = HttpConnection().url(url)

        /** 创建一个新会话,等价于 [HttpConnection] 实例 */
        @JvmStatic
        fun newSession(): Connection = HttpConnection()
    }
}
