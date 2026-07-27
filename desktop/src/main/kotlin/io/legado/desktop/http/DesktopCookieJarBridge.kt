package io.legado.desktop.http

import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieJarBridge
import io.legado.app.help.http.SharedCookieStore
import io.legado.app.help.http.mergeCookies
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.NetworkUtils
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.Response

/**
 * 桌面端 [CookieJarBridge]: app 端 CookieManager.loadRequest/saveResponse 原版语义,
 * 存取经 commonMain [SharedCookieStore] (Room cookieDao 持久化 + CacheManager 内存热层),
 * 与业务层 CookieStoreProviders / JS cookie API 同一份存储 (原内存 Map 版重启即丢且与业务层隔离)。
 */
class DesktopCookieJarBridge : CookieJarBridge {

    override fun loadRequest(request: Request): Request {
        val urlString = request.url.toString()
        val domain = NetworkUtils.getSubDomain(urlString)

        val storeCookie = SharedCookieStore.getCookie(domain)
        val requestCookie = request.header("Cookie")

        val newCookie = mergeCookies(requestCookie, storeCookie) ?: return request

        return try {
            request.newBuilder()
                .header("Cookie", newCookie)
                .build()
        } catch (e: Exception) {
            SharedCookieStore.removeCookie(urlString)
            AppLog.put(jvmGetString("desktop_cookie_set_error", domain, newCookie), e)
            request
        }
    }

    override fun saveResponse(response: Response) {
        val url = response.request.url
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = Cookie.parseAll(url, response.headers)
        if (cookies.isEmpty()) return

        val (persistent, session) = cookies.partition { it.persistent }

        if (session.isNotEmpty()) {
            SharedCookieStore.updateSessionCookie(domain, session.toCookieString())
        }

        if (persistent.isNotEmpty()) {
            SharedCookieStore.replaceCookie(domain, persistent.toCookieString())
        }
    }

    private fun List<Cookie>.toCookieString(): String {
        return joinToString("; ") { "${it.name}=${it.value}" }
    }
}
