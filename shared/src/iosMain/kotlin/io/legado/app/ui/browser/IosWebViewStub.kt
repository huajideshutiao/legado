@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.help.http.CookieStoreProviders
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.setValue
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS 真实 WebView slot: WKWebView 经 UIKitView 嵌入 Compose。
 *
 * 对照 app 端 AndroidWebView: JS/domStorage + onPageFinished cookie 同步。
 * WKWebView 默认启用 JS / DOM storage / mixed content, 无需显式配置;
 * didFinish 时取 WKHTTPCookieStore.allCookies, 转 "k=v; k=v" 同步到
 * [CookieStoreProviders] 供 OkHttp 复用登录态 (等价 Android CookieManager.getCookie),
 * 并回调 [WebViewCallbacks.onPageFinished] (CF 挑战检测/验证回传由 WebViewRoute 处理)。
 *
 * 加载语义对照 AndroidWebView: [WebViewConfig.headerMap] 注入 loadRequest 请求头,
 * [WebViewConfig.html] 非空时 loadHTMLString (POST body/data: 解包/原始 HTML);
 * [WebViewCallbacks.host] 暴露 evaluateJavaScript/canGoBack/goBack 供路由执行验证回传。
 *
 * delegate 弱引用: WKWebView.navigationDelegate 是 weak, 用 remember 持有 navDelegate
 * 强引用防 GC 回收 (模式同 IosFilePicker activeDelegates)。
 */
@Composable
fun IosWebViewSlot(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    val webView = remember {
        WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
    }
    val navDelegate = remember { WebViewNavDelegate(callbacks) }

    SideEffect {
        webView.navigationDelegate = navDelegate
        callbacks.host = WebViewHostImpl(webView)
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val html = config.html
            if (html == null) {
                val cur = view.URL?.absoluteString
                if (cur != config.url) {
                    loadWithHeaders(view, config)
                }
            } else if (view.tag != HTML_LOADED_TAG) {
                view.tag = HTML_LOADED_TAG
                view.loadHTMLString(
                    html,
                    baseURL = config.url.takeIf { it.isNotBlank() }
                        ?.let { NSURL.URLWithString(it) },
                )
            }
        },
    )
}

/** 带 headerMap 的请求加载 (loadRequest 请求头注入, 对照 Android loadUrl(url, headers))。 */
private fun loadWithHeaders(webView: WKWebView, config: WebViewConfig) {
    val nsUrl = NSURL.URLWithString(config.url) ?: return
    val request = NSMutableURLRequest(uRL = nsUrl)
    config.headerMap.forEach { (key, value) -> request.setValue(value, forHTTPHeaderField = key) }
    webView.loadRequest(request)
}

/** html 模式已加载标记 (避免每次重组重复 loadHTMLString); UIView.tag 为 NSInteger (Long)。 */
private const val HTML_LOADED_TAG = 1L

/**
 * WKNavigationDelegate: 仅监听 didFinish, 取 WKHTTPCookieStore cookie 同步到业务层 store,
 * 并回调 [WebViewCallbacks.onPageFinished]。
 */
private class WebViewNavDelegate(
    private val callbacks: WebViewCallbacks,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        val url = webView.URL?.absoluteString ?: return
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            // 拼接 "k=v; k=v" 形式 (对照 Android CookieManager.getCookie 输出格式)
            val cookieString = cookies.orEmpty().joinToString("; ") {
                val cookie = it as NSHTTPCookie
                "${cookie.name}=${cookie.value}"
            }
            if (cookieString.isNotBlank()) {
                CookieStoreProviders.get()?.replaceCookie(url, cookieString)
            }
        }
        callbacks.onPageFinished?.invoke(url)
    }
}

/** [WebViewHost] 的 iOS 实现, 直通 WKWebView。 */
private class WebViewHostImpl(private val webView: WKWebView) : WebViewHost {
    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavaScript(script) { result, error ->
            onResult(
                when {
                    error != null -> null
                    // 布尔结果 (如 `!!window._cf_chl_opt`) 归一为 "true"/"false", 对齐 Android
                    result is Boolean -> if (result) "true" else "false"
                    result is NSNumber -> if (result.boolValue) "true" else "false"
                    else -> result?.toString()
                }
            )
        }
    }

    override fun canGoBack(): Boolean = webView.canGoBack

    override fun goBack() {
        webView.goBack()
    }

    override fun getUrl(): String? = webView.URL?.absoluteString

    override fun reload() {
        webView.reload()
    }
}
