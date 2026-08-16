@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.URL
import org.jsoup.internal.HttpConnection

/**
 * jsoup 兼容层入口
 *
 * 保留原 jsoup `Jsoup.connect` / `Jsoup.parse` 系列静态方法签名,js 中可继续使用:
 * - `org.jsoup.Jsoup.connect(url)` 返回 [Connection]
 * - `org.jsoup.Jsoup.parse(html)` 返回 [Document] (实际由 ksoup 解析)
 *
 * 不再依赖 jsoup jar,底层 HTTP 用 OkHttp (jvm) / Kmp 抽象 (native),HTML 解析用 ksoup。
 *
 * expect/actual: JVM/Android 的 actual 带 `@JvmStatic` (JS 桥按静态方法反射调用,
 * 对齐原版 Java jsoup 的静态 connect); native 无 JvmStatic, 故对象本体下沉 commonMain,
 * 各端 actual 委托本文件内部实现函数 (jsoupConnect/jsoupParse/...), 实现单份不漂移。
 */
expect object Jsoup {

    /**
     * 底层 HTTP client 工厂,由宿主 App 注入。
     *
     * jvm: 类型为 `(() -> OkHttpClient)?` (KmpHttpClient typealias),未注入时
     * [HttpConnection] 裸建客户端(模块独立可测);注入后所有请求复用宿主共享 client——
     * 继承其拦截器(CookieJar 注入/回写、限流、Cronet 等),per-request 配置由平台门面
     * [org.jsoup.internal.buildPlatformClient] 用 newBuilder 覆盖。
     * native: 无人注入时由平台门面回退 [io.legado.app.help.http.OkHttpClientProviders] 的共享客户端。
     * (expect 属性无 backing field, @Volatile 由各端 actual 加)
     */
    var clientFactory: (() -> KmpHttpClient)?

    /** 创建一个 [Connection] */
    fun connect(url: String): Connection

    /** 创建一个 [Connection] */
    fun connect(url: URL): Connection

    /** 创建一个新的会话 [Connection] */
    fun newSession(): Connection

    fun parse(html: String): Document

    fun parse(html: String, baseUri: String): Document

    fun parseBodyFragment(bodyHtml: String): Document

    fun parseBodyFragment(bodyHtml: String, baseUri: String): Document
}

// ---- 共享实现 (各端 actual object 委托; JVM actual 附 @JvmStatic 供 JS 静态反射) ----

internal fun jsoupConnect(url: String): Connection = HttpConnection().url(url)

internal fun jsoupConnect(url: URL): Connection = HttpConnection().url(url)

internal fun jsoupNewSession(): Connection = HttpConnection()

internal fun jsoupParse(html: String): Document = Ksoup.parse(html)

internal fun jsoupParse(html: String, baseUri: String): Document = Ksoup.parse(html, baseUri)

internal fun jsoupParseBodyFragment(bodyHtml: String): Document = Ksoup.parseBodyFragment(bodyHtml)

internal fun jsoupParseBodyFragment(bodyHtml: String, baseUri: String): Document =
    Ksoup.parseBodyFragment(bodyHtml, baseUri)
