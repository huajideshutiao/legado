package io.legado.app.help

import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.model.script.jsContext
import org.jsoup.Connection
import org.jsoup.Jsoup

/**
 * JVM 半区 (Android/desktop) JS 扩展面: 在 [JsExtensionsCommon] 基础上补 jsoup get/head/post
 * (书源 JS 的 java.get/post/head 核心 API)。原 app 端 [JsExtensions] 与 desktop 端
 * [DesktopJsExtensions] 各维护一份相同实现, 统一下沉至此; 加密工厂面见 [JsEncodeUtilsDefaults]。
 * native 端无 jsoup, 本接口仅 jvmAndAndroidMain 提供 (与现状一致)。
 */
@Suppress("unused")
interface JsExtensionsJvm : JsEncodeUtilsDefaults, JsExtensionsCommon {

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.GET)
                .execute()
        }
        return response
    }

    /**
     * js实现重定向拦截,网络访问head,不返回Response Body更省流量
     */
    fun head(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.HEAD)
                .execute()
        }
        return response
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .requestBody(body)
                .headers(requestHeaders)
                .method(Connection.Method.POST)
                .execute()
        }
        return response
    }
}
