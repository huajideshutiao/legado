package io.legado.desktop.help.webview

import io.legado.app.utils.EscapeUtils

/**
 * 桌面端内嵌浏览器引擎抽象。
 *
 * 引擎按三级回退链选择 (见 [DesktopWebViewEngines]): 系统自带引擎 → JavaFX WebView → 无
 * (无引擎时调用方回退系统浏览器)。三个消费点分别是无头回源
 * ([io.legado.desktop.help.http.registerDesktopBackstageWebView])、书源网页验证
 * ([io.legado.desktop.help.source.DesktopVerificationUiProvider]) 与登录页
 * ([io.legado.desktop.ui.browser.DesktopWebViewSlot])。
 */
interface DesktopWebViewEngine {

    /** 引擎标识, 仅日志与诊断用。 */
    val id: String

    /** 本机是否可用 (系统 runtime 已装 / 本地引擎已下载)。探测结果由实现自行缓存。 */
    fun isAvailable(): Boolean

    /**
     * 无头抓取, 语义对照 app 端 `BackstageWebView.getStrResponse`:
     * 加载页面 → 等 [WebViewFetchRequest.delayTime] → 执行 JS 取结果 (空则每秒重试) →
     * 回收 cookie 写入 CookieStore。超时/失败抛异常由调用方 runCatching 回退 HTTP。
     */
    suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult

    /** 打开可见浏览器窗口 (登录 / 网页验证), 失败返回 null 由调用方回退系统浏览器。 */
    fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle?
}

/**
 * 无头抓取请求, 字段与 app 端 `BackstageWebView` 构造参数一一对应。
 *
 * @param cookieTag cookie 保存标签 (通常 source.getKey()), 对应 app 端 `tag`
 */
data class WebViewFetchRequest(
    val url: String? = null,
    val html: String? = null,
    val encode: String? = null,
    val cookieTag: String? = null,
    val headerMap: Map<String, String>? = null,
    val sourceRegex: String? = null,
    val overrideUrlRegex: String? = null,
    val javaScript: String? = null,
    val delayTime: Long = 1000L,
)

/**
 * 无头抓取结果。
 *
 * @param url 最终地址 (跟随重定向后)
 * @param body 网页源码; 嗅探模式 (sourceRegex/overrideUrlRegex) 下为命中的资源地址
 * @param redirected 是否发生过重定向, 对应 app 端 `isRedirect` (决定 StrResponse 是否带 priorResponse)
 */
class WebViewFetchResult(
    val url: String,
    val body: String,
    val redirected: Boolean,
)

/**
 * 可见窗口请求。
 *
 * 引擎会在每次导航完成后把浏览器 cookie 写回 CookieStore, 对照 app 端
 * `WebViewActivity.onPageFinished`: 按页面地址存一份, [cookieTag] 非空时再按书源 key 存一份。
 *
 * @param html 渲染内容 (对照 app 端 `loadDataWithBaseURL` 分支): POST / dataUri / 非 http 源
 *   由调用方先抓取解码后传入; 非空时引擎渲染 html 而非 [url]。两个引擎 (WebView2
 *   `NavigateToString` / JavaFX `loadContent`) 都无 base URL 参数, 页面相对资源按
 *   about:blank 根解析, 与原版 `loadDataWithBaseURL` 存在此语义差异 (实现时评估)。
 * @param onNavigated 每次导航完成回调 (参数为当前地址), cookie 回写已由引擎完成
 * @param onClosed 窗口关闭回调 (用户点 X 或代码 close 都会触发, 保证只回调一次)
 */
data class WebViewWindowRequest(
    val url: String,
    val title: String,
    val html: String? = null,
    val userAgent: String? = null,
    val cookieTag: String? = null,
    val onNavigated: (String) -> Unit = {},
    val onClosed: () -> Unit = {},
)

/** 可见窗口句柄。 */
interface WebViewWindowHandle {

    /** 当前地址, 窗口已销毁时返回 null。 */
    val currentUrl: String?

    /** 取当前网页源码 (document.documentElement.outerHTML), 失败返回 null。 */
    suspend fun currentHtml(): String?

    fun reload()

    /** 关闭窗口 (幂等), 触发 [WebViewWindowRequest.onClosed]。 */
    fun close()
}

private val quoteRegex = "^\"|\"$".toRegex()

/**
 * 还原引擎返回的 JS 结果: 各端 (WebView2 / JavaFX / 安卓 evaluateJavascript) 回传的都是
 * JSON 编码值, 处理方式与 app 端 `BackstageWebView.handleResult` 逐字一致 —— 反转义后
 * 去掉首尾引号, `null` / 空串视为"还没结果"由调用方重试。
 */
fun unwrapScriptResult(raw: String?): String? {
    if (raw.isNullOrEmpty() || raw == "null") return null
    return EscapeUtils.unescapeJson(raw).replace(quoteRegex, "")
}
