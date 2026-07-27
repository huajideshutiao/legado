package io.legado.app.help.http

import io.legado.app.utils.NetworkUtils
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面/JVM 端 [CookieStoreProvider] 实现 (基于 [java.net.CookieManager])。
 *
 * # 实现
 * - **持久 cookie**: 用 [CookieManager] 的 [java.net.CookieStore] 存储, 按 domain 构造
 *   [URI] 作为 key (与 app 端按二级域名存储语义对齐)。
 * - **session cookie**: 用 [ConcurrentHashMap] (domain -> cookie 字符串) 存储, 对应 app 端
 *   CacheManager 的 `"${domain}_session_cookie"` 内存缓存。
 * - **domain 提取**: 复用 commonMain 的 [NetworkUtils.getSubDomain] (jvmAndAndroidMain actual),
 *   与 app 端 [io.legado.app.help.http.CookieStore] 保持一致的二级域名匹配规则。
 *
 * # 与 app 端的行为差异
 * - **不持久化到数据库**: app 端 cookie 存 Room (appDb.cookieDao), 本实现进程内存储, 重启即丢
 *   (与 [io.legado.desktop.http.DesktopCookieJarBridge] 一致, 桌面端简化策略)。
 * - **不同步 WebView**: 桌面端无 WebView, app 端 `setCookie` 中的 android.webkit.CookieManager
 *   同步逻辑不适用 (applyToWebView 为 Android 专属, 不在 [CookieStoreProvider] 接口内)。
 * - **setCookie 覆盖语义**: 与 app 端一致, 先清除 domain 旧 cookie 再写入新 cookie (非合并)。
 *
 * # 注册
 * 通过 [registerDefaultJvmCookieStoreProvider] 在 desktop Main 启动早期注册一次。
 *
 * 模式参考 [io.legado.app.help.DirectLinkUploadStoreProviders] (各平台独立注册)。
 */
class JvmCookieStoreProvider : CookieStoreProvider {

    // 持久 cookie 底层存储 (java.net.CookieManager, 进程内)
    private val cookieManager = CookieManager()
    private val store = cookieManager.cookieStore

    // session cookie (进程内, 对应 app 端 CacheManager "${domain}_session_cookie")
    private val sessionStore = ConcurrentHashMap<String, String>()

    private fun domainToUri(domain: String): URI = URI.create("http://$domain")

    override fun setCookie(url: String, cookie: String?) {
        if (!url.startsWith("http")) return
        val domain = NetworkUtils.getSubDomain(url)
        val uri = domainToUri(domain)
        val cookieStr = cookie ?: ""
        if (cookieStr.isBlank()) return
        // app 端 setCookie 语义: 覆盖整个 domain 的 cookie 字符串 (insert upsert)
        // 先清除该 domain 旧 cookie, 再写入新 cookie, 保证覆盖语义
        store.get(uri).toList().forEach { store.remove(uri, it) }
        cookieStr.split(';').forEach { c ->
            val trimmed = c.trim()
            if (trimmed.isNotEmpty()) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val name = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    val httpCookie = HttpCookie(name, value)
                    httpCookie.domain = domain
                    // 设为持久 (不随 JVM 退出自动清除, 但进程内存储重启仍丢失)
                    httpCookie.maxAge = Long.MAX_VALUE
                    try {
                        store.add(uri, httpCookie)
                    } catch (_: Exception) {
                        // 单个 cookie 解析失败不影响其余
                    }
                }
            }
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        // 与 app 端 CookieStore.replaceCookie 语义一致: 合并同名 key, 新 cookie 覆盖旧值
        val oldCookie = getCookieNoSession(url)
        if (oldCookie.isEmpty()) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie)
            cookieMap.putAll(cookieToMap(cookie))
            mapToCookie(cookieMap)?.let { setCookie(url, it) }
        }
    }

    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        val persistent = getPersistentCookies(domain)
        val session = sessionStore[domain] ?: ""
        return mergeCookies(persistent, session) ?: ""
    }

    override fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        if (cookie.isBlank()) return ""
        // 与 app 端 CookieStore.getKey 一致: 直接解析不转 Map
        cookie.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == key) {
                return pair.substring(index + 1).trim()
            }
        }
        return ""
    }

    override fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        val uri = domainToUri(domain)
        // 清除持久 cookie
        store.get(uri).toList().forEach { store.remove(uri, it) }
        // 清除 session cookie
        sessionStore.remove(domain)
    }

    override fun removeCookie(url: String, key: String) {
        val domain = NetworkUtils.getSubDomain(url)
        // session cookie: 按 key 移除
        sessionStore[domain]?.let { session ->
            val map = cookieToMap(session)
            if (map.remove(key) != null) {
                mapToCookie(map)?.let { sessionStore[domain] = it } ?: sessionStore.remove(domain)
            }
        }
        // 持久 cookie: 按 name 移除
        val uri = domainToUri(domain)
        store.get(uri).toList().forEach { c ->
            if (c.name == key) store.remove(uri, c)
        }
    }

    override fun getCookieNoSession(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        return getPersistentCookies(domain)
    }

    override fun getSessionCookie(domain: String): String? = sessionStore[domain]

    override fun clear() {
        store.removeAll()
        sessionStore.clear()
    }

    /** 从 [store] 取指定 domain 的未过期持久 cookie, 拼成 "k1=v1; k2=v2" 字符串。 */
    private fun getPersistentCookies(domain: String): String {
        val uri = domainToUri(domain)
        return store.get(uri)
            .filter { !it.hasExpired() }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }
}

/**
 * 桌面/JVM 端默认 [CookieStoreProvider] 注册入口。
 *
 * 在 desktop Main 启动早期调用一次 (对齐 [io.legado.desktop.http.registerDesktopHttpProvider]
 * 中 `CookieJarBridgeHolder.register` 的时机), 把 [JvmCookieStoreProvider] 注册到 [CookieStoreProviders]。
 *
 * 对应 app 端 `registerAndroidCookieStoreProvider` / iOS `registerDefaultIosCookieStoreProvider` /
 * 鸿蒙 `registerDefaultOhosCookieStoreProvider`。
 */
fun registerDefaultJvmCookieStoreProvider() {
    CookieStoreProviders.register(JvmCookieStoreProvider())
}
