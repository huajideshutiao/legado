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
 * 纯 Kotlin 实现, 对照 OkHttp 5.3.2 `Cookie.parseAll` 语义:
 * - name/value 校验: 空名或含控制/非 ASCII 字符 → 丢弃整个 cookie;
 * - Domain 属性: 规范化域名 (小写/去前导点/字符与 label 长度校验) 后与响应主机做
 *   domain match (相等或 "." 边界后缀; IP 主机不做后缀匹配), 不匹配 → 丢弃整个 cookie
 *   (OkHttp 同款 "No domain match? This is either incompetence or malice!");
 *   非法 Domain 属性 → 忽略该属性, 视为 host-only cookie (OkHttp 同款);
 * - Expires/Max-Age: RFC 6265 5.1.1 日期解析 (纯 Kotlin), **解析成功才 persistent=true**;
 *   Max-Age 优先于 Expires (RFC 6265 5.3); Max-Age 为 0/负数时 persistent 仍为 true
 *   (OkHttp 同款: persistent 仅表示「存在合法的 Expires/Max-Age 属性」);
 * - 差异说明: OkHttp 还会用 PublicSuffixDatabase 拒绝 public suffix 域名的 cookie
 *   (如 Domain=com), 纯 Kotlin 无 PSL 数据库, 该项跳过; IDN 域不做 punycode 转换,
 *   非 ASCII 域名视为非法属性。
 *
 * iOS/鸿蒙 HTTP 引擎 (Ktor/@ohos.net.http) 响应头经 [KmpHeaders] 统一暴露,
 * Set-Cookie 多值通过 `headers.toMultimap()["Set-Cookie"]` 取出逐条解析。
 */
actual fun parseResponseCookies(url: KmpHttpUrl, headers: KmpHeaders): List<KmpCookie> {
    val urlHost = url.hostName() ?: return emptyList()
    val multimap = headers.toMultimap()
    val result = mutableListOf<KmpCookie>()
    for ((key, values) in multimap) {
        if (key.equals("Set-Cookie", ignoreCase = true)) {
            for (header in values) {
                parseSetCookieHeader(urlHost, header)?.let { result.add(it) }
            }
        }
    }
    return result
}

// ===== Set-Cookie 头解析 (对照 OkHttp 5.3.2 Cookie.parse) =====

private fun parseSetCookieHeader(urlHost: String, header: String): KmpCookie? {
    // 首段 "name=value" (到第一个 ';' 为止)
    val cookiePairEnd = delimiterOffset(header, ';', 0, header.length)
    val pairEqualsSign = delimiterOffset(header, '=', 0, cookiePairEnd)
    if (pairEqualsSign == cookiePairEnd) return null

    val name = header.substring(0, pairEqualsSign).trim()
    if (name.isEmpty() || name.hasControlOrNonAscii()) return null
    val value = header.substring(pairEqualsSign + 1, cookiePairEnd).trim()
    if (value.hasControlOrNonAscii()) return null

    var domain: String? = null
    var persistent = false
    var pos = cookiePairEnd + 1
    while (pos < header.length) {
        val attributePairEnd = delimiterOffset(header, ';', pos, header.length)
        val attributeEqualsSign = delimiterOffset(header, '=', pos, attributePairEnd)
        val attributeName = header.substring(pos, attributeEqualsSign).trim()
        val attributeValue =
            if (attributeEqualsSign < attributePairEnd) {
                header.substring(attributeEqualsSign + 1, attributePairEnd).trim()
            } else {
                ""
            }

        when {
            attributeName.equals("expires", ignoreCase = true) -> {
                // 日期解析失败 → 忽略该属性 (OkHttp 同款), persistent 保持原值
                if (parseRfc6265Date(attributeValue) != null) {
                    persistent = true
                }
            }

            attributeName.equals("max-age", ignoreCase = true) -> {
                // 解析失败 → 忽略该属性 (OkHttp 同款); 0/负数也置 persistent (OkHttp 同款)
                if (parseMaxAge(attributeValue) != null) {
                    persistent = true
                }
            }

            attributeName.equals("domain", ignoreCase = true) -> {
                // 非法域名 → 忽略该属性, 视为 host-only cookie (OkHttp 同款)
                parseDomain(attributeValue)?.let { domain = it }
            }
        }

        pos = attributePairEnd + 1
    }

    // Max-Age 存在时优先于 Expires (RFC 6265 5.3)。OkHttp 会据此计算 expiresAt,
    // 但 KmpCookie 只暴露 persistent (SharedCookieJarBridge 分区用), 实际时间戳不消费,
    // 故只保留 persistent 判定 (解析成功即 true, 与 okhttp3.Cookie.persistent 语义一致)。

    // Domain 属性存在时要求与响应主机 domain match (OkHttp 同款, 不匹配丢弃 cookie)
    if (domain != null && !domainMatch(urlHost, domain)) {
        return null
    }

    // OkHttp 还拒绝 public suffix 域名 (PublicSuffixDatabase, 如 Domain=com);
    // 纯 Kotlin 无 PSL 数据库, 此处跳过 (见函数头差异说明)。

    return KmpCookie(name, value, persistent)
}

