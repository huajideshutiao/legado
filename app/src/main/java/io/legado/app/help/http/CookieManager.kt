package io.legado.app.help.http

import io.legado.app.data.appDb
import io.legado.app.help.CacheManager
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.runBlocking

@Suppress("ConstPropertyName")
object CookieManager {
    /**
     * <domain>_session_cookie 会话期 cookie，应用重启后失效
     * <domain>_cookie cookies 缓存
     */

    /**
     * CookieJarBridge (saveResponse / loadRequest) 已下沉 shared commonMain
     * (见 SharedCookieJarBridge, 1:1 复刻本 object 原实现, 经 CookieStoreProvider 间接访问
     * 各端 cookie 存储)。app 端在 App.onCreate 经 registerSharedCookieJarBridge 注册。
     *
     * cookieJarHeader / mergeCookies / mergeCookiesToMap 三个纯函数已下沉 shared
     * (见 modules/shared/src/commonMain/kotlin/io/legado/app/help/http/CookieUtils.kt),
     * 跨模块同包名合并, 消费方 import 零改动。本 object 仅保留安卓绑定方法。
     *
     * P0-0c: 为 AnalyzeUrl 主体下沉 shared 做前置。
     */

    private fun getSessionCookieMap(domain: String): MutableMap<String, String>? {
        return getSessionCookie(domain)?.let { CookieStore.cookieToMap(it) }
    }

    fun getSessionCookie(domain: String): String? {
        return CacheManager.getFromMemory("${domain}_session_cookie") as? String
    }

    fun updateSessionCookie(domain: String, cookies: String) {
        val cacheKey = "${domain}_session_cookie"
        val sessionCookie = CacheManager.getFromMemory(cacheKey) as? String
        val ck =
            if (sessionCookie.isNullOrEmpty()) cookies else mergeCookies(sessionCookie, cookies)
        ck?.let {
            CacheManager.putMemory(cacheKey, it)
        }
    }

    fun mergeCookies(vararg cookies: String?): String? =
        io.legado.app.help.http.mergeCookies(*cookies)

    fun mergeCookiesToMap(vararg cookies: String?): MutableMap<String, String> =
        io.legado.app.help.http.mergeCookiesToMap(*cookies)

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

        return cacheCookie ?: runBlocking { appDb.cookieDao.get(domain) }?.cookie ?: ""
    }

    fun applyToWebView(url: String) {
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
        val cookies = CookieStore.getCookie(url).splitNotBlank(";")
        if (cookies.isEmpty()) return

        val webManager = android.webkit.CookieManager.getInstance()
        // 不建议在这里直接 removeSessionCookies，因为它会影响全局
        cookies.forEach {
            webManager.setCookie(baseUrl, it)
        }
        webManager.flush()
    }
}
