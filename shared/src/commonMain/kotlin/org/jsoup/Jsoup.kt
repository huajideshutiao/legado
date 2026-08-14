@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.URL
import org.jsoup.internal.HttpConnection
import kotlin.concurrent.Volatile

/**
 * jsoup 兼容层入口
 *
 * 保留原 jsoup `Jsoup.connect` / `Jsoup.parse` 系列静态方法签名,js 中可继续使用:
 * - `org.jsoup.Jsoup.connect(url)` 返回 [Connection]
 * - `org.jsoup.Jsoup.parse(html)` 返回 [Document] (实际由 ksoup 解析)
 *
 * 不再依赖 jsoup jar,底层 HTTP 用 OkHttp (jvm) / Kmp 抽象 (native),HTML 解析用 ksoup。
 */
object Jsoup {

    /**
     * 底层 HTTP client 工厂,由宿主 App 注入。
     *
     * jvm: 类型为 `(() -> OkHttpClient)?` (KmpHttpClient typealias),未注入时
     * [HttpConnection] 裸建客户端(模块独立可测);注入后所有请求复用宿主共享 client——
     * 继承其拦截器(CookieJar 注入/回写、限流、Cronet 等),per-request 配置由平台门面
     * [org.jsoup.internal.buildPlatformClient] 用 newBuilder 覆盖。
     * native: 无人注入时由平台门面回退 [io.legado.app.help.http.OkHttpClientProviders] 的共享客户端。
     */
    @Volatile
    var clientFactory: (() -> KmpHttpClient)? = null

    /** 创建一个 [Connection] */
    // @JvmStatic: JS 桥接按静态方法反射调用 (对齐原版 Java jsoup 的静态 connect), 缺了会报
    // Cannot find static method 'connect' (Kotlin object 方法默认是 INSTANCE 实例方法)
    @JvmStatic
    fun connect(url: String): Connection = HttpConnection().url(url)

    /** 创建一个 [Connection] */
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
