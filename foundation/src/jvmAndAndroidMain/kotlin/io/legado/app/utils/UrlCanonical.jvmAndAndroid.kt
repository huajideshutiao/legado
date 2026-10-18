package io.legado.app.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * JVM/Android: OkHttp 的宽松解析就是规范实现 ——
 * 未编码的空格/非 ASCII 自动百分号编码，非法 `%xx` 序列编码为 `%25xx`
 * (见 OkHttp HttpUrl 文档)。
 *
 * 非 http(s) scheme（`mailto:`、`data:`、`file:` 等）OkHttp 不接受，回退到
 * [canonicalizeUri]（RFC 3986 逐段合法化）。两条路径输出都是可解析的 URI。
 */
object OkHttpUrlCanonicalizer : UrlCanonicalizer {
    override fun canonicalize(url: String): String =
        url.toHttpUrlOrNull()?.toString() ?: url.canonicalizeUri()
}
