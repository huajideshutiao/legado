package io.legado.app.help.source

import io.legado.app.constant.AppPattern
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 内置浏览器页的加载三态 (对照原 WebViewModel 的 baseUrl/headerMap/html)。
 *
 * - [baseUrl]: 页面基准地址 (相对地址解析与来源判定用)。原始 HTML 场景为空串 —— 内容不是
 *   地址, 既不参与地址解析, 也不得作为历史地址交给浏览器 (非空非法地址会被浏览器拒绝导航);
 * - [headerMap]: 书源请求头 (原始 HTML 场景为空);
 * - [html]: 非空时浏览器渲染该内容而非访问 [baseUrl] (原始 HTML / POST body / data: 解包)。
 */
data class WebViewLoadSpec(
    val baseUrl: String,
    val headerMap: Map<String, String>,
    val html: String?,
)

/**
 * 推导浏览器页加载三态, 各端浏览器宿主共用这一份 (原 WebViewModel.initData 的唯一一处判定)。
 *
 * 判定顺序与原版一致: 先判"非登录且地址不以 data/http 开头 → 整段文本就是 HTML", 命中即返回,
 * 不解析地址也不跑书源请求头 JS; 否则按书源解析地址, 并预取 POST body 与 data: 内容。
 */
suspend fun deriveWebViewLoadSpec(
    url: String,
    isLogin: Boolean,
    sourceKey: String,
    sourceType: Int,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): WebViewLoadSpec {
    if (!isLogin && !url.startsWith("data") && !url.startsWith("http")) {
        return WebViewLoadSpec(baseUrl = "", headerMap = emptyMap(), html = url)
    }
    val source = SourceHelp.getSource(sourceKey, sourceType)
    val analyzeUrl = AnalyzeUrlCore(
        url,
        source = source,
        coroutineContext = coroutineContext,
    )
    // 有 Origin 头用 Origin, 否则用解析后的地址 (rawUrl 可能带 ",{...}" 选项串)
    val baseUrl = analyzeUrl.headerMap["Origin"] ?: analyzeUrl.url
    var html: String? = null
    if (analyzeUrl.isPost()) {
        html = analyzeUrl.getStrResponseAwait(allowWebView = false).body
    }
    if (AppPattern.dataUriRegex.matches(analyzeUrl.url)) {
        html = analyzeUrl.getByteArrayAwait().decodeToString()
    }
    return WebViewLoadSpec(baseUrl, analyzeUrl.headerMap, html)
}
