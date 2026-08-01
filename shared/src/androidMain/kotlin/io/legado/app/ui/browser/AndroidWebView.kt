package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank

/**
 * Android 端 WebView 平台实现 (供 [LocalWebViewSlot] 注入)。
 *
 * 复用下沉的 [VisibleWebView] (原 app 端, 保持 onWindowVisibilityChanged 强制 VISIBLE 语义);
 * WebSettings 对齐原 app 端 `WebViewUtil.applyCommonSettings`;
 * onPageFinished 同步 WebView cookie → [CookieStoreProviders] (供 OkHttp 复用登录态)
 * 并回调 [WebViewCallbacks.onPageFinished] (CF 挑战检测/验证回传由 WebViewRoute 处理)。
 *
 * 加载语义对照原 WebViewActivity.initWebView / ReadRssActivity.initWebView:
 * - 加载前把业务层 cookie 灌进 WebView (原 `CookieManager.applyToWebView(url)`);
 * - [WebViewConfig.headerMap] 注入 loadUrl(url, headers), 含 User-Agent 头 (原
 *   `headerMap[AppConst.UA_NAME]` → settings.userAgentString);
 * - [WebViewConfig.html] 非空时 loadDataWithBaseURL (POST body/data: 解包/RSS clHtml 正文);
 * - [WebViewCallbacks.shouldOverrideUrl] 接原 `BaseWebViewClient.interceptUrl` (书源跳转拦截 JS);
 * - [WebViewCallbacks.onReceivedTitle] / [WebViewCallbacks.onFullScreenChanged] 接原
 *   `CommonWebChromeClient` 的标题与 `<video>` 全屏 (custom view 铺满本组件自己的容器)。
 *
 * 未迁移项: 长按图片保存与下载监听 (原 WebViewUtil.setupImageLongClick / setupDownloadListener),
 * 依赖 Activity 级目录选择器, 留在 app 端。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AndroidWebView(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    // 全屏 custom view 由本组件自己托管: 有值时铺满整个 slot, 盖住 WebView
    var customView by remember { mutableStateOf<View?>(null) }
    val callbacksRef by rememberUpdatedState(callbacks)

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VisibleWebView(ctx).apply {
                    applyCommonSettings(settings)
                    webViewClient = AndroidWebViewClient(callbacksRef)
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            callbacksRef.onReceivedTitle?.invoke(title)
                        }

                        override fun onShowCustomView(
                            view: View?,
                            callback: CustomViewCallback?,
                        ) {
                            customView = view
                            callbacksRef.onFullScreenChanged?.invoke(true)
                        }

                        override fun onHideCustomView() {
                            customView = null
                            callbacksRef.onFullScreenChanged?.invoke(false)
                        }
                    }
                    callbacksRef.host = WebViewHostImpl(this)
                }
            },
            update = { web ->
                config.headerMap[AppConst.UA_NAME]?.let { web.settings.userAgentString = it }
                // tag 判等避免无关重组触发重复加载 (tag 持最终加载 url, html 模式与 loadUrl 模式互斥同源)
                val loadUrl = if (config.html.isNullOrEmpty()) config.url else ""
                if (web.tag != loadUrl) {
                    web.tag = loadUrl
                    // 原 CookieManager.applyToWebView: 业务层 cookie → WebView, 登录态才带得过去
                    applyCookiesToWebView(config.url)
                    if (config.html.isNullOrEmpty()) {
                        web.loadUrl(config.url, HashMap(config.headerMap))
                    } else {
                        web.loadDataWithBaseURL(
                            config.url, config.html, "text/html", "utf-8", config.url
                        )
                    }
                }
            },
        )
        customView?.let { view ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { container ->
                    if (container.childCount == 0 || container.getChildAt(0) !== view) {
                        container.removeAllViews()
                        (view.parent as? ViewGroup)?.removeView(view)
                        container.addView(view)
                    }
                },
                onRelease = { it.removeAllViews() },
            )
        }
    }
}

/** 对照原 app 端 WebViewUtil.applyCommonSettings。 */
@SuppressLint("SetJavaScriptEnabled")
private fun applyCommonSettings(settings: WebSettings) {
    settings.apply {
        javaScriptEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        domStorageEnabled = true
        allowContentAccess = true
        builtInZoomControls = true
        displayZoomControls = false
        setDarkeningAllowed(AppConfigProviders.get().isNightTheme)
    }
}

/** 下沉自 app 端 `WebSettings.setDarkeningAllowed`: Q 以上走算法反色, 以下退回 forceDark。 */
@SuppressLint("RequiresFeature")
private fun WebSettings.setDarkeningAllowed(allow: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val applied = runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allow)
        }.isSuccess
        if (applied) return
    }
    if (!allow) return
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
        @Suppress("DEPRECATION")
        WebSettingsCompat.setForceDarkStrategy(
            this,
            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
        )
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        @Suppress("DEPRECATION")
        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
    }
}

/**
 * 业务层 cookie → WebView (原 `io.legado.app.help.http.CookieManager.applyToWebView`)。
 *
 * 不在这里 removeSessionCookies —— 那是全局操作, 会影响别的页面。
 */
private fun applyCookiesToWebView(url: String) {
    val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
    val cookies = CookieStoreProviders.get()?.getCookie(url)?.splitNotBlank(";") ?: return
    if (cookies.isEmpty()) return
    val webManager = CookieManager.getInstance()
    cookies.forEach { webManager.setCookie(baseUrl, it) }
    webManager.flush()
}

/**
 * onPageFinished 同步 cookie 到业务层 store + 通知路由 (CF 检测/验证回传);
 * shouldOverrideUrlLoading 交路由拦截 (书源跳转 JS); SSL 错误一律放行 (对照原 BaseWebViewClient)。
 */
private class AndroidWebViewClient(
    private val callbacks: WebViewCallbacks,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = callbacks.shouldOverrideUrl?.invoke(request.url.toString()) == true

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        callbacks.shouldOverrideUrl?.invoke(url) == true

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: android.net.http.SslError?,
    ) {
        handler?.proceed()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url ?: return
        val webCookie = CookieManager.getInstance().getCookie(url)
        if (!webCookie.isNullOrEmpty()) {
            CookieStoreProviders.get()?.replaceCookie(url, webCookie)
        }
        callbacks.onPageFinished?.invoke(url)
    }
}

/** [WebViewHost] 的 Android 实现, 直通 android.webkit.WebView。 */
private class WebViewHostImpl(private val webView: WebView) : WebViewHost {
    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            // Android evaluateJavascript 返回 JSON 转义串, 归一为纯文本
            // (原 WebViewModel.saveVerificationResult 的 unescapeJson + 去首尾引号)
            onResult(if (raw == null) null else EscapeUtils.unescapeJson(raw).trim('"'))
        }
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun goBack() = webView.goBack()
}

/**
 * 可见态 WebView: 覆盖 onWindowVisibilityChanged 强制 VISIBLE,
 * 避免嵌入 Fragment/ViewPager 时被 visibility 隐藏暂停渲染。
 */
class VisibleWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(VISIBLE)
    }
}
