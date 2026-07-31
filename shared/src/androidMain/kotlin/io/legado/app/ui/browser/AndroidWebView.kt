package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.http.CookieStoreProviders

/**
 * Android 端 WebView 平台实现 (供 [LocalWebViewSlot] 注入)。
 *
 * 复用下沉的 [VisibleWebView] (原 app 端, 保持 onWindowVisibilityChanged 强制 VISIBLE 语义);
 * WebSettings 内联基础配置 (js/domStorage/mixedContent), 不复制 app 端 [WebViewUtil]
 * (其依赖 AppCompatActivity/Download/FileAssociationFragment, 留 app 端);
 * onPageFinished 同步 WebView cookie → [CookieStoreProviders] (供 OkHttp 复用登录态)。
 *
 * 对照 app 端原 WebViewActivity (isLogin) 的 cookie 持久化: 取 android.webkit.CookieManager
 * 当前 url 的 cookie, replaceCookie 合并到业务层 store。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AndroidWebView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VisibleWebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webViewClient = AndroidWebViewClient
            }
        },
        update = { web ->
            // tag 判等避免无关重组触发重复 loadUrl
            if (web.tag != url) {
                web.tag = url
                web.loadUrl(url)
            }
        },
    )
}

/** onPageFinished 同步 WebView cookie 到业务层 store。 */
private object AndroidWebViewClient : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url ?: return
        val webCookie = CookieManager.getInstance().getCookie(url)
        if (!webCookie.isNullOrEmpty()) {
            CookieStoreProviders.get()?.replaceCookie(url, webCookie)
        }
    }
}

/**
 * 可见态 WebView: 覆盖 onWindowVisibilityChanged 强制 VISIBLE,
 * 避免嵌入 Fragment/ViewPager 时被 visibility 隐藏暂停渲染。
 *
 * 自 app 端下沉至 shared androidMain (app 端 [io.legado.app.ui.book.rss.ReadRssActivity]
 * 等仍按同包 io.legado.app.ui.browser 引用, import 不变)。
 */
class VisibleWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(VISIBLE)
    }
}
