package io.legado.app.utils

import kotlin.concurrent.Volatile

/**
 * URL 合法化的唯一入口：外链打开与网络请求共用这一条规则。
 *
 * 具体实现由各端注册：JVM/Android 注册 OkHttp 的宽松解析（未编码的空格/非 ASCII
 * 自动百分号编码，非法 `%xx` 编码为 `%25xx`）；iOS/鸿蒙没有等价的宽松解析 API，
 * 未注册时走 [canonicalizeUri] 的 RFC 3986 逐段合法化。
 */
fun interface UrlCanonicalizer {
    fun canonicalize(url: String): String
}

object UrlCanonicalProviders {
    @Volatile
    private var impl: UrlCanonicalizer? = null

    fun register(canonicalizer: UrlCanonicalizer) {
        impl = canonicalizer
    }

    fun getOrNull(): UrlCanonicalizer? = impl
}

fun canonicalizeUrl(url: String): String =
    UrlCanonicalProviders.getOrNull()?.canonicalize(url) ?: url.canonicalizeUri()
