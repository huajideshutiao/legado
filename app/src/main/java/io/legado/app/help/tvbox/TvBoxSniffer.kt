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
import com.github.catvod.crawler.Spider
import io.legado.app.App
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TVBox parse=1 (网页嗅探 + type=1 json API + type=2/3 jar 聚合) 的宿主实现。
 *
 * 生态语义 (查证自 FongMi/TV fongmi 分支与 TVBox 原版 q215613905/TVBoxOS):
 * - spider 的 `playerContent` 回 `parse`(1) 或 `jx`(1) 表明 [sniff] 的入参 [url] 是**播放页**
 *   而非视频直链, 需先加载该页取出真实媒体地址; 这是 TVBox 站点播放爱优腾会员线、
 *   各类解析站内容的标准手段 —— FongMi `bean/Result.needParse()` / `isUseParse()`,
 *   `player/parse/ParseJob.doInBackground()` 的 type=0 分支 → `ui/custom/CustomWebView`;
 *   原版 TVBox `bean/ParseBean.type` 注释亦写明 "0 普通嗅探"。
 * - 嗅探点在 `WebViewClient.shouldInterceptRequest` (WebView 的**全部**子资源请求都过这里),
 *   逐个请求用视频地址判据过一遍 —— FongMi `utils/Sniffer.isVideoFormat()`; 解析页常把真实
 *   播放器放进 iframe, 故再对 `player…http` 形态的内嵌页**往下钻一层**再嗅 —— FongMi
 *   `CustomWebView.webViewClient()` 的 PLAYER 分支。
 *
 * 本实现**不做** FongMi/TVBox 的本地 HTTP 代理 9978 (`server/Server` 起 NanoHTTPD、
 * `server/process/Parse` 出静态 iframe 页、`com.github.catvod.Proxy` 作地址提供): 那层中转
 * 是为把"已嗅到的地址"暴露成一个 URL 交给**外部**播放器去拉流; 本仓库嗅探在进程内完成,
 * 出来的真实 m3u8/mp4 直接交给宿主 ExoPlayer 管线 (理由见 `help/tvbox/README.md`),
 * 没有中转需求, 故不搬运该组件。
 *
 * WebView 只能在主线程打交道, 故 [sniff] 自身切 `Dispatchers.Main`; 每轮带超时,
 * 无论成功失败都会立即销毁 WebView, 取播链路不会被挂死的静态解析页拖住。
 */
object TvBoxSniffer {

    /** 单次嗅探的默认超时; 超出即视为失败, 避免 WebView 无限挂住取播链路。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** 停止加载时写入的占位页 (顺带让 Chromium 释放已起播的媒体资源)。 */
    private const val BLANK = "about:blank"

    /** 内嵌播放器页的下钻深度上限; 到顶后不再往下套娃。 */
    private const val MAX_DEPTH = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 加载 [url] (播放页) 并嗅探出真实媒体地址。可在任意线程调用, 内部切主线程。
     *
     * @param url 播放页地址
     * @param headers 附加请求头 (UA/Referer/Cookie)
     * @param timeoutMs 超时
     * @param videoChecker 视频地址判据; 默认 [TvBoxVideoPredicate.Sniffer]。spider 声明
     *   `manualVideoCheck()` 时应在此钩子委托它自己的 `isVideoFormat()` —— FongMi
     *   `CustomWebView.isVideoFormat()` 的 spider 委托分支。
     * @param depth 下钻剩余深度 (内部递归用)
     * @return 嗅到的真实媒体地址与应随播放请求携带的头
     */
    suspend fun sniff(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        videoChecker: TvBoxVideoPredicate = TvBoxVideoPredicate.Sniffer,
        depth: Int = MAX_DEPTH,
    ): TvBoxSniffResult = withContext(Dispatchers.Main) {
        check(url.startsWith("http")) { "嗅探地址非 http(s): $url" }
        descend(url, headers, timeoutMs, videoChecker, depth)
    }

