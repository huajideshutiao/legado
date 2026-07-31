package io.legado.desktop.help.http

import io.legado.app.help.http.BackstageWebViewFactory
import io.legado.app.help.http.BackstageWebViewHandle
import io.legado.app.help.http.BackstageWebViewProviders
import io.legado.app.help.http.StrResponse
import io.legado.app.ui.compose.platform.jvmGetString
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
                jvmGetString("desktop_backstage_webview_unsupported")
            )
        val result = engine.fetch(request)
        if (!result.redirected) return StrResponse(result.url, result.body)
        // 与 app 端 buildStrResponse 一致: 发生过重定向时补一层 302 priorResponse,
        // 调用方靠它区分"原始地址"与"最终地址"
        val origin = Response.Builder()
            .code(302)
            .message("Found")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url(request.url ?: result.url).build())
            .build()
        val response = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url(result.url).build())
            .priorResponse(origin)
            .build()
        return StrResponse(response, result.body)
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopBackstageWebView() {
    BackstageWebViewProviders.register(DesktopBackstageWebViewFactory)
}
