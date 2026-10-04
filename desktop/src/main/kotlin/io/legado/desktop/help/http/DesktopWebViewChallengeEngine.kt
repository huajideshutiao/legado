package io.legado.desktop.help.http

import io.legado.app.exception.NoStackTraceException
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.http.DesktopChallengeEngine

/**
 * [DesktopChallengeEngine] 的内嵌浏览器实现: 转发 [DesktopWebViewEngines]
 * (Windows = WebView2 Runtime, Linux = WebKitGTK, macOS = WKWebView,
 * 平台探测结果由引擎侧缓存)。
 *
 * 书源栈 CF 挑战求解与 Android 端 eu.kanade.tachiyomi.network.interceptor.
 * CloudflareInterceptor 语义对齐; 引擎缺失时无解, 调用方按绕过失败处理。
 */
class DesktopWebViewChallengeEngine : DesktopChallengeEngine {

    override fun isAvailable(): Boolean = DesktopWebViewEngines.isAvailable()

    override suspend fun awaitChallengeCookies(
        url: String,
        headerMap: Map<String, String>?,
        delayTimeMs: Long,
        timeoutMs: Long,
        isSolved: (cookies: String) -> Boolean,
    ): String {
        val engine = DesktopWebViewEngines.get()
            ?: throw NoStackTraceException("无可用内嵌浏览器引擎")
        return engine.awaitChallengeCookies(url, headerMap, delayTimeMs, timeoutMs, isSolved)
    }
}