    /**
     * type=1 json API 解析 (FongMi ParseJob.jsonParse): HTTP 请求 `parse.url + 播放页`,
     * 按返回 JSON 结构提取直链 (`url` 或 `data.url`), 结果直链直接可用, 无 WebView。
     *
     * 有效性判据对齐 FongMi checkResult: url 过短 (≤40 字符) 视为无直链。
     */
    suspend fun parseJsonApi(
        parse: TvBoxParse,
        playUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): TvBoxSniffResult = withContext(Dispatchers.IO) {
        val requestUrl = parse.url + playUrl
        check(requestUrl.startsWith("http")) { "json 解析站地址非 http(s): $requestUrl" }
        val merged = parse.headers + headers
        val json = com.github.catvod.net.OkHttp.newCall(requestUrl, merged).execute().use { resp ->
            check(resp.isSuccessful) { "json 解析请求 HTTP ${resp.code}: $requestUrl" }
            resp.body.string()
        }
        val root = JSONObject(json)
        val url = extractJsonUrl(root)
        check(url.length > 40) { "json 解析未返回有效直链 (url=${url.take(40)}): $requestUrl" }
        check(url.startsWith("http") || url.startsWith("rtmp")) { "json 解析返回非媒体地址: $url" }
        val respHeaders = extractJsonHeaders(root)
        TvBoxSniffResult(url, if (respHeaders.isEmpty()) merged else respHeaders)
    }

    /**
     * type=2/3 (Json 扩展/聚合) 解析 (FongMi ParseJob.jsonExtend/jsonMix):
     * 收集全体解析项, 经站点 jar 内解析类 (`com.github.catvod.parser.Json{url}`/`Mix{url}`)
     * 反射提取直链。结果若仍标 parse/jx=1 则下钻 WebView 嗅探 (FongMi checkResult(Result) 同义)。
     * JS spider 站点无 jar, 解析类缺失时如实失败。
     */
    suspend fun parseJsonAggregate(
        parse: TvBoxParse,
        allParses: List<TvBoxParse>,
        flag: String,
        playUrl: String,
        spider: Spider,
        headers: Map<String, String> = emptyMap(),
        videoChecker: TvBoxVideoPredicate = TvBoxVideoPredicate.Sniffer,
    ): TvBoxSniffResult = withContext(Dispatchers.IO) {
        val root = invokeJarParser(parse, allParses, flag, playUrl, spider)
        val url = root.optString("url").trim()
        check(url.isNotEmpty()) { "聚合解析返回无 url: ${parse.name}" }
        val respHeaders = extractJsonHeaders(root)
        val merged = if (respHeaders.isEmpty()) parse.headers + headers else respHeaders
        if (root.optInt("parse", 0) != 0 || root.optInt("jx", 0) != 0) {
            return@withContext sniff(url, headers = merged, videoChecker = videoChecker)
        }
        TvBoxSniffResult(url, merged)
    }

