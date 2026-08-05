package io.legado.desktop.help.webview

import io.legado.app.constant.SourceType
import io.legado.app.help.RssToolbarActions
import io.legado.app.utils.EscapeUtils

/**
 * 桌面端内嵌浏览器引擎抽象。
 *
 * 引擎按平台选择 (见 [DesktopWebViewEngines]): Windows = WebView2 Runtime, Linux =
 * webkit2gtk-4.1, macOS = WKWebView, 全不可用返回 null (调用方回退系统浏览器)。三个消费点
 * 分别是无头回源 ([io.legado.desktop.help.http.registerDesktopBackstageWebView])、书源网页验证
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
 * 窗口带 CustomTab 式工具栏 (对照 shared `WebViewRoute` 的标题栏 + 进度条 + 菜单动作):
 * 动态网页标题、加载进度细条、返回/前进/刷新、关闭 ×; [isLogin] 或 [saveResult] 为 true
 * 时额外显示"确定"按钮 (对照原版 `menu_ok`):
 * - [isLogin]: 点击提示并 reload, 下次导航完成自动关窗 (对照原版 check_host_cookie 分支);
 * - [saveResult]: 点击时引擎先抓取当前页 outerHTML (页面仍存活) 回传 [onSaveResult],
 *   由调用方决定关窗 (对照原版 `saveVerificationResult` 后 finish)。
 *
 * @param html 渲染内容 (对照 app 端 `loadDataWithBaseURL` 分支): POST / dataUri / 非 http 源
 *   由调用方先抓取解码后传入; 非空时引擎渲染 html 而非 [url]。两个引擎 (WebView2
 *   `NavigateToString` / JavaFX `loadContent`) 都无 base URL 参数, 页面相对资源按
 *   about:blank 根解析, 与原版 `loadDataWithBaseURL` 存在此语义差异 (实现时评估)。
 * @param bottomSheet 置底半屏语义 (对照 app 端 startBrowser asBottomSheet=true 的
 *   BottomSheetDialog): 窗口高度取屏幕一半并贴屏幕底部; 默认普通居中窗口
 * @param isLogin 登录页语义 (对照 WebViewActivity isLogin): "确定"按钮走 check_host_cookie
 * @param saveResult 验证语义 (对照 WebViewActivity sourceVerificationEnable): "确定"按钮
 *   抓 html 回传 [onSaveResult]
 * @param cookieTag cookie 保存标签 (通常 source.getKey() = 书源 key), 对应 app 端 `tag`;
 *   非空时工具栏溢出菜单显示"禁用源/删除源" (对照原版 web_view.xml 菜单的
 *   sourceKey 非空条件): 禁用源直接执行成功后关窗; 删除源先弹确认再执行, 成功后关窗
 * @param sourceType 书源类型 (SourceType.book/rss/tts, 对应 AppRoute.WebView.sourceType),
 *   禁用/删除源动作使用; 调用方能拿到源对象时传真实类型, 否则默认 book
 * @param sourceName 书源名 (删除源确认弹窗显示, 对照原版 sure_del + sourceName; 空时回退 cookieTag)
 * @param onSaveResult 验证回传回调 (仅 [saveResult] 时接线; 参数为页面存活时抓取的
 *   outerHTML, 引擎关闭后不可再取, 故在关窗前调用)
 * @param onNavigated 每次导航完成回调 (参数为当前地址), cookie 回写已由引擎完成
 * @param onClosed 窗口关闭回调 (用户点 X 或代码 close 都会触发, 保证只回调一次)
 * @param rssActions RSS 阅读模式 (2026-08-07: RSS 阅读去页面外壳, 收藏/朗读/分享/登录
 *   移入窗口工具栏); 非空时工具栏显示 RSS 按钮组, 动作经 [io.legado.app.help.RssToolbarActions]
 *   回调回 shared, 星收藏态经 [io.legado.app.help.RssToolbarActions.onStarChanged] 反推更新
 */
data class WebViewWindowRequest(
    val url: String,
    val title: String,
    val html: String? = null,
    val userAgent: String? = null,
    val cookieTag: String? = null,
    val sourceType: Int = SourceType.book,
    val sourceName: String = "",
    val bottomSheet: Boolean = false,
    val isLogin: Boolean = false,
    val saveResult: Boolean = false,
    val onSaveResult: ((String?) -> Unit)? = null,
    val onNavigated: (String) -> Unit = {},
    val onClosed: () -> Unit = {},
    val rssActions: RssToolbarActions? = null,
)

/** 工具栏"确定"按钮 isLogin 分支的提示文案 (对照 strings.xml check_host_cookie)。 */
internal const val CHECK_HOST_COOKIE_TEXT = "正在打开首页，成功后自动返回"

/** 可见窗口句柄。 */
interface WebViewWindowHandle {

    /** 当前地址, 窗口已销毁时返回 null。 */
    val currentUrl: String?

    /** 取当前网页源码 (document.documentElement.outerHTML), 失败返回 null。 */
    suspend fun currentHtml(): String?

    /**
     * 在当前页面执行 JS 并取回结果, 失败返回 null。
     *
     * 结果已按平台归一为纯文本 (对齐安卓 evaluateJavascript 的 JSON 反转义+去引号),
     * 供 [WebViewHost] 桥接 (验证回传 outerHTML / CF 挑战检测)。
     */
    suspend fun evaluateJavascript(script: String): String?

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
