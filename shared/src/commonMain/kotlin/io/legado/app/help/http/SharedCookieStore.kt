package io.legado.app.help.http

import com.script.jsdispatch.JsApi
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.help.http.SharedCookieStore.getCookieNoSession
import io.legado.app.help.http.SharedCookieStore.getSessionCookie
import io.legado.app.help.http.SharedCookieStore.removeCookie
import io.legado.app.help.http.SharedCookieStore.updateSessionCookie
import io.legado.app.utils.NetworkUtils
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 共享路径 (desktop/iOS/鸿蒙) 的 CookieStore 实现: app 端 CookieStore + CookieManager
 * 业务语义原版下沉, cookie 持久化走 Room cookieDao (commonMain AppDatabase),
 * [CacheManager] 内存缓存 (`<domain>_cookie` / `<domain>_session_cookie`) 作热层。
 *
 * - 主体 CRUD 继承 [CookieStoreBase] (app 端同一基类, 行为逐行一致);
 * - DB 钩子对照 app 端 CookieStore override (runBlockingInScope 模式与 [CacheManager] 一致,
 *   @JsApi 同步语义不能 suspend);
 * - [getCookieNoSession] / [getSessionCookie] / [removeCookie] (url, key) /
 *   [updateSessionCookie] 对照 app 端 CookieManager 同名方法;
 * - WebView 同步钩子保持基类 no-op (Android 专属, app 端自有实现不经此类)。
 *
 * app 端仍用自身 CookieStore/CookieManager (App.onCreate 注册 AndroidCookieStoreProvider),
 * 本 object 仅由 desktop/iOS/鸿蒙的 registerDefaultXxxCookieStoreProvider 注册。
 */
@JsApi
@Suppress("unused")
object SharedCookieStore : CookieStoreBase(), CookieStoreProvider {

    // ===== 数据库持久化钩子 (对照 app 端 CookieStore override, appDb → AppDbProviders) =====

    override fun onInsertCookieToDb(domain: String, cookieStr: String) {
        runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().cookieDao.insert(Cookie(domain, cookieStr))
        }
    }

    override fun onDeleteCookieFromDb(domain: String) {
        runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().cookieDao.delete(domain)
        }
    }

    override fun onClearOkHttpCookie() {
        runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().cookieDao.deleteOkHttp()
        }
    }

    // ===== CookieManager 语义 (对照 app 端 CookieManager 同名方法) =====

    /** 对照 app 端 CookieManager.getCookieNoSession: 内存热层 → Room cookieDao 兜底。 */
    public override fun getCookieNoSession(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        val cacheCookie = CacheManager.getFromMemory("${domain}_cookie") as? String
        return cacheCookie
            ?: runBlockingInScope(EmptyCoroutineContext) { AppDbProviders.get().cookieDao.get(domain) }?.cookie
            ?: ""
    }

    /** 对照 app 端 CookieManager.getSessionCookie。 */
    public override fun getSessionCookie(domain: String): String? {
        return CacheManager.getFromMemory("${domain}_session_cookie") as? String
    }

    /** 对照 app 端 CookieManager.removeCookie(url, key)。 */
    public override fun removeCookie(url: String, key: String) {
        val domain = NetworkUtils.getSubDomain(url)

        getSessionCookie(domain)?.let { cookieToMap(it) }?.let { map ->
            if (map.remove(key) != null) {
                mapToCookie(map)?.let {
                    CacheManager.putMemory("${domain}_session_cookie", it)
                }
            }
        }

        val cookie = getCookieNoSession(url)
        if (cookie.isNotEmpty()) {
            val cookieMap = cookieToMap(cookie)
            if (cookieMap.remove(key) != null) {
                setCookie(url, mapToCookie(cookieMap))
            }
        }
    }

    /** 对照 app 端 CookieManager.updateSessionCookie (CookieJar saveResponse 会话分支用)。 */
    override fun updateSessionCookie(domain: String, cookies: String) {
        val cacheKey = "${domain}_session_cookie"
        val sessionCookie = CacheManager.getFromMemory(cacheKey) as? String
        val ck =
            if (sessionCookie.isNullOrEmpty()) cookies else mergeCookies(sessionCookie, cookies)
        ck?.let {
            CacheManager.putMemory(cacheKey, it)
        }
    }
}
