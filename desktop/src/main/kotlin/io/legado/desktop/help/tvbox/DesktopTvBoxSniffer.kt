package io.legado.desktop.help.tvbox

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.tvbox.TvBoxSniffPlatform
import io.legado.app.help.tvbox.TvBoxSniffResult
import io.legado.app.help.tvbox.TvBoxSniffer
import io.legado.app.help.tvbox.TvBoxVideoPredicate
import io.legado.desktop.help.webview.DesktopWebViewEngine
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewFetchResult
import io.legado.desktop.help.webview.WebViewFetchRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * [TvBoxSniffPlatform] 的桌面实现: 经 [DesktopWebViewEngines] 选出系统内嵌浏览器引擎
 * (Windows = WebView2, Linux = WebKitGTK, macOS = WKWebView), 用其无头抓取的嗅探模式
 * (sourceRegex 子资源匹配) 加载解析页找真实媒体地址。编排对齐 app 模块
 * AndroidTvBoxSniffer: 命中视频即交差, 引出内嵌播放器页则 depth 内下钻, 每轮带超时;
 * 引擎会话在 fetch 内部 finally 销毁, 与 Android 的"用完即毁"等价。
 *
 * 平台无关的编排 (type=1 json API / type=2/3 jar 聚合 / parse 语义) 在 data 层
 * [TvBoxSniffer]; 本类由 :desktop Main.kt 注册进 TvBoxSniffPlatforms (与 CF 挑战引擎
 * 同层注册: DesktopWebViewEngines 依赖 JNA, 按模块抽取规则只存在于 :desktop)。
 *
 * 与 Android 版的差异:
 * - 无"WebView 只能主线程"约束: 三端引擎自带消息泵线程 (WebView2Loop/GtkLoop/CocoaLoop),
 *   fetch 是普通挂起调用, 嗅探在调用方协程上下文直接跑, 不切 Dispatchers;
 * - 引擎按 sourceRegex (正则全串匹配) 过滤子资源, videoChecker 判据无法直接透传: 单轮用
 *   [io.legado.app.help.tvbox.isVideoUrl] 同源的视频形态正则预筛 (FongMi Sniffer.SNIFFER,
 *   剔除 rtmp 分支 —— Android inspect() 只放行 http(s)), 命中后再以 videoChecker 终判。
 *   spider 自带 isVideoFormat 比 URL 形态更宽时, 桌面端嗅不到形态外地址 (如实报错);
 * - 引擎抓取内部另有 AppConst.timeLimit (15s) 上限, timeoutMs 大于它时引擎上限先到期,
 *   两侧超时统一转成 Android 同文案的嗅探超时错误。
 */
object DesktopTvBoxSniffer : TvBoxSniffPlatform {

    override suspend fun sniff(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
        depth: Int,
    ): TvBoxSniffResult {
        val engine = DesktopWebViewEngines.get()
            ?: error("桌面 WebView 引擎不可用, 无法进行 TVBox 网页嗅探 (仅支持直链与 json 解析)")
        return descend(engine, url, headers, timeoutMs, videoChecker, depth)
    }

    /** 逐层下钻: 当前页引出视频地址即交差, 引出内嵌播放器页则更深一层 (同 Android descend)。 */
    private suspend fun descend(
        engine: DesktopWebViewEngine,
        page: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
        depth: Int,
    ): TvBoxSniffResult {
        val turn = loadRound(engine, page, headers, timeoutMs)
        if (videoChecker.isVideoFormat(turn.body)) {
            return TvBoxSniffResult(turn.body, playHeaders(turn.headers, page))
        }
        if (INNER_PLAYER.containsMatchIn(turn.body)) {
            if (depth <= 0) {
                error("TVBox 解析页下钻超过 ${TvBoxSniffer.MAX_DEPTH} 层仍未嗅到视频地址: $page")
            }
            return descend(
                engine, turn.body, playHeaders(turn.headers, page),
                timeoutMs, videoChecker, depth - 1,
            )
        }
        error("TVBox 解析页未嗅到视频地址: $page")
    }

    /**
     * 单页一轮: 引擎无头加载 [page] (解析配置头随导航携带, 对齐 Android loadUrl(url, headers)),
     * 按 [sniffSourceRegex] 匹配子资源, 返回首个命中地址 (result.body) 与随带头 (result.headers)。
     * 引擎保证命中才返回; 超时/引擎失败如实抛错。
     */
    private suspend fun loadRound(
        engine: DesktopWebViewEngine,
        page: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): WebViewFetchResult {
        val request = WebViewFetchRequest(
            url = page,
            headerMap = headers.takeIf { it.isNotEmpty() },
            sourceRegex = sniffSourceRegex(page),
        )
        return try {
            withTimeout(timeoutMs) {
                engine.fetch(request)
            }
        } catch (e: TimeoutCancellationException) {
            throw NoStackTraceException("TVBox 解析页嗅探超时: $page")
        } catch (e: NoStackTraceException) {
            // WebKitGTK/WKWebView 资源嗅探的引擎内部上限先到期时的超时文案归一
            if (e.message?.contains("超时") == true) {
                throw NoStackTraceException("TVBox 解析页嗅探超时: $page")
            }
            throw e
        }
    }

    /**
     * 命中头归一: WebView2/WebKitGTK 命中时已按引擎 playHeaders 补齐 UA/Referer 兜底;
     * WKWebView 读不到子资源请求头 (返回空 Map), 这里补宿主 UA + 解析页 Referer,
     * 对齐 Android playHeaders 的兜底面。
     */
    private fun playHeaders(hitHeaders: Map<String, String>, page: String): Map<String, String> =
        if (hitHeaders.isNotEmpty()) hitHeaders
        else mapOf("User-Agent" to UserAgentProviders.get(), "Referer" to page)

    /**
     * 单轮嗅探的子资源过滤正则 (引擎按全串匹配消费): 视频形态 (isVideoUrl 同源) 或
     * 内嵌播放器页 (FongMi CustomWebView.PLAYER = player.*http), 解析页自身排除 ——
     * WebKitGTK resource-load-started 与 WebView2 WebResourceRequested 均含主框架资源,
     * 不排除会把解析页自己当命中 (Android INNER_PLAYER 分支的 resourceUrl != page 同义)。
     */
    private fun sniffSourceRegex(page: String): String {
        val pageGuard = "(?!" + Regex.escape(page) + ")"
        val video = """(?!.*url=http)(?!.*v=http)(?!.*\.html)""" +
            """(?:https?://[^\s]{12,}\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd).*|https?://.*video/tos.*)"""
        val inner = """.*player.*https?://.*"""
        return "$pageGuard(?:$video|$inner)"
    }

    /** 内嵌播放器页判据: FongMi `CustomWebView.PLAYER` = `player.*https?://`。 */
    private val INNER_PLAYER = Regex("player.*https?://")
}
