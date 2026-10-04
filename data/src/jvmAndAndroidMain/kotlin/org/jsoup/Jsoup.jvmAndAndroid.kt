@file:Suppress("unused")

package org.jsoup

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.InputStream
import io.legado.app.utils.URL
import org.jsoup.internal.HttpConnection
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import kotlin.concurrent.Volatile

/**
 * jsoup 兼容层 JVM/Android actual。
 *
 * `@JvmStatic`: JS 桥接按静态方法反射调用 (对齐原版 Java jsoup 的静态 connect), 缺了会报
 * Cannot find static method 'connect' (Kotlin object 方法默认是 INSTANCE 实例方法)。
 * 实现委托 commonMain 的内部函数 (jsoupConnect/jsoupParse/...), 与 native actual 共用单份逻辑。
 *
 * InputStream/URL 重载为 JVM 专属 jsoup 成员, 仅在本 actual 补充 (Mihon 扩展只在 Android 运行)。
 */
actual object Jsoup {

    @Volatile
    actual var clientFactory: (() -> KmpHttpClient)? = null

    @JvmStatic
    actual fun connect(url: String): Connection = jsoupConnect(url)

    @JvmStatic
    actual fun connect(url: URL): Connection = jsoupConnect(url)

    @JvmStatic
    actual fun newSession(): Connection = jsoupNewSession()

    @JvmStatic
    actual fun parse(html: String): Document = jsoupParse(html)

    @JvmStatic
    actual fun parse(html: String, baseUri: String): Document = jsoupParse(html, baseUri)

    @JvmStatic
    actual fun parse(html: String, parser: Parser): Document = jsoupParse(html, parser)

    @JvmStatic
    actual fun parse(html: String, baseUri: String, parser: Parser): Document =
        jsoupParse(html, baseUri, parser)

    @JvmStatic
    actual fun parseBodyFragment(bodyHtml: String): Document = jsoupParseBodyFragment(bodyHtml)

    @JvmStatic
    actual fun parseBodyFragment(bodyHtml: String, baseUri: String): Document =
        jsoupParseBodyFragment(bodyHtml, baseUri)

    @JvmStatic
    fun parse(inputStream: InputStream): Document =
        jsoupParseInputStream(inputStream, null, "", Parser.htmlParser().ksoupParser)

    @JvmStatic
    fun parse(inputStream: InputStream, charsetName: String?): Document =
        jsoupParseInputStream(inputStream, charsetName, "", Parser.htmlParser().ksoupParser)

    @JvmStatic
    fun parse(inputStream: InputStream, charsetName: String?, baseUri: String): Document =
        jsoupParseInputStream(inputStream, charsetName, baseUri, Parser.htmlParser().ksoupParser)

    @JvmStatic
    fun parse(
        inputStream: InputStream,
        charsetName: String?,
        baseUri: String,
        parser: Parser,
    ): Document = jsoupParseInputStream(inputStream, charsetName, baseUri, parser.ksoupParser)

    @JvmStatic
    fun parse(url: URL, timeoutMillis: Int): Document =
        HttpConnection().url(url).timeout(timeoutMillis).execute().parse()
}
