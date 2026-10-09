package io.legado.desktop.help.http

import io.legado.app.help.http.BackstageWebViewFactory
import io.legado.app.help.http.BackstageWebViewHandle
import io.legado.app.help.http.BackstageWebViewProviders
import io.legado.app.help.http.StrResponse
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewFetchRequest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * 桌面端 [BackstageWebViewFactory]: 走 [DesktopWebViewEngines] 选出的内嵌浏览器引擎
 * (Windows = 系统自带 WebView2 Runtime), 语义对齐 app 端 `BackstageWebView`。
 *
 * 引擎不可用时仍抛 [UnsupportedOperationException], 由调用方 runCatching 吞掉自动回退
 * HTTP 路径 —— 与接入引擎之前的行为一致, 老机器不会因此变差。
 */
private object DesktopBackstageWebViewFactory : BackstageWebViewFactory {

    override fun create(
        url: String?,
        html: String?,
        encode: String?,
        tag: String?,
        headerMap: Map<String, String>?,
        sourceRegex: String?,
        overrideUrlRegex: String?,
        javaScript: String?,
        delayTime: Long,
    ): BackstageWebViewHandle = DesktopBackstageWebViewHandle(
        WebViewFetchRequest(
            url = url,
            html = html,
            encode = encode,
            cookieTag = tag,
            headerMap = headerMap,
            sourceRegex = sourceRegex,
            overrideUrlRegex = overrideUrlRegex,
            javaScript = javaScript,
            delayTime = delayTime,
        )
    )
}

private class DesktopBackstageWebViewHandle(
    private val request: WebViewFetchRequest,
) : BackstageWebViewHandle {

    override suspend fun getStrResponse(): StrResponse {
        val engine = DesktopWebViewEngines.get()
            ?: throw UnsupportedOperationException(
                syncGetString("desktop_backstage_webview_unsupported")
            )
        val result = engine.fetch(request)
        // 非法 URL 不抛: 与 `StrResponse(url, body)` 构造器同口径回落 localhost,
        // 否则带头的命中分支会比原来的无头分支多一个新异常面。
        fun safeRequest(url: String): Request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Request.Builder().url("http://localhost/").build()
        }
        // 嗅探命中时引擎会带上防盗链头 ([WebViewFetchResult.headers]): 挂到 raw 响应上,
        // 下游经 [StrResponse.headers] 读得到 —— 与 iOS `IosBackstageWebView.hitResponse`
        // 同一口径 (原版 Android `SnifferWebClient` 只回命中地址, 不提取请求头)。
        // 此前无条件用 `StrResponse(url, body)` 构造, 把头丢掉了; 非嗅探路径 headers
        // 恒为空 Map ([WebViewFetchResult] 契约), 故行为不变。
        val hitHeaders = result.headers
        if (!result.redirected && hitHeaders.isEmpty()) return StrResponse(result.url, result.body)
        // 与 app 端 buildStrResponse 一致: 发生过重定向时补一层 302 priorResponse,
        // 调用方靠它区分"原始地址"与"最终地址" (WebBook.checkRedirect 读 raw.priorResponse,
        // 故未重定向的带头命中不得补这一层)。
        val origin = if (result.redirected) {
            Response.Builder()
                .code(302)
                .message("Found")
                .protocol(Protocol.HTTP_1_1)
                .request(safeRequest(request.url ?: result.url))
                .build()
        } else null
        val response = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(safeRequest(result.url))
            .apply {
                origin?.let { priorResponse(it) }
                hitHeaders.forEach { (name, value) -> addHeader(name, value) }
            }
            .build()
        return StrResponse(response, result.body)
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopBackstageWebView() {
    BackstageWebViewProviders.register(DesktopBackstageWebViewFactory)
}
