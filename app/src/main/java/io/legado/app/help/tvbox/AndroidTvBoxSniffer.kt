package io.legado.app.help.tvbox

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.App
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [TvBoxSniffPlatform] 的安卓实现: headless WebView 加载解析页并在
 * `shouldInterceptRequest` 拦子资源请求嗅出真实媒体地址 (FongMi CustomWebView 同构)。
 *
 * 平台无关的编排 (type=1 json API / type=2/3 jar 聚合 / parse 语义) 在 data 层
 * [TvBoxSniffer]; 本类只承载 WebView 机制, 由 App.onCreate 注册进 [TvBoxSniffPlatforms]。
 * 桌面端嗅探走 DesktopWebViewEngine 属后续任务, 未注册端嗅探如实报错。
 *
 * WebView 只能在主线程打交道, 故 [sniff] 自身切 `Dispatchers.Main`; 每轮带超时,
 * 无论成功失败都会立即销毁 WebView, 取播链路不会被挂死的静态解析页拖住。
 */
object AndroidTvBoxSniffer : TvBoxSniffPlatform {

    /** 停止加载时写入的占位页 (顺带让 Chromium 释放已起播的媒体资源)。 */
    private const val BLANK = "about:blank"

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun sniff(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
        depth: Int,
    ): TvBoxSniffResult = withContext(Dispatchers.Main) {
        descend(url, headers, timeoutMs, videoChecker, depth)
    }

    /** 逐层下钻: 当前页引出视频地址即交差, 引出内嵌播放器页则更深一层。 */
    private suspend fun descend(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
        depth: Int,
    ): TvBoxSniffResult {
        val turn = loadPage(url, headers, timeoutMs, videoChecker)
        if (turn.videoUrl != null) {
            return TvBoxSniffResult(turn.videoUrl, turn.videoHeaders)
        }
        val inner = turn.innerPage
            ?: error("TVBox 解析页未嗅到视频地址: $url")
        if (depth <= 0) {
            error("TVBox 解析页下钻超过 ${TvBoxSniffer.MAX_DEPTH} 层仍未嗅到视频地址: $url")
        }
        return descend(inner, turn.innerHeaders, timeoutMs, videoChecker, depth - 1)
    }

    /** 单页一轮: WebView 加载 [page], 直到嗅出视频地址 / 引出内嵌页 / 超时。 */
    private suspend fun loadPage(
        page: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        videoChecker: TvBoxVideoPredicate,
    ): Turn = suspendCancellableCoroutine { cont ->
        var webView: WebView? = null
        var timeoutTask: Runnable? = null
        val torn = AtomicBoolean(false)
        val teardown = {
            // webkit 拦截回调线程、超时与协程取消都会走到这里: WebView 只能在主线程销毁
            if (torn.compareAndSet(false, true)) {
                timeoutTask?.let { mainHandler.removeCallbacks(it) }
                val view = webView
                webView = null
                if (view != null) {
                    mainHandler.post {
                        runCatching {
                            view.stopLoading()
                            view.loadUrl(BLANK)
                            view.destroy()
                        }
                    }
                }
            }
            Unit
        }
        val gate = Gate(cont, teardown)
        val created = runCatching {
            createWebView(page, videoChecker, gate).also { webView = it }
        }
        if (created.isFailure) {
            gate.fail(created.exceptionOrNull()!!)
            return@suspendCancellableCoroutine
        }
        // 退出播放 (协程被取消) 时立即销毁 WebView, 不靠 30s 超时兜底
        cont.invokeOnCancellation { teardown() }
        val task = Runnable { gate.timeout(page) }
        timeoutTask = task
        mainHandler.postDelayed(task, timeoutMs)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView?.loadUrl(page, headers)
    }

    /** 一轮的结局: 二态互斥, 恰一个字段非空。 */
    private class Turn(
        val videoUrl: String? = null,
        val videoHeaders: Map<String, String> = emptyMap(),
        val innerPage: String? = null,
        val innerHeaders: Map<String, String> = emptyMap(),
    ) {
        init {
            check(videoUrl != null || innerPage != null) { "Turn 必须带视频地址或内嵌页" }
        }
    }

