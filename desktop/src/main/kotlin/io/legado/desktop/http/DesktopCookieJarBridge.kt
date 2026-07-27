package io.legado.desktop.http

import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieJarBridge
import io.legado.app.help.http.mergeCookies
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.NetworkUtils
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面端 [CookieJarBridge] 内存实现 (KP1.3)。
 *
 * shared 的 CookieManager/CookieStore 在 app 模块 (依赖 appDb/CacheManager/android.webkit),
 * 无法直接复用; 桌面端按任务要求实现简化版: 内存 Map 按 domain 存 cookie, 进程退出即丢失。
 *
 * - [loadRequest]: 按 [NetworkUtils.getSubDomain] 取 domain cookie, merge 到请求 Cookie 头;
 * - [saveResponse]: 用 [Cookie.parseAll] 解析 Set-Cookie, 按 domain 合并入内存 Map (不区分持久/会话)。
 *
 * 注册到 [io.legado.app.help.http.CookieJarBridgeHolder] 后, [DesktopHttpProvider] 的头注入拦截器
 * 会在请求头带 cookieJarHeader 标记时自动调用桥接。
 */
class DesktopCookieJarBridge : CookieJarBridge {

    // domain -> cookie 字符串 ("k1=v1; k2=v2")
    private val store: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    override fun loadRequest(request: Request): Request {
        val domain = NetworkUtils.getSubDomain(request.url.toString())
        val storeCookie = store[domain] ?: return request
        val requestCookie = request.header("Cookie")
        val newCookie = mergeCookies(requestCookie, storeCookie) ?: return request
        return try {
            request.newBuilder()
                .header("Cookie", newCookie)
                .build()
        } catch (e: Exception) {
            AppLog.put(jvmGetString("desktop_cookie_set_error", domain, newCookie), e)
            request
        }
    }

    override fun saveResponse(response: Response) {
        val url = response.request.url
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = Cookie.parseAll(url, response.headers)
        if (cookies.isEmpty()) return
        // 内存版不区分 persistent/session, 统一 merge 入 store
        val incoming = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        store.merge(domain, incoming) { old, new -> mergeCookies(old, new) ?: new }
    }
}
