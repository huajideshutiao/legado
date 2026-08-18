package io.legado.app.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.ui.compose.theme.AppTheme

/**
 * WebView 加载配置 (对应原 app 端 WebViewModel 的 baseUrl/headerMap/html 等状态)。
 *
 * 由 [WebViewScreen] 按原 WebViewModel.initData 逻辑预取生成:
 * - [headerMap]: 书源 header 注入 (loadUrl(url, headers)), 含 `,{...}` URL 级请求头;
 * - [html]: 非空时以 loadDataWithBaseURL 方式加载 (POST body 预拉 / data: 前缀解包 / 纯 HTML url);
 * - [isLogin] / [saveResult] / [sourceKey]: 源验证/登录场景 —— [isLogin] + [sourceKey] 驱动
 *   cookie 按书源回写 (原 WebViewActivity 的 onPageStarted/onPageFinished 双写);
 *   桌面端 slot 另用它们决定独立浏览器窗口的工具栏"确定"语义与 cookie 回写标签。
 * - [wideViewPort]: 原 WebViewActivity.initWebView 的 `useWideViewPort + loadWithOverviewMode`
 *   (认 `<meta viewport>` 并缩放适配)。原版只在浏览器/登录页设, `ReadRssActivity.initWebView`
 *   只调 applyCommonSettings 不设, 故默认 false, 由浏览器形态显式打开。
 */
data class WebViewConfig(
    val url: String,
    val headerMap: Map<String, String> = emptyMap(),
    val html: String? = null,
    val isLogin: Boolean = false,
    val saveResult: Boolean = false,
    val sourceKey: String = "",
    val wideViewPort: Boolean = false,
)

/**
 * 平台 WebView 能力抽象, 供 [WebViewRoute] 执行原 WebViewActivity 的验证回传:
 * evaluateJavascript (outerHTML 抓取 / CF 挑战检测) 与页面后退。
 * 平台注入失败 (desktop/鸿蒙 TODO) 时验证回传降级为 refetch 分支。
 */
interface WebViewHost {
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit)
    fun canGoBack(): Boolean
    fun goBack()

    /** 退出 HTML5 视频全屏 (对照原版 WebViewActivity 的 customViewCallback.onCustomViewHidden)。 */
    fun exitFullScreen() {}

    /** 当前加载页 URL (原 WebViewActivity 的 `webView.url ?: baseUrl`)。未实现时返回 null, 由路由回退预取 URL。 */
    fun getUrl(): String? = null

    /** 重新加载当前页 (原 menu_refresh → `webView.reload()`)。 */
    fun reload() {}
}

/**
 * 路由 ↔ 平台 WebView 事件桥:
 * 平台实现填充 [host] 并在页面加载完成时回调 [onPageFinished]。
 */
class WebViewCallbacks {
    var host: WebViewHost? = null
    var onPageFinished: ((String?) -> Unit)? = null

    /**
     * 页面标题变化 (对照原 ReadRssActivity 在 onPageFinished 里读 `view.title` 更新标题栏)。
     * 平台实现在 WebChromeClient.onReceivedTitle 触发。
     */
    var onReceivedTitle: ((String?) -> Unit)? = null

    /**
     * 页面加载进度 (对照原 CommonWebChromeClient.onProgressChanged → RefreshProgressBar)。
     * 参数为 0..100 的进度值; 平台实现在 WebChromeClient.onProgressChanged 触发。
     */
    var onProgressChanged: ((Int) -> Unit)? = null

    /**
     * 进入/退出全屏 (HTML5 `<video>` 全屏播放, 对照原 CommonWebChromeClient 的
     * onShowCustomView / onHideCustomView)。平台实现负责把 custom view 铺满自己的容器,
     * 这里只上报状态供路由隐藏标题栏。
     */
    var onFullScreenChanged: ((Boolean) -> Unit)? = null

    /**
     * 页面 URL 变化 (导航完成后的当前地址, 对照原 WebViewActivity 菜单取
     * `webView.url ?: baseUrl` 的语义)。平台实现在每次导航完成时触发,
     * 路由侧维护共享 currentUrl 状态供菜单读取 (页面内跳转后取最新链接)。
     */
    var onUrlChanged: ((String) -> Unit)? = null

    /**
     * URL 跳转拦截 (对照原 BaseWebViewClient.interceptUrl → 书源
     * `contentRule.shouldOverrideUrlLoading` JS)。返回 true 表示已处理, WebView 不再加载。
     *
     * 注意: 平台实现会在 WebView 线程同步调用, 回调内不要做耗时 IO。
     */
    var shouldOverrideUrl: ((String) -> Boolean)? = null

    /**
     * 网页请求关闭窗口 (`window.close()`, 对照原 CommonWebChromeClient.onCloseWindow)。
     *
     * 原 WebViewActivity 给 CommonWebChromeClient 传了该回调: 验证场景先回传网页源码再关闭,
     * 否则直接关闭 —— 验证/登录站点过关后常自己关窗收尾, 不接就永远等不到结果。
     * 原 ReadRssActivity 未传该回调 (走 super 默认), 故只有浏览器形态接线; 为 null 时
     * 平台实现须回退到 `super.onCloseWindow`。
     */
    var onCloseWindow: (() -> Unit)? = null
}

/**
 * WebView 渲染 slot 的 CompositionLocal: 默认兜底 [SharedWebViewPlaceholder]。
 *
 * 宿主端用 [CompositionLocalProvider] 覆盖注入 Android WebView、iOS WKWebView、
 * desktop 系统浏览器或鸿蒙 NAPI Web 能力,
 * 供 shared 路由 (登录 Overlay / [io.legado.app.ui.route.ReadRssRoute] /
 * [io.legado.app.ui.route.WebViewRoute]) 渲染 WebView, 避免 shared 路由硬编码平台 WebView 组件。
 *
 * 签名 `(WebViewConfig, Modifier, WebViewCallbacks) -> Unit`: 待加载 URL/header/html 配置
 * + 外部 Modifier + 路由↔平台事件桥。cookie 持久化 (onPageFinished) 由平台 actual 内部处理。
 *
 * 模式参考 [io.legado.app.ui.bookshelf.LocalBookCoverSlot]。
 */
val LocalWebViewSlot = staticCompositionLocalOf<@Composable (
    WebViewConfig,
    Modifier,
    WebViewCallbacks,
) -> Unit> {
    @Composable { config, modifier, _ ->
        SharedWebViewPlaceholder(config.url, modifier)
    }
}

/**
 * WebView 平台未注入时的兜底占位 (对照 [io.legado.app.ui.bookshelf.SharedBookCover] 占位语义)。
 *
 * 非伪造 WebView, 仅显示提示文本, 等待宿主端通过 [LocalWebViewSlot] 注入真实实现。
 */
@Composable
fun SharedWebViewPlaceholder(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "WebView 待平台注入\n$url",
            color = AppTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
