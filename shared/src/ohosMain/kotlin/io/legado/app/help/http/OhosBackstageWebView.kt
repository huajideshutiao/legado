package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.getUserAgent
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.napi.OhosNativeBridge.WebViewHeader
import io.legado.app.napi.OhosNativeBridge.WebViewRequestPayload
import io.legado.app.napi.OhosNativeBridge.WebViewResult
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [BackstageWebViewFactory]: 隐藏 Web 组件桥 (napi → ArkTS WebViewBridgeHandler)。
 *
 * ArkUI 的 `Web` 组件只能存在于 ArkTS 页面组件树中, Kotlin/Native 无法直接实例化;
 * 采用与 Image/Http 桥一致的 "tsfn 发请求 + @CName 回调返回结果" 同步等待模式, 传输为混合协议:
 *
 * ```
 * KMP OhosBackstageWebViewHandle.getStrResponse
 *   → OhosNativeBridge.invokeWebViewSync(jsonControl, htmlRaw)   (阻塞 JS 引擎线程)
 *   → tsfn dispatch (控制面 JSON + 数据面裸 html 双参数) → ArkTS 主线程 WebViewBridgeHandler
 *   → 隐藏 Web 组件 loadUrl/loadData + onPageEnd 后 runJavaScript 取源码
 *   → legado.webViewCallback(requestId, resultJson, bodyRaw)     (回送结果唤醒 Kotlin)
 * ```
 *
 * # 混合协议 (大字符串不 JSON 转义)
 * 书源 webView 规则可能传大段 HTML (整章/整页, 数百 KB~数 MB)。若塞进 JSON payload,
 * KS_JSON 转义会膨胀体积 (引号/反斜杠/换行 → \\uXXXX 等) 且双端各多一次编解码拷贝。
 * 故 html 入参 (数据面) 作为 invokeWebViewSync 第二参数裸字符串直接经 napi 传递,
 * 返回的源码同样作为 webViewCallback 第三参数裸字符串回传;
 * JSON 只承载控制面小字段 (url/tag/encode/headers/正则/js/delayTime/cookie 与 ok/url/cookie/error)。
 *
 * # 语义对齐
 * - 页面加载 + JS 执行 + 重试上限与 app 端一致 (等 delayTime 后执行 JS,
 *   30 次 × 1s 重试直到非空结果), 总超时 [AppConst.timeLimit] (15s);
 * - **cookie**: 加载前把业务层 cookie (CookieStoreProviders) 注入 payload,
 *   ArkTS 侧 `WebCookieManager.setCookie` 预置; 加载完成后 ArkTS 回传页面 host 域 cookie,
 *   这里写回业务层 (对齐 app 端 setCookie(tag, cookie) 语义);
 * - **sourceRegex / overrideUrlRegex**: ArkUI Web 组件支持 onInterceptRequest
 *   (拦截所有子资源请求, 比 iOS WKWebView 能力更强), 由 ArkTS 侧命中后立即回传命中 URL;
 * - **UA / 请求头**: 由 ArkTS 侧 setCustomUserAgent / loadUrl headers 应用。
 *
 * # 平台限制 (桥未就绪时)
 * 宿主 (ohosApp) 未注册 WebViewBridgeHandler (EntryAbility 未调 registerWebViewCallback,
 * 或 Index.ets 未挂隐藏 Web 组件) 时, [isWebViewBridgeReady] 为 false,
 * 本实现抛带明确说明的 [NoStackTraceException] —— 规则层 runCatching 后呈现为书源规则错误,
 * 替代此前 BackstageWebViewProviders.get() 的 IllegalStateException 裸崩。
 */
private object OhosBackstageWebViewFactory : BackstageWebViewFactory {

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
    ): BackstageWebViewHandle = OhosBackstageWebViewHandle(
        url = url,
        html = html,
        encode = encode,
        tag = tag,
        headerMap = headerMap,
        sourceRegex = sourceRegex,
        overrideUrlRegex = overrideUrlRegex,
        javaScript = javaScript,
        delayTime = delayTime,
    )
}

