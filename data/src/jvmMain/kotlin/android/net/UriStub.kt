// android.net.Uri JVM 实现: 扩展常把 Uri.parse(url) 的结果当字符串用或取 path/host/query,
// 空壳会让 toString() 返回 Object 默认串 (防盗链判定与 URL 拼装都会错), 故用 java.net.URI
// 真实解析; 非法输入与 Android 一致不抛异常, toString() 原样返回入参。
package android.net

import java.net.URI

class Uri private constructor(private val raw: String) {

    private val parsed: URI? = runCatching { URI(raw) }.getOrNull()

    fun getPath(): String? = parsed?.path

    fun getHost(): String? = parsed?.host

    fun getScheme(): String? = parsed?.scheme

    fun getQuery(): String? = parsed?.query

    fun getLastPathSegment(): String? = parsed?.path
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotEmpty() }

    fun getQueryParameter(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return parsed?.rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.let { decode(it) }
    }

    override fun toString(): String = raw

    private fun decode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    companion object {

        @JvmStatic
        fun parse(s: String?): Uri = Uri(s.orEmpty())
    }
}