    /** 经站点 jar 内解析类取结果 (FongMi JarLoader.jsonExt/jsonExtMix 同语义: 反射调静态 parse)。 */
    private fun invokeJarParser(
        parse: TvBoxParse,
        allParses: List<TvBoxParse>,
        flag: String,
        playUrl: String,
        spider: Spider,
    ): JSONObject {
        val suffix = when (parse.type) {
            TvBoxParse.TYPE_JSON_EXT -> "Json" + parse.url
            TvBoxParse.TYPE_MIX -> "Mix" + parse.url
            else -> error("非聚合 type: ${parse.type}")
        }
        val className = "com.github.catvod.parser.$suffix"
        val loader = spider.javaClass.classLoader
            ?: error("站点无 jar 类加载器, 无法调用聚合解析类: $className")
        val clz = try {
            loader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            // JS spider 站点无 jar 侧解析类, 聚合解析不可用 (FongMi 同语义, 如实失败)
            error("站点 jar 无聚合解析类 $className (${e.message})")
        }
        return try {
            when (parse.type) {
                TvBoxParse.TYPE_JSON_EXT -> {
                    clz.getMethod("parse", LinkedHashMap::class.java, String::class.java)
                        .invoke(null, jsonExtJxs(allParses), playUrl) as JSONObject
                }
                else -> {
                    clz.getMethod(
                        "parse", LinkedHashMap::class.java, String::class.java,
                        String::class.java, String::class.java,
                    ).invoke(null, mixJxs(allParses), parse.name, flag, playUrl) as JSONObject
                }
            }
        } catch (e: Exception) {
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            error("调用聚合解析类 $className 失败: ${root.message}")
        }
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
            error("TVBox 解析页下钻超过 $MAX_DEPTH 层仍未嗅到视频地址: $url")
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
        val teardown = {
            runCatching {
                webView?.apply {
                    stopLoading()
                    loadUrl(BLANK)
                    destroy()
                }
            }
            webView = null
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
        mainHandler.postDelayed({ gate.timeout(page) }, timeoutMs)
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
            if (isAd(request.url.host)) return emptyResponse()
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

    /** 广告/统计域: 这些域的请求常抢在视频之前抵达, 且形态上也可能命中视频判据。 */
    private fun isAd(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )

    /** 广告/统计域名表 (无 rule 配置时的兜底)。 */
    private val AD_HOSTS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adnxs.com",
        "scorecardresearch.com",
        "advertising.com",
    )

    /** 内嵌播放器页判据: FongMi `CustomWebView.PLAYER` = `player.*https?://`。 */
    private val INNER_PLAYER = Regex("player.*https?://")
}

/** 嗅探结果: 真实媒体地址 + 应随播放请求携带的头。 */
data class TvBoxSniffResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 一条解析配置 (jxs), 对齐 FongMi `bean/Parse` / TVBox 原版 `bean/ParseBean`:
 * 配置 json 顶层 `parses[]` 的元素, 形如
 * `{"name":"虾米","type":0,"url":"https://jx.xmflv.com/?url=","ext":{"flag":[...],"header":{...}}}`。
 *
 * `type` 语义 (原版 ParseBean 注释 + FongMi ParseJob.doInBackground 分支):
 * 0=普通嗅探 (WebView 加载 `url + 播放页` 后嗅出真实地址)、
 * 1=json 请求 `url + 播放页` 取 `{url}`、2=Json 扩展 (jar 内 `Json{url}` 类)、
 * 3=聚合 (jar 内 `Mix{url}` 类)、4=FongMi 自加的"神解析"。
 * `ext.flag` 限定该解析只对哪些播放线路 (play_from) 生效。
 *
 * type=0 参与 WebView 解析页拼装, type=1 走 json API, type=2/3 走 jar 聚合类:
 * 三类在 [TvBoxSourceDelegateImpl.sniffContent] 按序尝试 (FongMi ParseJob 的 type 分支)。
 */
data class TvBoxParse(
    val name: String,
    val type: Int,
    val url: String,
    val flags: List<String>,
    val headers: Map<String, String>,
    /** ext 原始 JSON 文本 (聚合类透传用, FongMi Parse.ext 的字符串形态)。 */
    val extJson: String = "",
) {
    /** type=0 普通嗅探: 可参与 WebView 解析页拼装。 */
    val isWebSniff: Boolean get() = type == TYPE_WEB_SNIFF && url.isNotBlank()

    /** type=1 json API: 请求 `url+播放页` 按返回 JSON 结构提取直链 (FongMi ParseJob.jsonParse)。 */
    val isJsonApi: Boolean get() = type == TYPE_JSON && url.isNotBlank()

    /** type=2/3 (Json 扩展/聚合): 依赖 jar 内解析类, 经站点 jar 类加载器反射调用。 */
    val isAggregate: Boolean get() = type == TYPE_JSON_EXT || type == TYPE_MIX

    /** 拼出加载给 WebView 的解析页地址 (播放页原样拼在 url 之后)。 */
    fun pageOf(playUrl: String): String = url + playUrl

    /** type=2 传给 jar 解析类的请求形态 (FongMi Parse.extUrl): 带 base64 的 ext。 */
    fun extUrl(): String {
        if (extJson.isBlank()) return url
        val index = url.indexOf('?')
        if (index == -1) return url
        return url.substring(0, index + 1) + "cat_ext=" + base64Url(extJson) + "&" + url.substring(index + 1)
    }

    /** type=3 传给 jar 聚合类的元信息 (FongMi Parse.mixMap)。 */
    fun mixMap(): Map<String, String> = linkedMapOf(
        "type" to type.toString(),
        "ext" to extJson,
        "url" to url,
    )

    companion object {
        /** FongMi ParseJob 分支 type: 0 普通嗅探 / 1 json API / 2 Json 扩展 / 3 聚合 / 4 神解析。 */
        const val TYPE_WEB_SNIFF = 0
        const val TYPE_JSON = 1
        const val TYPE_JSON_EXT = 2
        const val TYPE_MIX = 3

        fun fromJson(obj: JSONObject): TvBoxParse? {
            val url = obj.optString("url").trim()
            if (url.isEmpty()) return null
            val ext = obj.optJSONObject("ext") ?: JSONObject()
            val flags = ArrayList<String>()
            ext.optJSONArray("flag")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(flags::add)
                }
            }
            val headers = linkedMapOf<String, String>()
            for (table in listOf(ext.optJSONObject("header"), obj.optJSONObject("header"))) {
                val entry = table ?: continue
                for (key in entry.keys()) {
                    val value = entry.opt(key)?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty() && !headers.containsKey(key)) headers[key] = value
                }
            }
            return TvBoxParse(
                name = obj.optString("name").trim(),
                type = obj.optInt("type", TYPE_WEB_SNIFF),
                url = url,
                flags = flags,
                headers = headers,
                extJson = if (obj.has("ext")) obj.opt("ext")?.toString().orEmpty() else "",
            )
        }
    }
}

