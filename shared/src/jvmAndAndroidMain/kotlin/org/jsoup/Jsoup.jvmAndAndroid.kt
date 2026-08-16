@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.nodes.Document
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.URL
import kotlin.concurrent.Volatile

/**
 * jsoup 兼容层 JVM/Android actual。
 *
 * `@JvmStatic`: JS 桥接按静态方法反射调用 (对齐原版 Java jsoup 的静态 connect), 缺了会报
 * Cannot find static method 'connect' (Kotlin object 方法默认是 INSTANCE 实例方法)。
 * 实现委托 commonMain 的内部函数 (jsoupConnect/jsoupParse/...), 与 native actual 共用单份逻辑。
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
    actual fun parseBodyFragment(bodyHtml: String): Document = jsoupParseBodyFragment(bodyHtml)

    @JvmStatic
    actual fun parseBodyFragment(bodyHtml: String, baseUri: String): Document =
        jsoupParseBodyFragment(bodyHtml, baseUri)
}
