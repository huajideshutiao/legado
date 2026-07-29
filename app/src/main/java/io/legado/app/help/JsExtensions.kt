package io.legado.app.help

import androidx.annotation.Keep
import com.script.jsdispatch.JsApi
import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.model.script.jsContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 *
 * 浏览器验证流程 (startBrowser/startBrowserAwait/getVerificationCode) /
 * UI 反馈 (toast/longToast/getWebViewUA/openUrl) 与
 * 文件操作 (getFile/readFile/readTxtFile/deleteFile/unArchiveFile/getTxtInFolder/
 * downloadFile/cacheFile/importScript 等) 已下沉 [JsExtensionsCommon] (shared/commonMain),
 * 本接口仅保留 jvmAndAndroid 专属方法 (jsoup Connection.Response /
 * Android Intent / Android Activity + JsFn)。
 */
@Keep
@JsApi
@Suppress("unused")
interface JsExtensions : JsEncodeUtilsAndroid, JsExtensionsCommon {

    private val context: CoroutineContext
        get() = jsContext.coroutineContext ?: EmptyCoroutineContext

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
