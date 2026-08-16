@file:Suppress("unused")

package org.jsoup

import com.fleeksoft.ksoup.nodes.Document
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.URL
import kotlin.concurrent.Volatile

/**
 * jsoup 兼容层 native (iOS/鸿蒙) actual: 无 JvmStatic 注解。
 * 实现委托 commonMain 的内部函数, 与 JVM actual 共用单份逻辑。
 */
actual object Jsoup {

    @Volatile
    actual var clientFactory: (() -> KmpHttpClient)? = null

    actual fun connect(url: String): Connection = jsoupConnect(url)

    actual fun connect(url: URL): Connection = jsoupConnect(url)

    actual fun newSession(): Connection = jsoupNewSession()

    actual fun parse(html: String): Document = jsoupParse(html)

    actual fun parse(html: String, baseUri: String): Document = jsoupParse(html, baseUri)

    actual fun parseBodyFragment(bodyHtml: String): Document = jsoupParseBodyFragment(bodyHtml)

    actual fun parseBodyFragment(bodyHtml: String, baseUri: String): Document =
        jsoupParseBodyFragment(bodyHtml, baseUri)
}
