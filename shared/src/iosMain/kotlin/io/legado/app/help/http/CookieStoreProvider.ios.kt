package io.legado.app.help.http

import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.concurrent.newConcurrentMap
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL

/**
 * iOS 端 [CookieStoreProvider] 真实实现 (基于 [NSHTTPCookieStorage.sharedHTTPCookieStorage])。
 *
 * 替代原 nativeMain 共用的 [NativeCookieStoreProvider] stub, 让 iOS 端 cookie 读写真实落盘到
 * 系统级 NSHTTPCookieStorage, 与 desktop [JvmCookieStoreProvider] (java.net.CookieManager) /
 * app 端 CookieStore (appDb.cookieDao) 行为对齐。
 *
 * # 实现
 * - **持久 cookie**: 用 [NSHTTPCookieStorage.sharedHTTPCookieStorage] 存储, 按 [NetworkUtils.getSubDomain]
 *   归一化的二级域名构造 NSURL 作为查询 key (与 JVM 端 domainToUri / app 端按二级域名存储语义对齐)。
 * - **session cookie**: 用 [newConcurrentMap] (domain -> cookie 字符串) 内存存储, 对应 app 端
 *   CacheManager 的 "${domain}_session_cookie"; 本接口无 setSessionCookie, 故仅作占位
 *   (与 [JvmCookieStoreProvider] 的 sessionStore 一致, 结构对齐便于后续扩展)。
 * - **domain 提取**: 复用 commonMain 的 [NetworkUtils.getSubDomain] (nativeMain actual)。
 *
 * # 与 app/desktop 的行为差异
 * - **setCookie 覆盖语义**: 与 app/JVM 一致, 先 [NSHTTPCookieStorage.deleteCookie] 清除该 domain
 *   旧 cookie, 再 [NSHTTPCookieStorage.setCookies] 写入新 cookie (非合并)。
 * - **不同步 WebView**: iOS 端无 Android WebView, app 端 android.webkit.CookieManager 同步不适用。
 * - **cookie 创建**: 用 [NSHTTPCookie.cookiesWithHeaderFields] 解析 "k=v" pair + Domain/Path 属性,
 *   复用 Foundation 自带 parser (比手动构造 properties 更健壮)。
 *
 * # 注册
 * 通过 [registerDefaultIosCookieStoreProvider] 在 [io.legado.app.help.config.registerIosProviders]
 * 启动早期调用一次。对应 desktop `registerDefaultJvmCookieStoreProvider` /
 * app `registerAndroidCookieStoreProvider` / 鸿蒙 `registerDefaultOhosCookieStoreProvider`
 * (鸿蒙仍用 nativeMain [NativeCookieStoreProvider] stub)。
 *
 * 模式参考 [JvmCookieStoreProvider]。
 */
class IosCookieStoreProvider : CookieStoreProvider {

    // 系统级共享 cookie 存储 (进程内单例, iOS 自动管理)
    private val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage

    // session cookie 内存存储 (对应 app CacheManager "${domain}_session_cookie")
    private val sessionStore = newConcurrentMap<String, String>()

    // 按 domain 构造查询 URL (与 JVM domainToUri 对齐, 用 http scheme)
    private fun domainToUrl(domain: String): NSURL =
        NSURL.URLWithString("http://$domain")!!

    override fun setCookie(url: String, cookie: String?) {
        if (!url.startsWith("http")) return
        val domain = NetworkUtils.getSubDomain(url)
        val cookieStr = cookie ?: ""
        if (cookieStr.isBlank()) return
        val nsUrl = domainToUrl(domain)
        // 覆盖语义: 先清除该 domain 旧 cookie (与 app/JVM 一致)
        storage.cookiesForURL(nsUrl)?.forEach { storage.deleteCookie(it) }
        // 解析并写入新 cookie (复用 NSHTTPCookie headerFields parser)
        val cookies = mutableListOf<NSHTTPCookie>()
        cookieStr.split(';').forEach { c ->
            val trimmed = c.trim()
            if (trimmed.isNotEmpty()) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    // 构造 Set-Cookie header, 附 Domain/Path 属性让 NSHTTPCookie 正确归域
                    // 显式 Map<Any?, Any?>: NSHTTPCookie.cookiesWithHeaderFields 的 headerFields
                    // 参数在 Kotlin/Native 桥接为 NSDictionary -> Map<Any?, Any?> (K 不变, 需精确匹配)
                    val headerFields = mapOf<Any?, Any?>("Set-Cookie" to "$trimmed; Domain=$domain; Path=/")
                    NSHTTPCookie.cookiesWithHeaderFields(headerFields, nsUrl)?.forEach { cookies.add(it) }
                }
            }
        }
        if (cookies.isNotEmpty()) {
            storage.setCookies(cookies, nsUrl, null)
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        // 与 app/JVM 一致: 合并同名 key, 新 cookie 覆盖旧值
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
        // 与 app/JVM 一致: 直接解析不转 Map
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
        val nsUrl = domainToUrl(domain)
        // 清除持久 cookie
        storage.cookiesForURL(nsUrl)?.forEach { storage.deleteCookie(it) }
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
        val nsUrl = domainToUrl(domain)
        storage.cookiesForURL(nsUrl)?.forEach { c ->
            if (c.name == key) storage.deleteCookie(c)
        }
    }

    override fun getCookieNoSession(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)
        return getPersistentCookies(domain)
    }

    override fun getSessionCookie(domain: String): String? = sessionStore[domain]

    override fun clear() {
        storage.cookies?.forEach { storage.deleteCookie(it) }
        sessionStore.clear()
    }

    /** 取指定 domain 的持久 cookie, 拼成 "k1=v1; k2=v2" (与 JVM getPersistentCookies 对齐)。 */
    private fun getPersistentCookies(domain: String): String {
        val nsUrl = domainToUrl(domain)
        return storage.cookiesForURL(nsUrl)
            ?.joinToString("; ") { "${it.name}=${it.value}" }
            ?: ""
    }
}

/**
 * iOS 端默认 [CookieStoreProvider] 注册入口 (真实实现)。
 *
 * 在 [io.legado.app.help.config.registerIosProviders] 中调用一次, 把 [IosCookieStoreProvider]
 * (基于 [NSHTTPCookieStorage.sharedHTTPCookieStorage]) 注册到 [CookieStoreProviders],
 * 替代原 nativeMain 共用的 [NativeCookieStoreProvider] stub。
 *
 * 对应 app 端 `registerAndroidCookieStoreProvider` /
 * desktop `registerDefaultJvmCookieStoreProvider` /
 * 鸿蒙 `registerDefaultOhosCookieStoreProvider` (鸿蒙仍用 nativeMain stub)。
 */
fun registerDefaultIosCookieStoreProvider() {
    CookieStoreProviders.register(IosCookieStoreProvider())
}
