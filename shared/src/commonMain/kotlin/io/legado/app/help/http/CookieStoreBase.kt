package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.help.CacheManager
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils

/**
 * CookieStore 主体逻辑 (从 app 端下沉到 commonMain)。
 *
 * # 背景
 * app 端 [CookieStore] 是 @JsApi 暴露给书源 JS 的高频调用, 主体 cookie CRUD 逻辑
 * (setCookie/replaceCookie/getCookie/removeCookie/cookieToMap/mapToCookie) 与平台无关,
 * 下沉 shared/commonMain 让 desktop/iOS/鸿蒙复用。app 端 object CookieStore 改为
 * 继承本类并 override 平台钩子, 保留 WebkitCookieManager 同步等 Android 专属逻辑。
 *
 * # 设计
 * - **纯逻辑方法** ([replaceCookie] / [getKey] / [cookieToMap] / [mapToCookie]):
 *   直接实现, 跨平台共享。cookieToMap/mapToCookie 委托 commonMain 已下沉的纯函数
 *   (见 [CookieUtils]), 避免重复实现。
 * - **需平台资源的方法** ([setCookie] / [removeCookie] / [clear]):
 *   主体 (URL 校验 / domain 提取 / CacheManager 内存缓存 / try-catch) 在 base;
 *   数据库持久化 / WebView 同步等专属逻辑做成 `protected open` 钩子, app 端 override。
 * - **需平台 CookieManager 的方法** ([getCookieNoSession] / [getSessionCookie] /
 *   [removeCookie] (url, key)): 做成 `protected open`, 默认返回空值,
 *   app 端 override 委托给 app 端 [CookieManager] object。
 *
 * # 与已有下沉部分的关系
 * - **纯函数** (cookieToMap/mapToCookie/mergeCookies/mergeCookiesToMap): 已在 [CookieUtils]
 *   下沉 commonMain, 本类 cookieToMap/mapToCookie 直接委托, 行为与原 app 端一致。
 * - **业务层 Provider** ([CookieStoreProviders]): 跨平台 cookie 业务层抽象,
 *   desktop/iOS/鸿蒙宿主注入实现; app 端 [AndroidCookieStoreProvider] 委托本 object。
 *   本 class 与 Provider 各司其职: base 提供主体逻辑复用, Provider 提供注入点。
 *
 * # 注意
 * - 包名保持 `io.legado.app.help.http`, 与 app 端同包名合并, 调用方 import 零改动。
 * - `@Keep` / `@JsApi` 是注解约束, 放在 app 端 object CookieStore 上 (与 CacheManager
 *   下沉时 @Keep 处理一致: commonMain base 不加 Android 专属注解)。
 * - 行为与原 app 端 CookieStore 完全一致, 仅平台专属逻辑通过 override 注入。
 */
abstract class CookieStoreBase : CookieManagerInterface {

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
            onInsertCookieToDb(domain, cookieStr)
            // 同步到内置浏览器
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            if (cookieStr.isNotBlank()) {
                onSyncCookieToWebView(baseUrl, cookieStr)
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
        val sessionCookie = getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        var ck = mapToCookie(cookieMap) ?: ""
        if (ck.length > 4096) {
            val keys = cookieMap.keys.toList()
            for (key in keys.shuffled()) {
                cookieMap.remove(key)
                removeCookie(url, key)
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
            onDeleteCookieFromDb(domain)
            CacheManager.deleteMemory(domain + "_cookie")
            CacheManager.deleteMemory("${domain}_session_cookie")

            onRemoveWebViewCookie(url)
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
        return io.legado.app.help.http.cookieToMap(cookie)
    }

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        return io.legado.app.help.http.mapToCookie(cookieMap)
    }

    fun clear() {
        onClearOkHttpCookie()
    }

    // ===== 平台钩子: 数据库 / WebView 同步 (app 端 override 实现) =====

    /** 持久化 cookie 到数据库 (app 端 `appDb.cookieDao.insert(Cookie(domain, cookieStr))`)。默认 no-op。 */
    protected open fun onInsertCookieToDb(domain: String, cookieStr: String) {}

    /** 同步 cookie 到 WebView (app 端 `android.webkit.CookieManager.setCookie`)。默认 no-op。 */
    protected open fun onSyncCookieToWebView(baseUrl: String, cookieStr: String) {}

    /** 从数据库删除 domain 对应 cookie (app 端 `appDb.cookieDao.delete(domain)`)。默认 no-op。 */
    protected open fun onDeleteCookieFromDb(domain: String) {}

    /** 从 WebView 移除 url 对应 cookie (app 端 `android.webkit.CookieManager.removeCookie`)。默认 no-op。 */
    protected open fun onRemoveWebViewCookie(url: String) {}

    /** 清除 OkHttp cookie (app 端 `appDb.cookieDao.deleteOkHttp()`)。默认 no-op。 */
    protected open fun onClearOkHttpCookie() {}

    // ===== 平台钩子: CookieManager 委托 (app 端 override 委托给 app 端 CookieManager) =====

    /** 获取不含 session 的持久 cookie (app 端委托 `CookieManager.getCookieNoSession`)。默认空串。 */
    protected open fun getCookieNoSession(url: String): String = ""

    /** 获取会话期 cookie (app 端委托 `CookieManager.getSessionCookie`)。默认 null。 */
    protected open fun getSessionCookie(domain: String): String? = null

    /** 移除 url 所属域名中指定 key 的 cookie (app 端委托 `CookieManager.removeCookie(url, key)`)。默认 no-op。 */
    protected open fun removeCookie(url: String, key: String) {}
}
