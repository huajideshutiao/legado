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
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
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
 * [CookieStoreProviders] 供 OkHttp 复用登录态 (等价 Android CookieManager.getCookie)。
 *
 * delegate 弱引用: WKWebView.navigationDelegate 是 weak, 用 remember 持有 navDelegate
 * 强引用防 GC 回收 (模式同 IosFilePicker activeDelegates)。
 */
@Composable
fun IosWebViewSlot(url: String, modifier: Modifier = Modifier) {
    val webView = remember {
        WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
    }
    val navDelegate = remember { WebViewNavDelegate() }

    SideEffect {
        webView.navigationDelegate = navDelegate
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val cur = view.URL?.absoluteString
            if (cur != url) {
                NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest(it)) }
            }
        },
    )
}

/**
 * WKNavigationDelegate: 仅监听 didFinish, 取 WKHTTPCookieStore cookie 同步到业务层 store。
 */
private class WebViewNavDelegate : NSObject(), WKNavigationDelegateProtocol {
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
    }
}
