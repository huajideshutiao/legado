package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.CacheManager
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import android.webkit.CookieManager as WebkitCookieManager

@Suppress("ConstPropertyName")
object CookieManager {
    /**
     * <domain>_session_cookie 会话期 cookie，应用重启后失效
     * <domain>_cookie cookies 缓存
     */

    const val cookieJarHeader = "CookieJar"

    /**
     * 从响应中保存Cookies
     */
    fun saveResponse(response: Response) {
        val url = response.request.url
        saveCookiesFromHeaders(url, response.headers)
    }

    private fun saveCookiesFromHeaders(url: HttpUrl, headers: Headers) {
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = Cookie.parseAll(url, headers)
        if (cookies.isEmpty()) return

        val (persistent, session) = cookies.partition { it.persistent }

        if (session.isNotEmpty()) {
            updateSessionCookie(domain, session.toCookieString())
        }

        if (persistent.isNotEmpty()) {
            CookieStore.replaceCookie(domain, persistent.toCookieString())
        }
    }

    /**
     * 加载Cookies到请求中
     */
    fun loadRequest(request: Request): Request {
        val urlString = request.url.toString()
        val domain = NetworkUtils.getSubDomain(urlString)

        val storeCookie = CookieStore.getCookie(domain)
        val requestCookie = request.header("Cookie")

        val newCookie = mergeCookies(requestCookie, storeCookie) ?: return request

        return try {
            request.newBuilder()
                .header("Cookie", newCookie)
                .build()
        } catch (e: Exception) {
            CookieStore.removeCookie(urlString)
            val msg = "设置cookie出错，已清除cookie $domain cookie:$newCookie"
            AppLog.put(msg, e)
            request
        }
    }

    private fun getSessionCookieMap(domain: String): MutableMap<String, String>? {
        return getSessionCookie(domain)?.let { CookieStore.cookieToMap(it) }
    }

    fun getSessionCookie(domain: String): String? {
        return CacheManager.getFromMemory("${domain}_session_cookie") as? String
    }

    private fun updateSessionCookie(domain: String, cookies: String) {
        val cacheKey = "${domain}_session_cookie"
        val sessionCookie = CacheManager.getFromMemory(cacheKey) as? String
        val ck =
            if (sessionCookie.isNullOrEmpty()) cookies else mergeCookies(sessionCookie, cookies)
        ck?.let {
            CacheManager.putMemory(cacheKey, it)
        }
    }

    fun mergeCookies(vararg cookies: String?): String? {
        val cookieMap = mergeCookiesToMap(*cookies)
        return CookieStore.mapToCookie(cookieMap)
    }

    fun mergeCookiesToMap(vararg cookies: String?): MutableMap<String, String> {
        val combinedMap = mutableMapOf<String, String>()
        cookies.forEach { cookieStr ->
            if (!cookieStr.isNullOrBlank()) {
                combinedMap.putAll(CookieStore.cookieToMap(cookieStr))
            }
        }
        return combinedMap
    }

    /**
     * 删除单个Cookie
     */
    fun removeCookie(url: String, key: String) {
        val domain = NetworkUtils.getSubDomain(url)

        getSessionCookieMap(domain)?.let { map ->
            if (map.remove(key) != null) {
                CookieStore.mapToCookie(map)?.let {
                    CacheManager.putMemory("${domain}_session_cookie", it)
                }
            }
        }

        val cookie = getCookieNoSession(url)
        if (cookie.isNotEmpty()) {
            val cookieMap = CookieStore.cookieToMap(cookie)
            if (cookieMap.remove(key) != null) {
                CookieStore.setCookie(url, CookieStore.mapToCookie(cookieMap))
            }
        }
    }

    fun getCookieNoSession(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        val cacheCookie = CacheManager.getFromMemory("${domain}_cookie") as? String

        return cacheCookie ?: appDb.cookieDao.get(domain)?.cookie ?: ""
    }

    fun applyToWebView(url: String) {
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
        val cookies = CookieStore.getCookie(url).splitNotBlank(";")
        if (cookies.isEmpty()) return

        val webManager = WebkitCookieManager.getInstance()
        // 不建议在这里直接 removeSessionCookies，因为它会影响全局
        cookies.forEach {
            webManager.setCookie(baseUrl, it)
        }
        webManager.flush()
    }

    private fun List<Cookie>.toCookieString(): String {
        return joinToString("; ") { "${it.name}=${it.value}" }
    }
}
