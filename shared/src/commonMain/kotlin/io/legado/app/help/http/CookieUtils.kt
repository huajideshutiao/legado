package io.legado.app.help.http

/**
 * Cookie 纯函数半区。原 app 端 CookieManager/CookieStore 中的纯字符串处理函数下沉,
 * 安卓绑定方法 (CacheManager/appDb/WebkitCookieManager) 留 app 端。
 *
 * 包名/函数签名不变, 消费方 import 零改动 (跨模块同包名同签名扩展自动合并)。
 *
 * P0-0c: 为 AnalyzeUrl 主体下沉 shared 做前置, 让 mergeCookies/cookieJarHeader 等
 * 纯函数可在 shared 内直接调用, 不反向依赖 app 的 CookieManager object。
 */

/**
 * CookieJar 启用标记 header 名。请求头中带此键表示走 CookieStore 自动管理 cookie。
 */
const val cookieJarHeader = "CookieJar"

/**
 * 解析 cookie 字符串为 Map。格式 "k1=v1; k2=v2" -> {k1=v1, k2=v2}。
 * 空 value 或 "null" value 跳过。
 */
fun cookieToMap(cookie: String): MutableMap<String, String> {
    val cookieMap = mutableMapOf<String, String>()
    if (cookie.isBlank()) return cookieMap
    cookie.split(';').forEach { pair ->
        val index = pair.indexOf('=')
        if (index > 0) {
            val key = pair.take(index).trim()
            val value = pair.substring(index + 1).trim()
            if (value.isNotEmpty() && value != "null") {
                cookieMap[key] = value
            }
        }
    }
    return cookieMap
}

/**
 * Map 转 cookie 字符串。空 Map 返回 null。格式 {k1=v1, k2=v2} -> "k1=v1; k2=v2"。
 */
fun mapToCookie(cookieMap: Map<String, String>?): String? {
    if (cookieMap.isNullOrEmpty()) return null
    return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 合并多个 cookie 字符串为 Map。后参数覆盖前参数的同名 key。
 */
fun mergeCookiesToMap(vararg cookies: String?): MutableMap<String, String> {
    val combinedMap = mutableMapOf<String, String>()
    cookies.forEach { cookieStr ->
        if (!cookieStr.isNullOrBlank()) {
            combinedMap.putAll(cookieToMap(cookieStr))
        }
    }
    return combinedMap
}

/**
 * 合并多个 cookie 字符串为单个 cookie 字符串。后参数覆盖前参数的同名 key。
 * 返回 null 表示无 cookie。
 */
fun mergeCookies(vararg cookies: String?): String? {
    val cookieMap = mergeCookiesToMap(*cookies)
    return mapToCookie(cookieMap)
}
