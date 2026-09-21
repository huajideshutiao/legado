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
 * - 相对 URL 解析: 基于 baseUrl 拼接, 逐条对齐 java.net.URL(context, spec)
 *   (含 ./ .. 点段归一化、query-only/fragment-only/空串语义; 无路径时 pathname 为空串)
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
        // 与 java.net.URL.getPath() 一致: 无路径时为空串 (而非 "/")
        val pathname = afterAuthority.substring(0, pathEnd)
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

    /**
     * 相对 URL 解析, 语义逐条对齐 java.net.URL(context, spec) (JDK URLStreamHandler.parseURL):
     * - 绝对 (http/https) / 协议相对 (//host) / 根相对 (/path, 不做点段归一化)
     * - 查询相对 (?q) / 片段相对 (#f) / 空串 (沿用基 URL)
     * - 普通相对路径: 基目录拼接 + 点段归一化 (仅相对路径做, 规则与 JDK 完全一致:
     *   "./" 删除、"/../" 回退上一段但保留越界的 ".."、尾部 "/.." "/." 处理)
     */
    private fun resolveRelative(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://", true) || relative.startsWith("https://", true)) {
            return relative
        }
        val baseScheme = baseUrl.substringBefore("://", "")
        if (baseScheme.isEmpty()) return relative
        val baseAfterScheme = baseUrl.substringAfter("://", "")
        val baseHostEnd = indexOfAny(baseAfterScheme, charArrayOf('/', '?', '#'))
        val baseAuthority = if (baseHostEnd < 0) baseAfterScheme else baseAfterScheme.substring(0, baseHostEnd)
        // 基 URL 的路径部分 (不含 query/fragment)
        val baseFile = if (baseHostEnd < 0) "" else baseAfterScheme.substring(baseHostEnd)
        val basePath = baseFile.substringBefore('?').substringBefore('#')
        return when {
            relative.startsWith("//") -> "$baseScheme:$relative"
            relative.startsWith("/") -> "$baseScheme://$baseAuthority$relative"
            relative.startsWith("?") -> {
                // JDK queryOnly: 基路径取到最后一个 '/' 并保留尾部 '/'
                val ind = basePath.lastIndexOf('/')
                val dir = if (ind < 0) "/" else basePath.substring(0, ind) + "/"
                "$baseScheme://$baseAuthority$dir$relative"
            }

            relative.startsWith("#") -> "$baseScheme://$baseAuthority$basePath$relative"
            relative.isEmpty() -> baseUrl
            else -> {
                // 普通相对路径: 基目录 + spec 路径 (spec 的 query/fragment 拆出保留), 再做点段归一化
                val pathSpec = relative.substringBefore('?').substringBefore('#')
                val suffix = relative.substring(pathSpec.length)
                val ind = basePath.lastIndexOf('/')
                // JDK: 仅基路径非空时 (isRelPath) 做点段归一化; 基路径为空时原样拼接不归一化
                val baseDir = if (ind >= 0) basePath.substring(
                    0,
                    ind + 1
                ) else if (baseAuthority.isNotEmpty()) "/" else ""
                val joined = "$baseDir$pathSpec"
                val path = if (basePath.isNotEmpty()) normalizeRelativePath(joined) else joined
                "$baseScheme://$baseAuthority$path$suffix"
            }
        }
    }

    /**
     * 相对路径点段归一化, 逐条镜像 JDK URLStreamHandler.parseURL 的 isRelPath 分支:
     * 1. 删除所有 "/./"
     * 2. 删除可回退的 "/../" (前一段自身是 ".." 时保留, 越界的 ".." 不删除)
     * 3. 尾部 "/.." 回退 (无法回退时保留)
     * 4. 开头 "./" / 尾部 "/." 删除
     */
    private fun normalizeRelativePath(path: String): String {
        var p = path
        // 1. Remove embedded /./
        var i = p.indexOf("/./")
        while (i >= 0) {
            p = p.substring(0, i) + p.substring(i + 2)
            i = p.indexOf("/./")
        }
        // 2. Remove embedded /../ if possible
        i = 0
        while (i >= 0) {
            i = p.indexOf("/../", i)
            if (i < 0) break
            val prev = if (i > 0) p.lastIndexOf('/', i - 1) else -1
            if (prev >= 0 && p.indexOf("/../", prev) != 0) {
                p = p.substring(0, prev) + p.substring(i + 3)
                i = 0
            } else {
                i += 3
            }
        }
        // 3. Remove trailing /.. if possible
        while (p.endsWith("/..")) {
            val j = p.indexOf("/..")
            val prev = p.lastIndexOf('/', j - 1)
            if (prev >= 0) {
                p = p.substring(0, prev + 1)
            } else {
                break
            }
        }
        // 4. Remove starting ./
        if (p.startsWith("./") && p.length > 2) {
            p = p.substring(2)
        }
        // 5. Remove trailing /.
        if (p.endsWith("/.")) {
            p = p.substring(0, p.length - 1)
        }
        return p
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
