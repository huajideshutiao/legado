@file:Suppress("unused")

package org.jsoup.internal

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.legado.app.help.http.KmpResponse
import io.legado.app.help.http.charsetName
import io.legado.app.help.http.header
import io.legado.app.utils.InputStream
import io.legado.app.utils.URL
import io.legado.app.utils.textCharsetCodec
import io.legado.app.utils.toInputStream
import okio.IOException
import org.jsoup.Connection
import org.jsoup.Connection.Method

/**
 * [Connection.Response] 的实现。
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
 *
 * 平台差异: gzip/deflate 解压与 charset 解码经 [HttpPlatform]/[charsetName] 门面
 * (jvm 与原实现逐行等价;native 的 ios 端 Ktor 层已透明解压,ohos 端手动解压)。
 */
class HttpResponse(
    private val raw: KmpResponse,
    private val request: Connection.Request,
) : Connection.Response {

    /** 缓冲后的 body 字节,null 表示尚未读取 */
    private var bodyBytes: ByteArray? = null

    /** 显式覆盖的 charset,null 表示用响应头自动检测 */
    private var overrideCharset: String? = null

    /** 由 Set-Cookie 头解析得到的 cookies */
    private val responseCookies: MutableMap<String, String> by lazy { parseCookies() }

    override fun statusCode(): Int = raw.code

    override fun statusMessage(): String = raw.message

    override fun charset(): String? {
        overrideCharset?.let { return it }
        // 平台门面: jvm 走 MediaType.charset().name(), native 走字符串解析
        raw.body.contentType()?.charsetName()?.let { return it }
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

    override fun parse(): Document {
        ensureBuffered()
        return Ksoup.parse(body(), url()?.toString() ?: "")
    }

    override fun body(): String {
        ensureBuffered()
        return decodeBody(bodyBytes!!)
    }

    override fun bodyAsBytes(): ByteArray {
        ensureBuffered()
        return bodyBytes!!.copyOf()
    }

    override fun readFully(): HttpResponse = apply { ensureBuffered() }

    override fun bufferUp(): HttpResponse = apply { ensureBuffered() }

    override fun bodyStream(): InputStream {
        ensureBuffered()
        return bodyBytes!!.toInputStream()
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

    override fun header(name: String, value: String?): HttpResponse = this // 响应头不可变

    override fun addHeader(name: String, value: String?): HttpResponse = this

    override fun hasHeader(name: String): Boolean = raw.header(name) != null

    override fun hasHeaderWithValue(name: String, value: String): Boolean =
        raw.headers(name).any { it.equals(value, ignoreCase = true) }

    override fun removeHeader(name: String): HttpResponse = this

    override fun headers(): Map<String, String> {
        // toMultimap 等价 okhttp Headers 逐行迭代: 名取首值, 保持插入序
        val result = LinkedHashMap<String, String>()
        raw.headers().toMultimap().forEach { (name, values) ->
            result[name] = values.firstOrNull() ?: ""
        }
        return result
    }

    override fun multiHeaders(): Map<String, List<String>> {
        return raw.headers().toMultimap()
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
     * OkHttp (jvm) 只在自己注入 Accept-Encoding 时透明解压;调用方手动设置该头时
     * 拿到的是原始压缩字节,这里按 Content-Encoding 补解压,同 jsoup。
     * (native: ios 端 KmpResponse 构造时已透明解压且头未剥离,ohos 端 @ohos.net.http
     * 不透明解压 — 两端差异由 [decompressBody] 门面分端处理)
     */
    private fun ensureBuffered() {
        if (bodyBytes != null) return
        val bytes = try {
            val rawBytes = raw.body.bytes()
            decompressBody(rawBytes, raw.header("Content-Encoding")?.lowercase())
        } catch (e: IOException) {
            // jvm: 包装为 UncheckedIOException (与原实现一致); native: 原样抛出
            throw uncheckedIoException(e)
        }
        bodyBytes = bytes
    }

    /**
     * 解码 body:优先 [overrideCharset],其次响应 body 的 charset,再次从 HTML meta 解析,
     * 最后 UTF-8 (语义与原 JVM 实现一致,经 [textCharsetCodec] 门面,未知 charset 回退 UTF-8)。
     */
    private fun decodeBody(bytes: ByteArray): String {
        overrideCharset?.let { name ->
            return decodeOrNull(bytes, name) ?: bytes.decodeToString()
        }
        raw.body.contentType()?.charsetName()?.let { name ->
            return decodeOrNull(bytes, name) ?: bytes.decodeToString()
        }
        // 检测 HTML meta charset
        if (bytes.size > 4) {
            val head = decodeLatin1(bytes, 0, minOf(bytes.size, 2048))
            Regex("""charset=["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.getOrNull(1)?.let { name ->
                    decodeOrNull(bytes, name)?.let { return it }
                }
        }
        return bytes.decodeToString()
    }

    /** 按 charset 名解码;非法/平台不支持的名字返回 null (调用方回退 UTF-8) */
    private fun decodeOrNull(bytes: ByteArray, name: String): String? =
        runCatching { textCharsetCodec(name).decode(bytes) }.getOrNull()

    /** ISO-8859-1 解码 (HTML 头扫描用,与 jvm String(bytes, off, len, ISO_8859_1) 字节级一致) */
    private fun decodeLatin1(bytes: ByteArray, offset: Int, length: Int): String =
        textCharsetCodec("ISO-8859-1").decode(bytes, offset, length)

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