/**
 * 在 [start, end) 内查找字符 [c], 未找到返回 [end] (OkHttp delimiterOffset 等价)。
 */
private fun delimiterOffset(s: String, c: Char, start: Int, end: Int): Int {
    for (i in start until end) {
        if (s[i] == c) return i
    }
    return end
}

/** 控制字符或非 ASCII 字符 (OkHttp indexOfControlOrNonAscii 等价) */
private fun String.hasControlOrNonAscii(): Boolean =
    any { it.code < 0x20 || it.code == 0x7f || it.code > 0x7f }

/**
 * Max-Age 解析 (OkHttp parseMaxAge 等价):
 * - 正数 → 原值; 0/负数 → [Long.MIN_VALUE] (表示会话期);
 * - 超出 Long 范围但仍是整数字符串 → 正数 [Long.MAX_VALUE] / 负数 [Long.MIN_VALUE];
 * - 非整数 → null (属性忽略)。
 */
private fun parseMaxAge(s: String): Long? {
    s.toLongOrNull()?.let { return if (it <= 0L) Long.MIN_VALUE else it }
    if (Regex("-?\\d+").matches(s)) {
        return if (s.startsWith("-")) Long.MIN_VALUE else Long.MAX_VALUE
    }
    return null
}

/**
 * 域名规范化 (OkHttp parseDomain + toCanonicalHost 的纯 Kotlin 简化版):
 * 去单个前导 '.' / 转小写 / 字符与 label 长度校验; 非法返回 null (属性忽略)。
 * 注: 无 IDN 支持, 非 ASCII 域名视为非法 (与 OkHttp 的 punycode 转换不同)。
 */
private fun parseDomain(s: String): String? {
    if (s.endsWith(".")) return null
    val d = s.removePrefix(".").lowercase()
    if (d.isEmpty()) return null
    // 仅允许 [a-z0-9-.] (OkHttp toCanonicalHost 经 IDNA 映射后 ASCII 域名的有效字符集;
    // 下划线等其余 ASCII 字符 IDNA 映射失败 → 非法域名, 属性忽略)
    if (d.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' }) return null
    // OkHttp containsInvalidLabelLengths 等价: 总长 1..253, 每 label 1..63
    if (d.length !in 1..253) return null
    if (d.split('.').any { it.isEmpty() || it.length > 63 }) return null
    return d
}

/**
 * domain match (OkHttp Cookie.domainMatch 等价):
 * 主机与域名相等, 或主机以 ".domain" 结尾 (边界 '.'), 且主机不是 IP 地址。
 */
private fun domainMatch(urlHost: String, domain: String): Boolean {
    if (urlHost == domain) return true
    if (urlHost.isIpAddress()) return false
    if (!urlHost.endsWith(domain)) return false
    val dotIndex = urlHost.length - domain.length - 1
    return dotIndex >= 0 && urlHost[dotIndex] == '.'
}

private fun String.isIpAddress(): Boolean {
    if (contains(':')) return true
    val parts = split('.')
    return parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }
}

