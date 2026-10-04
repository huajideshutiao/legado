// Copyright The Keiyoushi Contributors. Apache-2.0.
// 扩展 dex 按 org.jsoup FQCN 解析, Document/Element 直取 data 模块门面类 (org.jsoup.nodes)
package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup

typealias Document = org.jsoup.nodes.Document

typealias Element = org.jsoup.nodes.Element

fun Element.selectText(css: String, defaultValue: String? = null): String? {
    return select(css).first()?.text() ?: defaultValue
}

fun Element.selectInt(css: String, defaultValue: Int = 0): Int {
    return select(css).first()?.text()?.toInt() ?: defaultValue
}

fun Element.attrOrText(css: String): String {
    return if (css != "text") attr(css) else text()
}

fun Response.asJsoup(html: String? = null): Document {
    return Jsoup.parse(html ?: body.string(), request.url.toString())
}
