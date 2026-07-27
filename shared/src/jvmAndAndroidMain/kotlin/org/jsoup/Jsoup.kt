@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import okhttp3.OkHttpClient
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

    /**
     * 底层 OkHttpClient 工厂,由宿主 App 注入。
     *
     * 未注入时 [HttpConnection] 裸建客户端(模块独立可测);注入后所有请求复用宿主
     * 共享 client——继承其拦截器(CookieJar 注入/回写、限流、Cronet 等),per-request
     * 配置(超时/重定向/SSL)由 HttpConnection 用 newBuilder 覆盖。
     */
    @JvmStatic
    @Volatile
    var clientFactory: (() -> OkHttpClient)? = null

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
