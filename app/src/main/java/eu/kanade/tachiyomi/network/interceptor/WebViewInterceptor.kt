// Copyright The Mihon Authors. Apache-2.0.
// 移植自 mihon core/common .../network/interceptor/WebViewInterceptor.kt;
// MR.strings toast 换为 AppLog, DeviceUtil 判定内联
// 挑战状态机 (触发判定/per-host 去重/重发) 收在共享基类 ChallengeInterceptorBase,
// 本类只提供 WebView 求解通道 (可用性/预热/请求头过滤/创建)。
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import io.legado.app.constant.AppLog
import io.legado.app.help.http.WebViewUtil
import io.legado.app.help.http.setDefaultSettings
import io.legado.app.help.http.setUserAgent
import okhttp3.Headers
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : ChallengeInterceptorBase() {

    // 预热 WebView (首次 WebSettings.getDefaultUserAgent 很慢, 提前到首个挑战请求之外);
    // MIUI / Samsung(API 31) 预热可崩溃 chromium#1279562, 跳过仅损失首次挑战速度
    private val initWebView by lazy {
        val manufacturer = Build.MANUFACTURER
        if (manufacturer.equals("Xiaomi", true) ||
            (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && manufacturer.equals("Samsung", true))
        ) {
            return@lazy
        }

        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            // WebView/Chrome 更新中可能抛异常, 忽略
        }
    }

    override fun isEngineAvailable(): Boolean = WebViewUtil.supportsWebView(context)

    override fun onEngineUnavailable() {
        AppLog.put("WebView 不可用, 无法通过 Cloudflare 挑战")
    }

    override fun prepareEngine() {
        initWebView
    }

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            // 不安全头会让 WebView 抛 net::ERR_INVALID_ARGUMENT
            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            // UA 为空时 Chromium WebView 会重置为默认值
            setUserAgent(request.header("User-Agent") ?: defaultUserAgentProvider())
        }
    }
}

// Based on [IsRequestHeaderSafe] in
// https://source.chromium.org/chromium/chromium/src/+/main:services/network/public/cpp/header_util.cc
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}
private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
)