/** 挑一条适用于 [flag] 线路的 type=0 解析配置; 无线路限定或限定不命中时退回首条可用项。 */
fun List<TvBoxParse>.pickWebSniff(flag: String): TvBoxParse? {
    val usable = filter { it.isWebSniff }
    if (usable.isEmpty()) return null
    return usable.firstOrNull { flag.isNotBlank() && it.flags.contains(flag) } ?: usable.first()
}

/** 挑一条适用于 [flag] 线路的 type=1 json API 解析; 无线路限定或限定不命中时退回首条可用项。 */
fun List<TvBoxParse>.pickJsonApi(flag: String): TvBoxParse? {
    val usable = filter { it.isJsonApi }
    if (usable.isEmpty()) return null
    return usable.firstOrNull { flag.isNotBlank() && it.flags.contains(flag) } ?: usable.first()
}

/** 聚合类解析 (type=2/3, Json 扩展/聚合): 配置里通常至多一条, 取首条。 */
fun List<TvBoxParse>.pickAggregate(): TvBoxParse? = firstOrNull { it.isAggregate }

/** type=1 json 解析的 url 提取 (FongMi ParseJob.jsonParse: 根 url → data.url 兜底)。 */
internal fun extractJsonUrl(root: JSONObject): String {
    val url = root.optString("url").trim()
    if (url.isNotEmpty()) return url
    return root.optJSONObject("data")?.optString("url")?.trim().orEmpty()
}

/**
 * type=1/聚合响应的防盗链头提取 (FongMi ParseJob.getHeader 同语义): 只认 UA/Referer/Cookie,
 * "ua" 键归一为 "User-Agent"。
 */
internal fun extractJsonHeaders(root: JSONObject): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for (key in root.keys()) {
        val value = root.opt(key)?.toString()?.trim().orEmpty()
        if (value.isEmpty()) continue
        when (key.lowercase()) {
            "user-agent", "ua" -> headers["User-Agent"] = value
            "referer" -> headers["Referer"] = value
            "cookie" -> headers["Cookie"] = value
        }
    }
    return headers
}

/** type=2 传给 jar 解析类的 jxs 映射 (全体 type=1 解析的 name→extUrl)。 */
internal fun jsonExtJxs(parses: List<TvBoxParse>): Map<String, String> {
    val map = linkedMapOf<String, String>()
    for (item in parses) if (item.isJsonApi) map[item.name] = item.extUrl()
    return map
}

/** type=3 传给 jar 聚合类的 jxs 映射 (全体解析的 name→{type,ext,url})。 */
internal fun mixJxs(parses: List<TvBoxParse>): Map<String, Map<String, String>> {
    val map = linkedMapOf<String, Map<String, String>>()
    for (item in parses) map[item.name] = item.mixMap()
    return map
}

/** Android Base64(URL_SAFE|NO_WRAP) 的 JVM 等价编码 (FongMi Util.base64 URL_SAFE)。 */
private fun base64Url(text: String): String =
    java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

/** 视频地址形态正则: FongMi `utils/Sniffer.SNIFFER`。 */
private val SNIFFER = Regex(
    "https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?" +
        "|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+",
)

/**
 * 视频地址形态过滤 (FongMi `Sniffer.isVideoFormat()` 的纯 URL 面, 无 rule 配置部分):
 * 明显的非视频形态 (`url=http` / `v=http` / `.html`) 直接排除 —— 解析页的 URL 参数里
 * 往往带着真实播放页且常常是 .html, 不排除会把解析页自己误判成视频。
 * 纯函数 (无 Android 依赖), JVM 单测直接覆盖。
 */
internal fun isVideoUrl(url: String): Boolean {
    if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false
    return SNIFFER.containsMatchIn(url)
}

/** 视频地址判据: 默认 [Sniffer] 走 URL 形态; spider 侧 `manualVideoCheck()` 可换实现。 */
fun interface TvBoxVideoPredicate {
    fun isVideoFormat(url: String): Boolean

    companion object {
        val Sniffer = TvBoxVideoPredicate { isVideoUrl(it) }
    }
}
