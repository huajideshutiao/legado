package io.legado.app.utils

/**
 * URI 语法合法化：把含裸 `%`、空格、非 ASCII、段内非法字符的 URL 改写为逐段合法的 URI，
 * 供按 RFC 3986 硬校验字符集的平台网络层（Apple NSURLComponents、@ohos.net.http）消费。
 *
 * 依据（外部约束）：
 * - RFC 3986 §3: `URI = scheme ":" ["//" authority] path ["?" query] ["#" fragment]`
 * - RFC 3986 §2.2: `pchar = unreserved / pct-encoded / sub-delims / ":" / "@"`；
 *   `path = *(pchar / "/")`；`query` 与 `fragment` = `*(pchar / "/" / "?")`
 * - RFC 3986 §3.2.2: `host = IP-literal / reg-name`，`reg-name = *(unreserved / sub-delims)`；
 *   IPv6 字面量写作 `[...]`，其内允许 `pchar`（含 `:`）
 * - RFC 3986 §3.2.3: `port = *DIGIT`
 * - RFC 3986 §2.1: 唯一的转义形态是 `%` + 两位十六进制；字面 `%` 必须写作 `%25`
 * - Apple `NSURLComponents` 的 `percentEncodedUser/Password/Host/Path/Query/Fragment`
 *   按同一套字符集校验，并要求 `%` 后恰有两位十六进制；不满足即抛 NSInvalidArgumentException
 *   （Kotlin/Native 无法捕获 ObjC 异常 → 进程终止）
 * - WHATWG URL Standard「strip and parse」：首尾的 C0 控制符与空格在解析前移除
 *
 * 幂等：已合法的输入原样返回，成套 `%XX` 不二次转义。不做默认端口移除、dot-segment 折叠、
 * 大小写归一、百分号大写化 —— 那些属 URL 语义规范化，不属语法合法性。
 */
fun String.canonicalizeUri(): String {
    val input = stripSurroundingControls()
    if (input.isEmpty()) return input

    var remaining = input
    val hashPos = remaining.indexOf('#')
    val fragment = if (hashPos >= 0) remaining.substring(hashPos + 1).also {
        remaining = remaining.substring(0, hashPos)
    } else null

    val queryPos = remaining.indexOf('?')
    val query = if (queryPos >= 0) remaining.substring(queryPos + 1).also {
        remaining = remaining.substring(0, queryPos)
    } else null

    val out = StringBuilder(input.length + 16)
    val schemeColon = schemeDelimiterIndex(remaining)
    var pos = 0
    if (schemeColon >= 0) {
        out.append(remaining, 0, schemeColon + 1)
        pos = schemeColon + 1
    }

    var path = remaining.substring(pos)
    if (remaining.startsWith("//", pos)) {
        val authorityStart = pos + 2
        val slash = remaining.indexOf('/', authorityStart)
        path = if (slash < 0) "" else remaining.substring(slash)
        out.append("//").append(canonicalizeAuthority(remaining.substring(authorityStart,
            if (slash < 0) remaining.length else slash)))
    }

    out.append(uriPathCodec.encodeKeepEscapes(path, ::toUriBytes))
    if (query != null) out.append('?').append(uriQueryCodec.encodeKeepEscapes(query, ::toUriBytes))
    if (fragment != null) out.append('#').append(uriQueryCodec.encodeKeepEscapes(fragment, ::toUriBytes))
    return out.toString()
}

/**
 * 网络请求入口的地址校验: 对齐 okhttp 5 `String.toHttpUrl()`（`Builder().parse(null, this)`）
 * 的三类拒绝与文案：
 * - 无可识别的 scheme（okhttp: `no scheme was found for <前 6 字符>...`）
 * - scheme 非 http/https（okhttp: `but was '<scheme>'`）
 * - authority 段无 host（okhttp: `Invalid URL host: "<host 段>"`，空串取不到 canonical host）
 *
 * 由上层 (StrResponse 构造) catch 后回退 http://localhost/。
 * 只用于网络层 (KmpHttpUrl / Ktor 请求构造), 不用于打开系统链接或 WebView 加载 ——
 * 那些场景允许 file:/tel: 等任意 scheme, 不能套 http(s) 白名单。
 */
fun String.requireHttpScheme(): String {
    val colon = schemeDelimiterIndex(this)
    if (colon <= 0) {
        val truncated = if (length > 6) take(6) + "..." else this
        throw IllegalArgumentException(
            "Expected URL scheme 'http' or 'https' but no scheme was found for $truncated"
        )
    }
    val scheme = substring(0, colon)
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
        throw IllegalArgumentException(
            "Expected URL scheme 'http' or 'https' but was '$scheme'"
        )
    }
    val authorityStart = colon + 1
    if (!startsWith("//", authorityStart)) {
        throw IllegalArgumentException("Invalid URL host: \"\"")
    }
    val authorityEnd = (authorityStart + 2 until length).firstOrNull {
        this[it] == '/' || this[it] == '?' || this[it] == '#'
    } ?: length
    val hostAndPort = substring(authorityStart + 2, authorityEnd).substringAfterLast('@')
    val host = if (hostAndPort.startsWith("[")) {
        hostAndPort.substring(1).substringBefore(']')
    } else {
        hostAndPort.substringBefore(':')
    }
    if (host.isEmpty()) {
        throw IllegalArgumentException("Invalid URL host: \"$hostAndPort\"")
    }
    return this
}

