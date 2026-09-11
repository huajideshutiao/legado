package io.legado.app.help.image

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import io.legado.app.help.http.cookieJarHeader

/**
 * 书源防盗链 header + 书源 JS 改写 URL 的注入点 (android/jvm/ios 三端共享单份)。
 *
 * 拦截位置在 Coil3 `NetworkFetcher.fetch()` 的磁盘缓存查询**之后**: NetworkFetcher 先
 * `readFromDiskCache()`, 命中即 return, 只有慢路径才 `executeNetworkRequest` → 本 NetworkClient。
 * 对齐原版 Glide「磁盘缓存命中不进 `OkHttpStreamFetcher.loadData`, header/URL 解析只在真发请求时
 * 发生一次」的语义 —— 内存命中 (不进 fetcher) 与磁盘命中均零查源。
 * 例外: NetworkFetcher 的「响应 body 为空则不带头重试一次」回退分支
 * (coil3-network NetworkFetcher.fetch 尾部 executeNetworkRequest(newRequest())) 会再进本类一次,
 * 即多一次查源 + 多跑一遍 header JS (旧 fetcher 层设计不会重复); 仅「写盘失败且 body 为空」
 * 触发, 概率极低, 对带时效签名的 url 重新解析反而更正确。
 *
 * 包在 [ImageGuardNetworkClient] 内层: failUrls 的查询与拉黑因此始终按调用方原始 URL 计数,
 * 与原版 `failUrl.add(url.toStringUrl())` (GlideUrl 原始串) 一致; 本类只负责换形。
 *
 * header 合并而非整体替换: 保留消费点经 options.httpHeaders 已设的请求头, 解析结果按同名 key 覆盖
 * (整体替换会丢消费点显式设置的头), 等价原版「解析出的 header 覆盖消费点显式设置」。
 * 注: 当前 [SourceDecodeCacheStrategy].read 恒接受磁盘命中、NetworkFetcher 不写 If-None-Match,
 * 本链不发条件请求、无 304 重验证; 此合并的保留语义是为将来若支持条件请求预留的前提。
 * 副作用: 经 NetworkHeaders 合并后上行的
 * 头名会被统一成小写 (NetworkHeaders 内部 key 恒 lowercase) —— HTTP 头名大小写不敏感、
 * HTTP/2 本就要求小写, 但抓包对比原版时头名 case 会变, 不是 bug。
 *
 * 解析失败/无书源不在本类兜底: [resolve] 抛出的异常照原路上抛 (与改造前 fetcher 层一致,
 * 错误必须暴露); 书源取不到时由各端 `resolveSourceRequest` 仍返回剥掉 `url,{options}` 后缀的
 * 解析结果 (对齐原版 `AnalyzeUrl(url, source = null)` 照样解析 URL 形态)。
 */
internal class SourceHeaderNetworkClient(
    private val delegate: NetworkClient,
    /** (sourceOrigin, 原始 url) → (真实 url, 请求头)。各端 `resolveSourceRequest` 实现。 */
    private val resolve: suspend (sourceOrigin: String, url: String) -> Pair<String, Map<String, String>>,
    /**
     * 是否保留 cookieJar 伪头。
     *
     * - true (android/desktop): 伪头由 app 拦截器摘除 (jvmAndAndroidMain/help/http/HttpHelper.kt
     *   的 CookieJarBridgeHolder.loadRequest/saveResponse), 摘除前用它识别「本次访问结束即落库 cookie」,
     *   必须保留。
     * - false (iOS): 不剔除会把 `CookieJar: 1` 当真请求头发到服务器。注意原因**不是**没注册桥
     *   (IosProviderRegistry 已调 registerSharedCookieJarBridge), 而是该桥只挂在
     *   KmpHttpClient.newCall → executeKtor → KmpRequest.prepareForSend 路径上 (那里摘伪头 +
     *   注入 Cookie); 而 Coil 图片链用 `ktorClient.asNetworkClient()` 直接走 Ktor 插件链,
     *   不经 prepareForSend, 没人摘 → 只能在本层剔除。
     */
    private val keepCookieJarMarker: Boolean,
) : NetworkClient {

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val sourceOrigin = request.extras[SourceOriginKey]
        if (sourceOrigin.isNullOrEmpty()) return delegate.executeRequest(request, block)
        val (url, resolved) = resolve(sourceOrigin, request.url)
        val sourceHeaders =
            if (keepCookieJarMarker) resolved else resolved - cookieJarHeader
        if (url == request.url && sourceHeaders.isEmpty()) {
            return delegate.executeRequest(request, block)
        }
        val headers = NetworkHeaders.Builder().apply {
            request.headers.asMap().forEach { (name, values) -> set(name, values) }
            sourceHeaders.forEach { (name, value) -> set(name, value) }
        }.build()
        return delegate.executeRequest(
            NetworkRequest(
                url = url,
                method = request.method,
                headers = headers,
                body = request.body,
                extras = request.extras,
            ),
            block,
        )
    }
}
