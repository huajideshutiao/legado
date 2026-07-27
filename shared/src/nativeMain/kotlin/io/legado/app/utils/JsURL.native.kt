package io.legado.app.utils

/**
 * JsURL 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/JsURL.kt expect 注释。
 * 纯 Kotlin URL 解析 (与 jvmAndAndroidMain 行为一致),
 * 不依赖 java.net.URL / java.net.URLDecoder。
 *
 * 解析逻辑:
 * - 协议: "http://" / "https://" 大小写不敏感识别
 * - 主机: 协议后到下一个 '/' / ':' / '?' 之前
 * - 端口: host 后 ':' 到下一个 '/' / '?' 之前 (可选)
 * - 路径: host[:port] 后到 '?' 之前
 * - 查询: '?' 之后
 * - 相对 URL 解析: 基于 baseUrl 拼接 (基础协议/主机/端口继承)
 */
actual class JsURL actual constructor(url: String, baseUrl: String?) {

    actual val searchParams: Map<String, String>?
    actual val host: String
    actual val origin: String
    actual val pathname: String

    init {
        val resolved = if (!baseUrl.isNullOrEmpty()) resolveRelative(baseUrl, url) else url
        val parsed = parse(resolved)
        host = parsed.host
        origin = parsed.origin
        pathname = parsed.pathname
        searchParams = parsed.query?.let { q -> parseQuery(q) }
    }

    private data class Parsed(
        val host: String,
        val origin: String,
        val pathname: String,
        val query: String?,
    )

    private fun parse(url: String): Parsed {
        // 找协议
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            // 无协议, 简化返回
            return Parsed("", "", url, null)
        }
        val scheme = url.substring(0, schemeEnd).lowercase()
        val afterScheme = url.substring(schemeEnd + 3)
        // 找 host 结束位置
        val hostEnd = indexOfAny(afterScheme, charArrayOf('/', '?', '#'))
        val authority = if (hostEnd < 0) afterScheme else afterScheme.substring(0, hostEnd)
        // 分离 host 和 port
        val colonIdx = authority.indexOf(':')
        val hostOnly = if (colonIdx < 0) authority else authority.substring(0, colonIdx)
        val portStr = if (colonIdx < 0) null else authority.substring(colonIdx + 1)
        val port = portStr?.toIntOrNull() ?: -1
        val origin = if (port > 0) "$scheme://$hostOnly:$port" else "$scheme://$hostOnly"
        // 路径和查询
        val afterAuthority = if (hostEnd < 0) "" else afterScheme.substring(hostEnd)
        val queryStart = afterAuthority.indexOf('?')
        val fragStart = afterAuthority.indexOf('#')
        val pathEnd = minOf(
            if (queryStart < 0) afterAuthority.length else queryStart,
            if (fragStart < 0) afterAuthority.length else fragStart,
        )
        val pathname = afterAuthority.substring(0, pathEnd).ifEmpty { "/" }
        val query = when {
            queryStart < 0 -> null
            fragStart in 0 until queryStart -> null
            fragStart < 0 -> afterAuthority.substring(queryStart + 1)
            else -> afterAuthority.substring(queryStart + 1, fragStart)
        }
        return Parsed(hostOnly, origin, pathname, query)
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = hashMapOf<String, String>()
        query.split("&").forEach { segment ->
            val eq = segment.indexOf('=')
            if (eq > 0) {
                val k = segment.substring(0, eq)
                val v = segment.substring(eq + 1)
                map[k] = urlDecode(v)
            }
        }
        return map
    }

    /** 相对 URL 解析: 仅处理常见情况 (绝对路径 / 协议相对 / 同主机相对路径)。 */
    private fun resolveRelative(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            return relative
        }
        val baseScheme = baseUrl.substringBefore("://", "")
        if (baseScheme.isEmpty()) return relative
        val baseAfterScheme = baseUrl.substringAfter("://", "")
        val baseHostEnd = indexOfAny(baseAfterScheme, charArrayOf('/', '?', '#'))
        val baseAuthority = if (baseHostEnd < 0) baseAfterScheme else baseAfterScheme.substring(0, baseHostEnd)
        return when {
            relative.startsWith("//") -> "$baseScheme:$relative"
            relative.startsWith("/") -> "$baseScheme://$baseAuthority$relative"
            else -> {
                // 同主机相对路径: 简化为基目录拼接
                val basePath = if (baseHostEnd < 0) "" else baseAfterScheme.substring(baseHostEnd)
                val baseDir = basePath.substringBeforeLast('/', "/")
                "$baseScheme://$baseAuthority$baseDir/$relative"
            }
        }
    }

    private fun indexOfAny(s: String, chars: CharArray): Int {
        var min = -1
        for (c in chars) {
            val idx = s.indexOf(c)
            if (idx >= 0 && (min < 0 || idx < min)) min = idx
        }
        return min
    }

    /** 纯 Kotlin URL 解码: %XX → 字节, '+' → ' ' (form 模式)。 */
    private fun urlDecode(s: String): String {
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                c == '%' && i + 2 < s.length -> {
                    val h = hexVal(s[i + 1])
                    val l = hexVal(s[i + 2])
                    if (h >= 0 && l >= 0) {
                        bytes.add(((h shl 4) or l).toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i++
                    }
                }
                else -> {
                    // UTF-8 编码当前 char (含多字节)
                    val cp = c.code
                    when {
                        cp < 0x80 -> bytes.add(cp.toByte())
                        cp < 0x800 -> {
                            bytes.add((0xC0 or (cp ushr 6)).toByte())
                            bytes.add((0x80 or (cp and 0x3F)).toByte())
                        }
                        else -> {
                            // 简化处理: BMP 范围内 3 字节
                            bytes.add((0xE0 or (cp ushr 12)).toByte())
                            bytes.add((0x80 or ((cp ushr 6) and 0x3F)).toByte())
                            bytes.add((0x80 or (cp and 0x3F)).toByte())
                        }
                    }
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
