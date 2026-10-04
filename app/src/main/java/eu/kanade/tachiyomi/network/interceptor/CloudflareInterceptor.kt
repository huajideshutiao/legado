// Copyright The Mihon Authors. Apache-2.0.
// 移植自 mihon core/common .../network/interceptor/CloudflareInterceptor.kt (main);
// 差异: toast 换 AppLog, 新增同 host 并发挑战去重排队, 挑战成功后 cookie 回写宿主 CookieStore
package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.isOutdated
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

// ChallengeCookieResolver 已下沉 data 模块 (eu.kanade.tachiyomi.network.interceptor), 本文件引用不变。

class CloudflareInterceptor(
    private val context: Context,
    private val cookieResolver: ChallengeCookieResolver,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    // 同 host 挑战去重: 并发 403 只允许一个请求开 WebView 解挑战, 其余排队复用结果
    // (mihon main 无此处理, 每个请求各开一个 WebView; 宿主书源抓取并发量大, 需去重)
    private val hostStates = ConcurrentHashMap<String, HostChallengeState>()

    override fun shouldIntercept(response: Response): Boolean {
        // cf-mitigated: challenge 是 Cloudflare 官方挑战检测方式
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        val state = hostStates.computeIfAbsent(request.url.host) { HostChallengeState() }
        // 本请求遭遇挑战的时刻, 用于区分排队可复用的解与更早轮次的旧解
        val challengeStart = SystemClock.elapsedRealtime()

        response.close()
        try {
            // 超过挑战等待上限仍未拿到锁, 视同绕过失败 (排队者不再重复开 WebView)
            if (!state.lock.tryLock(CHALLENGE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw CloudflareBypassException()
            }
            try {
                if (state.lastAttemptEnd >= challengeStart) {
                    // 本次挑战窗口内已有请求完成解挑战, 直接复用其结果:
                    // 成功 → 带新 cookie 重发; 失败 → 同样抛异常, 避免排队者串行重试
                    if (!state.lastSolveOk) {
                        throw CloudflareBypassException()
                    }
                } else {
                    val oldCookie = cookieResolver.get(request.url)
                        .firstOrNull { it.name == "cf_clearance" }
                    cookieResolver.remove(request.url, COOKIE_NAMES, 0)
                    try {
                        resolveWithWebView(request, oldCookie)
                        state.lastSolveOk = true
                        syncCookiesToHostStore(request.url)
                    } finally {
                        state.lastAttemptEnd = SystemClock.elapsedRealtime()
                    }
                }
            } finally {
                state.lock.unlock()
            }

            return chain.proceed(request)
        }
        // OkHttp 的 enqueue 只处理 IOException, 统一包装避免崩溃整个 app
        catch (e: CloudflareBypassException) {
            throw IOException(CLOUDFLARE_BYPASS_FAILURE_MESSAGE, e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // OkHttp 不支持异步拦截器, 需锁住当前线程直至 WebView 解出挑战
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest)

            webview.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun interactiveDetected() {
                        // 挑战需人工交互, 无法静默通过, 放弃
                        latch.countDown()
                    }
                },
                "mihon",
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieResolver.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl) {
                        if (!challengeFound) {
                            // 首次请求未返回挑战页, 放弃
                            latch.countDown()
                        } else {
                            // 监听 interactiveBegin 事件
                            view.evaluateJavascript(
                                """
                                    addEventListener("message", ({data}) => {
                                        if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                            mihon.interactiveDetected();
                                        }
                                    })
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.responseHeaders["cf-mitigated"] == "challenge") {
                            // 找到 Cloudflare 挑战页
                            challengeFound = true
                        } else {
                            // 非挑战错误, 放弃
                            latch.countDown()
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        // 未能绕过 Cloudflare
        if (!cloudflareBypassed) {
            if (isWebViewOutdated) {
                // 上游为 information_webview_outdated toast; 宿主无 moko 资源, 只记日志
                AppLog.put("系统 WebView 版本过低, 可能无法通过 Cloudflare 挑战")
            }

            throw CloudflareBypassException()
        }
    }

    /**
     * 挑战成功后把 webkit CookieManager 中的 cookie (含 cf_clearance) 回写宿主 CookieStore:
     * replaceCookie 为合并语义 (同名覆盖), 不丢书源既有 cookie; 兼容层 AndroidCookieJar
     * 与书源栈 SharedCookieJarBridge 均读 CookieStore, 两栈同样生效。
     */
    private fun syncCookiesToHostStore(url: HttpUrl) {
        try {
            val cookieStr = CookieManager.getInstance().getCookie(url.toString()) ?: return
            CookieStore.replaceCookie(url.toString(), cookieStr)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // 持久化失败不影响本次重发, 兼容层仍持有内存中的 cookie
        }
    }
}

// 与上游 awaitFor30Seconds 对齐, 排队锁的上限留出余量
private const val CHALLENGE_WAIT_SECONDS = 35L

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

// 上游为 MR.strings.information_cloudflare_bypass_failure; 宿主无 moko 资源
private const val CLOUDFLARE_BYPASS_FAILURE_MESSAGE = "Failed to bypass Cloudflare challenge"

private class CloudflareBypassException : Exception()

private class HostChallengeState {

    /** 公平锁: 先触发挑战的请求先解 */
    val lock = ReentrantLock(true)

    /** 最近一次解挑战尝试结束时刻 (elapsedRealtime); 请求以它判定排队结果是否属于本次挑战窗口 */
    @Volatile
    var lastAttemptEnd = Long.MIN_VALUE

    /** 最近一次解挑战是否成功 */
    @Volatile
    var lastSolveOk = false
}
