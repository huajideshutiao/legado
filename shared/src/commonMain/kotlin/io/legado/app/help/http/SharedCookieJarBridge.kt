package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.utils.NetworkUtils

/**
 * CookieJarBridge 共享实现 (commonMain), 1:1 复刻 app 端 CookieManager.saveResponse / loadRequest。
 *
 * # 背景
 * app 端 [io.legado.app.help.http.CookieManager] object 实现 [CookieJarBridge],
 * 在 OkHttp / Cronet 拦截器中自动加载/保存 cookie。原实现依赖 app 端 CookieStore/CookieManager
 * (appDb.cookieDao + CacheManager + android.webkit), 无法直接下沉。
 *
 * 本类把 saveResponse/loadResponse 主体逻辑下沉 commonMain, 通过 [CookieStoreProvider]
 * 间接访问各端 cookie 存储:
 * - **Android**: [AndroidCookieStoreProvider] 委托 app 端 CookieStore/CookieManager (含 WebView 同步);
 * - **Desktop/iOS/鸿蒙**: [SharedCookieStore] (Room cookieDao + CacheManager 内存热层)。
 *
 * # 1:1 对照 app 端 CookieManager
 * - [loadRequest]: 对照 `CookieManager.loadRequest` — domain 提取 / getCookie(domain) /
 *   mergeCookies(requestCookie, storeCookie) / header 设置 / catch 清除;
 * - [saveResponse]: 对照 `CookieManager.saveResponse` → `saveCookiesFromHeaders` —
 *   parseResponseCookies / persistent·session 分区 / updateSessionCookie + replaceCookie。
 *
 * 行为与 app 端完全一致, 仅把 CookieStore 单例调用改为 CookieStoreProvider 间接。
 *
 * # 注册时机
 * 宿主启动早期 (CookieStoreProvider 注册之后) 调 [registerSharedCookieJarBridge] 一次:
 * - app: App.onCreate (紧跟 registerAndroidCookieStoreProvider 之后);
 * - desktop: registerDesktopHttpProvider;
 * - iOS: registerIosProviders;
 * - 鸿蒙: registerOhosProviders。
 *
 * 替代原 desktop DesktopCookieJarBridge (桌面端独立实现), 统一三端桥接逻辑。
 */
class SharedCookieJarBridge : CookieJarBridge {

    /**
     * 加载 Cookies 到请求中 (对照 app 端 CookieManager.loadRequest)。
     *
     * 取 url 所属二级域名 cookie, 与请求已有 Cookie 头合并; 设置失败时清除该 url cookie 并返回原请求。
     */
    override fun loadRequest(request: KmpRequest): KmpRequest {
        val store = CookieStoreProviders.get() ?: return request
        val urlString = request.url.toString()
        val domain = NetworkUtils.getSubDomain(urlString)

        val storeCookie = store.getCookie(domain)
        val requestCookie = request.header("Cookie")

        val newCookie = mergeCookies(requestCookie, storeCookie) ?: return request

        return try {
            request.newBuilder()
                .header("Cookie", newCookie)
                .build()
        } catch (e: Exception) {
            store.removeCookie(urlString)
            val msg = "设置cookie出错，已清除cookie $domain cookie:$newCookie"
            AppLog.put(msg, e)
            request
        }
    }

    /**
     * 从响应中保存 Cookies (对照 app 端 CookieManager.saveResponse → saveCookiesFromHeaders)。
     *
     * 解析 Set-Cookie 头, 按 persistent 分区:
     * - 会话期 cookie → [CookieStoreProvider.updateSessionCookie] (CacheManager 内存缓存);
     * - 持久 cookie → [CookieStoreProvider.replaceCookie] (DB + 内存 + WebView 同步)。
     */
    override fun saveResponse(response: KmpResponse) {
        val store = CookieStoreProviders.get() ?: return
        val url = response.request.url
        saveCookiesFromHeaders(url, response.headers(), store)
    }

    private fun saveCookiesFromHeaders(
        url: KmpHttpUrl,
        headers: KmpHeaders,
        store: CookieStoreProvider
    ) {
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = parseResponseCookies(url, headers)
        if (cookies.isEmpty()) return

        val (persistent, session) = cookies.partition { it.persistent }

        if (session.isNotEmpty()) {
            store.updateSessionCookie(domain, session.toCookieString())
        }

        if (persistent.isNotEmpty()) {
            store.replaceCookie(domain, persistent.toCookieString())
        }
    }
}

/**
 * 注册 [SharedCookieJarBridge] 到 [CookieJarBridgeHolder]。
 *
 * 宿主启动早期调用一次 (须在 [CookieStoreProviders] 注册之后)。
 * 替代各端独立 CookieJarBridge 实现 (desktop DesktopCookieJarBridge / app CookieManager 直接注册),
 * 统一桥接逻辑到 commonMain。
 */
fun registerSharedCookieJarBridge() {
    CookieJarBridgeHolder.register(SharedCookieJarBridge())
}