    /**
     * 结局闸门: webkit 回调线程与超时回调可能并发进入, 这里保证一轮只提交一次结局,
     * 且首次提交即销毁 WebView (成功时不用再让解析页继续跑, 失败时也不会泄漏 WebView)。
     */
    private class Gate(
        private val cont: kotlinx.coroutines.CancellableContinuation<Turn>,
        private val teardown: () -> Unit,
    ) {
        private val delivered = AtomicBoolean(false)

        fun video(url: String, headers: Map<String, String>) {
            land(Turn(videoUrl = url, videoHeaders = headers))
        }

        fun inner(url: String, headers: Map<String, String>) {
            land(Turn(innerPage = url, innerHeaders = headers))
        }

        fun timeout(page: String) {
            if (!delivered.compareAndSet(false, true)) return
            teardown()
            if (cont.isActive) {
                cont.resumeWithException(IllegalStateException("TVBox 解析页嗅探超时: $page"))
            }
        }

        /** WebView 创建失败等同步异常走这条。 */
        fun fail(e: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            if (cont.isActive) cont.resumeWithException(e)
        }

        private fun land(turn: Turn) {
            if (!delivered.compareAndSet(false, true)) return
            teardown()
            if (cont.isActive) cont.resume(turn)
        }
    }

    /**
     * WebView 构建 (必须在主线程)。设置对齐 FongMi `CustomWebView.initSettings()`:
     * 同为 headless 解析场景 —— 开 JS/DOM 存储、放行混合内容、禁 JS 自行开窗,
     * 且关闭「需用户手势才起播」(解析页靠 JS 自动起播才走得上真实 m3u8 请求)。
     * UA 取宿主 [AppConfig.userAgent] (FongMi 取自己的 `Setting.getUa()`)。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        page: String,
        videoChecker: TvBoxVideoPredicate,
        gate: Gate,
    ): WebView = WebView(App.instance).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = AppConfig.userAgent
        }
        webViewClient = webClient(page, videoChecker, gate)
    }

    /**
     * 嗅探客户端, 与 FongMi `CustomWebView.webViewClient()` 同构: 在 `shouldInterceptRequest`
     * 里逐个请求过视频形态判据; 非 http / 广告域的请求直接吞掉; 其余原样放行让页面正常渲染
     * (页面渲染不出来就不会有后续视频请求, 故不能整体拦截)。
     * 差异: 本 sniffer 无 FongMi `RuleConfig` 的 ads 配置, 广告/统计域退化为内置 [AD_HOSTS]。
     */
    private fun webClient(
        page: String,
        videoChecker: TvBoxVideoPredicate,
        gate: Gate,
    ): WebViewClient = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = inspect(request) ?: super.shouldInterceptRequest(view, request)

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            handler?.proceed()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = false

        /** 返回 null 表示放行; 非空表示本请求已被消费 (命中或吞掉)。 */
        private fun inspect(request: WebResourceRequest): WebResourceResponse? {
            val resourceUrl = request.url.toString()
            if (!resourceUrl.startsWith("http")) return null
            if (isTvBoxAdHost(request.url.host)) return emptyResponse()
            if (videoChecker.isVideoFormat(resourceUrl)) {
                gate.video(resourceUrl, playHeaders(request, page))
                return emptyResponse()
            }
            // 内嵌播放器页: 解析页把真播放器藏在 iframe 里, 需钻进去再嗅一层。
            // 排除 page 自身 —— 它已经在嗅了, 再钻会原地打转。
            if (resourceUrl != page && INNER_PLAYER.containsMatchIn(resourceUrl)) {
                gate.inner(resourceUrl, playHeaders(request, page))
                return emptyResponse()
            }
            return null
        }
    }

    /**
     * 播放嗅到的地址时该带的头: 优先取 WebView 实际发出的头里与防盗链相关的三项
     * (`User-Agent`/`Referer`/`Cookie`), FongMi 的 json 解析分支同样只保留这三项
     * (见 `ParseJob.getHeader()`); 缺失则退回 Referer=当前解析页、UA=宿主 UA。
     */
    private fun playHeaders(
        request: WebResourceRequest,
        page: String,
    ): Map<String, String> {
        val source = runCatching { request.requestHeaders }.getOrNull().orEmpty()
        val headers = linkedMapOf<String, String>()
        source["User-Agent"]?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        source["Referer"]?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        source["Cookie"]?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        if (!headers.containsKey("User-Agent")) headers["User-Agent"] = AppConfig.userAgent
        if (!headers.containsKey("Referer")) headers["Referer"] = page
        return headers
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    /** 内嵌播放器页判据: FongMi `CustomWebView.PLAYER` = `player.*https?://`。 */
    private val INNER_PLAYER = Regex("player.*https?://")
}