/** 宿主启动早期注册一次 (registerOhosProviders 中, 任何 webView 规则解析之前)。 */
fun registerOhosBackstageWebView() {
    BackstageWebViewProviders.register(OhosBackstageWebViewFactory)
}

private class OhosBackstageWebViewHandle(
    private val url: String?,
    private val html: String?,
    private val encode: String?,
    private val tag: String?,
    private val headerMap: Map<String, String>?,
    private val sourceRegex: String?,
    private val overrideUrlRegex: String?,
    private val javaScript: String?,
    private val delayTime: Long,
) : BackstageWebViewHandle {

    override suspend fun getStrResponse(): StrResponse {
        if (html.isNullOrEmpty() && url.isNullOrEmpty()) {
            throw NoStackTraceException("url与html不能同时为空")
        }
        if (!OhosNativeBridge.isWebViewBridgeReady()) {
            // 桥未就绪: 明确失败信息代替崩溃 (规则层 runCatching 成书源错误)
            throw NoStackTraceException(
                "鸿蒙端后台WebView不可用: WebViewBridgeHandler 未注册" +
                    " (ohosApp EntryAbility 需调 legado.registerWebViewCallback 且 Index.ets 挂载隐藏 Web 组件); " +
                    "可改用非 webView 规则"
            )
        }
        // cookie 读取走 DB (SharedCookieStore 内部 runBlocking), 切到 IO 线程做
        val pendingCookie = readStoredCookie()
        val effectiveHeaders = (headerMap ?: emptyMap()).toMutableMap()
        if (effectiveHeaders.keys.none { it.equals(AppConst.UA_NAME, ignoreCase = true) }) {
            effectiveHeaders[AppConst.UA_NAME] = headerMap.getUserAgent()
        }
        // 控制面: 小字段走 JSON; html (可能数百 KB~数 MB) 走裸字符串第二参数
        val payload = WebViewRequestPayload(
            url = url,
            encode = encode,
            tag = tag,
            headers = effectiveHeaders.map { (name, value) -> WebViewHeader(name, value) },
            sourceRegex = sourceRegex,
            overrideUrlRegex = overrideUrlRegex,
            js = javaScript,
            delayTime = delayTime,
            cookie = pendingCookie,
        )
        return withTimeout(AppConst.timeLimit) {
            withContext(IoDispatcher) {
                // 阻塞等待 ArkTS 侧完成加载 + JS 执行 (桥内部超时 15s, 与 AppConst.timeLimit 一致)
                val response = OhosNativeBridge.invokeWebViewSync(
                    KS_JSON.encodeToString(payload),
                    htmlRaw = html,
                    timeoutMs = AppConst.timeLimit,
                )
                val result = response?.resultJson?.let {
                    runCatching {
                        KS_JSON.decodeFromString(
                            WebViewResult.serializer(),
                            it
                        )
                    }.getOrNull()
                }
                if (result == null) {
                    throw NoStackTraceException("webView执行超时或无响应")
                }
                if (!result.ok) {
                    throw NoStackTraceException(
                        "鸿蒙webView加载失败: ${result.error ?: "未知错误"}"
                    )
                }
                // cookie 回写 (对齐 app 端 setCookie(tag, cookie): 有 tag 才保存)
                result.cookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                    val cookieTag = tag?.takeIf { it.isNotBlank() }
                    if (cookieTag != null) {
                        runCatching { CookieStoreProviders.get()?.setCookie(cookieTag, cookie) }
                    }
                }
                // 数据面: 源码/命中 URL 裸字符串 (失败路径已提前抛异常, 成功时非空)
                StrResponse(result.url ?: url.orEmpty(), response.bodyRaw)
            }
        }
    }

    private suspend fun readStoredCookie(): String? = withContext(IoDispatcher) {
        val target = url?.takeIf { it.startsWith("http") } ?: return@withContext null
        runCatching { CookieStoreProviders.get()?.getCookie(target) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
