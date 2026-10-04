@file:Suppress("unused")

package org.jsoup

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.utils.URL
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import kotlin.concurrent.Volatile

/**
 * jsoup 兼容层 native (iOS/鸿蒙) actual: 无 JvmStatic 注解。
 * 实现委托 commonMain 的内部函数, 与 JVM actual 共用单份逻辑。
 * InputStream/URL 重载是 JVM 专属 jsoup 成员, 仅存在于 jvmAndAndroid actual。
 */
actual object Jsoup {

    @Volatile
    actual var clientFactory: (() -> KmpHttpClient)? = null

    actual fun connect(url: String): Connection = jsoupConnect(url)

    actual fun connect(url: URL): Connection = jsoupConnect(url)

    actual fun newSession(): Connection = jsoupNewSession()

    actual fun parse(html: String): Document = jsoupParse(html)

    actual fun parse(html: String, baseUri: String): Document = jsoupParse(html, baseUri)

    actual fun parse(html: String, parser: Parser): Document = jsoupParse(html, parser)

    actual fun parse(html: String, baseUri: String, parser: Parser): Document =
        jsoupParse(html, baseUri, parser)

    actual fun parseBodyFragment(bodyHtml: String): Document = jsoupParseBodyFragment(bodyHtml)

    actual fun parseBodyFragment(bodyHtml: String, baseUri: String): Document =
        jsoupParseBodyFragment(bodyHtml, baseUri)
}
