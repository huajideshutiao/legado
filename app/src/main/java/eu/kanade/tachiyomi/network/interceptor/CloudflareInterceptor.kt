// Copyright The Mihon Authors. Apache-2.0.
// 移植自 mihon core/common .../network/interceptor/CloudflareInterceptor.kt (main);
// 差异: toast 换 AppLog, 挑战状态机 (同 host 去重/挑战窗口/重发) 收在共享基类
// ChallengeInterceptorBase (Android 与桌面同一份), 本类只提供 WebView 求解通道。
//
// 与上游的运行期差异 (主动偏离, 依据):
// 1. 不按"首个加载完成的文档是不是挑战页"提前放弃 —— 外层拦截器已用官方挑战头
//    (cf-mitigated: challenge) 判定过这确实是挑战, 内层再猜一次会在 1~2 秒内误失败
//    (CF 的 JS 挑战页常以 200 返回, 不会触发 HTTP 错误回调, 猜不出“挑战页”)。
// 2. 非 GET 请求改用站点根页面加载 —— WebView 只能发 GET, 直接加载别的请求方式的
//    地址常落到一个不返挑战的端点 (与上游同一处理)。
//
// 覆盖范围: 仅 Android (本模块) 与桌面 (:desktop-core) 挂 CF 自动解挑战; iOS / 鸿蒙的共享
// HTTP 栈没有本拦截器, 遇到挑战仍按原版行为把挑战响应交回调用方。
package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
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
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieResolver: ChallengeCookieResolver,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    /**
     * 解出的 cookie 能否随重发请求带上。
     *
     * [cookieResolver] 是宿主 OkHttp CookieJar (插件栈 client 自带 AndroidCookieJar) 时,
     * OkHttp 自己会把 webkit CookieManager 里的新 cf_clearance 合进重发请求; 书源栈的
     * BookSourceChallengeCookieResolver 且 client 无 CookieJar, cookie 只在请求带
     * CookieJar 伪头时由 cookie bridge 合入 —— 未启用 cookie 的书源解了也带不上。
     */
    override fun canCarrySolvedCookies(request: Request): Boolean =
        cookieResolver is CookieJar || super.canCarrySolvedCookies(request)

    override fun oldClearance(request: Request): String? =
        cookieResolver.get(request.url)
            .firstOrNull { it.name == COOKIE_CLEARANCE }
            ?.value

    override fun clearClearance(request: Request) {
        cookieResolver.remove(request.url, COOKIE_NAMES, 0)
    }

    override fun solve(request: Request, oldClearance: String?) {
        resolveWithWebView(request, oldClearance)
        syncCookiesToHostStore(request.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldClearance: String?) {
        // OkHttp 不支持异步拦截器, 需锁住当前线程直至 WebView 解出挑战
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        // WebView 只能发 GET: 非 GET 请求改在站点根页面获取放行凭证 (凭证覆盖整站)
        val challengeUrl = if (originalRequest.method == "GET") {
            origRequestUrl
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
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
                            .firstOrNull { it.name == COOKIE_CLEARANCE }
                            .let { it != null && it.value != oldClearance }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                        return
                    }
                    // 监听 interactiveBegin 事件 (需人工交互时立刻放弃, 不必等满求解窗)
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

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    // 挑战页以 200 返回时不会走到这里; 只有主文档返回非挑战错误才没有可等的东西
                    if (request?.isForMainFrame == true &&
                        errorResponse?.responseHeaders?.get("cf-mitigated") != "challenge"
                    ) {
                        AppLog.put(
                            "挑战求解放弃: 主文档返回非挑战错误 ${errorResponse?.statusCode}\n$challengeUrl"
                        )
                        latch.countDown()
                    }
                }
            }

            webview.loadUrl(challengeUrl, headers)
        }

        latch.awaitChallengeWindow()

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
            } else {
                AppLog.put("挑战求解放弃: ${CHALLENGE_TIMEOUT_MS / 1000} 秒求解窗耗尽\n$challengeUrl")
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

private val COOKIE_NAMES = listOf("cf_clearance")
