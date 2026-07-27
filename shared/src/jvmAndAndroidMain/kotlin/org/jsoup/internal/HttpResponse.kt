@file:Suppress("unused")

package org.jsoup.internal

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import okhttp3.Response
import org.jsoup.Connection
import org.jsoup.Connection.Method
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * [Connection.Response] 的 OkHttp 实现。
 *
 * 用户 js 通过 `java.get/post/head` 拿到的就是本对象,可继续调用:
 * - [body] / [bodyAsBytes] 获取响应体
 * - [statusCode] / [statusMessage] 获取状态信息
 * - [header] / [headers] / [multiHeaders] 获取响应头
 * - [cookie] / [cookies] 获取 Set-Cookie 解析结果
 * - [url] 获取最终 URL
 * - [parse] 用 ksoup 解析为 Document
 *
 * body 首次访问会缓冲到内存,后续 [body]/[bodyAsBytes]/[parse] 共享同一份缓冲,
 * 与 jsoup 1.22 `bufferUp` 之后的语义一致。
 */
class HttpResponse(
    private val raw: Response,
    private val request: Connection.Request,
) : Connection.Response {

    /** 缓冲后的 body 字节,null 表示尚未读取 */
    private var bodyBytes: ByteArray? = null

    /** 显式覆盖的 charset,null 表示用响应头自动检测 */
    private var overrideCharset: String? = null

    /** 由 Set-Cookie 头解析得到的 cookies */
    private val responseCookies: MutableMap<String, String> by lazy { parseCookies() }

    init {
        // OkHttp 在网络层已读取 body 时,这里不做主动缓冲,延后到首次访问
    }

    override fun statusCode(): Int = raw.code

    override fun statusMessage(): String = raw.message

    override fun charset(): String? {
        overrideCharset?.let { return it }
        // OkHttp 5.x ResponseBody.charset() 私有,改用 MediaType.charset()
        raw.body.contentType()?.charset()?.name()?.let { return it }
        // 兜底:从 content-type 头解析 charset
        contentType()?.let { ct ->
            val idx = ct.indexOf("charset=", ignoreCase = true)
            if (idx >= 0) {
                return ct.substring(idx + "charset=".length)
                    .split(';', limit = 2)[0]
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    override fun charset(charset: String?): HttpResponse = apply {
        this.overrideCharset = charset
    }

    override fun contentType(): String? = raw.header("Content-Type")

    @Throws(IOException::class)
    override fun parse(): Document {
        ensureBuffered()
        return Ksoup.parse(body(), url()?.toString() ?: "")
    }

    override fun body(): String {
        ensureBuffered()
        val bytes = bodyBytes!!
        val cs = resolveCharset(bytes)
        return String(bytes, cs)
    }

    override fun bodyAsBytes(): ByteArray {
        ensureBuffered()
        return bodyBytes!!.copyOf()
    }

    override fun readFully(): HttpResponse = apply { ensureBuffered() }

    override fun bufferUp(): HttpResponse = apply { ensureBuffered() }

    override fun bodyStream(): BufferedInputStream {
        ensureBuffered()
        return BufferedInputStream(ByteArrayInputStream(bodyBytes!!))
    }

    // --- Base<Response> 接口实现 ---

    override fun url(): URL? {
        val str = raw.request.url.toString()
        return try {
            URL(str)
        } catch (_: Exception) {
            null
        }
    }

    override fun url(url: URL): HttpResponse = this // 响应侧 url 不可变

    override fun url(url: String): HttpResponse = this

    override fun method(): Method = request.method()

    override fun header(name: String): String? = raw.header(name)

    override fun headers(name: String): List<String> = raw.headers(name)

    override fun header(name: String, value: String): HttpResponse = this // 响应头不可变

    override fun addHeader(name: String, value: String): HttpResponse = this

    override fun hasHeader(name: String): Boolean = raw.header(name) != null

    override fun hasHeaderWithValue(name: String, value: String): Boolean =
        raw.headers(name).any { it.equals(value, ignoreCase = true) }

    override fun removeHeader(name: String): HttpResponse = this

    override fun headers(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        raw.headers.forEach { (name, value) ->
            if (seen.add(name)) {
                result[name] = value
            }
        }
        return result
    }

    override fun multiHeaders(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        raw.headers.forEach { (name, value) ->
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result
    }

    override fun cookie(name: String): String? = responseCookies[name]

    override fun cookie(name: String, value: String): HttpResponse = apply {
        responseCookies[name] = value
    }

    override fun hasCookie(name: String): Boolean = responseCookies.containsKey(name)

    override fun removeCookie(name: String): HttpResponse = apply {
        responseCookies.remove(name)
    }

    override fun cookies(): Map<String, String> = responseCookies.toMap()

    // --- 内部 ---

    /**
     * 读取并缓冲 body 字节。多次调用幂等。
     *
     * OkHttp 只在自己注入 Accept-Encoding 时透明解压;调用方手动设置该头时
     * 拿到的是原始压缩字节,这里按 Content-Encoding 补解压,同 jsoup。
     */
    private fun ensureBuffered() {
        if (bodyBytes != null) return
        val bytes = try {
            val rawBytes = raw.body.bytes()
            when (raw.header("Content-Encoding")?.lowercase()) {
                "gzip" -> GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
                "deflate" -> InflaterInputStream(rawBytes.inputStream(), Inflater(true))
                    .use { it.readBytes() }

                else -> rawBytes
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        bodyBytes = bytes
    }

    /**
     * 解析最终使用的 [Charset]:优先 [overrideCharset],其次响应 body 的 charset,
     * 再次从 Content-Type 解析,最后 UTF-8。
     */
    private fun resolveCharset(bytes: ByteArray): Charset {
        overrideCharset?.let { name ->
            return runCatching { Charset.forName(name) }.getOrNull()
                ?: StandardCharsets.UTF_8
        }
        // OkHttp 5.x ResponseBody.charset() 私有,改用 MediaType.charset()
        raw.body.contentType()?.charset()?.let { return it }
        // 检测 HTML meta charset
        if (bytes.size > 4) {
            val head = String(bytes, 0, minOf(bytes.size, 2048), StandardCharsets.ISO_8859_1)
            Regex("""charset=["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.getOrNull(1)
                ?.let { name ->
                    runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
                }
        }
        return StandardCharsets.UTF_8
    }

    /** 解析 Set-Cookie 头为 name->value 映射 */
    private fun parseCookies(): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val setCookies = raw.headers("Set-Cookie")
        for (headerValue in setCookies) {
            // Set-Cookie: name=value; Path=/; ...
            val pair = headerValue.substringBefore(';', "").trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    result[name] = value
                }
            }
        }
        return result
    }
}