// ===== RFC 6265 5.1.1 日期解析 (对照 OkHttp Cookie.parseExpires, 纯 Kotlin) =====

private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{1,2}):(\d{1,2})""")
private val DAY_OF_MONTH_PATTERN = Regex("""(\d{1,2})[^\d]*""")
private val YEAR_PATTERN = Regex("""(\d{2,4})[^\d]*""")
private val MONTH_NAMES =
    listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/**
 * RFC 6265 5.1.1 日期解析 (OkHttp parseExpires 等价逻辑):
 * 按日期字符 (字母/数字/':') 切 token, 依次匹配 时间 / 日 / 月 / 年 (OkHttp 同款顺序与守卫),
 * 任一必需部分缺失或越界 → null (属性忽略)。支持 "Wed, 09 Jun 2021 10:18:14 GMT"、
 * "Sunday, 06-Nov-94 08:49:37 GMT"、"Sun Nov  6 08:49:37 1994" 等 RFC 6265 变体。
 */
private fun parseRfc6265Date(s: String): Long? {
    var hour = -1
    var minute = -1
    var second = -1
    var dayOfMonth = -1
    var month = -1
    var year = -1

    val tokens = s.split(NON_DATE_CHAR_PATTERN).filter { it.isNotEmpty() }
    for (token in tokens) {
        when {
            hour == -1 && TIME_PATTERN.matches(token) -> {
                val m = TIME_PATTERN.matchEntire(token)!!
                hour = m.groupValues[1].toInt()
                minute = m.groupValues[2].toInt()
                second = m.groupValues[3].toInt()
            }

            dayOfMonth == -1 && DAY_OF_MONTH_PATTERN.matches(token) -> {
                dayOfMonth = DAY_OF_MONTH_PATTERN.matchEntire(token)!!.groupValues[1].toInt()
            }

            month == -1 && MONTH_NAMES.any { token.lowercase().startsWith(it) } -> {
                month = MONTH_NAMES.indexOfFirst { token.lowercase().startsWith(it) } + 1
            }

            year == -1 && YEAR_PATTERN.matches(token) -> {
                year = YEAR_PATTERN.matchEntire(token)!!.groupValues[1].toInt()
            }
        }
    }

    // 两位年份转换 (OkHttp 同款): 70-99 → 19xx, 0-69 → 20xx
    if (year in 70..99) year += 1900
    if (year in 0..69) year += 2000

    // 任一必需部分缺失或越界 → 日期非法 (OkHttp require 同款)
    if (year < 1601 || month == -1 || dayOfMonth !in 1..31 ||
        hour !in 0..23 || minute !in 0..59 || second !in 0..59
    ) {
        return null
    }

    return utcMillis(year, month, dayOfMonth, hour, minute, second)
}

/** 非日期字符 (OkHttp dateCharacterOffset 反向: 空格/标点/制表符等) */
private val NON_DATE_CHAR_PATTERN = Regex("""[^0-9A-Za-z:]+""")

/**
 * 公历日期 → UTC epoch 毫秒 (Howard Hinnant days_from_civil 算法, 纯 Kotlin, 无时区依赖)。
 */
private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468L
    return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L
}

/**
 * 从 URL 字符串提取主机名 (KmpHttpUrl.toString 解析, 支持 userinfo/IPv6 方括号)。
 */
private fun KmpHttpUrl.hostName(): String? {
    val s = toString()
    val schemeEnd = s.indexOf("://")
    if (schemeEnd == -1) return null
    var hostPort = s.substring(schemeEnd + 3)
    val pathStart = hostPort.indexOf('/')
    if (pathStart != -1) hostPort = hostPort.substring(0, pathStart)
    val at = hostPort.lastIndexOf('@')
    if (at != -1) hostPort = hostPort.substring(at + 1)
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        return if (close != -1) hostPort.substring(1, close).lowercase() else null
    }
    val colon = hostPort.indexOf(':')
    return (if (colon != -1) hostPort.substring(0, colon) else hostPort).lowercase()
}
