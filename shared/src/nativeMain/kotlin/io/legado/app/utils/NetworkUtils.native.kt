package io.legado.app.utils

/**
 * NetworkUtils 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/NetworkUtils.kt expect 注释。
 * 纯 Kotlin 实现: URL 解析部分与 jvmAndAndroidMain 行为一致;
 * PublicSuffixDatabase (JVM-only) 不可用, getSubDomain/getSubDomainOrNull 退化为返回 host (功能受限但不崩)。
 *
 * 附加成员 (仅 jvmAndAndroid 可见的 java.net.URL/InetAddress 重载) 在 iOS/鸿蒙不暴露。
 * 仅 [isAvailable] 留 app (走 connectivityManager, 见 app 端 NetworkAvailability)。
 */
actual object NetworkUtils {

    private val notNeedEncodingQuery: BooleanArray by lazy {
        val arr = BooleanArray(256)
        for (i in 'a'.code..'z'.code) arr[i] = true
        for (i in 'A'.code..'Z'.code) arr[i] = true
        for (i in '0'.code..'9'.code) arr[i] = true
        for (c in "!*\$&()*+,-./:;=?@[\\]^_`{|}~") arr[c.code] = true
        arr
    }

    private val notNeedEncodingForm: BooleanArray by lazy {
        val arr = BooleanArray(256)
        for (i in 'a'.code..'z'.code) arr[i] = true
        for (i in 'A'.code..'Z'.code) arr[i] = true
        for (i in '0'.code..'9'.code) arr[i] = true
        for (c in "*-._") arr[c.code] = true
        arr
    }

    actual fun encodedQuery(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c.code < 256 && notNeedEncodingQuery[c.code]) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    actual fun encodedForm(str: String): Boolean {
        var needEncode = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c.code < 256 && notNeedEncodingForm[c.code]) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[++i]
                val c2 = str[++i]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i++
                    continue
                }
            }
            needEncode = true
            break
        }
        return !needEncode
    }

    private fun isDigit16Char(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

    actual fun getAbsoluteURL(baseURL: String?, relativePath: String): String {
        if (baseURL.isNullOrEmpty() || baseURL.isDataUrl()) return relativePath.trim()
        val base = baseURL.substringBefore(",")
        val absoluteUrl = try {
            JsURL(base).let { "${it.origin}${it.pathname}" }
        } catch (e: Exception) {
            return relativePath.trim()
        }
        return getAbsoluteURLFromBase(absoluteUrl, relativePath)
    }

    /**
     * URL 重载: 不经 String 门面 (避免 substringBefore(",") 截断含逗号的 URL)。
     * baseURL.toString() 已是完整 URL 文本, 直接走 [getAbsoluteURLFromBase] 手工拼接
     * (内部自处理 query/# 与相对路径, 与原 JDK URL(base, relative) 语义对齐)。
     */
    actual fun getAbsoluteURL(baseURL: URL?, relativePath: String): String {
        if (baseURL == null) return relativePath.trim()
        return getAbsoluteURLFromBase(baseURL.toString(), relativePath)
    }

    private fun getAbsoluteURLFromBase(baseURL: String, relativePath: String): String {
        val relativePathTrim = relativePath.trim()
        if (relativePathTrim.isAbsUrl()) return relativePathTrim
        if (relativePathTrim.isDataUrl()) return relativePathTrim
        if (relativePathTrim.startsWith("javascript")) return ""
        // 解析 baseURL 提取 scheme/host/path
        val schemeEnd = baseURL.indexOf("://")
        if (schemeEnd < 0) return relativePathTrim
        val scheme = baseURL.substring(0, schemeEnd)
        val afterScheme = baseURL.substring(schemeEnd + 3)
        val hostEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (hostEnd < 0) afterScheme else afterScheme.substring(0, hostEnd)
        val basePath = if (hostEnd < 0) "" else afterScheme.substring(hostEnd).substringBefore('?').substringBefore('#')
        return when {
            relativePathTrim.startsWith("//") -> "$scheme:$relativePathTrim"
            relativePathTrim.startsWith("/") -> "$scheme://$authority$relativePathTrim"
            else -> {
                val baseDir = basePath.substringBeforeLast('/', "/")
                "$scheme://$authority$baseDir/$relativePathTrim"
            }
        }
    }

    actual fun getBaseUrl(url: String?): String? {
        url ?: return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            val index = url.indexOf("/", 9)
            return if (index == -1) url else url.take(index)
        }
        return null
    }

    actual fun getSubDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return kotlin.runCatching {
            val host = JsURL(baseUrl).host
            if (isIPAddress(host)) host
            // 退化: 无 PublicSuffixDatabase, 返回 host 作为 eTLD+1 的近似
            // (功能受限: 多级子域名场景不能归一到 eTLD+1, 但 cookie 读写不崩, 仅 cookie 域范围扩大)
            host
        }.getOrDefault(baseUrl)
    }

    actual fun getSubDomainOrNull(url: String): String? {
        val baseUrl = getBaseUrl(url) ?: return null
        return kotlin.runCatching {
            val host = JsURL(baseUrl).host
            if (isIPAddress(host)) host
            host
        }.getOrDefault(null)
    }

    actual fun getDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return kotlin.runCatching {
            JsURL(baseUrl).host
        }.getOrDefault(baseUrl)
    }

    // 与 hutool RegexPool.IPV4/IPV6 相同的正则, Validator.isIpv4/isIpv6 行为对齐
    private val ipv4Regex = Regex(
        "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)\\." +
            "(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)$"
    )
    private val ipv6Regex = Regex(
        "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|" +
            "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
            "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
            "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
            ":((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+|" +
            "::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|" +
            "([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9]))"
    )

    actual fun isIPv4Address(input: String?): Boolean {
        return !input.isNullOrEmpty()
                && input[0] in '1'..'9'
                && input.count { it == '.' } == 3
                && ipv4Regex.matches(input)
    }

    actual fun isIPv6Address(input: String?): Boolean {
        return input != null && input.contains(":") && ipv6Regex.matches(input)
    }

    actual fun isIPAddress(input: String?): Boolean {
        return isIPv4Address(input) || isIPv6Address(input)
    }
}
