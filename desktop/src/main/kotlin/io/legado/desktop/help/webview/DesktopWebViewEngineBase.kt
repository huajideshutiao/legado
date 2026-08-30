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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
     * @param navigation 导航状态, 由引擎在自己的导航回调里更新 (见 [NavigationState]):
     *   每次导航都重新等 delayTime, 对齐 app 端 onPageStarted 的
     *   `removeCallbacks + postDelayed(delayTime)` —— 否则 Cloudflare 挑战页这类中转页
     *   会在跳转前被当成结果取回
     * @param evaluate 平台执行 JS 取结果 (空串/"null" 由本函数归一为"还没结果")
     * @param currentUrl 平台读当前地址 (结果 url 的首选)
     */
    protected suspend fun awaitScriptBody(
        request: WebViewFetchRequest,
        navigation: NavigationState,
        evaluate: suspend (script: String) -> String?,
        currentUrl: suspend () -> String?,
    ): WebViewFetchResult {
        val script = request.javaScript?.takeIf { it.isNotEmpty() } ?: DEFAULT_JS
        var armed = -1
        var retry = 0
        while (retry <= MAX_JS_RETRY) {
            val generation = navigation.generation()
            if (generation != armed) {
                armed = generation
                delay(request.delayTime)
                continue
            }
            val body = evaluate(script)?.takeIf { it.isNotEmpty() && it != "null" }
            if (body != null) {
                val url = currentUrl() ?: request.url.orEmpty()
                return WebViewFetchResult(url, body, navigation.redirected())
            }
            retry++
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
     * 回收 cookie 写入 CookieStore, 对应 app 端 `BackstageWebView.setCookie`:
     * 仅在有 tag 时保存, 且存到 tag (书源 key) 名下而非页面地址。
     */
    protected fun harvestTagCookies(url: String, tag: String?, readCookies: suspend () -> String?) {
        scope.harvestWebViewCookies(url, listOf(tag), platformLabel, readCookies = readCookies)
    }
}

/**
 * runHtml 主循环用的导航状态: 引擎在自己的导航回调里调 [onNavigation]
 * (Windows NavigationStarting / GTK LOAD_STARTED / mac didFinishNavigation),
 * [awaitScriptBody] 据此重新计时并判断是否发生过重定向。
 */
internal class NavigationState {

    private val navigations = AtomicInteger(0)

    private val redirected = AtomicBoolean(false)

    /** @param redirected 本次导航是否为重定向 (三平台探测方式不同: 导航标志 / LOAD_REDIRECTED / 地址对比) */
    fun onNavigation(redirected: Boolean = false) {
        if (redirected) this.redirected.set(true)
        navigations.incrementAndGet()
    }

    /** 已发生的导航次数, 变化即代表页面重新导航过。 */
    fun generation(): Int = navigations.get()

    fun redirected(): Boolean = redirected.get()
}

/**
 * 回收浏览器 cookie 写入 CookieStore, 对应 app 端 onPageFinished 的 setCookie
 * (无头抓取与可见窗口共用, 与注入 [injectWebViewCookies] 合起来成闭环)。
 *
 * @param url 当前页面地址 (读浏览器 cookie 的范围), 空则不回收
 * @param storeKeys 存入 CookieStore 的键, 按序各存一份, 空白项跳过: 无头抓取只存书源 key
 *   (对照 `BackstageWebView.setCookie`), 可见窗口再按页面地址存一份 (对照 `WebViewActivity`)
 * @param subject 失败日志里的主体 ("cookie" / 可见窗口用 "窗口 cookie")
 */
internal fun CoroutineScope.harvestWebViewCookies(
    url: String,
    storeKeys: List<String?>,
    platformLabel: String,
    subject: String = "cookie",
    readCookies: suspend () -> String?,
) {
    if (url.isBlank()) return
    val keys = storeKeys.filterNotNull().filter { it.isNotBlank() }
    if (keys.isEmpty()) return
    launch {
        val cookie = readCookies() ?: return@launch
        runCatching {
            val store = CookieStoreProviders.get() ?: return@runCatching
            keys.forEach { store.setCookie(it, cookie) }
        }.onFailure { AppLog.put("$platformLabel $subject 回写失败", it) }
    }
}

/**
 * 把 CookieStore 里已有的 cookie 注入浏览器再导航 (fetch 路径与可见窗口共用)。
 *
 * 安卓上 WebView 与 OkHttp 同样是两套独立 cookie 存储, 原版靠 `CookieStore` 的
 * `onSyncCookieToWebView` 与 `WebViewActivity` 的 `AppCookieManager.applyToWebView` 手工注入;
 * 本函数是桌面端的等价实现, 与回收 ([harvestWebViewCookies]) 合起来成闭环。
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
