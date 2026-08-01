package io.legado.app.help.http

/**
 * KmpCookie native actual (iOS/鸿蒙共用)。
 *
 * native target 无 OkHttp 变体, 用简单 data class 持有 cookie 解析结果。
 * 由 [parseResponseCookies] 纯 Kotlin 解析 Set-Cookie 头填充。
 *
 * 与 jvmAndAndroid 的 `actual typealias KmpCookie = okhttp3.Cookie` 行为等价:
 * 均暴露 name/value/persistent 三个属性供 SharedCookieJarBridge 使用。
 */
actual class KmpCookie(
    actual val name: String,
    actual val value: String,
    actual val persistent: Boolean
)

/**
 * 解析响应 Set-Cookie 头 (native actual, iOS/鸿蒙共用)。
 *
 * 纯 Kotlin 实现, 解析 RFC 6265 Set-Cookie 头子集:
 * - 首段 "name=value" 提取 name/value;
 * - "Expires=..." / "Max-Age=..." 属性判定 persistent (与会话 cookie 区分)。
 *
 * 与 jvmAndAndroid 的 `okhttp3.Cookie.parseAll` 行为等价 (用于 SharedCookieJarBridge.saveResponse
 * 的 persistent/session 分区), cookie 自身 domain/path 属性不参与 (SharedCookieJarBridge 用
 * NetworkUtils.getSubDomain(url) 统一取二级域名作存储 key, 与 app 端一致)。
 *
 * iOS/鸿蒙 HTTP 引擎 (Ktor/@ohos.net.http) 响应头经 [KmpHeaders] 统一暴露,
 * Set-Cookie 多值通过 `headers.toMultimap()["Set-Cookie"]` 取出逐条解析。
 */
actual fun parseResponseCookies(url: KmpHttpUrl, headers: KmpHeaders): List<KmpCookie> {
    val multimap = headers.toMultimap()
    val result = mutableListOf<KmpCookie>()
    for ((key, values) in multimap) {
        if (key.equals("Set-Cookie", ignoreCase = true)) {
            for (header in values) {
                parseSetCookieHeader(header)?.let { result.add(it) }
            }
        }
    }
    return result
}

// Set-Cookie 头纯 Kotlin 解析 (RFC 6265 子集)
private fun parseSetCookieHeader(header: String): KmpCookie? {
    val parts = header.split(";")
    if (parts.isEmpty()) return null
    val nvPart = parts[0].trim()
    val eqIdx = nvPart.indexOf('=')
    if (eqIdx <= 0) return null
    val name = nvPart.substring(0, eqIdx).trim()
    val value = nvPart.substring(eqIdx + 1).trim()
    if (name.isEmpty()) return null

    var persistent = false
    for (i in 1 until parts.size) {
        val attr = parts[i].trim()
        val colonIdx = attr.indexOf('=')
        val attrName = if (colonIdx > 0) attr.substring(0, colonIdx).trim() else attr
        val attrValue = if (colonIdx > 0) attr.substring(colonIdx + 1).trim() else ""
        when (attrName.lowercase()) {
            "expires" -> persistent = true
            "max-age" -> {
                val maxAge = attrValue.toLongOrNull() ?: 0L
                persistent = maxAge > 0
            }
        }
    }
    return KmpCookie(name, value, persistent)
}
