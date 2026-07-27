package io.legado.app.help.http

import androidx.annotation.Keep
import com.script.jsdispatch.JsApi
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.utils.removeCookie
import kotlinx.coroutines.runBlocking

/**
 * app 端 CookieStore 薄壳 (Android 专属逻辑)。
 *
 * 主体 cookie CRUD 逻辑 (setCookie/replaceCookie/getCookie/removeCookie/
 * cookieToMap/mapToCookie/getKey/clear) 已下沉到 commonMain [CookieStoreBase],
 * 本 object 继承基类并 override 平台钩子, 保留 Android 专属逻辑:
 * - **数据库持久化**: [onInsertCookieToDb] / [onDeleteCookieFromDb] / [onClearOkHttpCookie]
 *   委托 `appDb.cookieDao` (Room + appCtx, 留 app 端)。
 * - **WebView 同步**: [onSyncCookieToWebView] / [onRemoveWebViewCookie]
 *   委托 `android.webkit.CookieManager` (Android 专属)。
 * - **CookieManager 委托**: [getCookieNoSession] / [getSessionCookie] /
 *   [removeCookie] (url, key) 委托 app 端 [CookieManager] object
 *   (依赖 appDb.cookieDao + CacheManager 内存缓存)。
 *
 * # 行为一致性
 * 所有 override 方法严格保留原 app 端 CookieStore 的实现逻辑 (runBlocking + appDb /
 * WebkitCookieManager / CookieManager 委托), 仅通过 protected 钩子注入到基类流程中,
 * 外部调用方 `CookieStore.setCookie(...)` 等签名/行为完全不变。
 *
 * # 注解
 * `@Keep` (androidx.annotation.Keep, Android 专属) / `@JsApi` (com.script.jsdispatch)
 * 保留在 app 端 object 上 (与 CacheManager 下沉时 @Keep 处理一致: commonMain base 不加)。
 */
@Keep
@JsApi
object CookieStore : CookieStoreBase() {

    // ===== 数据库持久化钩子 (appDb.cookieDao) =====

    /** 持久化 cookie 到数据库 (原 setCookie 中 `appDb.cookieDao.insert`)。 */
    override fun onInsertCookieToDb(domain: String, cookieStr: String) {
        runBlocking { appDb.cookieDao.insert(Cookie(domain, cookieStr)) }
    }

    /** 从数据库删除 domain 对应 cookie (原 removeCookie 中 `appDb.cookieDao.delete`)。 */
    override fun onDeleteCookieFromDb(domain: String) {
        runBlocking { appDb.cookieDao.delete(domain) }
    }

    /** 清除 OkHttp cookie (原 clear 中 `appDb.cookieDao.deleteOkHttp`)。 */
    override fun onClearOkHttpCookie() {
        runBlocking { appDb.cookieDao.deleteOkHttp() }
    }

    // ===== WebView 同步钩子 (android.webkit.CookieManager) =====

    /** 同步 cookie 到内置浏览器 (原 setCookie 中 `WebkitCookieManager.setCookie`)。 */
    override fun onSyncCookieToWebView(baseUrl: String, cookieStr: String) {
        val cookieManager = android.webkit.CookieManager.getInstance()
        // 性能优化：使用 split 迭代避免创建多余的 List
        cookieStr.split(';').forEach {
            val c = it.trim()
            if (c.isNotEmpty()) {
                cookieManager.setCookie(baseUrl, c)
            }
        }
    }

    /** 从 WebView 移除 url 对应 cookie (原 removeCookie 中 `WebkitCookieManager.removeCookie`)。 */
    override fun onRemoveWebViewCookie(url: String) {
        android.webkit.CookieManager.getInstance().removeCookie(url)
    }

    // ===== CookieManager 委托钩子 (app 端 CookieManager object) =====

    /** 获取不含 session 的持久 cookie, 委托 [CookieManager.getCookieNoSession]。 */
    override fun getCookieNoSession(url: String): String =
        CookieManager.getCookieNoSession(url)

    /** 获取会话期 cookie, 委托 [CookieManager.getSessionCookie]。 */
    override fun getSessionCookie(domain: String): String? =
        CookieManager.getSessionCookie(domain)

    /** 移除 url 所属域名中指定 key 的 cookie, 委托 [CookieManager.removeCookie]。 */
    override fun removeCookie(url: String, key: String) {
        CookieManager.removeCookie(url, key)
    }
}
