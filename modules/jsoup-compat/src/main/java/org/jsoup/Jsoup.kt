@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import org.jsoup.internal.HttpConnection
import java.net.URL

/**
 * jsoup 兼容层入口
 *
 * 保留原 jsoup `Jsoup.connect` / `Jsoup.parse` 系列静态方法签名,js 中可继续使用:
 * - `org.jsoup.Jsoup.connect(url)` 返回 [Connection]
 * - `org.jsoup.Jsoup.parse(html)` 返回 [Document] (实际由 ksoup 解析)
 *
 * 不再依赖 jsoup jar,底层 HTTP 用 OkHttp,HTML 解析用 ksoup。
 */
object Jsoup {

    /** 创建一个 [Connection],使用 OkHttp 底层 */
    @JvmStatic
    fun connect(url: String): Connection = HttpConnection().url(url)

    /** 创建一个 [Connection],使用 OkHttp 底层 */
    @JvmStatic
    fun connect(url: URL): Connection = HttpConnection().url(url)

    /** 创建一个新的会话 [Connection] */
    @JvmStatic
    fun newSession(): Connection = HttpConnection()

    @JvmStatic
    fun parse(html: String): Document = Ksoup.parse(html)

    @JvmStatic
    fun parse(html: String, baseUri: String): Document = Ksoup.parse(html, baseUri)

    @JvmStatic
    fun parseBodyFragment(bodyHtml: String): Document = Ksoup.parseBodyFragment(bodyHtml)

    @JvmStatic
    fun parseBodyFragment(bodyHtml: String, baseUri: String): Document =
        Ksoup.parseBodyFragment(bodyHtml, baseUri)
}
