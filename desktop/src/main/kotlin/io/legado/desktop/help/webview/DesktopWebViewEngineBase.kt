package io.legado.desktop.help.webview

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.utils.NetworkUtils
import io.legado.desktop.help.webview.DesktopWebViewEngineBase.Companion.COOKIE_TIMEOUT_MS
import io.legado.desktop.help.webview.DesktopWebViewEngineBase.Companion.DEFAULT_JS
import io.legado.desktop.help.webview.DesktopWebViewEngineBase.Companion.JS_RETRY_INTERVAL_MS
import io.legado.desktop.help.webview.DesktopWebViewEngineBase.Companion.MAX_JS_RETRY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 三平台系统引擎 (WebView2 / WebKitGTK / WKWebView, 见 win/gtk/mac 包) 的公共基类。
 *
 * 三份引擎的无头抓取编排原本逐段相同, 收敛到此:
 * - 常量 [MAX_JS_RETRY] / [JS_RETRY_INTERVAL_MS] / [COOKIE_TIMEOUT_MS] / [DEFAULT_JS];
 * - runHtml 的「延时 → JS 重试 30 次 → 超时抛错」主循环 (见 [awaitScriptBody]);
 * - cookie 回收写入 CookieStore (见 [harvestTagCookies]);
 * 平台差异 (会话 API 与线程桥) 由子类以 lambda 钩子提供, 行为与原三份散落实现一致。
 */
internal abstract class DesktopWebViewEngineBase : DesktopWebViewEngine {

    companion object {

        /** JS 取不到结果时的重试上限, 与 app 端 EvalJsRunnable 的 `retry > 30` 一致。 */
        const val MAX_JS_RETRY = 30

        const val JS_RETRY_INTERVAL_MS = 1000L

        const val COOKIE_TIMEOUT_MS = 5_000L

        /** app 端 BackstageWebView.JS 默认脚本。 */
        const val DEFAULT_JS = "document.documentElement.outerHTML"
    }

    /** 日志里的平台名 (cookie 注入/回写失败信息前缀, 如 "WebView2")。 */
    protected abstract val platformLabel: String

    /** cookie 回收与窗口创建都不能占用引擎消息泵线程, 统一挪到 IO。 */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否走嗅探 (sourceRegex / overrideUrlRegex 任一非空)。 */
    protected fun isSniffing(request: WebViewFetchRequest): Boolean =
        !request.sourceRegex.isNullOrBlank() || !request.overrideUrlRegex.isNullOrBlank()

    /**
     * runHtml 主循环 (对应 app 端 HtmlWebViewClient): 等 [WebViewFetchRequest.delayTime] →
     * 反复执行 JS 直到拿到非空结果 (空则每秒重试), 重试上限后抛"js执行超时"。
     *
     * @param evaluate 平台执行 JS 取结果, 须先归一 (空串/"null" 视为还没结果, 返回 null)
     * @param currentUrl 平台读当前地址 (结果 url 的首选)
     * @param redirected 是否发生过重定向 (三平台探测方式不同: 导航标志 / LOAD_REDIRECTED / 地址对比)
     */
    protected suspend fun awaitScriptBody(
        request: WebViewFetchRequest,
        evaluate: suspend (script: String) -> String?,
        currentUrl: suspend () -> String?,
        redirected: () -> Boolean,
    ): WebViewFetchResult {
        delay(request.delayTime)
        val script = request.javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        repeat(MAX_JS_RETRY + 1) {
            val body = evaluate(script)
            if (!body.isNullOrEmpty()) {
                val url = currentUrl() ?: request.url.orEmpty()
                return WebViewFetchResult(url, body, redirected())
            }
            delay(JS_RETRY_INTERVAL_MS)
        }
        throw NoStackTraceException("js执行超时")
    }

    /** 嗅探正则对 (overrideUrlRegex, sourceRegex), 空白串视为未配置。 */
    protected fun snifferRegexes(request: WebViewFetchRequest): Pair<Regex?, Regex?> =
        request.overrideUrlRegex?.takeIf { it.isNotBlank() }?.toRegex() to
            request.sourceRegex?.takeIf { it.isNotBlank() }?.toRegex()

    /** 嗅探结果: 命中地址作为 body, 原 url 为空时兜底用命中地址。 */
    protected fun snifferResult(request: WebViewFetchRequest, hitUrl: String): WebViewFetchResult =
        WebViewFetchResult(request.url ?: hitUrl, hitUrl, false)

    /** app 端在 onPageStarted 延时注入 JS, 嗅探这里等页面就绪后补一次。 */
    protected fun injectJsOnPageReady(request: WebViewFetchRequest, inject: (String) -> Unit) {
        request.javaScript?.takeIf { it.isNotEmpty() }?.let { inject(it) }
    }

    /**
     * 回收 cookie 写入 CookieStore, 对应 app 端 `setCookie(url)`:
     * 仅在有 tag 时保存, 且存到 tag (书源 key) 名下而非页面地址。
     */
    protected fun harvestTagCookies(url: String, tag: String?, readCookies: suspend () -> String?) {
        if (tag.isNullOrBlank() || url.isBlank()) return
        scope.launch {
            val cookie = readCookies() ?: return@launch
            runCatching { CookieStoreProviders.get()?.setCookie(tag, cookie) }
                .onFailure { AppLog.put("$platformLabel cookie 回写失败", it) }
        }
    }
}

/**
 * 把 CookieStore 里已有的 cookie 注入浏览器再导航 (fetch 路径与 Mac 可见窗口共用)。
 *
 * app 端不需要这步 —— 安卓 WebView 与 OkHttp 共用进程级 CookieManager; 桌面端两者是
 * 各自独立的存储, 不注入的话 webView 回源会丢掉此前 HTTP 侧登录拿到的会话。
 * 注入与回收 (见 [DesktopWebViewEngineBase.harvestTagCookies]) 合起来才是完整闭环。
 *
 * @param subject 失败日志里的主体 ("cookie" / 可见窗口用 "窗口 cookie")
 */
internal suspend fun injectWebViewCookies(
    url: String?,
    platformLabel: String,
    subject: String = "cookie",
    addCookies: suspend (domain: String, cookie: String) -> Unit,
) {
    val target = url?.takeIf { it.isNotBlank() } ?: return
    runCatching {
        val store = CookieStoreProviders.get() ?: return
        val cookie = store.getCookie(target).takeIf { it.isNotBlank() } ?: return
        val domain = NetworkUtils.getSubDomain(target).takeIf { it.isNotBlank() } ?: return
        addCookies(domain, cookie)
    }.onFailure { AppLog.put("$platformLabel $subject 注入失败", it) }
}
