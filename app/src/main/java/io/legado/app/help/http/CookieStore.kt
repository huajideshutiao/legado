package io.legado.app.help.http

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie

@Keep
object CookieStore : CookieManagerInterface {

    /**
     * 保存cookie到数据库，并同步到内置浏览器
     */
    override fun setCookie(url: String, cookie: String?) {
        if (!url.startsWith("http")) return
        try {
            val domain = NetworkUtils.getSubDomain(url)
            val cookieStr = cookie ?: ""

            val cacheKey = domain + "_cookie"
            val oldCache = CacheManager.getFromMemory(cacheKey) as? String
            if (oldCache == cookieStr && cookieStr.isNotEmpty()) return

            // 内存缓存同步更新，保证 getCookie 能立即拿到新值
            CacheManager.putMemory(cacheKey, cookieStr)
            appDb.cookieDao.insert(Cookie(domain, cookieStr))
            // 同步到内置浏览器
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            if (cookieStr.isNotBlank()) {
                val cookieManager = android.webkit.CookieManager.getInstance()
                // 性能优化：使用 split 迭代避免创建多余的 List
                cookieStr.split(';').forEach {
                    val c = it.trim()
                    if (c.isNotEmpty()) {
                        cookieManager.setCookie(baseUrl, c)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$url\n$e", e)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return

        val oldCookie = getCookieNoSession(url)
        if (oldCookie.isEmpty()) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie)
            cookieMap.putAll(cookieToMap(cookie))
            mapToCookie(cookieMap)?.let {
                setCookie(url, it)
            }
        }
    }

    /**
     * 获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        var ck = mapToCookie(cookieMap) ?: ""
        if (ck.length > 4096) {
            val keys = cookieMap.keys.toList()
            for (key in keys.shuffled()) {
                cookieMap.remove(key)
                CookieManager.removeCookie(url, key)
                ck = mapToCookie(cookieMap) ?: ""
                if (ck.length <= 4096) break
            }
        }
        return ck
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        // 性能优化：直接解析不转换成 Map
        if (cookie.isBlank()) return ""
        cookie.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == key) {
                return pair.substring(index + 1).trim()
            }
        }
        return ""
    }

    override fun removeCookie(url: String) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            appDb.cookieDao.delete(domain)
            CacheManager.deleteMemory(domain + "_cookie")
            CacheManager.deleteMemory("${domain}_session_cookie")

            android.webkit.CookieManager.getInstance().removeCookie(url)
//
//            // 清理 WebStorage (Local Storage / Session Storage)
//            val baseUrl = NetworkUtils.getBaseUrl(url)
//            if (baseUrl != null) {
//                WebStorage.getInstance().deleteOrigin(baseUrl)
//            }
        } catch (e: Exception) {
            AppLog.put("删除Cookie失败\n$url\n$e", e)
        }
    }

    override fun cookieToMap(cookie: String): MutableMap<String, String> {
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

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) return null
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun clear() {
        appDb.cookieDao.deleteOkHttp()
    }
}