/**
 * `scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (RFC 3986 §3.1)。
 * 返回 scheme 与下一段之间的 `:` 下标，不匹配时返回 -1（整串按 path 处理）。
 */
private fun schemeDelimiterIndex(url: String): Int {
    if (url.isEmpty()) return -1
    if (url[0] !in 'A'..'Z' && url[0] !in 'a'..'z') return -1
    for (i in 1 until url.length) {
        when (url[i]) {
            ':' -> return i
            in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '-', '.' -> Unit
            else -> return -1
        }
    }
    return -1
}

/**
 * `authority = [userinfo "@"] host [":" port]` (RFC 3986 §3.2)。
 * userinfo 不含 `@`，取最后一个 `@` 为界；userinfo 内第一个 `:` 分隔 user 与 password。
 */
private fun canonicalizeAuthority(authority: String): String {
    val out = StringBuilder(authority.length)
    val atPos = authority.lastIndexOf('@')
    val hostSection = if (atPos < 0) authority else authority.substring(atPos + 1)
    if (atPos >= 0) {
        val userinfo = authority.substring(0, atPos)
        val colon = userinfo.indexOf(':')
        if (colon < 0) {
            out.append(uriUserinfoCodec.encodeKeepEscapes(userinfo, ::toUriBytes))
        } else {
            out.append(uriUserinfoCodec.encodeKeepEscapes(userinfo.substring(0, colon), ::toUriBytes))
                .append(':')
                .append(uriPasswordCodec.encodeKeepEscapes(userinfo.substring(colon + 1), ::toUriBytes))
        }
        out.append('@')
    }
    out.append(canonicalizeHostAndPort(hostSection))
    return out.toString()
}

/**
 * `host = IP-literal / reg-name` + 可选 `port` (RFC 3986 §3.2.2-3.2.3)。
 * `[` 开头且成对出现 `]` 时按 IP-literal 保留方括号（IPv6 地址与 `%25` zone id 依赖方括号），
 * 其余按 reg-name 逐字符校验；不成对的 `[` 属 reg-name 非法字符，转义。
 * `:` 后非纯数字时该 `:` 不可能是端口分隔符，按 reg-name 字符转义。
 */
private fun canonicalizeHostAndPort(hostAndPort: String): String {
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close >= 0) {
            val out = StringBuilder(hostAndPort.length + 6)
            out.append('[')
                .append(uriIpLiteralCodec.encodeKeepEscapes(hostAndPort.substring(1, close), ::toUriBytes))
                .append(']')
            val tail = hostAndPort.substring(close + 1)
            if (tail.isEmpty()) return out.toString()
            val portDigits = tail.substring(1)
            if (tail[0] == ':' && (portDigits.isEmpty() || portDigits.all { it in '0'..'9' })) {
                out.append(tail)
                return out.toString()
            }
            // 闭合括号后还有非法残尾: 整段按 reg-name 编码 ([ ] : 都不是 reg-name 合法字符)
            return uriHostCodec.encodeKeepEscapes(hostAndPort, ::toUriBytes)
        }
    }
    val colon = hostAndPort.lastIndexOf(':')
    if (colon >= 0) {
        val port = hostAndPort.substring(colon + 1)
        if (port.isEmpty() || port.all { it in '0'..'9' }) {
            return uriHostCodec.encodeKeepEscapes(hostAndPort.substring(0, colon), ::toUriBytes) + ":" + port
        }
    }
    return uriHostCodec.encodeKeepEscapes(hostAndPort, ::toUriBytes)
}

/** WHATWG URL Standard：解析前移除首尾 C0 控制符与空格（`<= 0x20`）。 */
private fun String.stripSurroundingControls(): String {
    var start = 0
    var end = length
    while (start < end && this[start].code <= 0x20) start++
    while (end > start && this[end - 1].code <= 0x20) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

private fun toUriBytes(s: String): ByteArray = s.encodeToByteArray()

/** `unreserved` + `sub-delims`：userinfo / reg-name（RFC 3986 §3.2.1、§3.2.2）。 */
private val uriUserinfoCodec: PercentCodec =
    PercentCodec.UNRESERVED.orNew(PercentCodec.of("!$&'()*+,;="))

/** 密码段允许 `:`（userinfo = *( unreserved / pct-encoded / sub-delims / ":" )）。 */
private val uriPasswordCodec: PercentCodec = uriUserinfoCodec.orNew(PercentCodec.of(":"))

private val uriHostCodec: PercentCodec = uriUserinfoCodec

/** `reg-name` + `:`：IP-literal 内层（RFC 3986 §3.2.2 IPv6address / IPvFuture）。 */
private val uriIpLiteralCodec: PercentCodec = uriHostCodec.orNew(PercentCodec.of(":"))

/** `pchar / "/"`（RFC 3986 §3.3）。 */
private val uriPathCodec: PercentCodec = uriUserinfoCodec.orNew(PercentCodec.of(":/@"))

/** `pchar / "/" / "?"`（RFC 3986 §3.4、§3.5）。 */
private val uriQueryCodec: PercentCodec = uriPathCodec.orNew(PercentCodec.of("?"))
